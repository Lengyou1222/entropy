// File: app/src/main/java/com/entropy/data/FrameEngine.kt
//
// 帧率增强引擎：并行采证 + 系统真实存在的下发通道。
//
// 这一页原先叫"插帧"是不准确的，现在按做得到的东西重写：
//
//  1) 采证是并行跑的。三路 dumpsys（应用侧 gfxinfo、合成侧 SurfaceFlinger --latency、
//     面板侧 dumpsys display）各起一个 su 进程，async 起来一起等。
//     总耗时应接近最慢那一路、明显小于三路之和 —— 页面把每路耗时和总耗时都打出来，
//     "并行"这两个字由用户自己核对，不是我说了算。
//  2) 下发走系统真实存在的通道：GameManagerService 的按应用 fps / 降渲染分辨率、
//     DisplayManager 的内容帧率匹配、面板档位与 peak 上限。每条都回读，
//     回读不上就只能显示"已下发未确认"。
//  3) 不做光学补帧。MEMC 要拿到别的应用的解码输出和运动矢量，应用层没有合法入口，
//     厂商的 MEMC 也只开在自家游戏空间里。这条边界不换个好听的名字就会消失。
package com.entropy.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.entropy.hardware.DOWNSCALE_LITERALS
import com.entropy.hardware.HardwareBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/** 一路采证的用时与成败；value=null 时 detail 要说清是没数据还是没权限 */
data class Shot<T>(val name: String, val value: T?, val detail: String, val ms: Long)

/** 展示用的一路结果：名字、有没有拿到、原话、耗时 */
data class ChannelRead(val name: String, val ok: Boolean, val detail: String, val ms: Long)

/** 应用侧：Choreographer 自己报的账，掉帧的源头在这里 */
data class RenderStat(
    val frames: Int = 0,
    val jankyPct: Double = 0.0,
    val p50: Int = 0,
    val p95: Int = 0,
    val p99: Int = 0,
    val missedVsync: Int = 0,
    val highInputLatency: Int = 0,
    val slowUiThread: Int = 0,
    val deadlineMissed: Int = 0,
)

/** 合成侧：真正送到屏上的节奏 */
data class Cadence(
    val fps: Double = 0.0,
    val jitterMs: Double = 0.0,
    val samples: Int = 0,
    val even: Boolean = true,
    val note: String = "",
)

/** 面板侧：这台机器的物理上限与此刻的限制 */
data class PanelCap(
    val renderHz: Double = 0.0,
    val arr: Boolean? = null,
    val thermal: String = "",
    val overrideHz: Double = 0.0,
    val userPreferredModeId: Int = -1,
)

data class FrameReport(
    val pkg: String,
    val render: RenderStat?,
    val cadence: Cadence?,
    val panel: PanelCap?,
    val channels: List<ChannelRead>,
    val totalMs: Long,
) {
    val slowestMs: Long get() = channels.maxOfOrNull { it.ms } ?: 0L
    val serialMs: Long get() = channels.sumOf { it.ms }

    /** 实测出帧，取整后可以直接和锁帧目标对比；采不到就是 null，不拿 0 冒充"没帧" */
    val measuredFps: Int? get() = cadence?.takeIf { it.fps > 0 }?.fps?.roundToInt()
}

class FrameEngine private constructor(private val context: Context) {

    private val bridge: HardwareBridge get() = EntropyStore.get(context).bridge
    private val main = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    var report by mutableStateOf<FrameReport?>(null)
        private set
    var sampling by mutableStateOf(false)
        private set

    /** 最近一次下发的原话。SysOp.detail 直接进这里，中间层不改写措辞 */
    var op by mutableStateOf("还没下发过任何通道")
        private set

    /** 目标应用此刻在系统侧的真实配置，全部来自回读而不是本地记忆 */
    var target by mutableStateOf(""); private set
    var targetFps by mutableStateOf<Int?>(null); private set
    var targetScale by mutableStateOf<Double?>(null); private set
    /** false = 没能和 GameManagerService 谈上（不是游戏类别 / 命令失败 / 没通道） */
    var targetOk by mutableStateOf(false); private set
    var targetNote by mutableStateOf("还没读过这个包的服务端配置"); private set
    var matchPref by mutableStateOf<Int?>(null); private set
    var peakHz by mutableStateOf<String?>(null); private set
    var displayMode by mutableStateOf<String?>(null); private set

