// File: app/src/main/java/com/entropy/data/EntropyStore.kt
//
// 全局状态容器。
//
// 为什么不用 ViewModel 而是自建单例：
//  悬浮窗 OverlayService 与 MainActivity 是两个不同的宿主，ViewModel 的作用域
//  各自独立，而"当前档位/熔断状态"必须是同一份 —— 悬浮窗改了档位，
//  主界面要立刻同步显示。所以状态放在进程级单例里，两边都订阅同一个 StateFlow。
package com.entropy.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import com.entropy.hardware.DaemonClient
import com.entropy.hardware.GrantOutcome
import com.entropy.hardware.HardwareBridge
import com.entropy.hardware.HwSampler
import com.entropy.hardware.ShellRunner
import com.entropy.hardware.ShizukuBridge
import com.entropy.hardware.TerminalRunner
import com.entropy.shared.Proto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class UiState(
    val path: HardwareBridge.Path = HardwareBridge.Path.READONLY,
    val connected: Boolean = false,
    val profile: Int = Proto.PROFILE_BALANCED,
    val telemetry: Proto.Telemetry? = null,
    val probe: Proto.ProbeResult? = null,
    val probing: Boolean = false,
    val safety: Safety = Safety(),
    val heartbeat: Proto.Heartbeat? = null,
    val frozenApps: Int = 0,
    val savedCpuMs: Long = 0,
    val busy: Boolean = false,
    val lastMessage: String = "",
)

@Immutable
data class Safety(
    val fused: Boolean = false,
    val daemonState: Int = 0,
    val daemonCpuPermille: Int = 0,
    val reason: String = "",
    val lastThermalTempC: Float? = null,
)

/** 主题模式。放在 data 层是因为它是"用户偏好"，UI 只是它的读取方 */
enum class ThemeMode {
    SYSTEM, LIGHT, DARK;

    val label: String
        get() = when (this) {
            SYSTEM -> "跟随系统"
            LIGHT -> "浅色"
            DARK -> "深色"
        }

    companion object {
        /** 序号越界（版本降级、手工改过 sp）一律退回跟随系统，不抛异常 */
        fun of(value: Int): ThemeMode = entries.getOrNull(value) ?: SYSTEM
    }
}

/**
 * 配色方案。
 *
 * DYNAMIC 是 Android 12+ 的壁纸取色；其余是手调的，深浅各一份。
 * 给固定色板而不是只有动态取色，是因为动态色完全由壁纸决定：
 * 换一张深红壁纸，整个"熵"就变成红色调，而红色在这个 App 里同时是"过热/熔断"的语义色，
 * 满屏红让用户分不清哪块是真在报警。固定色板把这件事交回给用户。
 *
 * 只能往末尾追加：这里存的是 ordinal，中间插一档会把老用户选好的方案整体挪一格。
 */
enum class Palette {
    DYNAMIC, PURPLE, TEAL, GOLD, SAKURA, FROST, MATCHA, EMBER, INK,
    ULTRAMARINE, TWILIGHT, CARNATION, FOLIAGE, CELADON, AZURE, STRAW, PAPER;

    val label: String
        get() = when (this) {
            DYNAMIC -> "跟随壁纸"
            PURPLE -> "熵紫"
            TEAL -> "熵青"
            GOLD -> "熵金"
            SAKURA -> "樱吹雪"
            FROST -> "霜蓝"
            MATCHA -> "抹茶"
            EMBER -> "落霞"
            INK -> "石墨"
            ULTRAMARINE -> "群青"
            TWILIGHT -> "暮山"
            CARNATION -> "石竹"
            FOLIAGE -> "新绿"
            CELADON -> "青瓷"
            AZURE -> "天青"
            STRAW -> "缃叶"
            PAPER -> "素笺"
        }

    companion object {
        /** 越界（版本降级、手工改过 sp）退回出厂默认，而不是退回"跟随壁纸" */
        fun of(value: Int): Palette = entries.getOrNull(value) ?: SAKURA
    }
}

/**
 * 灵动岛展开态上可以勾选的读数项。
 *
 * 安全结论不在这张表里 —— 它是这块岛存在的理由，永久显示，不给关掉。
 * 用位掩码而不是字符串列表存：加一项只是多一个 bit，老用户的偏好不用迁移，
 * 而且"一个都没勾"这种状态可以按 DEFAULT 兜回去，不会出现一块空岛。
 */
enum class HudItem(val bit: Int, val label: String) {
    FPS(0x01, "帧率"),
    TEMP(0x02, "温度"),
    CPU(0x04, "CPU 频率"),
    BATTERY(0x08, "电池温度"),
    THERMAL(0x10, "热档"),
    HEADROOM(0x20, "越线余量"),
    ZONES(0x40, "热区明细"),
    ;

    companion object {
        const val DEFAULT = 0x01 or 0x02 or 0x10
        fun of(mask: Int): List<HudItem> = entries.filter { mask and it.bit != 0 }
    }
}

/**
 * 悬浮控制台的形态。
 * BALL  可拖动、可捏合的熵粒子，点开是它自己的紧凑控制台。
 * PANEL 贴住屏幕左侧的整高控制台，标签栏在左边竖排，控制项比小球控制台多得多。
 *
 * 宽度按屏幕走（见 OverlayService 的 panelWidthPx），不是死的一半屏：
 * 竖屏手机上半屏放不下左侧标签栏加卡片，横屏平板上半屏又太宽。
 *
 * 只有这两种：中间那档"窄条 Dock"和 PANEL 功能重叠、面积又只有它的一半，
 * 留着只会让用户在两个长得很像的面板之间来回找同一个开关。
 */
enum class OverlayForm {
    BALL, PANEL;

    val label: String
        get() = when (this) {
            BALL -> "熵粒子"
            PANEL -> "左侧控制台"
        }

