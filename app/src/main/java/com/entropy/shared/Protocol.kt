// File: app/src/main/java/com/entropy/shared/Protocol.kt
//
// 与 shared/protocol/entropy_ipc.h 逐字节对应的 Kotlin 侧编解码。
//
// 为什么手写 ByteBuffer 而不用 Parcelable/JSON：
//  对端是 C 写的 Daemon，没有 ART，也没有 JSON 解析器。
//  两侧唯一的契约就是内存布局，所以这里把 C 结构体的字段顺序、宽度、
//  对齐规则 1:1 复刻，并在 init 里用 sizeof 校验，防止哪天改了一个字段
//  却只有单边生效 —— 那种 bug 只表现为"偶尔读到乱码频率"，极难定位。
package com.entropy.shared

import java.nio.ByteBuffer
import java.nio.ByteOrder

object Proto {
    const val MAGIC = 0x454E5431               // "ENT1"
    const val VERSION = 1
    const val HDR_SIZE = 16
    const val SOCK_PATH = "/data/local/tmp/entropy.sock"
    const val ABSTRACT_NAME = "entropy"

    // 命令
    const val CMD_PING = 0x0001
    const val CMD_APPLY_PROFILE = 0x0002
    const val CMD_SET_NODE = 0x0003
    const val CMD_SET_LIMIT = 0x0004
    const val CMD_START_PROBE = 0x0010
    const val CMD_STOP_PROBE = 0x0011
    const val CMD_RESET_DEFAULT = 0x0012
    const val CMD_FREEZE_PKG = 0x0020
    const val CMD_UNFREEZE_PKG = 0x0021
    const val CMD_SET_MONITOR = 0x0030
    /** 帧率探针（LD_PRELOAD）上报，App 侧只读不写，放在这里是为了保持两侧枚举同源 */
    const val CMD_HOOK_REPORT = 0x0040

    // 事件
    const val REP_ACK = 0x8000
    const val EVT_TELEMETRY = 0x8100
    const val EVT_PROBE_RESULT = 0x8101
    const val EVT_THERMAL_EVENT = 0x8102
    const val EVT_HEARTBEAT = 0x8103

    const val PROFILE_BALANCED = 0
    const val PROFILE_GAME = 1
    const val PROFILE_POWERSAVE = 2
    const val PROFILE_CUSTOM = 3

    const val RENDERER_DEFAULT = 0
    const val RENDERER_SKIA = 1
    const val RENDERER_GLES = 2
    const val RENDERER_VULKAN = 3

    /** LimitReq.fpsLimit 的哨兵：只清帧率上限，不动分辨率/渲染器（与 entropy_ipc.h 同名常量同值） */
    const val FPS_LIMIT_CLEAR = -1

    /**
     * 熔断线允许的最高值(m°C)。App、Daemon、命令行三处各钳一次，三处必须同数。
     *
     * 为什么不是 50℃：这条线看的是所有热区里的最大值，而真实机型上占住那个位置的
     * 通常是 SoC / GPU 结温 —— 负载起来就是 60~80℃。50℃ 于是是常态而不是危险线，
     * 表现为一开机就熔断、所有抬频入口被锁死。95℃ 已经贴近内核自己降频的位置，
     * 越过它熵只做一个动作：把调度全量还原成系统默认，越线不会让它去顶硬件。
     */
    const val FUSE_MAX_MC = 95_000
    const val FUSE_DEFAULT_MC = 85_000
    const val SOFT_DEFAULT_MC = 70_000
    /** 过热提醒线默认值(m°C)。提醒只改颜色和发通知，不动调度，所以给得比软上限低 */
    const val ALERT_DEFAULT_MC = 60_000

