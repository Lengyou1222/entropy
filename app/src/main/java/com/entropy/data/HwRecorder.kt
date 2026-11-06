// File: app/src/main/java/com/entropy/data/HwRecorder.kt
//
// 硬件记录：把 HwSampler 的读数按固定节拍写成 CSV，外加一行式事件。
//
// 三个设计取舍：
//
// 1) 记录器自己开协程循环，不挂在 UI 生命周期上。
//    记录的目的是"游戏跑 30 分钟之后的温度曲线"，中途切后台、锁屏、退出 Activity
//    都不该把记录打断；能打断它的只有用户点"停止"和进程被杀。
//    进程被杀时最后一行可能没写完，所以每行都 flush，丢的最多是最后一拍。
//
// 2) 落在应用私有目录 filesDir/hwlog，不写 /sdcard。
//    写公共目录要存储权限（Android 10+ 还只能走 MediaStore），而"记录"这件事
//    默认就该是本地可读的；导出时才按需复制到下载目录或交给分享 Intent。
//
// 3) 帧率允许是空字段。
//    HwSampler 取不到帧率时给 null，这里写空串而不是 0 ——
//    表格里一个 0 会被读成"这段时间稳定 0 帧"，而空表示"这台机器当时没有可信来源"。
package com.entropy.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.FileProvider
import com.entropy.hardware.HardwareBridge
import com.entropy.hardware.HwSampler
import com.entropy.shared.Proto
import java.io.BufferedWriter
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 记录里的一行。kind 为 sample 时带数值，为 event 时只有 detail */
data class HwRow(
    val ts: Long,
    val kind: String,
    val tempC: String,
    val fps: String,
    val cpuMhz: String,
    val detail: String,
)

/** 一次导出的结果。path 给人看，uri 给分享用，两者都可能为空 —— UI 要如实显示 */
data class ExportResult(val name: String, val bytes: Long, val path: String, val uri: Uri?)

class HwRecorder(private val context: Context, private val sampler: HwSampler) {

    val running = mutableStateOf(false)
    /** 本次会话已写入的采样拍数，UI 用它显示"已记录 N 条" */
    val written = mutableIntStateOf(0)
    val currentFile = mutableStateOf<String?>(null)
    /** 最近一次导出/读取的结论，直接念给用户，不靠 UI 自己猜 */
    val notice = mutableStateOf("")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ring = ArrayDeque<HwRow>(RING_CAPACITY)
    private var job: Job? = null
    private var writer: BufferedWriter? = null
    private var startedAt = 0L
    private var partIndex = 0
    private var bytesSinceRotate = 0L
    private var sampleCount = 0

    val logDir: File get() = File(context.filesDir, "hwlog").apply { mkdirs() }

    // ---------------------------------------------------------------- //
    // 生命周期                                                          //
    // ---------------------------------------------------------------- //

