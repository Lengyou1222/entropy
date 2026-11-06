// File: app/src/main/java/com/entropy/data/GameFrame.kt
//
// 按应用帧率档案、前台跟随、面板刷新率实况。
//
// 三条边界必须先钉死，不然这个功能会被理解成它其实做不到的样子：
//
//  1) "给某个应用单独设帧率上限"只对有两条通道，而且都不通用：
//     · GameManagerService（`cmd game set --fps`）确实能按包干预，
//       但只接受 manifest 声明了 android:appCategory="game" 的包 —— 走 FrameEngine。
//     · debug.sf.max_refresh_rate / vendor.display.max_frame_rate 这一对是**全局**属性。
//       对普通应用，"按应用"的真实机制就是盯着前台包名，轮到谁把全局上限换成谁的档案值，
//       前台换成没配置的应用时再换回去。用户看到的"这个应用的帧率"是真的，
//       但不是应用级隔离 —— 界面必须这样写。
//  2) 不动游戏自己的文件（UE4 的 *.ini、config.lua 之类）。腾讯 ACE/TpGuard
//     把游戏目录的改动当特征文件处理，轻则踢下线重则封号。所有操作留在系统侧。
//  3) 不做进程注入。改 frame pacing 需要在游戏进程里 hook Choreographer，
//     那是同一条封号路径上的另一件事。
package com.entropy.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.entropy.hardware.HardwareBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 面板的一个可用刷新率档 */
data class PanelMode(val id: Int, val width: Int, val height: Int, val hz: Double)

/**
 * 从 SurfaceFlinger 读出来的面板实况。
 *
 * appRequestRanges 才是"应用能要到多少"的那条线：物理面板 120Hz 的机器上，
 * 个别 ROM 会把应用可用区间压到 60~90，这时候把上限设到 120 是设不出来的，
 * 争抢的根源在这一行，不在游戏的帧率设置里。
 */
data class PanelState(
    val modes: List<PanelMode> = emptyList(),
    val activeId: Int = -1,
    val appMaxHz: Double = 0.0,
    val renderMaxHz: Double = 0.0,
) {
    val activeHz: Double get() = modes.firstOrNull { it.id == activeId }?.hz ?: 0.0

    /** 应用能申请到的上限；读不到就退回面板当前档，别让界面显示 0 */
    val ceilingHz: Double get() = if (appMaxHz > 0) appMaxHz else activeHz
}

/** 一条档案：某个包名对应一个帧率上限，0 表示不锁 */
data class GameCap(val pkg: String, val fps: Int)

/**
 * 常见大型手游的公开包名，以及它们在游戏设置里开放过的帧率档。
 *
 * 两件事要说在前面：
 *  · 包名是会变的（渠道服、BETA、海外服各一套），所以界面上每一条都要用
 *    getPackageInfo 当场验一遍，验不过就写"本机没找到"，绝不因为表里有就假装装了。
 *  · "游戏内可开到的档"是厂商按机型白名单放开的。系统侧的帧率上限只能往下压，
 *    不可能替游戏打开它自己没放出来的 90/120 帧。想解锁得改游戏内的 ini/config，
 *    那是 ACE/TpGuard 的封号路径，这个 App 不做。
 */
data class KnownGame(val label: String, val pkg: String, val tiers: String)

val KNOWN_GAMES = listOf(
    KnownGame("和平精英", "com.tencent.tmgp.pubgmhd", "40 / 60 / 90 帧"),
    KnownGame("王者荣耀", "com.tencent.tmgp.sgame", "60 / 90 / 120 帧"),
    KnownGame("使命召唤手游", "com.tencent.tmgp.cod", "60 / 90 / 120 帧"),
    KnownGame("英雄联盟手游", "com.tencent.lolm", "60 / 120 帧"),
    KnownGame("穿越火线：枪战王者", "com.tencent.tmgp.cf", "60 / 90 / 120 帧"),
    KnownGame("金铲铲之战", "com.tencent.jkchess", "60 / 90 帧"),
    KnownGame("暗区突围", "com.tencent.mf.uam", "60 / 90 / 120 帧"),
    KnownGame("原神", "com.miHoYo.Yuanshen", "30 / 45 / 60 帧"),
    KnownGame("崩坏：星穹铁道", "com.miHoYo.hkrpg", "30 / 60 帧"),
    KnownGame("第五人格", "com.netease.dwrg", "30 / 60 帧"),
)