    val shortLabel: String
        get() = when (this) {
            BALL -> "小球"
            PANEL -> "控制台"
        }

    companion object {
        fun of(value: Int): OverlayForm = entries.getOrNull(value) ?: BALL
    }
}

/**
 * 用户可调项，落在 SharedPreferences 里，重启后保持。
 *
 * 每个写操作都要回调一次 onChange：悬浮窗和主界面读的是这些普通值，
 * 它们不是快照状态，改完不会自己触发重组。以前由 OverlayService 自己维护一个
 * uiTick 来补这一刀，结果只有"悬浮窗里改"能刷新悬浮窗，主界面改了悬浮窗还是旧值。
 * 现在把通知收口到写入处，两边改都一样。
 */
class Prefs(context: Context, private val onChange: () -> Unit) {
    private val sp: SharedPreferences = context.getSharedPreferences("entropy", Context.MODE_PRIVATE)

    private fun put(key: String, v: Int) { sp.edit().putInt(key, v).apply(); onChange() }
    private fun put(key: String, v: Float) { sp.edit().putFloat(key, v).apply(); onChange() }
    private fun put(key: String, v: Boolean) { sp.edit().putBoolean(key, v).apply(); onChange() }
    private fun put(key: String, v: String) { sp.edit().putString(key, v).apply(); onChange() }

    /**
     * 偏好文件里的颜色只认两种：alpha 是 0xFF 的真颜色，和 highlight 的 0 哨兵。
     * 其余一律当作写坏了，退回 fallback。
     *
     * 调色板写出的值 alpha 恒为 255（Color.HSVToColor(255, …)），所以 alpha 不对的
     * 整数不可能来自界面，只可能来自损坏或被改过的偏好文件 —— 实测过一台机器上
     * highlight 存成了 1。Color(1) 的 alpha 是 0，一旦被并进 ColorScheme.primary，
     * 全站主色文字就变成"照样排版、照样进无障碍树、一像素都不画"：按钮、页签、
     * 状态行集体空白，看上去像界面坏了。这里不补 alpha 而是直接丢回 fallback ——
     * 把 1 补成 0xFF000001 只是把"看不见"换成"一套发黑的高亮色"，同样是错的。
     */
    private fun saneColor(v: Int, fallback: Int): Int = if (v ushr 24 == 0xFF) v else fallback

    init {
        // 三条温度线的区间整体上移过（旧上限 50℃ 在真实机型上是常态温度，不是危险线）。
        // 老装机里存的还是按旧区间算出来的数，滑杆一打开就停在"已经撞熔断"的位置上，
        // 所以只在第一次读到新区间时把这三把清掉，让新默认值生效；之后用户自己调的不再动。
        if (!sp.getBoolean("thermal_v2", false)) {
            sp.edit().remove("fuse_mc").remove("soft_mc").remove("hud_alert_mc")
                .putBoolean("thermal_v2", true).apply()
        }
    }