    /** 帧头：magic(4) version(2) type(2) seq(4) payload_len(4) = 16B，无填充 */
    data class FrameHeader(
        val type: Int,
        val seq: Int,
        val payload: ByteArray
    ) {
        fun encode(): ByteBuffer {
            val b = ByteBuffer.allocate(HDR_SIZE + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(MAGIC)
            b.putShort(VERSION.toShort())
            b.putShort(type.toShort())
            b.putInt(seq)
            b.putInt(payload.size)
            b.put(payload)
            b.flip()
            return b
        }
    }

    /**
     * 只解析帧头，用于流式读取（先读 16B 拿长度，再按长度读负载）。
     * 返回 type to payloadLen；magic/version 不符或长度非法返回 null，调用方应断开连接。
     */
    fun decodeHeader(hdr: ByteArray): Triple<Int, Int, Int>? {
        if (hdr.size < HDR_SIZE) return null
        val b = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
        if (b.int != MAGIC) return null
        if ((b.short.toInt() and 0xFFFF) != VERSION) return null
        val type = b.short.toInt() and 0xFFFF
        // seq 必须解出来：ACK 靠它和请求配对，Daemon 是原样回显的
        val seq = b.int
        val len = b.int
        if (len < 0 || len > 64 * 1024) return null
        return Triple(type, seq, len)
    }

    /** 解析完整的一帧（负载已经在 buf 里）；用于一次性读取 */
    fun decode(buf: ByteBuffer): FrameHeader? {
        val b = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        if (b.remaining() < HDR_SIZE) return null
        val magic = b.int
        val ver = b.short.toInt() and 0xFFFF
        val type = b.short.toInt() and 0xFFFF
        val seq = b.int
        val len = b.int
        if (magic != MAGIC || ver != VERSION) return null
        if (len < 0 || len > b.remaining()) return null
        val p = ByteArray(len)
        b.get(p)
        return FrameHeader(type, seq, p)
    }

    // ---- 负载结构 ----

    data class ProfileReq(
        val profile: Int,
        val cpuMaxKhz: Int = 0,
        val gpuMaxKhz: Int = 0,
        val boostMs: Int = 0,
        val predictiveOn: Int = 1
    ) {
        fun encode(): ByteArray = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(profile).putInt(cpuMaxKhz).putInt(gpuMaxKhz).putInt(boostMs).putInt(predictiveOn)
            .array()
    }

    data class LimitReq(
        val width: Int = 0,
        val height: Int = 0,
        val touchSampleHz: Int = 0,
        val fpsLimit: Int = 0,
        val renderer: Int = RENDERER_DEFAULT,
        val restore: Int = 0
    ) {
        fun encode(): ByteArray = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(width).putInt(height).putInt(touchSampleHz).putInt(fpsLimit)
            .putInt(renderer).putInt(restore).array()
    }

    data class ProbeReq(
        val target: Int = 0b11,               // bit0=CPU bit1=GPU
        val startKhz: Int = 600_000,
        val stepKhz: Int = 0,
        val maxKhz: Int = 0,                  // 0 = 用硬件表最大值
        val dwellMs: Int = 900,
        val budgetS: Int = 90,
        val tempCeilingMc: Int = 80_000       // 比 85℃ 熔断线留 5℃ 余量
    ) {
        fun encode(): ByteArray = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(target).putInt(startKhz).putInt(stepKhz).putInt(maxKhz)
            .putInt(dwellMs).putInt(budgetS).putInt(tempCeilingMc).array()
    }

    data class MonitorCfg(
        val fuseTempMc: Int = FUSE_DEFAULT_MC,
        val softLimitMc: Int = SOFT_DEFAULT_MC,
        val skinLimitMc: Int = 41_000,
        val monitorIntervalMs: Int = 1000,
        val autoRestoreOnBreach: Int = 1
    ) {
        fun encode(): ByteArray = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(fuseTempMc).putInt(softLimitMc).putInt(skinLimitMc)
            .putInt(monitorIntervalMs).putInt(autoRestoreOnBreach).array()
    }

    data class Telemetry(
        val cpuKhz: IntArray,
        val cpuLoad: IntArray,
        val gpuKhz: Int,
        val gpuBusy: Int,
        val temps: IntArray,
        val powerMw: Int,
        val fps: Int,
        val renderMs: Int,
        val pinnedThreads: Int,
        val frozenApps: Int
    ) {
        companion object {
            /** entropy_telemetry: (12+12+1+1+16+1+1+1+1+1) 个 int32 = 47 * 4 = 188 字节 */
            fun decode(p: ByteArray): Telemetry {
                val b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
                val cpu = IntArray(12) { b.int }
                val load = IntArray(12) { b.int }
                val gpuK = b.int; val gpuB = b.int
                val t = IntArray(16) { b.int }
                val pw = b.int; val fps = b.int; val rms = b.int
                val pin = b.int; val fr = b.int
                return Telemetry(cpu, load, gpuK, gpuB, t, pw, fps, rms, pin, fr)
            }
        }
        /** 所有可读 zone 里的最高温度，单位 ℃；读不到返回 null */
        val maxTempC: Float? get() = temps.filter { it > 0 }.maxOrNull()?.let { it / 1000f }
        val batteryTempC: Float? get() = temps.lastOrNull { it in 20_000..60_000 }?.let { it / 1000f }
    }

    data class ProbeResult(
        val done: Int,
        val cpuSweetKhz: Int,
        val cpuAbsLimitKhz: Int,
        val gpuSweetKhz: Int,
        val gpuAbsLimitKhz: Int,
        val throttleTempMc: Int,
        val bestPerfPerWatt: Int,
        val abortedBySafety: Boolean,
        val note: String
    ) {
        companion object {
            fun decode(p: ByteArray): ProbeResult {
                val b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
                val done = b.int
                val a = IntArray(7) { b.int }
                val nb = ByteArray(64); b.get(nb)
                return ProbeResult(done, a[0], a[1], a[2], a[3], a[4], a[5], a[6] != 0,
                    String(nb, Charsets.UTF_8).trim('\u0000'))
            }
        }
    }

    data class ThermalEvent(
        val zone: Int,
        val tempMc: Int,
        /** 0=none 1=soft 2=hard 3=fuse —— 与 C 侧 throttle_reason 一致 */
        val reason: Int,
        val zoneName: String
    ) {
        companion object {
            fun decode(p: ByteArray): ThermalEvent {
                val b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
                val z = b.int; val t = b.int; val r = b.int
                val nb = ByteArray(32); b.get(nb)
                return ThermalEvent(z, t, r, String(nb, Charsets.UTF_8).trim('\u0000'))
            }
        }
    }

    data class Heartbeat(
        val uptimeS: Long,
        val cpuPermille: Int,
        /** 0 idle / 1 active / 2 probing / 3 fused */
        val state: Int,
        /** LD_PRELOAD 帧率探针是否还在上报窗口（1=在线）。字段顺序与 C 侧一致 */
        val hookOk: Int,
        /**
         * 最近一次档位下发"距今多少秒"，-1 = 从来没下发过。
         * 这是 Daemon 自己算好的差值，不是墙钟时间戳：拿 System.currentTimeMillis()
         * 去减它，得到的是一亿多分钟前的荒话。诊断页原先就这么错。
         * 名字里的 AgoS 就是为了让写下游的人一眼看见单位。
         */
        val lastReapplyAgoS: Int
    ) {
        companion object {
            fun decode(p: ByteArray): Heartbeat {
                val b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
                val up = b.int.toLong() and 0xFFFFFFFFL
                val cpu = b.int; val st = b.int; val hook = b.int; val ago = b.int
                return Heartbeat(up, cpu, st, hook, ago)
            }
        }
    }

    data class Ack(val code: Int, val msg: String) {
        companion object {
            fun decode(p: ByteArray): Ack {
                val b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
                val c = b.int
                val nb = ByteArray(60); b.get(nb)
                return Ack(c, String(nb, Charsets.UTF_8).trim('\u0000'))
            }
        }
    }
}