/**
 * 档案串编码：`pkg:fps;pkg:fps`。
 * 项目里没有 JSON 库，为这一个功能引一个依赖不值；
 * 包名和数字都不含 ':' 与 ';'，所以这个格式没有转义问题。
 * 解不开的段落直接丢掉，不让一条脏数据抹掉整张表。
 */
fun encodeCaps(caps: List<GameCap>): String =
    caps.filter { it.pkg.isNotEmpty() }.joinToString(";") { "${it.pkg}:${it.fps}" }

fun decodeCaps(raw: String): List<GameCap> =
    raw.split(';').mapNotNull {
        val parts = it.split(':')
        if (parts.size != 2) return@mapNotNull null
        val fps = parts[1].toIntOrNull() ?: return@mapNotNull null
        if (parts[0].isEmpty()) return@mapNotNull null
        GameCap(parts[0], fps)
    }

/**
 * 与面板同步的可选上限：只有"面板整数倍分频"才值得给。
 *
 * 120Hz 面板配 45fps 会产生 8ms/16.7ms 交替的 3:2 节奏，画面看着反而比
 * 稳定的 40fps 更抖 —— 这才是"掉帧感"的真凶。给整数分频，是把
 * 抖动换成稳定，而不是单纯降帧。
 */
fun syncFpsFor(panelHz: Double): List<Int> {
    val hz = panelHz.toInt()
    if (hz <= 0) return emptyList()
    return (1..6).map { divisor -> hz / divisor }
        .filter { fps -> fps in 24..240 && hz % fps == 0 }
        .distinct()
        .sortedDescending()
}

/** 争抢判定：上限值、面板能力、实测出帧三者对不上的那几种情况 */
enum class RateVerdict(val text: String) {
    OK("上限与面板同档或更低且为整数分频，节奏稳定"),
    JUDER("上限低于面板但不是整数分频，会出现 3:2 式的抽隙抖动"),
    ABOVE_PANEL("上限高于面板可用档，实际会被压回面板值"),
    ABOVE_APP_RANGE("面板把应用可用区间压得比上限更低，ROM 在中间截断"),
    BELOW_MEASURED("实测出帧低于上限：瓶颈在 GPU/引擎，不在锁帧"),
    UNKNOWN("缺少面板或实测数据，无法判定"),
}

fun judge(cap: Int, panel: PanelState?, measured: Int?): RateVerdict {
    if (cap <= 0) return RateVerdict.OK
    val ceiling = panel?.ceilingHz ?: 0.0
    if (ceiling <= 0) return RateVerdict.UNKNOWN
    if (panel != null && panel.appMaxHz > 0 && cap > panel.appMaxHz && cap <= panel.activeHz.coerceAtLeast(1.0))
        return RateVerdict.ABOVE_APP_RANGE
    if (cap > ceiling) return RateVerdict.ABOVE_PANEL
    val hz = ceiling.toInt()
    if (hz % cap != 0 && cap % hz != 0) return RateVerdict.JUDER
    if (measured != null && measured > 0 && measured < cap - 3) return RateVerdict.BELOW_MEASURED
    return RateVerdict.OK
}

/**
 * 前台跟随的执行体。
 *
 * 生命周期挂在进程上而不是页面上：用户是在游戏里享受这个功能的，
 * 设置页关掉之后它还得继续换档，否则"跟随"就成了"看着页面时才生效"的假把式。
 */
class GameFrame private constructor(private val context: Context) {

    private val bridge: HardwareBridge get() = EntropyStore.get(context).bridge
    private val prefs: Prefs get() = EntropyStore.get(context).prefs

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var followJob: Job? = null

    /** 面板实况，进页面时刷一次；跟随循环里低频复扫 */
    var panel by mutableStateOf<PanelState?>(null)
        private set