    /** 高亮警示色（ARGB）。默认是 Material 的红，用户可从调色板改 */
    var accentColor: Int
        get() = saneColor(sp.getInt("accent", 0xFFFF453A.toInt()), 0xFFFF453A.toInt())
        set(v) = put("accent", saneColor(v, 0xFFFF453A.toInt()))
    /** 温控策略阈值(m°C) —— 注意这是"真实熔断线"，不是伪造温度 */
    var fuseTempMc: Int
        get() = sp.getInt("fuse_mc", Proto.FUSE_DEFAULT_MC)
        set(v) = put("fuse_mc", v.coerceIn(45_000, Proto.FUSE_MAX_MC))
    var softTempMc: Int
        get() = sp.getInt("soft_mc", Proto.SOFT_DEFAULT_MC)
        set(v) = put("soft_mc", v.coerceIn(40_000, Proto.FUSE_MAX_MC - 2_000))
    /** 悬浮窗形态：小球 / 贴住左半屏的大面板 */
    var overlayForm: OverlayForm
        get() = OverlayForm.of(sp.getInt("overlay_form", OverlayForm.BALL.ordinal))
        set(v) = put("overlay_form", v.ordinal)
    var overlayEnabled: Boolean
        get() = sp.getBoolean("overlay_enabled", false)
        set(v) = put("overlay_enabled", v)
    /** 画质增强的两个滑杆（对应 Sharpness / Clarity），单位是厂商后处理档位 */
    var sharpness: Int
        get() = sp.getInt("sharpness", 20)
        set(v) = put("sharpness", v.coerceIn(0, 100))
    var clarity: Int
        get() = sp.getInt("clarity", 15)
        set(v) = put("clarity", v.coerceIn(0, 100))
    /** 渲染器选择，仅用于回显（真实状态以属性回读为准） */
    var renderer: Int
        get() = sp.getInt("renderer", Proto.RENDERER_DEFAULT)
        set(v) = put("renderer", v)
    var fpsLimit: Int
        get() = sp.getInt("fps", 0)
        set(v) = put("fps", v.coerceIn(0, 240))
    /**
     * CPU / GPU 频率上限（kHz）。0 = 不设额外上限，交回调度档自己算。
     * 存的是"意图"，节点上的真实值由 Daemon 每轮 tick 重新下档，所以重启后
     * 第一次下发要等 init() 完成，界面上的读数在此之前显示的是硬件上限。
     */
    var cpuMaxKhz: Int
        get() = sp.getInt("cpu_max_khz", 0)
        set(v) = put("cpu_max_khz", v.coerceAtLeast(0))
    var gpuMaxKhz: Int
        get() = sp.getInt("gpu_max_khz", 0)
        set(v) = put("gpu_max_khz", v.coerceAtLeast(0))
    /**
     * 熵粒子尺寸倍率：捏合缩放的结果。
     * 基准直径 44dp，倍率区间 0.7~1.4 —— 1.4 倍也才 62dp，
     * 再大就会盖住准星附近那块区域，那种需求该用左侧控制台。
     */
    var ballScale: Float
        get() = sp.getFloat("ball_scale", 1f)
        set(v) = put("ball_scale", v.coerceIn(0.7f, 1.4f))
    /** 分辨率预设：0=原生 1=3/4 2=2/3 3=1/2，与 DevMode 的候选表同一套语义 */
    var resPreset: Int
        get() = sp.getInt("res_preset", 0).coerceIn(0, 3)
        set(v) = put("res_preset", v.coerceIn(0, 3))
    var touchHz: Int
        get() = sp.getInt("touch_hz", 240).coerceIn(120, 300)
        set(v) = put("touch_hz", v.coerceIn(120, 300))
    /**
     * 安全显示悬浮窗：一块只写帧率和温度、可以常驻的小窗。
     * 它和悬浮控制台是两块独立窗口，所以开关也独立 —— 关控制台不该把提醒一起关掉。
     */
    var hudEnabled: Boolean
        get() = sp.getBoolean("hud_enabled", false)
        set(v) = put("hud_enabled", v)
    /** 过热提醒阈值(m°C)。和熔断线分开：提醒是给眼睛的，熔断是给调度的 */
    var hudAlertMc: Int
        get() = sp.getInt("hud_alert_mc", Proto.ALERT_DEFAULT_MC)
        set(v) = put("hud_alert_mc", v.coerceIn(40_000, 90_000))
    /** 帧率目标包名。空串表示只信 Daemon 的帧率探针，不做任何近似 */
    var hudTargetPkg: String
        get() = sp.getString("hud_target", "").orEmpty()
        set(v) = put("hud_target", v)
    /** 灵动岛展开态显示哪些读数（HudItem 位掩码）。安全结论不在其中，它永久显示 */
    var hudItems: Int
        get() = sp.getInt("hud_items", HudItem.DEFAULT)
        set(v) = put("hud_items", v)
    /**
     * 按应用帧率档案，编码见 GameFrame.encodeCaps 的注释（`pkg:fps;pkg:fps`）。
     * 存原始串而不是拆成 N 个 key：SharedPreferences 没有前缀枚举的廉价办法，
     * 拆开后"删掉一个游戏的档案"要额外记住它曾经存在过。
     */
    var gameCaps: String
        get() = sp.getString("game_caps", "").orEmpty()
        set(v) = put("game_caps", v)
    /** 前台跟随开关：通道就绪时（runDaemon 之后）自动接上，不需要用户重新点 */
    var gameFollow: Boolean
        get() = sp.getBoolean("game_follow", false)
        set(v) = put("game_follow", v)
    /** 硬件记录采样间隔 */
    var recordIntervalMs: Int
        get() = sp.getInt("record_interval", 2000)
        set(v) = put("record_interval", v.coerceIn(500, 60_000))
    /** 明暗模式：存序号而不是名字，改显示文案不会丢用户的设置 */
    var themeMode: ThemeMode
        get() = ThemeMode.of(sp.getInt("theme_mode", ThemeMode.SYSTEM.ordinal))
        set(v) = put("theme_mode", v.ordinal)
    /** 配色方案：跟随壁纸或十六套手调色板之一；出厂默认樱吹雪 */
    var palette: Palette
        get() = Palette.of(sp.getInt("palette", Palette.SAKURA.ordinal))
        set(v) = put("palette", v.ordinal)
    /**
     * 界面高亮色（ARGB）。0 是哨兵值，表示"跟随主题的 primary"，不自定。
     * 它覆盖的是 primary 那一族，所以滑杆、统计数字、卡片竖标、选中的页签会一起变；
     * 温度梯度不受它影响 —— 过热是安全语义，不能被用户的审美改写成绿色。
     */
    var highlightColor: Int
        get() = saneColor(sp.getInt("highlight", 0), 0)
        set(v) = put("highlight", saneColor(v, 0))
    /** 完全隐藏控制台后，是否还弹"按音量键打开"的引导动画 */
    var showVolumeGuide: Boolean
        get() = sp.getBoolean("vol_guide", true)
        set(v) = put("vol_guide", v)
    /**
     * 防录屏。开起来给悬浮控制台、灵动岛、音量键引导三块窗口都加 FLAG_SECURE，
     * 录屏与截屏拿不到这三层的内容，合成结果里对应区域是黑块。
     */
    var preventRecord: Boolean
        get() = sp.getBoolean("prevent_record", false)
        set(v) = put("prevent_record", v)
    /** 录屏帧率是否跟着当前屏幕刷新率走 */
    var recordFollowRefresh: Boolean
        get() = sp.getBoolean("record_follow_hz", true)
        set(v) = put("record_follow_hz", v)
    /** 手动指定的录屏帧率，只在"不跟随刷新率"时生效 */
    var recordFps: Int
        get() = sp.getInt("record_fps", 60)
        set(v) = put("record_fps", v.coerceIn(10, 240))
    /** 录屏码率（Mbps）。0 表示按分辨率与帧率自动估 */
    var recordBitrateMbps: Int
        get() = sp.getInt("record_bitrate", 0)
        set(v) = put("record_bitrate", v.coerceIn(0, 60))
    /** 录屏边长缩放（百分比）。100 = 原生分辨率，往下缩是为了压体积与编码负载 */
    var recordScalePct: Int
        get() = sp.getInt("record_scale", 100)
        set(v) = put("record_scale", v.coerceIn(25, 100))
    /** 录屏音源：0 不录声音，1 麦克风，2 系统内录（API 29 起才有这条通道） */
    var recordAudio: Int
        get() = sp.getInt("record_audio", 0)
        set(v) = put("record_audio", v.coerceIn(0, 2))
    /** 录到这么多分钟就自动收尾。0 = 不限时，只有用户点停止才停 */
    var recordAutoStopMin: Int
        get() = sp.getInt("record_autostop", 0)
        set(v) = put("record_autostop", v.coerceIn(0, 60))
    /**
     * 每次启动先查一次更新。默认开：这是本项目唯一的升级通道，
     * 关掉以后就只能在设置页手动点"立即检查"。
     */
    var autoUpdateCheck: Boolean
        get() = sp.getBoolean("auto_update", true)
        set(v) = put("auto_update", v)
}

