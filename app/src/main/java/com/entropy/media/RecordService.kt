// File: app/src/main/java/com/entropy/media/RecordService.kt
//
// 高帧率录屏。MediaProjection 取屏 + MediaCodec（Surface 输入）编码 + MediaMuxer 封 MP4。
//
// 帧率的来源有两条：跟随当前屏幕刷新率，或者用户手选一个值。前者是这台设备
// "此刻真的在跑多少 Hz"，不是机型支持的最高档 —— 120Hz 的机器被省电策略压到
// 60Hz 时，录出来的也应该是 60，否则时间戳和画面会越拉越开。
//
// 三件事决定了这套流程不能省：
//  1. Android 14 起必须先把服务前台化（带 mediaProjection 类型）再取 MediaProjection，
//     顺序反了系统直接抛 SecurityException；
//  2. 编码回调要跑在独立线程上，停止时才能在另一个线程等 EOS 而不把自己堵死；
//  3. EOS 之后必须 muxer.finish()，少这一步得到的是一堆无法播放的 mdat。
package com.entropy.media

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Display
import android.view.Surface
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import com.entropy.EntropyApp
import com.entropy.MainActivity
import com.entropy.R
import com.entropy.data.EntropyStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 录屏的进程级状态。
 *
 * 不放在 Service 里：Service 实例随 stopSelf 一起没了，而界面要长期显示
 * "上一次录到了哪个文件、多大"，悬浮窗那边也得能在服务没起来时读到同一份事实。
 */
object ScreenRecord {
    val recording = mutableStateOf(false)
    val elapsedSec = mutableIntStateOf(0)
    /** 本次实际下发给编码器的帧率，界面用它确认"跟随刷新率"到底跟到了多少 */
    val usedFps = mutableIntStateOf(0)
    val usedSize = mutableStateOf("")
    val lastFile = mutableStateOf<String?>(null)
    val note = mutableStateOf<String?>(null)

    /** 当前屏幕刷新率（Hz）。读不到返回 0，调用方按"未知"处理而不是当成 60 */
    fun currentRefreshHz(ctx: Context): Int = runCatching {
        val d = (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY)
        val hz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) d.refreshRate
                 else @Suppress("DEPRECATION") d.mode.refreshRate
        (hz + 0.5f).toInt()
    }.getOrDefault(0)

    /** 可录帧率的上限。界面显示的档位、下发的 KEY_FRAME_RATE 都按这一个数收口 */
    const val MAX_FPS = 240

    /** 把刷新率读数收成编码器真能接受的帧率，10 是下限 */
    fun clampFps(v: Int) = v.coerceIn(10, MAX_FPS)

    /** 系统那条"开始录屏"授权页的 Intent，必须由 Activity 端 launcher 发出去 */
    fun captureIntent(ctx: Context): Intent =
        manager(ctx).createScreenCaptureIntent()

    fun start(ctx: Context, resultCode: Int, data: Intent) {
        ctx.startForegroundService(Intent(ctx, RecordService::class.java).apply {
            action = RecordService.ACTION_START
            putExtra(RecordService.EXTRA_RESULT_CODE, resultCode)
            putExtra(RecordService.EXTRA_RESULT_DATA, data)
        })
    }

    fun stop(ctx: Context) {
        ctx.startService(Intent(ctx, RecordService::class.java).apply { action = RecordService.ACTION_STOP })
    }

    fun manager(ctx: Context): MediaProjectionManager =
        ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    /** 输出目录。用应用外部目录而不是 filesDir：文件管理器能直接看到，也方便 adb pull 复核 */
    fun outDir(ctx: Context): File {
        val base = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)
            ?: File(ctx.filesDir, "movies")
        return base.also { if (!it.isDirectory) it.mkdirs() }
    }

    fun files(ctx: Context): List<File> =
        outDir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".mp4") }?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /**
     * 把成片登记进系统媒体库（Android 10+）。
     * 10 以下没有分区存储，写 Movies 需要一次性的存储权限，这里不为了它多要一个权限，
     * 直接告诉用户文件已经在应用目录里。
     */
    fun saveToGallery(ctx: Context, src: File): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
            return "已保存在 ${src.absolutePath}（这台机器的相册目录需要存储权限，未自动写入）"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, src.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/熵")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = runCatching {
            ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return "相册写入失败：这台 ROM 的 MediaStore 没接住视频条目"
        val ok = runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().copyTo(out) }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            ctx.contentResolver.update(uri, values, null, null)
            true
        }.getOrDefault(false)
        return if (ok) "已存入系统相册 Movies/熵" else "相册写入失败：改用文件目录里的成片"
    }
}

class RecordService : Service() {