    /** 当前前台包名 */
    var foreground by mutableStateOf("")
        private set

    /** 跟随引擎上一次真正落到属性里的上限值；-1 = 还没回读过，0 = 未锁。用来避免每一拍都重发 setprop */
    var appliedFps by mutableStateOf(-1)
        private set

    /** 最近一次换档的说明，页面上要能看见"现在是谁、上了多少" */
    var line by mutableStateOf("未开启前台跟随")
        private set

    /** 跟随循环跑在 IO 上，快照状态统一送回主线程写（同 HwRecorder 的约定） */
    private inline fun publish(crossinline block: () -> Unit) = main.launch { block() }

    private fun caps(): Map<String, Int> = decodeCaps(prefs.gameCaps).associate { it.pkg to it.fps }

    fun capOf(pkg: String): Int? = caps()[pkg]

    /** 写档案：同一个包名只留一条；0 视为删除，表里不留一堆无效条目 */
    fun setCap(pkg: String, fps: Int) {
        if (pkg.isEmpty()) return
        val table = caps().toMutableMap()
        if (fps <= 0) table.remove(pkg) else table[pkg] = fps
        prefs.gameCaps = encodeCaps(table.map { GameCap(it.key, it.value) }.sortedBy { it.pkg })
    }

    /** 面板实况 + 属性回读 + 当前前台。进页面和点「重新探测」时调一次 */
    suspend fun refresh() {
        panel = readPanel()
        appliedFps = readLiveCap()
        // 前台包名也读一次：跟随没开时页面照样要告诉用户"现在在前面的是谁"，
        // 否则那一行永远是"未取到"，看起来像是功能坏了
        foreground = readForeground()
    }

    private suspend fun readPanel(): PanelState? = withContext(Dispatchers.IO) {
        val out = if (bridge.path == HardwareBridge.Path.READONLY)
            bridge.shell.plain(PANEL_CMD).out else bridge.shell.root(PANEL_CMD).out
        parsePanel(out)
    }

    /** 属性回读：界面报的是内核里真实生效的上限，不是我们以为写下去的那个数 */
    private suspend fun readLiveCap(): Int = bridge.readFpsLimit()

    /**
     * 开启/关闭前台跟随。
     * 关的时候要把自己上过的锁摘掉，否则会留着一个不属于任何应用的帧率上限 ——
     * 用户关掉功能之后发现整机被锁在 45fps，那比不给这个功能更糟。
     */
    fun setFollow(on: Boolean) {
        prefs.gameFollow = on
        if (on) startFollow() else {
            followJob?.cancel(); followJob = null
            scope.launch {
                if (appliedFps > 0) {
                    val cleared = bridge.clearFpsLimit()
                    publish {
                        appliedFps = if (cleared) 0 else appliedFps
                        line = if (cleared) "跟随已关闭，帧率上限已还原"
                               else "跟随已关闭，但帧率上限没清掉：SF 属性写不回去，去开发模式页手动设一次"
                    }
                } else publish { line = "跟随已关闭" }
            }
        }
    }

    fun startFollow() {
        if (!prefs.gameFollow || followJob?.isActive == true) return
        // 权限还没到手时不开循环：轮询本身要 fork dumpsys，
        // 让一个写不动任何属性的后台循环一直空转，是纯粹的耗电
        if (bridge.path == HardwareBridge.Path.READONLY) {
            line = "跟随未启动：没有 Root 或 Daemon 通道，SF 属性写不进去。拿到权限后会自动接上"
            return
        }
        followJob = scope.launch {
            var changed = false
            while (isActive) {
                val pkg = readForeground()
                // 没有档案时退回全局滑杆的值，而不是清成 0：
                // 前台跟随是"按应用覆盖全局"，不该把用户自己设的上限吃掉
                val target = capOf(pkg) ?: prefs.fpsLimit
                var applied = appliedFps
                var text: String
                if (target != appliedFps) {
                    val ok = if (target <= 0) bridge.clearFpsLimit() else bridge.setFpsLimit(target)
                    if (ok) applied = target
                    val name = nameOf(pkg)
                    text = when {
                        !ok -> "换档失败：SF 属性被 SELinux 拦住，或还没有 Root 通道"
                        target <= 0 -> "$name：无档案，上限跟随系统"
                        else -> "$name：上限 $target FPS"
                    }
                    changed = ok
                } else {
                    text = "跟随中：${nameOf(pkg)} 当前上限 " +
                        (if (appliedFps > 0) "$appliedFps FPS" else "无")
                    changed = false
                }
                publish {
                    foreground = pkg
                    appliedFps = applied
                    line = text
                }
                // 刚换过档说明用户正在应用之间切，盯得紧一点；
                // 稳定期退到 4 秒 —— 每次轮询都要 fork 一个 dumpsys，后台循环不能当它免费
                delay(if (changed) BUSY_POLL_MS else IDLE_POLL_MS)
            }
        }
    }