class EntropyStore private constructor(val context: Context) {

    val bridge = HardwareBridge(context)

    /** 免 Root 也能读的硬件采样：安全显示悬浮窗与硬件记录共用同一个取样口径 */
    val sampler by lazy { HwSampler(context, bridge.shell) }

    /** 硬件记录（CSV + 事件），生命周期跟着进程，不跟着 Activity */
    val recorder by lazy { HwRecorder(context, sampler) }

    /** 按应用帧率档案与前台跟随。同样跟着进程：用户是在游戏里享受它，不是在设置页里 */
    val gameFrame by lazy { GameFrame.get(context) }

    /** 帧率增强引擎：三路并行采证 + 系统真实通道的下发 */
    val frameEngine by lazy { FrameEngine.get(context) }

    /** 命令终端的执行端。无状态依赖，一个进程一份就够 */
    val terminal = TerminalRunner()

    /** 偏好变更计数：悬浮窗与主界面读它来感知"普通值被改过" */
    val prefsTick = mutableIntStateOf(0)

    val prefs = Prefs(context) { prefsTick.intValue++ }

    /**
     * 明暗模式的快照状态。主界面和悬浮窗订阅同一份，改一次两边同时变色。
     * 不靠 recreate Activity 生效：悬浮窗根本没有 Activity 可 recreate，
     * 而且 configChanges 里声明了 uiMode，系统翻明暗时 Activity 也不会重建。
     */
    val themeMode = mutableStateOf(prefs.themeMode)

    /**
     * 配色方案、界面高亮色、音量键引导开关的快照状态。
     * 理由同 themeMode：主界面与悬浮窗读同一份，改一次两边同时变 ——
     * 悬浮窗每次显示时从 prefs 重读会得到"刚改完要等下一次重开"的半吊子体验。
     */
    val palette = mutableStateOf(prefs.palette)
    val highlightColor = mutableIntStateOf(prefs.highlightColor)
    /**
     * 警示色也走快照。它是 EntropyTheme 的入参，而主题挂在每块悬浮窗的根上：
     * 靠 prefsTick 感知改动会让"任何一项偏好被写过"都把整块面板从头重组一遍，
     * 拖一根滑杆就是几十次全树重组。单独一个状态，改色时只重排主题那一层。
     */
    val accentColor = mutableIntStateOf(prefs.accentColor)
    val showVolumeGuide = mutableStateOf(prefs.showVolumeGuide)

    /**
     * 防录屏的快照状态。三块 overlay 窗口（控制台、灵动岛、音量键引导）
     * 都订阅它：FLAG_SECURE 是窗口属性，只能靠 updateViewLayout 重排，
     * 靠"下次显示时重读偏好"会变成改完要重开一遍才生效。
     */
    val preventRecord = mutableStateOf(prefs.preventRecord)
    val recordFollowRefresh = mutableStateOf(prefs.recordFollowRefresh)
    val recordFps = mutableIntStateOf(prefs.recordFps)
    val recordBitrateMbps = mutableIntStateOf(prefs.recordBitrateMbps)
    val recordScalePct = mutableIntStateOf(prefs.recordScalePct)
    val recordAudio = mutableIntStateOf(prefs.recordAudio)
    val recordAutoStopMin = mutableIntStateOf(prefs.recordAutoStopMin)
    val autoUpdateCheck = mutableStateOf(prefs.autoUpdateCheck)

    /**
     * 悬浮窗形态与小球尺寸的单一来源。
     *
     * 之前这两个值同时存在于 SharedPreferences 和 OverlayService 的内存状态里，
     * 只在服务恰好收到一次 start 命令时才对齐一次 —— 于是"从应用里切成大面板，
     * 悬浮窗却还是小球"这类切换异常就有了生存空间。
     * 现在两边读同一个快照状态，改一次两边同时生效。
     */
    val overlayForm = mutableStateOf(prefs.overlayForm)

    /**
     * 左侧控制台当前占掉的屏幕宽度（px），0 = 面板没在显示。
     *
     * 这是两个 Service 之间唯一的共享事实：灵动岛和悬浮控制台各挂各的窗口，
     * 岛默认顶部居中，面板从左边铺到 44% 屏宽，两者会实打实叠在一起。
     * 面板显示时岛让开这段宽度、在剩下的区域重新居中，比"谁也不让谁"读得清楚。
     */
    val panelInsetPx = mutableStateOf(0)

    /**
     * 右侧小面板当前占掉的屏幕宽度（px），0 = 没在显示。
     *
     * 和 panelInsetPx 成对：灵动岛要在"两块面板之间"剩下的那条空档里居中，
     * 只让开左边就会变成岛压在右面板上。
     */
    val sideInsetPx = mutableStateOf(0)
    val ballScale = mutableStateOf(prefs.ballScale)

