// File: app/src/main/java/com/entropy/hardware/DaemonClient.kt
//
// Daemon 的 UDS 客户端。
//
// 用 android.net.LocalSocket 而不是 java.nio.channels.UnixDomainSocketChannel：
//  后者要 API 28+，而 minSdk 是 24。LocalSocket 从 API 1 就在，
//  并且能直接连 abstract namespace（Daemon 优先 bind 的就是它，
//  好处是不依赖 /data 的 SELinux 文件标签，ADB 与 Magisk 两种模式下都能通）。
package com.entropy.hardware

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import com.entropy.shared.Proto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.DataInputStream
import java.io.OutputStream
import java.nio.ByteBuffer

class DaemonClient {

    sealed interface Event {
        data class Telemetry(val t: Proto.Telemetry) : Event
        data class Probe(val r: Proto.ProbeResult) : Event
        data class Thermal(val e: Proto.ThermalEvent) : Event
        data class Heartbeat(val h: Proto.Heartbeat) : Event
        data class Ack(val a: Proto.Ack, val seq: Int) : Event
        data object Disconnected : Event
    }

    private val _events = MutableSharedFlow<Event>(
        replay = 1,                       // 悬浮窗重启后能立刻拿到最后一帧遥测
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private var socket: LocalSocket? = null
    private var out: OutputStream? = null
    private var input: DataInputStream? = null
    private val writeMutex = Mutex()
    private var seq = 0
    private var readerJob: kotlinx.coroutines.Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile var connected: Boolean = false
        private set

    /**
     * 连接。先试 abstract，失败再试文件系统路径。
     * 返回 false 通常只有两种原因：Daemon 没起，或本 app 的 uid 不在 entropy.allow 里。
     * 调用方要据此区分提示文案 —— 这两种情况的修复动作完全不同。
     */
    fun connect(onFail: (String) -> Unit = {}): Boolean {
        if (connected) return true
        for (ns in listOf(LocalSocketAddress.Namespace.ABSTRACT, LocalSocketAddress.Namespace.FILESYSTEM)) {
            val name = if (ns == LocalSocketAddress.Namespace.ABSTRACT) Proto.ABSTRACT_NAME else Proto.SOCK_PATH
            try {
                val s = LocalSocket()
                s.connect(LocalSocketAddress(name, ns))
                s.receiveBufferSize = 256 * 1024
                socket = s
                out = s.outputStream
                input = DataInputStream(s.inputStream)
                connected = true
                startReader()
                Log.i(TAG, "connected via $ns ($name)")
                return true
            } catch (e: Exception) {
                Log.d(TAG, "connect $name failed: ${e.message}")
            }
        }
        onFail("daemon unreachable")
        return false
    }

    fun disconnect() {
        connected = false
        readerJob?.cancel()
        runCatching { socket?.close() }
        socket = null; out = null; input = null
    }

    private fun startReader() {
        readerJob = scope.launch {
            val ins = input ?: return@launch
            val hdr = ByteArray(Proto.HDR_SIZE)
            try {
                while (connected) {
                    ins.readFully(hdr)                       // 阻塞读，靠 disconnect() 关闭 fd 唤醒
                    val (type, seq, len) = Proto.decodeHeader(hdr) ?: break
                    val body = ByteArray(len).also { if (len > 0) ins.readFully(it) }
                    val ev: Event = when (type) {
                        Proto.EVT_TELEMETRY -> Event.Telemetry(Proto.Telemetry.decode(body))
                        Proto.EVT_PROBE_RESULT -> Event.Probe(Proto.ProbeResult.decode(body))
                        Proto.EVT_THERMAL_EVENT -> Event.Thermal(Proto.ThermalEvent.decode(body))
                        Proto.EVT_HEARTBEAT -> Event.Heartbeat(Proto.Heartbeat.decode(body))
                        Proto.REP_ACK -> Event.Ack(Proto.Ack.decode(body), seq)
                        else -> continue
                    }
                    _events.emit(ev)
                }
            } catch (e: Exception) {
                Log.d(TAG, "reader closed: ${e.message}")
            } finally {
                connected = false
                _events.emit(Event.Disconnected)
            }
        }
    }

    /**
     * 发送一帧。写锁保证多协程并发下发时帧不会交错 ——
     * UDS 是字节流，两帧的字节交叉了，Daemon 侧的 magic 校验会直接断开我们。
     */
    suspend fun send(type: Int, payload: ByteArray): Boolean {
        val o = out ?: return false
        return writeMutex.withLock {
            try {
                seq++
                val f = Proto.FrameHeader(type, seq, payload).encode()
                val arr = ByteArray(f.remaining()).also { f.get(it) }
                o.write(arr)
                o.flush()
                true
            } catch (e: Exception) {
                Log.w(TAG, "send 0x${type.toString(16)} failed: ${e.message}")
                disconnect()
                false
            }
        }
    }

    /** 一次性请求-应答：靠 seq 匹配；超时或断连返回 null */
    suspend fun request(type: Int, payload: ByteArray, timeoutMs: Long = 1500): Proto.Ack? {
        val o = out ?: return null
        val expected = writeMutex.withLock { ++seq }
        // 先建等待协程再发送：本地 socket 的应答可能在一两微秒内到达，
        // 如果先发再订阅，应答会掉进没人听的空档里，表现为"命令生效了但 UI 一直转圈"。
        val waiter = scope.async {
            try {
                withTimeout(timeoutMs) {
                    events.first { it is Event.Ack && it.seq == expected } as Event.Ack
                }
            } catch (e: Exception) {
                null
            }
        }
        val sent = send(type, payload)
        if (!sent) { waiter.cancel(); return null }
        return waiter.await()?.a
    }

    /** 主动拉一帧遥测 / 保活探测：App 侧不想等事件也能确认链路活着 */
    suspend fun ping(): Boolean = send(Proto.CMD_PING, ByteArray(0))

    fun shutdown() {
        disconnect()
        scope.cancel()
    }

    private companion object { const val TAG = "EntropyDaemonClient" }
}