    /** 应用/清掉一个上限，并同步 appliedFps，避免下一次轮询又重发一遍 */
    suspend fun applyCap(fps: Int): Boolean {
        val ok = if (fps <= 0) bridge.clearFpsLimit() else bridge.setFpsLimit(fps)
        if (ok) appliedFps = fps
        return ok
    }

    private suspend fun readForeground(): String = withContext(Dispatchers.IO) {
        val out = if (bridge.path == HardwareBridge.Path.READONLY)
            bridge.shell.plain(FG_CMD).out else bridge.shell.root(FG_CMD).out
        FG_REGEX.find(out)?.groupValues?.get(1).orEmpty()
    }

    private fun nameOf(pkg: String): String =
        if (pkg.isEmpty()) "无前台应用"
        else runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)

    companion object {
        private const val BUSY_POLL_MS = 1500L
        private const val IDLE_POLL_MS = 4000L

        /**
         * grep -m1 让 dumpsys 写满第一条就收到 EPIPE 提前退出，
         * 比 cat 全部再在 Kotlin 里找省一大截；stderr 里那句
         * "Failed to write while dumping" 是这个用法的正常副产品，不是失败。
         */
        private const val FG_CMD =
            "dumpsys activity activities | grep -m1 topResumedActivity || " +
                "dumpsys window | grep -m1 mCurrentFocus"
        private val FG_REGEX = Regex("""u\d+\s+([A-Za-z0-9._]+)/""")

        private const val PANEL_CMD =
            "dumpsys SurfaceFlinger | grep -m 8 -E 'activeMode=|displayModes=|vsyncRate=|appRequestRanges='"

        /** 只取第一条 display 的 activeMode：后面几条是虚拟屏 */
        private val ACTIVE_REGEX = Regex("""activeMode=\{id=(\d+)""")
        private val MODE_REGEX =
            Regex("""\{id=(\d+), hwcId=\d+, resolution=(\d+)x(\d+), vsyncRate=([\d.]+) Hz""")
        private val RANGE_REGEX = Regex(
            """appRequestRanges=\{physical=\[[\d.]+ Hz, ([\d.]+) Hz], render=\[[\d.]+ Hz, ([\d.]+) Hz]""")

        fun parsePanel(text: String): PanelState? {
            if (text.isEmpty()) return null
            val modes = MODE_REGEX.findAll(text).take(6).map {
                PanelMode(
                    it.groupValues[1].toInt(),
                    it.groupValues[2].toInt(), it.groupValues[3].toInt(),
                    it.groupValues[4].toDoubleOrNull() ?: 0.0)
            }.toList()
            if (modes.isEmpty()) return null
            val active = ACTIVE_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: modes.first().id
            val ranges = RANGE_REGEX.find(text)
            return PanelState(
                modes = modes.distinctBy { it.id }, activeId = active,
                appMaxHz = ranges?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0,
                renderMaxHz = ranges?.groupValues?.get(2)?.toDoubleOrNull() ?: 0.0)
        }

        @Volatile private var instance: GameFrame? = null
        fun get(context: Context): GameFrame =
            instance ?: synchronized(this) {
                instance ?: GameFrame(context.applicationContext).also { instance = it }
            }
    }
}