    /**
     * 两块悬浮窗各自"被真的按下"的那一次手势的 downTime。
     *
     * 点空白处收起用的是 FLAG_WATCH_OUTSIDE_TOUCH，而它送进来的 ACTION_OUTSIDE 实测坐标恒为
     * (0,0) —— 想知道这一下落在谁身上，只能看另一块窗有没有收到真正的 DOWN。
     * 比的是手势的 downTime 而不是收到的时刻：一次按下会同时派给两块窗，同一进程同一条
     * Looper，谁先到并不固定（实测两种顺序都出现过），而同一次手势两边带着同一个 downTime。
     * 收到 outside 的一方还要再等一小会儿才判，见 EntropyOverlay.OutsideSettleMs。
     */
    val overlayDownTime = mutableLongStateOf(0L)
    val hudDownTime = mutableLongStateOf(0L)

    /**
     * 安全显示悬浮窗的开关与提醒阈值。
     * 阈值必须是快照状态：HUD 服务在 1Hz 循环里读它，主界面的滑杆也在改它，
     * 两边读同一份才不会出现"拖了滑杆，提醒线还在老位置"。
     */
    val hudEnabled = mutableStateOf(prefs.hudEnabled)
    val hudAlertMc = mutableIntStateOf(prefs.hudAlertMc)
    /** 灵动岛显示哪些读数。快照状态：岛在 1Hz 循环里画它，应用里的勾选框同时改它 */
    val hudItems = mutableIntStateOf(prefs.hudItems)

    /**
     * 当前权限等级。
     *
     * 必须是快照状态而不是 UiState 里的一个字段：置灰的地方散布在首页三块屏、
     * 权限页和悬浮控制台里，它们只订阅 store.level 才会一起变色。
     * 放进 UiState 的话，悬浮控制台那侧拿的是 Activity 传下来的旧快照，
     * 就会出现"Root 已经到手，小球菜单里还是灰的"。
     */
    val level = mutableStateOf(PermLevel.NORMAL)

    /** 推出等级的那几条事实。等级由事实算，事实由 SystemViewModel 一次读完后送进来 */
    private var facts = PermFacts()

    /** 事实变了（或执行路径变了）就重算等级。同值不写，避免整棵依赖树白重组一次 */
    private fun recomputeLevel(path: HardwareBridge.Path) {
        val next = facts.levelOf(path)
        if (level.value != next) level.value = next
    }

    fun updatePermFacts(f: PermFacts) {
        facts = f
        recomputeLevel(bridge.path)
    }

    /** 这一档权限用不了这项功能吗？UI 用它决定灰不灰、锁标写"需要什么" */
    fun locked(f: Feature): Boolean = level.value.rank < f.min.rank

    fun setHudEnabled(on: Boolean) {
        prefs.hudEnabled = on
        hudEnabled.value = on
    }

    /** 写入后回读：Prefs 的 setter 会把越界值钳回 40~90℃，快照要跟落地的值一致 */
    fun setHudAlertMc(mc: Int) {
        prefs.hudAlertMc = mc
        hudAlertMc.intValue = prefs.hudAlertMc
    }

    /** 勾掉最后一项也要留个岛可看：全清时回到默认三项，而不是显示一块只有结论词的岛 */
    fun toggleHudItem(item: HudItem) {
        val next = hudItems.intValue xor item.bit
        prefs.hudItems = if (next == 0) HudItem.DEFAULT else next
        hudItems.intValue = prefs.hudItems
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.themeMode = mode
        themeMode.value = mode
    }

    fun setPalette(p: Palette) {
        prefs.palette = p
        palette.value = p
    }

    /** argb = 0 表示回到"跟随配色方案" */
    fun setHighlightColor(argb: Int) {
        prefs.highlightColor = argb
        // 快照取回读值而不是入参：写入处会把坏值丢掉，这里照抄入参会让两边不一致
        highlightColor.intValue = prefs.highlightColor
    }

    /** 警示色：同样取回读值，Prefs 会把 alpha 不对的整数丢回默认红 */
    fun setAccentColor(argb: Int) {
        prefs.accentColor = argb
        accentColor.intValue = prefs.accentColor
    }

    fun setShowVolumeGuide(on: Boolean) {
        prefs.showVolumeGuide = on
        showVolumeGuide.value = on
    }

    fun setPreventRecord(on: Boolean) {
        prefs.preventRecord = on
        preventRecord.value = on
    }

    fun setAutoUpdateCheck(on: Boolean) {
        prefs.autoUpdateCheck = on
        autoUpdateCheck.value = on
    }

    fun setRecordFollowRefresh(on: Boolean) {
        prefs.recordFollowRefresh = on
        recordFollowRefresh.value = on
    }

    fun setRecordFps(fps: Int) {
        prefs.recordFps = fps
        recordFps.intValue = prefs.recordFps
    }

    /** 0 = 码率交给分辨率与帧率决定 */
    fun setRecordBitrateMbps(mbps: Int) {
        prefs.recordBitrateMbps = mbps
        recordBitrateMbps.intValue = prefs.recordBitrateMbps
    }

    fun setRecordScalePct(pct: Int) {
        prefs.recordScalePct = pct
        recordScalePct.intValue = prefs.recordScalePct
    }

    fun setRecordAudio(mode: Int) {
        prefs.recordAudio = mode
        recordAudio.intValue = prefs.recordAudio
    }