    /** 回读到的缩放落回服务端的字面量写法，界面上的选中态就按它比，不做浮点相等判断 */
    val scaleSelection: String?
        get() = targetScale?.let { s -> DOWNSCALE_LITERALS.firstOrNull { abs(it.toDouble() - s) < 0.005 } }

    fun sample(pkg: String) {
        if (pkg.isEmpty() || sampling) return
        sampling = true
        main.launch {
            report = runCatching { sampleNow(pkg) }.getOrNull()
            sampling = false
        }
    }

    private suspend fun sampleNow(pkg: String): FrameReport = withContext(Dispatchers.IO) {
        val t0 = SystemClock.elapsedRealtime()
        val shots = coroutineScope {
            val a = async(Dispatchers.IO) { shot("应用侧 gfxinfo") { readRender(pkg) } }
            val b = async(Dispatchers.IO) { shot("合成侧帧间隔") { readCadence(pkg) } }
            val c = async(Dispatchers.IO) { shot("面板侧 display") { readPanelCap() } }
            Triple(a.await(), b.await(), c.await())
        }
        val (r, cd, p) = shots
        FrameReport(
            pkg = pkg,
            render = r.value,
            cadence = cd.value,
            panel = p.value,
            channels = listOf(r, cd, p).map { ChannelRead(it.name, it.value != null, it.detail, it.ms) },
            totalMs = SystemClock.elapsedRealtime() - t0,
        )
    }

    /** 每路单独计时：不计时就没法证明三路是同时跑的 */
    private suspend fun <T> shot(name: String, block: suspend () -> Pair<T?, String>): Shot<T> {
        val t = SystemClock.elapsedRealtime()
        val (value, detail) = block()
        return Shot(name, value, detail, SystemClock.elapsedRealtime() - t)
    }

    private suspend fun shell(cmd: String): String =
        if (bridge.path == HardwareBridge.Path.READONLY) bridge.shell.plain(cmd).out
        else bridge.shell.root(cmd).out

    // ---- 三路采集 ----

    private suspend fun readRender(pkg: String): Pair<RenderStat?, String> {
        if (!bridge.shell.isSafePackage(pkg)) return null to "包名不合法，没有执行这条命令"
        val out = shell(gfxCmd(pkg))
        val frames = FRAMES.find(out)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null to "这个进程没有帧统计：没画过东西，或 ROM 把统计关了"
        val pct = JANKY.find(out)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        return RenderStat(
            frames = frames,
            jankyPct = pct,
            p50 = num(P50, out), p95 = num(P95, out), p99 = num(P99, out),
            missedVsync = num(MISSED, out),
            highInputLatency = num(HIGH_INPUT, out),
            slowUiThread = num(SLOW_UI, out),
            deadlineMissed = num(DEADLINE, out),
        ) to "共 $frames 帧，Jank $pct%"
    }