    private var projection: MediaProjection? = null
    private var projCallback: MediaProjection.Callback? = null
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var inputSurface: Surface? = null
    private var virtual: VirtualDisplay? = null
    private var bg: Handler? = null
    private var bgThread: HandlerThread? = null
    private var outPath: String? = null
    private var startedAt = 0L
    private var track = -1
    private var muxStarted = false
    @Volatile private var eos = false
    @Volatile private var frames = 0L
    private var eosLatch: CountDownLatch? = null
    private var closing = false
    private val main = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (!ScreenRecord.recording.value) return
            ScreenRecord.elapsedSec.intValue =
                ((SystemClock.elapsedRealtime() - startedAt) / 1000L).toInt()
            main.postDelayed(this, 1000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (ScreenRecord.recording.value) stopRecording()
            return START_NOT_STICKY
        }
        if (ScreenRecord.recording.value) return START_NOT_STICKY
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (code != Activity.RESULT_OK || data == null) {
            ScreenRecord.note.value = "没有拿到录屏授权，已取消"
            stopSelf()
            return START_NOT_STICKY
        }
        // 前台化必须排在 getMediaProjection 之前，Android 14 上是硬顺序
        foreground()
        begin(code, data)
        return START_NOT_STICKY
    }

    private fun foreground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel(
            EntropyApp.RECORD_CHANNEL, getString(R.string.record_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.record_channel_desc) })
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, EntropyApp.RECORD_CHANNEL)
            .setContentTitle(getString(R.string.record_channel))
            .setContentText(getString(R.string.record_notif_hint))
            .setSmallIcon(R.drawable.ic_entropy_ball)
            .setOngoing(true)
            .addAction(0, getString(R.string.record_notif_stop), stop)
            .setContentIntent(PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIF_ID, n)
    }

    private fun begin(code: Int, data: Intent) {
        closing = false
        val store = EntropyStore.get(this)
        val p = runCatching { ScreenRecord.manager(this).getMediaProjection(code, data) }.getOrNull()
        if (p == null) {
            ScreenRecord.note.value = "系统拒绝了这次录屏授权"
            stopSelf(); return
        }
        projection = p

        val hz = ScreenRecord.currentRefreshHz(this)
        val follow = store.prefs.recordFollowRefresh
        // 刷新率读数要钳：模拟器与个别 ROM 会报出 480Hz 这种没人能录的数，
        // 直接写进 KEY_FRAME_RATE 会让编码器 configure 失败，看起来像"录屏打不开"。
        // 240 是这个应用 everywhere 的天花板，帧率档位与刷新率争抢都按它收口。
        val wanted = if (follow && hz > 0) hz else store.prefs.recordFps
        val fps = ScreenRecord.clampFps(wanted)

        val dm = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        // getRealSize 含状态栏那一圈；用 getSize 会录出缺边的画面，尺寸还和编码器对不上
        @Suppress("DEPRECATION")
        val size = Point().also { dm.getRealSize(it) }
        // 编码器要求偶数边长，奇数直接报错；这里向下取而不是向上，避免多出一列绿边
        val w = (size.x / 2) * 2
        val h = (size.y / 2) * 2
        val dpi = resources.displayMetrics.densityDpi
        if (w <= 0 || h <= 0) {
            ScreenRecord.note.value = "读不到屏幕尺寸，录屏未开始"
            releaseAll(); stopSelf(); return
        }

        val bit = if (store.prefs.recordBitrateMbps > 0)
            store.prefs.recordBitrateMbps * 1_000_000L
        else (w.toLong() * h * fps * 8L / 100L).coerceIn(2_000_000L, 40_000_000L)

        val thread = HandlerThread("entropy-record").also { it.start() }
        bgThread = thread
        val bh = Handler(thread.looper)
        bg = bh

        val fmt = MediaFormat.createVideoFormat(MIME, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bit.toInt())
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            // CAPTURE_RATE 决定采集侧丢帧节奏，FRAME_RATE 只是给码控的目标；
            // 只设后者会得到"编码器以为要 120，实际每秒只送来 60 帧"的空档时间戳
            setFloat(MediaFormat.KEY_CAPTURE_RATE, fps.toFloat())
        }
        val c = runCatching { MediaCodec.createEncoderByType(MIME) }.getOrNull()
        if (c == null) {
            ScreenRecord.note.value = "这台机器没有可用的 H.264 编码器"
            releaseAll(); stopSelf(); return
        }
        codec = c
        val surface = runCatching {
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.createInputSurface()
        }.getOrNull()
        if (surface == null) {
            ScreenRecord.note.value = "编码器初始化失败（分辨率或码率不被支持）"
            releaseAll(); stopSelf(); return
        }
        inputSurface = surface

        val stamp = STAMP.format(Date())
        val file = File(ScreenRecord.outDir(this), "熵录屏-$stamp.mp4")
        outPath = file.absolutePath
        muxer = runCatching { MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) }.getOrNull()
        if (muxer == null) {
            ScreenRecord.note.value = "成片写入失败：${file.absolutePath} 打不开"
            releaseAll(); stopSelf(); return
        }

        c.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(mc: MediaCodec, index: Int) { /* Surface 输入用不到 */ }
            override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) { }

            override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                val m = muxer
                if (m == null) { mc.releaseOutputBuffer(index, false); return }
                if (info.size > 0 && !muxStarted && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    track = m.addTrack(mc.outputFormat)
                    m.start(); muxStarted = true
                }
                val buf = mc.getOutputBuffer(index)
                if (buf != null && info.size > 0 && muxStarted) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val counted = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    runCatching { m.writeSampleData(track, buf, info) }
                        .onSuccess { if (counted) frames++ }
                }
                mc.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    eos = true
                    eosLatch?.countDown()
                }
            }

            override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                ScreenRecord.note.value = "编码器出错：${e.message}"
                main.post { finishUp() }
            }
        }, bh)

        runCatching { c.start() }.onFailure {
            ScreenRecord.note.value = "编码器启动失败：${it.message}"
            releaseAll(); stopSelf(); return
        }

        // 回调要先挂上再建虚拟屏：Android 14 起这个顺序是硬要求，
        // 而且用户在系统通知里点"停止录制"时，只有 onStop 回调能把这次收尾接住
        val cb = object : MediaProjection.Callback() {
            override fun onStop() { main.post { finishUp() } }
        }
        projCallback = cb
        p.registerCallback(cb, bh)

        val vd = runCatching {
            p.createVirtualDisplay(
                "熵录屏", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC, surface, null, bh)
        }.getOrNull()
        if (vd == null) {
            ScreenRecord.note.value = "虚拟屏创建失败：授权可能已经过期，重新点一次"
            releaseAll(); stopSelf(); return
        }
        virtual = vd

        ScreenRecord.usedFps.intValue = fps
        ScreenRecord.usedSize.value = "$w×$h"
        ScreenRecord.recording.value = true
        ScreenRecord.note.value = when {
            follow && hz > 0 && fps < hz -> "刷新率报出 $hz Hz，超出可录上限，按 $fps FPS 录制"
            follow && hz > 0 -> "帧率跟随当前刷新率 $hz Hz"
            follow -> "读不到当前刷新率，退回 $fps FPS"
            else -> "按选择以 $fps FPS 录制"
        }
        startedAt = SystemClock.elapsedRealtime()
        frames = 0L
        main.post(ticker)
    }

    private fun stopRecording() {
        if (closing) return
        closing = true
        // signalEndOfInputStream 只在编码器已经 start 且没崩的情况下有意义
        val latch = CountDownLatch(1)
        eosLatch = latch
        runCatching { codec?.signalEndOfInputStream() }
        Thread {
            // EOS 正常都在几百毫秒内到；等满 1.2s 还不来就强行收尾，
            // 宁可少最后几帧，也不要因为一次卡住把整段成片丢掉
            latch.await(1200L, TimeUnit.MILLISECONDS)
            main.post { finishUp() }
        }.start()
    }

    private fun finishUp() {
        val path = outPath
        ScreenRecord.recording.value = false
        main.removeCallbacks(ticker)
        releaseAll()
        val f = path?.let(::File)
        if (f != null && f.isFile && f.length() > 0) {
            ScreenRecord.lastFile.value = f.absolutePath
            // 编码器只会收到屏幕真正合成出来的那些帧，所以成片帧率只能事后量，
            // 不能拿下发给 KEY_FRAME_RATE 的那个数当结果
            val secs = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
            val real = if (secs > 0.5) frames / secs else 0.0
            ScreenRecord.note.value = "录制完成 · ${f.name} · ${f.length() / 1024 / 1024} MB · " +
                (if (real > 0.5) "实际 %.1f FPS（屏幕只合成出这么多帧）".format(real)
                 else "没有拿到有效帧")
        } else if (path != null) {
            runCatching { File(path).delete() }
            ScreenRecord.note.value = "没有写出有效文件：这次录制没有拿到任何画面"
        }
        stopSelf()
    }

    private fun releaseAll() {
        // 顺序是有讲究的：先撤虚拟屏（停止喂帧），再停编码器，最后才放 Surface。
        // 反过来会在编码器还在取帧时把 Surface 抽走，logcat 里就是一片 buffer 报错
        runCatching { virtual?.release() }; virtual = null
        codec?.let { c ->
            runCatching { c.stop() }
            runCatching { c.release() }
        }
        codec = null
        runCatching { inputSurface?.release() }; inputSurface = null
        if (muxStarted) runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
        muxer = null; muxStarted = false
        // unregisterCallback 要的就是当初注册进去的那个实例，没有无参版本
        projCallback?.let { cb -> runCatching { projection?.unregisterCallback(cb) } }
        projCallback = null
        // stop() 之后同一个 token 不能再取第二次，所以这里必须真的停掉
        runCatching { projection?.stop() }
        projection = null
        runCatching { bgThread?.quitSafely() }; bgThread = null; bg = null
    }

    override fun onDestroy() {
        if (ScreenRecord.recording.value) finishUp() else releaseAll()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.entropy.action.RECORD_START"
        const val ACTION_STOP = "com.entropy.action.RECORD_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val MIME = "video/avc"
        private const val NOTIF_ID = 41004
        private val STAMP = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    }
}