    fun setRecordAutoStopMin(min: Int) {
        prefs.recordAutoStopMin = min
        recordAutoStopMin.intValue = prefs.recordAutoStopMin
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 权限事实的读取入口。Shizuku 的异步变化也要重算等级，不能在 ViewModel 里另建一份 */
    private val sysRepo = SystemRepository(context, bridge.shell)

    /**
     * 订阅 Daemon 事件流。
     * 事件是"推"模型：telemetry 4Hz、heartbeat 1Hz。UI 侧只做状态搬运，
     * 不在这里执行任何 IO，否则悬浮窗会和主界面抢同一个协程线程。
     */
    fun start() {
        watchShizuku()
        scope.launch {
            bridge.events.collect { e ->
                when (e) {
                    is DaemonClient.Event.Telemetry -> _state.value =
                        _state.value.copy(telemetry = e.t, safety = _state.value.safety.copy(
                            lastThermalTempC = e.t.maxTempC
                        ))
                    is DaemonClient.Event.Probe -> _state.value = _state.value.copy(
                        probe = e.r, probing = e.r.done < 100)
                    is DaemonClient.Event.Thermal -> {
                        // reason 3 = 熔断。UI 要把顶栏整体染红并禁用所有抬频按钮
                        val fused = e.e.reason >= 2
                        if (e.e.reason >= 1) recorder.event(
                            "thermal",
                            "reason=${e.e.reason} %.1f℃ zone=${e.e.zoneName}".format(e.e.tempMc / 1000f)
                        )
                        _state.value = _state.value.copy(safety = _state.value.safety.copy(
                            fused = fused,
                            reason = when (e.e.reason) {
                                1 -> "软上限降档（仍在安全区）"
                                2 -> "硬件温控强制降频"
                                3 -> "绝对温度熔断，已全量还原"
                                else -> ""
                            },
                            lastThermalTempC = e.e.tempMc / 1000f
                        ))
                    }
                    is DaemonClient.Event.Heartbeat -> _state.value = _state.value.copy(
                        heartbeat = e.h,
                        safety = _state.value.safety.copy(
                            daemonState = e.h.state,
                            daemonCpuPermille = e.h.cpuPermille,
                            // 锁定状态只跟随 Daemon，不做"或"运算粘住：
                            // 原来写成 `e.h.state == 3 || 旧的 fused`，于是熔断一旦亮过，
                            // 即使 Daemon 已经到点解锁、甚至换了新进程重连，红色横幅也永远挂着，
                            // 用户看到的是一个再也不会消失的假警报。谁是锁的持有者谁说了算。
                            fused = e.h.state == 3))
                    is DaemonClient.Event.Disconnected -> {
                        recorder.event("daemon", "连接中断")
                        _state.value = _state.value.copy(
                            connected = false, safety = _state.value.safety.copy(reason = "Daemon 连接中断"))
                    }
                    is DaemonClient.Event.Ack -> {
                        // 回执是一次性事件，正好是"谁在什么时候把哪个节点改成了什么"的记录点
                        recorder.event("ack", if (e.a.code == 0) "已应用" else "失败: ${e.a.msg} (code=${e.a.code})")
                        _state.value = _state.value.copy(
                            busy = false,
                            lastMessage = if (e.a.code == 0) "已应用" else "失败: ${e.a.msg} (code=${e.a.code})")
                    }
                }
            }
        }
    }

    /** UI 侧即时提示：给那些没有 Daemon Ack 回程的本地动作复用同一条消息带 */
    fun note(msg: String) {
        recorder.event("note", msg)
        _state.value = _state.value.copy(lastMessage = msg)
    }

    fun refreshFreezer() {
        scope.launch {
            val (n, ms) = bridge.freezerStatus()
            _state.value = _state.value.copy(frozenApps = n, savedCpuMs = ms)
        }
    }

    /**
     * 把 Shizuku 的异步事实接进等级计算。
     *
     * 为什么不能只靠权限页的"重新检测"：binder 到达与用户在 Shizuku 那边点"允许"都发生在
     * 我们这边的任何按钮之外 —— 只有订阅能在它发生时把档位抬上去。不接这一条，
     * 用户授权完回到悬浮控制台，分辨率还是灰的，那又是一次"能用了却说不清为什么不能用"。
     *
     * 只对 running / granted 两个变化响应：versionName、uid 这些改了就改，
     * 每次 binder 事件都重算一遍等级会把整棵 UI 依赖树白重组一次。
     */
    private fun watchShizuku() {
        var lastRunning = false
        var lastGranted = false
        scope.launch {
            ShizukuBridge.state.collect { sz ->
                if (sz.running == lastRunning && sz.granted == lastGranted) return@collect
                lastRunning = sz.running
                lastGranted = sz.granted
                withContext(Dispatchers.IO) { bridge.shell.resyncShizuku() }
                updatePermFacts(sysRepo.permFacts(sz))
            }
        }
    }

    /**
     * 应用内请求 Shizuku 授权。
     *
     * 熵能做的只有"发起请求"这一半：确认页由 Shizuku 服务端起，同意与否在它那侧发生，
     * 结果异步从 OnRequestPermissionResultListener 回来 —— 所以这里不等结论，
     * 只把"请求发出去了没有"说清楚。deniedPersistent 的那句提醒由界面上单独一行给出。
     */
    fun requestShizuku() {
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { ShizukuBridge.requestGrant() }
            _state.value = _state.value.copy(lastMessage = when (outcome) {
                GrantOutcome.SENT -> "已在 Shizuku 发起授权请求，请在它的确认页里允许"
                GrantOutcome.ALREADY_GRANTED -> "Shizuku 已授权，不需要重复请求"
                GrantOutcome.NOT_INSTALLED -> "这台机器上没有 Shizuku：改用 adb 授予 WRITE_SECURE_SETTINGS"
                GrantOutcome.NOT_RUNNING -> "Shizuku 装了但没在跑：先把它启动（无线调试或 root），再回来点一次"
            })
        }
    }

    /**
     * 一键运行 entropyd。
     *
     * 顺序是 HardwareBridge.init() 里定死的：探测权限 -> 需要时从 assets 释放二进制 ->
     * 拉起进程 -> 把自己 uid 写进 entropy.allow -> 连 UDS。这里只负责把结果翻译成人话，
     * 三条路径各自的含义（有没有熔断保护）必须写在提示里，否则用户以为"点了就安全了"。
     */
    fun startDaemon(reinstall: Boolean = false) {
        scope.launch { runDaemon(reinstall) }
    }