    private suspend fun readCadence(pkg: String): Pair<Cadence?, String> {
        if (!bridge.shell.isSafePackage(pkg)) return null to "包名不合法，没有执行这条命令"
        val lines = shell(latencyCmd(pkg)).lines()
            .map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 4)
            return null to "SurfaceFlinger 只给了周期没给帧时间戳：本机合成层没开延迟统计"
        val present = lines.drop(1).mapNotNull { line ->
            val f = line.split(WHITESPACE).mapNotNull { it.toLongOrNull() }
            if (f.size >= 3 && f[2] > 0L) f[2] else null
        }
        val gaps = present.zipWithNext { x, y -> (y - x) / 1_000_000.0 }
            .filter { it in 1.0..500.0 }.sorted()
        if (gaps.size < 3) return null to "可对齐的出帧样本只有 ${gaps.size} 条，太少"
        val med = gaps[gaps.size / 2]
        val buckets = gaps.groupingBy { (it * 2).roundToInt() / 2.0 }.eachCount()
            .entries.sortedByDescending { it.value }.take(2)
        // 出现两档间隔、且长档约等于短档的 1.3~1.9 倍，就是 cadence 在交替：
        // 这是"看 24fps 视频觉得规律性抽动"的实测形态，比单看平均帧率有用
        val longKey = buckets.maxOf { it.key }
        val shortKey = buckets.minOf { it.key }
        val twoLevel = buckets.size == 2 && shortKey > 0 && longKey / shortKey in 1.3..1.9
        val fps = 1000.0 / med
        return Cadence(
            fps = fps,
            jitterMs = gaps.last() - gaps.first(),
            samples = gaps.size,
            even = !twoLevel,
            note = if (twoLevel)
                "间隔分两档（${longKey}ms / ${shortKey}ms），节奏不齐"
            else "帧间隔均匀",
        ) to "实测 ${"%.1f".format(fps)}fps，样本 ${gaps.size} 条"
    }

    private suspend fun readPanelCap(): Pair<PanelCap?, String> {
        val out = shell(DISPLAY_CMD)
        val hz = RENDER_HZ.find(out)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: return null to "读不到面板渲染帧率"
        val arr = ARR.find(out)?.groupValues?.get(1)?.toBooleanStrictOrNull()
        return PanelCap(
            renderHz = hz,
            arr = arr,
            thermal = THERMAL.find(out)?.groupValues?.get(1)?.trim().orEmpty(),
            overrideHz = OVERRIDE.find(out)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0,
            userPreferredModeId = USER_MODE.find(out)?.groupValues?.get(1)?.toIntOrNull() ?: -1,
        ) to "${"%.0f".format(hz)}Hz，自适应刷新率" +
            if (arr == true) "支持" else if (arr == false) "不支持" else "未知"
    }

    private fun num(re: Regex, text: String): Int =
        re.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    // ---- 目标应用的系统侧回读 ----

    /** 选中目标后先读它此刻到底被设成了什么；界面上不显示"我们以为写下去的值" */
    fun refreshTarget(pkg: String) {
        target = pkg
        // 每一项都要 fork 一个 su 进程，全部压到主线程就是几十到几百毫秒的白屏
        main.launch {
            val read = withContext(Dispatchers.IO) {
                if (pkg.isEmpty()) null else TargetRead(
                    bridge.readGameConfig(pkg),
                    bridge.readMatchContentFrameRate(),
                    bridge.readPeakRefreshRate(),
                    bridge.readUserPreferredDisplayMode())
            }
            val cfg = read?.config
            targetFps = cfg?.fps
            targetScale = cfg?.scale
            targetOk = cfg?.ok == true
            targetNote = cfg?.detail ?: if (pkg.isEmpty()) "没选目标" else "读不到服务端配置"
            matchPref = read?.match
            peakHz = read?.peak
            displayMode = read?.mode
        }
    }

    private data class TargetRead(
        val config: HardwareBridge.GameConfig, val match: Int?, val peak: String?, val mode: String?,
    )

    /**
     * 内容帧率匹配在这台机器上有没有物理意义，取决于面板支不支持 ARR。
     * hasArrSupport=false 时打开它只改了服务端的偏好、面板还是固定档，
     * 这话得说在前面，而不是等用户去找"为什么没变化"。
     */
    fun matchContentFeasible(): Boolean = report?.panel?.arr != false

    // ---- 下发 ----

    /**
     * 按应用干预只认游戏类别的包 —— 判据照 GameManagerService 自己的写法：
     * category == CATEGORY_GAME 或带 FLAG_IS_GAME，两个都不满足就是普通应用。
     * 注意 ApplicationInfo 里 CATEGORY_GAME = 0、CATEGORY_UNDEFINED = -1，
     * 没声明 appCategory 的应用落在 -1，不是 0，别把默认值当成游戏。
     * 普通应用被拒之后不退回去偷偷改用全局属性：那会把整机的帧率一起锁住，
     * 是用户没要的事情。
     */
    fun isGameCategory(pkg: String): Boolean = runCatching {
        val ai = context.packageManager.getApplicationInfo(pkg, 0)
        ai.category == ApplicationInfo.CATEGORY_GAME ||
            (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0
    }.getOrDefault(false)

    fun categoryText(pkg: String): String = runCatching {
        val ai = context.packageManager.getApplicationInfo(pkg, 0)
        when {
            isGameCategory(pkg) -> "游戏类：服务端接受按应用干预"
            ai.category == ApplicationInfo.CATEGORY_UNDEFINED ->
                "没声明应用类别：服务端不接受按应用锁帧"
            else -> "类别 ${ai.category}（非游戏）：服务端不接受按应用锁帧"
        }
    }.getOrDefault("读不到应用类别")

    /**
     * 锁帧。缩放那一档按当前回读值一起带上：`cmd game set` 写的是一整份 custom 配置，
     * 只发一个旗标会把另一个当"未设置"清掉 —— 用户取消降分辨率顺手丢了锁帧这种事不能发生。
     * 回读不通时直接拒，不拿"就当它没设过"来猜。
     */
    fun applyFps(fps: Int) = configOp { bridge.setGameConfig(target, fps, scaleSelection ?: "disable") }

    fun applyScale(lit: String) = configOp { bridge.setGameConfig(target, targetFps ?: 0, lit) }

    private fun configOp(block: suspend () -> HardwareBridge.SysOp) {
        val refusal = when {
            target.isEmpty() -> "被拒绝：还没选目标"
            !targetOk -> "被拒绝：没读到这个包当前的服务端配置（$targetNote）。" +
                "帧率和缩放共用一份配置，猜不得 —— 先按「并行采证三路」重读一次"
            else -> null
        }
        if (refusal != null) { op = refusal; return }
        submit(refresh = true, block = block)
    }

    fun applyMatchPref(pref: Int) = submit { bridge.setMatchContentFrameRate(pref) }
    fun applyPeak(hz: Int) = submit { bridge.setPeakRefreshRate(hz) }
    fun clearPeak() = submit { bridge.clearPeakRefreshRate() }

    /** refresh=true：下发完立刻重读，界面上的选中态跟着机器里的真值走，而不是跟着刚点过的那一下 */
    private fun submit(refresh: Boolean = false, block: suspend () -> HardwareBridge.SysOp) {
        main.launch {
            op = runCatching { block() }.fold(
                { s ->
                    when {
                        !s.ok -> "被拒绝：${s.detail}"
                        s.confirmed -> "已生效：${s.detail}"
                        else -> "已下发、未确认：${s.detail}"
                    }
                },
                { e -> "异常：${e.message ?: e.javaClass.simpleName}" })
            if (refresh) refreshTarget(target)
        }
    }

    companion object {
        // 包名先过 isSafePackage 再拼进命令；用函数而不是 %1$s 模板，
        // 避开 Kotlin 字符串里那个 $ 的转义坑
        private fun gfxCmd(pkg: String) =
            "dumpsys gfxinfo '$pkg' | grep -E 'Total frames rendered|Janky frames:|" +
                "50th percentile|95th percentile|99th percentile|" +
                "Missed Vsync|High input latency|Slow UI thread|Frame deadline missed'"

        private fun latencyCmd(pkg: String) = "dumpsys SurfaceFlinger --latency '$pkg'"

        private const val DISPLAY_CMD = "dumpsys display | grep -m 2 -E 'DisplayDeviceInfo|mBaseDisplayInfo'"

        private val WHITESPACE = Regex("\\s+")
        private val FRAMES = Regex("Total frames rendered:\\s*(\\d+)")
        private val JANKY = Regex("Janky frames:\\s*\\d+\\s*\\(([\\d.]+)%\\)")
        private val P50 = Regex("50th percentile:\\s*(\\d+)ms")
        private val P95 = Regex("95th percentile:\\s*(\\d+)ms")
        private val P99 = Regex("99th percentile:\\s*(\\d+)ms")
        private val MISSED = Regex("Number Missed Vsync:\\s*(\\d+)")
        private val HIGH_INPUT = Regex("Number High input latency:\\s*(\\d+)")
        private val SLOW_UI = Regex("Number Slow UI thread:\\s*(\\d+)")
        private val DEADLINE = Regex("Number Frame deadline missed:\\s*(\\d+)")
        private val RENDER_HZ = Regex("renderFrameRate ([\\d.]+)")
        private val ARR = Regex("hasArrSupport (true|false)")
        // 结尾的 } 必须转义：Android 的正则走 ICU，裸 } 会直接抛
        // PatternSyntaxException，且发生在 <clinit> 里 —— 整个类初始化失败
        private val THERMAL = Regex("thermalRefreshRateThrottling \\{([^}]*)\\}")
        private val OVERRIDE = Regex("refreshRateOverride ([\\d.]+)")
        private val USER_MODE = Regex("userPreferredModeId (-?\\d+)")

        @Volatile private var instance: FrameEngine? = null
        fun get(context: Context): FrameEngine =
            instance ?: synchronized(this) {
                instance ?: FrameEngine(context.applicationContext).also { instance = it }
            }
    }
}