    fun start(intervalMs: Int) {
        if (running.value) return
        running.value = true
        sampleCount = 0
        startedAt = System.currentTimeMillis()
        partIndex = 0
        publish { written.intValue = 0 }
        sampler.resetFps()
        openPart()
        job = scope.launch {
            while (isActive) {
                runCatching { tick() }.onFailure { Log.w(TAG, "record tick failed", it) }
                delay(intervalMs.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS).toLong())
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        closeWriter()
        running.value = false
    }

    fun release() {
        stop(); scope.cancel(); main.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    /**
     * 记一条事件（熔断、档位下发、终端命令）。
     * 不在记录会话中也照样进内存环形缓冲：用户先看到告警、再点开始记录时，
     * 至少刚才那条事件还能被"预览 + 导出"带走。
     */
    fun event(kind: String, detail: String) {
        val row = HwRow(System.currentTimeMillis(), kind, "", "", "", detail)
        push(row)
        if (running.value) writeRow(row, blankColumns())
    }

    fun preview(): List<HwRow> = synchronized(ring) { ring.toList() }

    // ---------------------------------------------------------------- //
    // 采样                                                             //
    // ---------------------------------------------------------------- //

    private suspend fun tick() {
        val store = EntropyStore.get(context)
        val st = store.state.value
        // 目标包名每拍现读，而不是 start 时抓一份快照：
        // 中途换游戏不该逼用户"先停止再开始"，那样前面记的曲线就被切成两个文件。
        // 换包时不必在这里补 reset —— HwSampler 发现包名变了会自己重算差分基线。
        val s = sampler.sample(store.prefs.hudTargetPkg)
        val tele: Proto.Telemetry? = st.telemetry

        // 帧率优先认 Daemon 探针：它按帧时间算，比累计帧数差分更贴当前瞬时值。
        // 探针给 0 的含义是"hook 在线但这一窗口没收到上报"，这时退回 gfxinfo 而不是记 0。
        val probeFps = tele?.fps ?: 0
        val fpsText: String
        val fpsSrc: String
        when {
            probeFps > 0 -> { fpsText = probeFps.toString(); fpsSrc = "帧率探针" }
            s.fps != null -> { fpsText = s.fps.toString(); fpsSrc = s.fpsSource }
            else -> { fpsText = ""; fpsSrc = s.fpsSource }
        }

        val tempC = tele?.maxTempC ?: s.zoneMaxC
        val zoneName = when {
            tele?.maxTempC != null -> "daemon热区"
            s.zones.isNotEmpty() -> s.zones.first().name
            s.batteryC != null -> "battery"
            else -> ""
        }
        val cpuMax = s.cpuKhz.maxOrNull()?.div(1000) ?: 0

        val detail = buildString {
            if (zoneName.isNotEmpty()) append("峰值源=").append(zoneName).append(' ')
            s.batteryC?.let { append("电池=%.1f℃ ".format(it)) }
            if (s.thermalStatus >= 0) append("热档=${s.thermalStatus} ")
            s.headroomS?.let { append("余量=%.0fs ".format(it)) }
            append("帧源=$fpsSrc")
            if (s.memAvailMb > 0) append(" 可用内存=${s.memAvailMb}MB")
        }.trim()

        val row = HwRow(
            ts = s.ts, kind = "sample",
            tempC = tempC?.let { "%.1f".format(it) } ?: "",
            fps = fpsText,
            cpuMhz = if (cpuMax > 0) cpuMax.toString() else "",
            detail = detail,
        )
        push(row)
        if (running.value) {
            writeRow(row, listOf(
                pathLabel(st.path),
                profileLabel(st.profile),
                row.tempC, row.fps, row.cpuMhz,
                s.zones.joinToString(";") { "${it.name}=%.1f".format(it.celsius) },
                s.batteryC?.let { "%.1f".format(it) } ?: "",
                s.thermalStatus.toString(),
                s.headroomS?.let { "%.0f".format(it) } ?: "",
                s.cpuKhz.joinToString("|") { (it / 1000).toString() },
                tele?.gpuBusy?.toString() ?: "",
                tele?.let { "%.2f".format(it.powerMw / 1000f) } ?: "",
                tele?.let { "%.1f".format(it.renderMs / 1000f) } ?: "",
                st.frozenApps.toString(),
                if (st.safety.fused) "1" else "0",
            ))
        }
    }

    private fun blankColumns() = List(15) { "" }

    private fun push(row: HwRow) {
        synchronized(ring) {
            if (ring.size >= RING_CAPACITY) ring.pollFirst()
            ring.addLast(row)
        }
        if (row.kind == "sample") { sampleCount++; publish { written.intValue = sampleCount } }
    }

    /** 快照状态要在主线程写：tick 跑在 IO 线程，直接自增会让 Compose 拿到跨线程的快照 */
    private inline fun publish(crossinline block: () -> Unit) = main.launch { block() }

    // ---------------------------------------------------------------- //
    // 文件                                                             //
    // ---------------------------------------------------------------- //

    private fun openPart() {
        val stamp = STAMP.format(Date(startedAt))
        val suffix = if (partIndex == 0) "" else "-p${partIndex + 1}"
        val f = File(logDir, "熵硬件记录-$stamp$suffix.csv")
        writer = f.bufferedWriter().apply { write(HEADER); newLine(); flush() }
        bytesSinceRotate = HEADER.length + 1L
        // 轮转会发生在中途，openPart 那时跑在 IO 线程上；快照状态统一送回主线程
        val path = f.absolutePath
        publish { currentFile.value = path }
    }

    private fun closeWriter() {
        runCatching { writer?.close() }
        writer = null
    }

    /** 列顺序与 HEADER 一致：时间 / 经过秒 / 类型 由 row 提供，其余 15 列由调用方给 */
    private fun writeRow(row: HwRow, columns: List<String>) {
        val w = writer ?: return
        val cols = buildList {
            add(TIME.format(Date(row.ts)))
            add(((row.ts - startedAt) / 1000).toString())
            add(row.kind)
            addAll(columns)
            add(if (row.kind == "sample") sanitize(row.detail) else sanitize("${row.tempC} ${row.detail}".trim()))
        }
        val line = cols.joinToString(",") { csv(it) }
        runCatching {
            w.write(line); w.newLine(); w.flush()
            bytesSinceRotate += line.length + 1
        }.onFailure { Log.w(TAG, "write failed", it) }
        if (bytesSinceRotate >= ROTATE_BYTES) { closeWriter(); partIndex++; openPart() }
    }

    private fun csv(v: String): String =
        if (v.any { it == ',' || it == '"' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    /** 逗号与引号已由 csv() 处理，这里只清掉会破坏"一行一条"的换行 */
    private fun sanitize(v: String): String = v.replace('\n', ' ').replace('\r', ' ')

    // ---------------------------------------------------------------- //
    // 文件列表与导出                                                     //
    // ---------------------------------------------------------------- //

    fun files(): List<File> =
        logDir.listFiles()?.filter { it.isFile && it.name.endsWith(".csv") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** 删除是 IO，notice 是快照状态 —— 前者在 IO 上跑，后者用 publish 送回主线程 */
    suspend fun clearFiles() {
        withContext(Dispatchers.IO) {
            files().forEach { runCatching { it.delete() } }
        }
        publish { notice.value = "已清空记录文件" }
    }

    /** 把当前（或指定）记录复制到系统下载目录，返回可直接念给用户的路径 */
    suspend fun exportToDownloads(file: File? = null): ExportResult = withContext(Dispatchers.IO) {
        val src = file ?: files().firstOrNull() ?: currentFile.value?.let(::File)
        if (src == null || !src.isFile) {
            return@withContext ExportResult("", 0, "还没有可导出的记录文件：先点开始记录", null)
        }
        val name = "熵硬件记录-${STAMP.format(Date(src.lastModified()))}.csv"
        val bytes = src.length()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                val ok = runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().copyTo(out) }
                    true
                }.getOrDefault(false)
                return@withContext ExportResult(
                    name, bytes,
                    if (ok) "已复制到系统「下载」目录，文件管理器可直接取"
                    else "写入下载目录失败：改用分享导出",
                    if (ok) uri else null
                )
            }
            // insert 返回 null：外部存储不可用（卷被撤、或这台 ROM 改动过 MediaStore）
            return@withContext ExportResult(name, bytes, "写入下载目录失败：改用分享导出", null)
        }
        val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: logDir
        val dst = File(dir, name)
        runCatching { src.copyTo(dst, overwrite = true) }
        ExportResult(name, bytes, dst.absolutePath, null)
    }

    /** 分享 Intent：Android 7+ 只能交 FileProvider 的 content://，交 file:// 对端会崩 */
    fun shareIntent(file: File? = null): Intent? {
        val src = file ?: files().firstOrNull() ?: currentFile.value?.let(::File) ?: return null
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", src)
        }.getOrNull() ?: return null
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, src.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * 导出 Daemon 侧审计日志的尾部。
     *
     * /data/local/tmp/entropy.log 实测是 root:root 0644，文件权限上谁都能读，
     * 但 SELinux 对 shell_data_file 的限制照样会拦普通应用，所以两条路都走：
     * 先试直接读，失败再用 su 的 tail。这个文件在本机能长到几十 MB，
     * 只能按尾部字节取，整读会把内存打满。
     */
    suspend fun exportDaemonLog(tailBytes: Int = 256 * 1024): ExportResult = withContext(Dispatchers.IO) {
        val name = "熵守护日志尾部-${STAMP.format(Date())}.log"
        val bytes = readDaemonTail(tailBytes)
        if (bytes.isEmpty()) {
            return@withContext ExportResult(name, 0,
                "读不到 $DAEMON_LOG：Daemon 没在跑，或本应用没有读取它的权限", null)
        }
        val dst = File(logDir, name)
        runCatching { dst.writeBytes(bytes) }
        val res = exportToDownloads(dst)
        res.copy(path = "${res.path}；源文件 ${(daemonLogSize() / 1024)}KB，本次取尾部 ${bytes.size / 1024}KB")
    }

    private suspend fun readDaemonTail(max: Int): ByteArray {
        runCatching {
            val f = File(DAEMON_LOG)
            if (f.isFile) RandomAccessFile(f, "r").use { raf ->
                val size = raf.length()
                val take = size.coerceAtMost(max.toLong()).toInt()
                if (take > 0) {
                    raf.seek((size - take).coerceAtLeast(0))
                    val buf = ByteArray(take)
                    raf.readFully(buf)
                    // 从半行开始会让首行看着像乱码，直接丢掉不完整的首行
                    val cut = buf.indexOfFirst { it == '\n'.code.toByte() }
                    return if (cut in 0 until buf.size - 1) buf.copyOfRange(cut + 1, buf.size) else buf
                }
            }
        }
        val shell = EntropyStore.get(context).bridge.shell
        val r = shell.root("tail -c $max $DAEMON_LOG", 6)
        return if (r.ok) r.out.toByteArray() else ByteArray(0)
    }

    private fun daemonLogSize(): Long = runCatching { File(DAEMON_LOG).length() }.getOrDefault(0L)

    private fun pathLabel(p: HardwareBridge.Path) = when (p) {
        HardwareBridge.Path.DAEMON -> "Daemon"
        HardwareBridge.Path.ROOT_SHELL -> "Root直写"
        HardwareBridge.Path.READONLY -> "只读"
    }

    private fun profileLabel(id: Int) = when (id) {
        Proto.PROFILE_GAME -> "游戏极速"
        Proto.PROFILE_POWERSAVE -> "极致省电"
        else -> "日常均衡"
    }

    private companion object {
        const val TAG = "EntropyRecord"
        const val RING_CAPACITY = 600
        const val MIN_INTERVAL_MS = 500
        const val MAX_INTERVAL_MS = 60_000
        const val ROTATE_BYTES = 4L * 1024 * 1024
        const val DAEMON_LOG = "/data/local/tmp/entropy.log"

        val TIME = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val STAMP = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

        /** 数值列 15 + 尾部的"说明"列，与 writeRow 拼出来的列数一一对应 */
        val HEADER = listOf(
            "时间", "经过秒", "类型", "执行路径", "档位", "温度C", "帧率", "CPU最大MHz", "热区明细",
            "电池C", "热档", "降频余量s", "各核MHz", "GPU占用%", "功率W", "帧耗时ms", "冻结数", "熔断", "说明",
        ).joinToString(",")
    }
}