    /** suspend 版：调用方要"拉起后紧接着刷新状态"时用这个，别去猜 lastMessage */
    suspend fun runDaemon(reinstall: Boolean = false) {
        _state.value = _state.value.copy(busy = true, lastMessage = "正在拉起 entropyd…")
        val p = bridge.init(reinstall)
        // 执行路径是等级的一条输入：adb 拉起的 Daemon 会让没有 su 的本 App 直接升到 Root 档
        recomputeLevel(p)
        // 通道刚变成可写，把上次开着的前台跟随接上；
        // 没开时 startFollow 自己会直接返回，不需要在这里再判一遍
        gameFrame.startFollow()
        _state.value = _state.value.copy(
            path = p,
            connected = bridge.daemon.connected,
            busy = false,
            lastMessage = when (p) {
                HardwareBridge.Path.DAEMON -> "entropyd 已运行，Daemon 通道已连接"
                HardwareBridge.Path.ROOT_SHELL ->
                    "已拿到 Root 但 entropyd 没起来：退回 Root 直写，此路径没有独立熔断线程"
                HardwareBridge.Path.READONLY ->
                    "entropyd 未运行：没有 Root 授权，或二进制释放失败（见权限页）"
            })
        if (p == HardwareBridge.Path.DAEMON) {
            // 重连成功，"Daemon 连接中断"这句已经过期；真正的锁定状态由 1Hz 的 heartbeat 重建
            _state.value = _state.value.copy(
                safety = _state.value.safety.copy(
                    reason = if (_state.value.safety.reason == "Daemon 连接中断") ""
                             else _state.value.safety.reason,
                    fused = false))
            // 把本地保存的阈值下发，保证 App 重装后 Daemon 策略跟 UI 显示一致
            bridge.setMonitorCfg(clampedMonitorCfg())
            refreshFreezer()
        }
    }

    /**
     * 应用内请求 Root。
     * 系统没有"申请 root"这个 API —— 唯一能做的是主动跑一次 su，
     * 让 KernelSU/Magisk 自己弹授权框。detect() 只问 `id -u`，不碰任何节点，
     * 所以"拒绝"也不会留下副作用。
     */
    fun requestRoot() {
        scope.launch {
            _state.value = _state.value.copy(
                busy = true, lastMessage = "已发起 Root 请求，请在 Root 管理器里确认")
            val mode = withContext(Dispatchers.IO) { bridge.shell.detect() }
            if (mode == ShellRunner.Mode.ROOT) runDaemon()
            else _state.value = _state.value.copy(
                busy = false,
                lastMessage = "Root 未授予：在管理器里允许本应用后再点一次「一键运行 entropyd」")
        }
    }

    /** 帧率上限。0 = 跟随系统，走清空属性这条恢复路径 */
    fun setFpsLimit(fps: Int) {
        prefs.fpsLimit = fps
        scope.launch {
            val ok = if (fps == 0) bridge.clearFpsLimit() else bridge.setFpsLimit(fps)
            note(if (ok) "帧率上限：${if (fps == 0) "跟随系统" else "$fps FPS"}"
                 else "帧率上限未生效：该机型的 SF 属性被 SELinux 拦住")
        }
    }

    fun setTouchHz(hz: Int) {
        prefs.touchHz = hz
        scope.launch {
            if (!bridge.setTouchHz(hz))
                note("触控采样率未生效：该机型没有暴露可用节点（或 Daemon 未运行）")
        }
    }

    /** 渲染后端。改完要重启 SystemUI 与目标 App 才生效，提示由调用方给 */
    fun setRenderer(which: Int) {
        prefs.renderer = which
        scope.launch { bridge.setRenderer(which) }
    }

    /** 分辨率预设：0=原生 1=3/4 2=2/3 3=1/2。按面板原生尺寸等比换算，不给物理上不存在的模式 */
    fun setResolutionPreset(which: Int) {
        prefs.resPreset = which
        scope.launch {
            if (which == 0) {
                bridge.resetResolution()
                return@launch
            }
            val (nw, nh) = bridge.profile.nativeResolution
            // 原生尺寸没探到就宁可不改：以前这里落到占位的 1080x2400，
            // 在 900x1600 的屏幕上算出 540x1200 这种整机没注册过的模式并真写了下去
            if (nw <= 0 || nh <= 0) {
                prefs.resPreset = 0
                note("分辨率降档未生效：没探到面板原生尺寸")
                return@launch
            }
            val f = when (which) { 1 -> 0.75f; 2 -> 2f / 3f; else -> 0.5f }
            bridge.setResolution((nw * f).toInt(), (nh * f).toInt())
        }
    }

    /** 屏幕刷新率模式：写 settings，需要 WRITE_SECURE_SETTINGS（只能 ADB 授予） */
    fun setRefreshMode(mode: Int) {
        scope.launch {
            val ok = bridge.setRefreshRate(mode)
            note(if (ok) "刷新率模式已下发：$mode"
                 else "刷新率未生效：需要 WRITE_SECURE_SETTINGS，见权限页的 ADB 命令")
        }
    }

    /** 画质清晰度：节点是厂商私有的，写不进去就如实说，不假装生效 */
    fun setClarity(level: Int) {
        prefs.clarity = level
        scope.launch {
            val ok = bridge.writeNode("/proc/mtkclear/clear_level", level)
            note(if (ok) "清晰度已下发：$level 档" else "画质增强未生效：该机型没有暴露后处理节点")
        }
    }

    /** 绝对熔断线，±步进后仍由 Prefs 与 Daemon 各钳一次（上限见 Proto.FUSE_MAX_MC） */
    fun nudgeFuse(deltaMc: Int) = setFuseTemp(prefs.fuseTempMc + deltaMc)

