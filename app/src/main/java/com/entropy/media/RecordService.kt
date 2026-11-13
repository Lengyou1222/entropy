// File: app/src/main/java/com/entropy/media/RecordService.kt
//
// 高帧率录屏。MediaProjection 取屏 + MediaCodec（Surface 输入）编码 + MediaMuxer 封 MP4。
//
// 帧率的来源有两条：跟随当前屏幕刷新率，或者用户手选一个值。前者是这台设备
// "此刻真的在跑多少 Hz"，不是机型支持的最高档 —— 120Hz 的机器被省电策略压到
// 60Hz 时，录出来的也应该是 60，否则时间戳和画面会越拉越开。
//
// 声音是第二条轨：麦克风或系统内录（AudioPlaybackCapture，API 29 起）读 PCM，
// 交给另一台 AAC 编码器，和视频一起写进同一个 MP4。两条轨的时间轴必须对齐，
// 所以视频 PTS 归零到第一帧、暂停时长按同一个量从视频 PTS 里扣掉，
// 而音频 PTS 由"真正写进去的采样数"推出来 —— 暂停期间读到的采样直接丢，
// 两边的时间轴就都只包含没暂停的那部分。
//
// 四件事决定了这套流程不能省：
//  1. Android 14 起必须先把服务前台化（带 mediaProjection 类型）再取 MediaProjection，
//     顺序反了系统直接抛 SecurityException；
//  2. 编码回调要跑在独立线程上，停止时才能在另一个线程等 EOS 而不把自己堵死；
//  3. EOS 之后必须 muxer.finish()，少这一步得到的是一堆无法播放的 mdat；
//  4. MediaMuxer 不是线程安全的：视频回调与音频循环都要写它，所有 addTrack/start/
//     writeSampleData/stop 必须收在同一把锁里。
package com.entropy.media

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Display
import android.view.Surface
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.FileProvider
import com.entropy.EntropyApp
import com.entropy.MainActivity
import com.entropy.R
import com.entropy.data.EntropyStore
import java.io.File
import java.nio.ByteBuffer
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
    val paused = mutableStateOf(false)
    val elapsedSec = mutableIntStateOf(0)
    /** 本次实际下发给编码器的帧率，界面用它确认"跟随刷新率"到底跟到了多少 */
    val usedFps = mutableIntStateOf(0)
    val usedSize = mutableStateOf("")
    /** 本次实际用的边长缩放，100 = 原生 */
    val usedScale = mutableIntStateOf(100)
    /** 本次真的接上的音源。选了麦克风但权限没给，这里就是 AUDIO_NONE */
    val audioMode = mutableIntStateOf(AUDIO_NONE)
    val writtenBytes = mutableLongStateOf(0L)
    /** 事后量出来的帧率：编码器只收到屏幕真正合成出来的那些帧 */
    val realFps = mutableFloatStateOf(0f)
    val lastFile = mutableStateOf<String?>(null)
    val note = mutableStateOf<String?>(null)

    const val AUDIO_NONE = 0
    const val AUDIO_MIC = 1
    const val AUDIO_INTERNAL = 2

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

    fun stop(ctx: Context) = send(ctx, RecordService.ACTION_STOP)
    fun pause(ctx: Context) = send(ctx, RecordService.ACTION_PAUSE)
    fun resume(ctx: Context) = send(ctx, RecordService.ACTION_RESUME)

    private fun send(ctx: Context, act: String) {
        ctx.startService(Intent(ctx, RecordService::class.java).setAction(act))
    }

    fun manager(ctx: Context): MediaProjectionManager =
        ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    fun hasAudioPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** 系统内录要 API 29 的 AudioPlaybackCapture，低版本只能选麦克风 */
    fun internalSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun audioLabel(mode: Int) = when (mode) {
        AUDIO_MIC -> "麦克风"
        AUDIO_INTERNAL -> "系统内录"
        else -> "不录声音"
    }

    fun mmss(sec: Int) = "%d:%02d".format(sec / 60, sec % 60)

    fun sizeText(bytes: Long) =
        if (bytes >= 1024 * 1024) "%.1f MB".format(bytes / 1024.0 / 1024.0)
        else "%.0f KB".format(bytes / 1024.0)

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
     * 成片交给别的应用（播放、分享）时必须换成 content://：
     * 直接递 file:// 在 Android 7+ 会让对端抛 FileUriExposedException。
     * 目录要在 file_paths.xml 里放开，否则这里拿不到 uri。
     */
    private fun uriOf(ctx: Context, f: File) = runCatching {
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
    }.getOrNull()

    fun playIntent(ctx: Context, f: File): Intent? {
        val uri = uriOf(ctx, f) ?: return null
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun shareIntent(ctx: Context, f: File): Intent? {
        val uri = uriOf(ctx, f) ?: return null
        return Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, f.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** 只删应用自己目录里的成片，别的路径一律不动 */
    fun delete(ctx: Context, f: File): Boolean =
        f.absolutePath.startsWith(outDir(ctx).absolutePath) && runCatching { f.delete() }.getOrDefault(false)

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
    private var videoTrack = -1
    private var muxStarted = false
    @Volatile private var eos = false
    @Volatile private var frames = 0L
    @Volatile private var bytesWritten = 0L
    private var eosLatch: CountDownLatch? = null
    private var closing = false

    // ---- 声音那条轨 ---- //
    private var audioRecord: AudioRecord? = null
    private var audioCodec: MediaCodec? = null
    private var audioThread: Thread? = null
    private var audioTrack = -1
    /** 本次要不要等音频轨才能开 muxer。接不上就置 false，画面不能陪着一起等 */
    @Volatile private var audioWanted = false
    @Volatile private var audioStopping = false
    private var audioDoneLatch: CountDownLatch? = null
    private var audioFail: String? = null

    /** 视频第一帧的 PTS：两条轨都从 0 起算，播放器才不会把声音甩在画面后面 */
    private var videoPtsBase = -1L
    /** 暂停区间。0 = 没在暂停；视频 PTS 要扣掉累计值，音频靠"丢掉采样"天然不含这段 */
    @Volatile private var pausedAtMs = 0L
    @Volatile private var pausedTotalMs = 0L
    private var autoStopSec = 0

    /** MediaMuxer 的唯一入口锁：视频回调在编码线程，音频循环在自己的线程 */
    private val muxLock = Any()

    /** muxer start 之前暂存的视频样本，start 后按序补写。只由 muxLock 保护 */
    private val pendingVideo = ArrayDeque<VideoSample>()
    private var pendingBytes = 0L

    private class VideoSample(val bytes: ByteArray, val info: MediaCodec.BufferInfo)

    private val main = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (!ScreenRecord.recording.value) return
            val active = activeMs()
            ScreenRecord.elapsedSec.intValue = (active / 1000L).toInt()
            ScreenRecord.writtenBytes.longValue = bytesWritten
            ScreenRecord.realFps.floatValue =
                if (active > 500L) frames * 1000f / active else 0f
            // 有的编码器（模拟器上的软件 AVC 就是）不理 KEY_I_FRAME_INTERVAL，
            // 整段只给开头一个 IDR：不主动要同步帧，成片就没法拖动进度条
            if (pausedAtMs == 0L) runCatching {
                codec?.setParameters(Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                })
            }
            if (autoStopSec > 0 && active >= autoStopSec * 1000L) {
                ScreenRecord.note.value = "已录满 ${autoStopSec / 60} 分钟，自动收尾"
                stopRecording()
                return
            }
            // 音频轨迟迟不来就把声音放弃掉：muxer 要等两条轨都 addTrack 才能 start，
            // 没有这一手，一次内录失败会让整段画面都写不出去
            if (audioWanted && !muxStarted && audioTrack < 0 && active > 3000L) {
                abandonAudio("声音通道没有出帧，这次只录画面")
            }
            main.postDelayed(this, 1000L)
        }
    }

    /** 真正在录的时长：暂停的那几段不算进去 */
    private fun activeMs(): Long {
        val now = SystemClock.elapsedRealtime()
        val paused = pausedTotalMs + (if (pausedAtMs > 0L) now - pausedAtMs else 0L)
        return (now - startedAt - paused).coerceAtLeast(0L)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                if (ScreenRecord.recording.value) stopRecording()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                if (ScreenRecord.recording.value && pausedAtMs == 0L) {
                    pausedAtMs = SystemClock.elapsedRealtime()
                    ScreenRecord.paused.value = true
                }
                return START_NOT_STICKY
            }
            ACTION_RESUME -> {
                if (ScreenRecord.recording.value && pausedAtMs > 0L) {
                    pausedTotalMs += SystemClock.elapsedRealtime() - pausedAtMs
                    pausedAtMs = 0L
                    ScreenRecord.paused.value = false
                }
                return START_NOT_STICKY
            }
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
        // 录声音时还要带 microphone 类型：Android 14 起后台用 AudioRecord 必须声明它，
        // 只报 mediaProjection 会在 startForeground 这一步就被拒
        val audio = EntropyStore.get(this).prefs.recordAudio != ScreenRecord.AUDIO_NONE &&
            ScreenRecord.hasAudioPermission(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                (if (audio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            startForeground(NOTIF_ID, n, type)
        } else startForeground(NOTIF_ID, n)
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
        val scale = store.prefs.recordScalePct.coerceIn(25, 100)
        // 编码器要求偶数边长，奇数直接报错；这里向下取而不是向上，避免多出一列绿边
        val w = ((size.x * scale / 100) / 2) * 2
        val h = ((size.y * scale / 100) / 2) * 2
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

        // 声音排在虚拟屏之前起：音频轨先 addTrack，视频第一帧到达时 muxer 就能立刻 start，
        // 不用丢掉开头那几帧去等另一条轨
        val mode = store.prefs.recordAudio
        audioWanted = mode != ScreenRecord.AUDIO_NONE && startAudio(p, mode)
        ScreenRecord.audioMode.intValue = if (audioWanted) mode else ScreenRecord.AUDIO_NONE

        c.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(mc: MediaCodec, index: Int) { /* Surface 输入用不到 */ }
            override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) { }

            override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                val config = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                if (info.size > 0 && !config) {
                    synchronized(muxLock) {
                        if (videoTrack < 0) {
                            videoTrack = runCatching { muxer?.addTrack(mc.outputFormat) }.getOrNull() ?: -1
                            startMuxerLocked()
                        }
                    }
                }
                val buf = mc.getOutputBuffer(index)
                // 暂停期间照样把缓冲区收下来再丢掉：不收的话编码器会被自己的输出队列堵死。
                // CODEC_CONFIG 那一包不写：csd 已经随 addTrack(outputFormat) 交给 muxer 了，
                // 再当样本写一次会多出一帧没有时间意义的空数据，还会把 PTS 基准带偏
                if (buf != null && info.size > 0 && !config && pausedAtMs == 0L) {
                    synchronized(muxLock) {
                        if (videoPtsBase < 0L) videoPtsBase = info.presentationTimeUs
                        info.presentationTimeUs =
                            (info.presentationTimeUs - videoPtsBase - pausedTotalMs * 1000L)
                                .coerceAtLeast(0L)
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val m = muxer
                        if (muxStarted && m != null && videoTrack >= 0) {
                            runCatching { m.writeSampleData(videoTrack, buf, info) }.onSuccess {
                                frames++; bytesWritten += info.size
                            }
                        } else if (videoTrack >= 0 && pendingBytes < MAX_PENDING_BYTES) {
                            // muxer 还在等音频轨 addTrack 才能 start。这段空窗里的样本先存下来，
                            // start 之后按序补写：开头那帧往往是整段唯一的 IDR，丢掉它
                            // MPEG4Writer 会把后面所有帧当 non-key 跳过，成片就只剩声音
                            val copy = ByteArray(info.size)
                            buf.get(copy)
                            val bi = MediaCodec.BufferInfo()
                            bi.set(0, info.size, info.presentationTimeUs, info.flags)
                            pendingVideo.addLast(VideoSample(copy, bi))
                            pendingBytes += info.size
                        }
                    }
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

        autoStopSec = store.prefs.recordAutoStopMin.coerceIn(0, 60) * 60
        ScreenRecord.usedFps.intValue = fps
        ScreenRecord.usedSize.value = "$w×$h"
        ScreenRecord.usedScale.intValue = scale
        ScreenRecord.writtenBytes.longValue = 0L
        ScreenRecord.realFps.floatValue = 0f
        ScreenRecord.paused.value = false
        ScreenRecord.recording.value = true
        val fpsNote = when {
            follow && hz > 0 && fps < hz -> "刷新率报出 $hz Hz，超出可录上限，按 $fps FPS 录制"
            follow && hz > 0 -> "帧率跟随当前刷新率 $hz Hz"
            follow -> "读不到当前刷新率，退回 $fps FPS"
            else -> "按选择以 $fps FPS 录制"
        }
        val audioNote = when {
            mode == ScreenRecord.AUDIO_NONE -> "不录声音"
            audioWanted && mode == ScreenRecord.AUDIO_MIC -> "麦克风已接入"
            audioWanted -> "系统内录已接入（被录的应用允许采集时才有声音）"
            else -> audioFail ?: "声音没接上，这次只录画面"
        }
        ScreenRecord.note.value = "$fpsNote · $audioNote" +
            (if (autoStopSec > 0) " · ${autoStopSec / 60} 分钟后自动停" else "")
        startedAt = SystemClock.elapsedRealtime()
        frames = 0L
        bytesWritten = 0L
        main.post(ticker)
    }

    // ------------------------------------------------------------------ //
    // 声音                                                                //
    // ------------------------------------------------------------------ //

    /**
     * 起一条 AAC 轨。失败一律返回 false 并留下原因，画面照常录 ——
     * 内录这条通道要看被录应用自己的 allowAudioPlaybackCapture，
     * 游戏普遍把它关掉，所以"选了内录却录不到声音"是常态而不是故障。
     */
    private fun startAudio(p: MediaProjection, mode: Int): Boolean {
        audioFail = null
        if (!ScreenRecord.hasAudioPermission(this)) {
            audioFail = "没有麦克风权限，这次只录画面"
            return false
        }
        if (mode == ScreenRecord.AUDIO_INTERNAL && !ScreenRecord.internalSupported()) {
            audioFail = "系统内录要 Android 10 起，这次只录画面"
            return false
        }
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNELS, ENCODING)
        if (minBuf <= 0) {
            audioFail = "这台机器读不到可用的录音缓冲，这次只录画面"
            return false
        }
        val bufSize = maxOf(minBuf * 2, 8192)
        val rec = runCatching {
            val b = AudioRecord.Builder()
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(ENCODING).setSampleRate(SAMPLE_RATE).setChannelMask(CHANNELS).build())
                .setBufferSizeInBytes(bufSize)
            if (mode == ScreenRecord.AUDIO_INTERNAL) {
                b.setAudioPlaybackCaptureConfig(internalConfig(p))
            } else {
                b.setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            b.build()
        }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec?.release() }
            audioFail = if (mode == ScreenRecord.AUDIO_INTERNAL)
                "系统内录没打开（被录的应用可能不允许采集），这次只录画面"
            else "麦克风没打开，这次只录画面"
            return false
        }
        val ac = runCatching {
            val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufSize)
            }
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
        }.getOrNull()
        if (ac == null) {
            runCatching { rec.release() }
            audioFail = "这台机器没有可用的 AAC 编码器，这次只录画面"
            return false
        }
        audioRecord = rec
        audioCodec = ac
        audioStopping = false
        val done = CountDownLatch(1)
        audioDoneLatch = done
        runCatching { rec.startRecording() }.onFailure {
            audioFail = "录音启动失败，这次只录画面"
            runCatching { ac.stop() }; runCatching { ac.release() }
            runCatching { rec.release() }
            audioCodec = null; audioRecord = null; audioDoneLatch = null
            return false
        }
        audioThread = Thread({ audioLoop(rec, ac, bufSize, done) }, "entropy-record-audio")
            .also { it.start() }
        return true
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun internalConfig(p: MediaProjection): AudioPlaybackCaptureConfiguration =
        AudioPlaybackCaptureConfiguration.Builder(p)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

    /**
     * 读 PCM → 喂 AAC → 写 muxer，全程在这一条线程上同步做。
     * 不用回调式：AudioRecord.read 本来就是阻塞的，两套异步叠在一起只会多一次排队。
     */
    private fun audioLoop(rec: AudioRecord, ac: MediaCodec, bufSize: Int, done: CountDownLatch) {
        val buf = ByteArray(bufSize)
        val info = MediaCodec.BufferInfo()
        // 每帧 4 字节（2 声道 × 16bit）。PTS 按写进去的采样数推，暂停时丢掉的采样不计时
        val bytesPerFrame = 4L
        var ptsUs = 0L
        try {
            while (!audioStopping) {
                val n = runCatching { rec.read(buf, 0, buf.size) }.getOrDefault(-1)
                if (n < 0) break
                if (n == 0) continue
                // 暂停期间也要一直读：不读会让 AudioRecord 的缓冲溢出，恢复后全是杂音
                if (pausedAtMs > 0L) continue
                var fed = 0
                while (fed < n && !audioStopping) {
                    val idx = runCatching { ac.dequeueInputBuffer(10_000L) }.getOrDefault(-1)
                    if (idx < 0) break
                    val ib = runCatching { ac.getInputBuffer(idx) }.getOrNull() ?: break
                    val chunk = minOf(ib.capacity(), n - fed)
                    ib.clear()
                    ib.put(buf, fed, chunk)
                    runCatching { ac.queueInputBuffer(idx, 0, chunk, ptsUs, 0) }
                    ptsUs += (chunk / bytesPerFrame) * 1_000_000L / SAMPLE_RATE
                    fed += chunk
                }
                drainAudio(ac, info)
            }
            // 收尾：byte-buffer 输入没有 signalEndOfInputStream，EOS 要自己 queue 一个空包
            runCatching {
                val idx = ac.dequeueInputBuffer(200_000L)
                if (idx >= 0) ac.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            val deadline = SystemClock.elapsedRealtime() + 1200L
            while (SystemClock.elapsedRealtime() < deadline) {
                if (drainAudio(ac, info)) break
            }
        } catch (t: Throwable) {
            // 编码器已经被拆掉时这里会抛 IllegalStateException：那是停止流程的正常竞态，
            // 不能让它把线程带着未 countDown 的 latch 一起结束
        } finally {
            done.countDown()
        }
    }

    /** 返回 true 表示收到了 EOS */
    private fun drainAudio(ac: MediaCodec, info: MediaCodec.BufferInfo): Boolean {
        while (true) {
            val idx = runCatching { ac.dequeueOutputBuffer(info, 0L) }.getOrDefault(-1)
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) return false
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized(muxLock) {
                    if (audioWanted && audioTrack < 0) {
                        audioTrack = runCatching { muxer?.addTrack(ac.outputFormat) }.getOrNull() ?: -1
                        startMuxerLocked()
                    }
                }
                continue
            }
            if (idx < 0) continue
            val buf = runCatching { ac.getOutputBuffer(idx) }.getOrNull()
            if (buf != null && info.size > 0) {
                synchronized(muxLock) {
                    val m = muxer
                    if (muxStarted && m != null && audioTrack >= 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        runCatching { m.writeSampleData(audioTrack, buf, info) }
                            .onSuccess { bytesWritten += info.size }
                    }
                }
            }
            runCatching { ac.releaseOutputBuffer(idx, false) }
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
        }
    }

    /** 两条轨都 addTrack 之后才能 start。调用方必须已经持有 muxLock */
    private fun startMuxerLocked() {
        if (muxStarted || videoTrack < 0) return
        if (audioWanted && audioTrack < 0) return
        val m = muxer ?: return
        runCatching { m.start() }.onSuccess {
            muxStarted = true
            while (pendingVideo.isNotEmpty()) {
                val s = pendingVideo.removeFirst()
                pendingBytes -= s.bytes.size
                runCatching {
                    m.writeSampleData(videoTrack, ByteBuffer.wrap(s.bytes), s.info)
                }.onSuccess { frames++; bytesWritten += s.bytes.size }
            }
        }
    }

    /** 声音接不上时把它摘掉，让画面这条轨能自己 start */
    private fun abandonAudio(reason: String) {
        audioFail = reason
        audioWanted = false
        audioStopping = true
        ScreenRecord.audioMode.intValue = ScreenRecord.AUDIO_NONE
        synchronized(muxLock) { startMuxerLocked() }
        ScreenRecord.note.value = reason
    }

    // ------------------------------------------------------------------ //
    // 收尾                                                                //
    // ------------------------------------------------------------------ //

    private fun stopRecording() {
        if (closing) return
        closing = true
        // signalEndOfInputStream 只在编码器已经 start 且没崩的情况下有意义
        val latch = CountDownLatch(1)
        eosLatch = latch
        runCatching { codec?.signalEndOfInputStream() }
        val audioLatch = audioDoneLatch
        audioStopping = true
        Thread {
            // EOS 正常都在几百毫秒内到；等满 1.2s 还不来就强行收尾，
            // 宁可少最后几帧，也不要因为一次卡住把整段成片丢掉
            latch.await(1200L, TimeUnit.MILLISECONDS)
            audioLatch?.await(1200L, TimeUnit.MILLISECONDS)
            main.post { finishUp() }
        }.start()
    }

    private fun finishUp() {
        val path = outPath
        val active = activeMs()
        ScreenRecord.recording.value = false
        ScreenRecord.paused.value = false
        main.removeCallbacks(ticker)
        releaseAll()
        val f = path?.let(::File)
        if (f != null && f.isFile && f.length() > 0) {
            ScreenRecord.lastFile.value = f.absolutePath
            // 编码器只会收到屏幕真正合成出来的那些帧，所以成片帧率只能事后量，
            // 不能拿下发给 KEY_FRAME_RATE 的那个数当结果
            val secs = active / 1000.0
            val real = if (secs > 0.5) frames / secs else 0.0
            ScreenRecord.realFps.floatValue = real.toFloat()
            ScreenRecord.writtenBytes.longValue = f.length()
            ScreenRecord.note.value = "录制完成 · ${f.name} · ${ScreenRecord.sizeText(f.length())} · " +
                (if (real > 0.5) "实际 %.1f FPS（屏幕只合成出这么多帧）".format(real)
                 else "没有拿到有效帧") +
                (if (ScreenRecord.audioMode.intValue != ScreenRecord.AUDIO_NONE)
                    " · 含${ScreenRecord.audioLabel(ScreenRecord.audioMode.intValue)}" else "")
        } else if (path != null) {
            runCatching { File(path).delete() }
            ScreenRecord.note.value = "没有写出有效文件：这次录制没有拿到任何画面"
        }
        stopSelf()
    }

    private fun releaseAll() {
        // 声音先收：它的最后几个采样还要写进 muxer，muxer.stop() 必须排在它后面
        audioStopping = true
        runCatching { audioDoneLatch?.await(800L, TimeUnit.MILLISECONDS) }
        runCatching { audioThread?.join(300L) }
        audioThread = null
        audioDoneLatch = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        audioCodec?.let { ac ->
            runCatching { ac.stop() }
            runCatching { ac.release() }
        }
        audioCodec = null
        audioWanted = false
        audioTrack = -1

        // 顺序是有讲究的：先撤虚拟屏（停止喂帧），再停编码器，最后才放 Surface。
        // 反过来会在编码器还在取帧时把 Surface 抽走，logcat 里就是一片 buffer 报错
        runCatching { virtual?.release() }; virtual = null
        codec?.let { c ->
            runCatching { c.stop() }
            runCatching { c.release() }
        }
        codec = null
        runCatching { inputSurface?.release() }; inputSurface = null
        synchronized(muxLock) {
            if (muxStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            muxer = null; muxStarted = false; videoTrack = -1
            pendingVideo.clear(); pendingBytes = 0L
        }
        // unregisterCallback 要的就是当初注册进去的那个实例，没有无参版本
        projCallback?.let { cb -> runCatching { projection?.unregisterCallback(cb) } }
        projCallback = null
        // stop() 之后同一个 token 不能再取第二次，所以这里必须真的停掉
        runCatching { projection?.stop() }
        projection = null
        runCatching { bgThread?.quitSafely() }; bgThread = null; bg = null
        pausedAtMs = 0L; pausedTotalMs = 0L; videoPtsBase = -1L
    }

    override fun onDestroy() {
        if (ScreenRecord.recording.value) finishUp() else releaseAll()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.entropy.action.RECORD_START"
        const val ACTION_STOP = "com.entropy.action.RECORD_STOP"
        const val ACTION_PAUSE = "com.entropy.action.RECORD_PAUSE"
        const val ACTION_RESUME = "com.entropy.action.RECORD_RESUME"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val MIME = "video/avc"
        private const val NOTIF_ID = 41004
        private const val SAMPLE_RATE = 48_000
        /** 等 muxer start 期间最多暂存这么多视频字节；超了就丢新帧，保住开头那帧 IDR */
        private const val MAX_PENDING_BYTES = 6L * 1024 * 1024
        private const val CHANNELS = AudioFormat.CHANNEL_IN_STEREO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private val STAMP = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    }
}