    fun nudgeSoftLimit(deltaMc: Int) = setSoftTemp(prefs.softTempMc + deltaMc)

    /**
     * 阈值只有一个入口：写偏好 + 立刻下发。
     * 滑杆、步进、对话框各写各的 prefs 的话，总有一处忘了 push，
     * 结果就是界面显示 85℃、Daemon 还按刚才那个值跑 —— 这类不一致比报错更糟。
     */
    fun setFuseTemp(mc: Int) {
        prefs.fuseTempMc = mc
        pushMonitorCfg()
    }

    fun setSoftTemp(mc: Int) {
        prefs.softTempMc = mc
        pushMonitorCfg()
    }

    private fun pushMonitorCfg() {
        val cfg = clampedMonitorCfg()
        scope.launch { bridge.setMonitorCfg(cfg) }
    }

    /**
     * 软上限必须始终低于熔断线，否则"主动退让"这个语义就反了；
     * 反过来的配置 Daemon 会整份拒收，表现成界面显示一套、内核按另一套跑。
     */
    private fun clampedMonitorCfg(): Proto.MonitorCfg {
        if (prefs.softTempMc >= prefs.fuseTempMc) {
            prefs.softTempMc = prefs.fuseTempMc - 1_000
        }
        return Proto.MonitorCfg(fuseTempMc = prefs.fuseTempMc, softLimitMc = prefs.softTempMc)
    }

    fun setBallScale(value: Float) {
        prefs.ballScale = value
        ballScale.value = value
    }

    fun setOverlayForm(form: OverlayForm) {
        prefs.overlayForm = form
        overlayForm.value = form
    }

    /** 明暗三态循环：悬浮窗上只放一个按钮，比放三个 Chip 省地方，效果是一样的 */
    fun cycleTheme() {
        val next = ThemeMode.entries[(prefs.themeMode.ordinal + 1) % ThemeMode.entries.size]
        setThemeMode(next)
    }

    fun setProfile(id: Int) {
        scope.launch {
            _state.value = _state.value.copy(busy = true, profile = id)
            // 上限随档位一起下发：Daemon 的 on_profile 每次都把 cfg 清零再按非零字段覆盖，
            // 换档时不带过去，用户刚设的 CPU/GPU 上限会在下一次 tick 里凭空消失
            val ok = bridge.applyProfile(id, prefs.cpuMaxKhz, prefs.gpuMaxKhz)
            _state.value = _state.value.copy(
                busy = false,
                lastMessage = when {
                    !ok -> "当前无权限：请授予 Root 或用 ADB 拉起 Daemon"
                    else -> "档位已下发"
                })
        }
    }

    /**
     * CPU 频率上限（kHz）。0 是"不设额外限制"，不是"锁在 0 kHz" ——
     * 这一档必须在界面上单独给出来，否则用户拉到底会把机器钉在最低频，
     * 而日志里那条写入看起来是成功的。
     */
    fun setCpuMaxKhz(khz: Int) {
        prefs.cpuMaxKhz = khz
        scope.launch { pushFreqCaps() }
    }

    /** GPU 频率上限（kHz），0 同上 */
    fun setGpuMaxKhz(khz: Int) {
        prefs.gpuMaxKhz = khz
        scope.launch { pushFreqCaps() }
    }

    /** 主界面一次改两项的入口：写 prefs 再整体下发，避免两次 push 各清掉对方 */
    fun setFreqCaps(cpuKhz: Int, gpuKhz: Int) {
        prefs.cpuMaxKhz = cpuKhz
        prefs.gpuMaxKhz = gpuKhz
        scope.launch { pushFreqCaps() }
    }

    /** 两个上限只有一个出口：分开下发会互相清零，见 HardwareBridge.applyFreqCaps */
    private suspend fun pushFreqCaps() {
        val ok = bridge.applyFreqCaps(prefs.cpuMaxKhz, prefs.gpuMaxKhz, _state.value.profile)
        note(if (ok) freqCapsText()
             else "频率上限未生效：需要 Root 或 Daemon，且该机型要暴露可写的频率节点")
    }

    private fun freqCapsText(): String {
        val cpu = if (prefs.cpuMaxKhz == 0) "CPU 不限"
        else "CPU %.2fGHz".format(prefs.cpuMaxKhz / 1_000_000.0)
        val gpu = if (prefs.gpuMaxKhz == 0) "GPU 不限" else "GPU ${prefs.gpuMaxKhz / 1000}MHz"
        return "$cpu · $gpu"
    }

    fun resetAll() {
        scope.launch {
            _state.value = _state.value.copy(busy = true)
            prefs.cpuMaxKhz = 0
            prefs.gpuMaxKhz = 0
            bridge.resetAll()
            _state.value = _state.value.copy(busy = false, lastMessage = "已还原出厂调度",
                safety = _state.value.safety.copy(fused = false, reason = ""))
        }
    }

    fun probe(req: Proto.ProbeReq = Proto.ProbeReq()) {
        scope.launch {
            _state.value = _state.value.copy(probing = true, busy = true)
            val ok = bridge.startProbe(req)
            _state.value = _state.value.copy(
                busy = false,
                probing = ok,
                lastMessage = if (ok) "探测开始（全程受熔断保护）" else "探测需要 Daemon 运行，请先完成授权")
        }
    }

    fun stopProbe() {
        scope.launch { bridge.stopProbe(); _state.value = _state.value.copy(probing = false) }
    }

    fun release() { recorder.release(); bridge.release() }

    companion object {
        @Volatile private var instance: EntropyStore? = null
        fun get(context: Context): EntropyStore =
            instance ?: synchronized(this) {
                instance ?: EntropyStore(context.applicationContext).also { instance = it }
            }
    }
}
