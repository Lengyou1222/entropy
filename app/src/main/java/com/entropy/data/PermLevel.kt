// File: app/src/main/java/com/entropy/data/PermLevel.kt
//
// 权限等级与功能门槛。
//
// 分档的依据是"这一档在真机上实际改得动什么"，不是用户自称有什么：
//   普通 —— 只读。Build/procfs/sysfs 读得到就显示，写操作一律不落笔。
//   Shizuku/ADB —— 拿到了 shell 那层的系统写入能力（分辨率、刷新率、dumpsys），
//                  但仍然碰不到 cpufreq/sysfs 与 setprop，那些不属于 shell uid。
//   Root —— 直接写内核节点，或以 root 身份跑 entropyd 走 Daemon 通道。
//
// Shizuku 这一档的判据是"授权到手了没有"，不是"Shizuku 进程活不活"。
// 本 App 确实集成了 Shizuku 客户端：绑它的 binder、在应用内发请求授权、
// 授权过之后命令走它的远端进程。那边的身份跟着 Shizuku 的启动方式变：adb 拉起是 shell(2000)，
// root 拉起就是 root(0) —— 界面按实际读到的 uid 说，不写死 shell。所以三个事实分开报：
// 装了没有、binder 在不在、授没授权 —— 压成一句"Shizuku 不可用"会让人去重启一个本来正常的服务。
// adb 那条 pm grant 依然是等价路径，由谁授权不重要，能落到 shell 身份才算数。
package com.entropy.data

import com.entropy.hardware.HardwareBridge

enum class PermLevel(val rank: Int, val label: String, val caption: String, val how: String) {
    NORMAL(
        0, "普通", "只读 + 悬浮窗",
        "装上就能用。能读硬件画像、看温度与调度状态，也能开悬浮控制台与灵动岛；" +
            "所有写操作都会被置灰。"
    ),
    SHIZUKU(
        1, "Shizuku / ADB", "屏幕设置与帧率读数",
        "两条路任选一条：\n" +
            "① Shizuku 已启动时点下面的「请求 Shizuku 授权」，在它弹出的确认页里允许。" +
            "授权页会列出「熵」，之后也能在那里收回。命令直接在 Shizuku 的远端进程里执行，" +
            "分辨率、刷新率、真实帧率差分三项全部可用。\n" +
            "② 用 adb 执行下面这条，只把系统设置的写权限给到本 App：\n" +
            "pm grant com.entropy android.permission.WRITE_SECURE_SETTINGS\n" +
            "这条路只解锁刷新率一项 —— 分辨率与 dumpsys 帧率读数要的是远端 shell/root 身份，" +
            "不是这条权限能给到的。"
    ),
    ROOT(
        2, "Root", "全部调度能力",
        "在 KernelSU / Magisk / APatch 里授予本应用 su，然后点「一键运行 entropyd」。" +
            "解锁档位下发、温控阈值、极限探测与后台冻结。" +
            "不想给 Root 也可以用 adb 把 entropyd 拉起来，App 连上同一个 Daemon 通道。"
    );

    companion object {
        /** 按序号还原；越界（改过枚举顺序、手工改过 sp）退回普通，绝不因为读到一个怪值就谎报高等级 */
        fun of(value: Int): PermLevel = entries.firstOrNull { it.rank == value } ?: NORMAL
    }
}

/**
 * 一项功能的最低门槛。
 * 把"谁需要什么等级"收在这一个表里：界面各处只写 Feature.X，不再各自比较 rank。
 * 否则同一条"要 Root"的判据会在首页、开发页、悬浮控制台各抄一遍，
 * 改一档漏一处，就会出现"首页能点、悬浮窗灰着"这种说不清的错位。
 */
enum class Feature(
    val title: String,
    val min: PermLevel,
    /** 为什么需要这一级 —— 置灰时要当场说清楚，不能只给一把锁 */
    val why: String,
) {
    READ_HW("读取硬件信息", PermLevel.NORMAL, "Build、procfs 与 sysfs 的只读访问，任何身份都拿得到"),
    OVERLAY("悬浮控制台", PermLevel.NORMAL, "要的是「显示在其他应用上层」这条系统开关，与权限等级无关"),
    ISLAND("灵动岛安全显示", PermLevel.NORMAL, "温度读得到就能提醒；帧率读不到时照实显示「—」"),
    HOT_ALERT("过热提醒通知", PermLevel.NORMAL, "本地判定 + 通知，不写任何节点"),

    RESOLUTION("渲染分辨率缩放", PermLevel.SHIZUKU, "走 wm size，只有远端 shell/root 身份（Shizuku、ADB 或 Root）会被 WindowManager 接受"),
    REFRESH_RATE("屏幕刷新率模式", PermLevel.SHIZUKU, "写 Settings.System，需要 WRITE_SECURE_SETTINGS"),
    FPS_READ("真实帧率差分", PermLevel.SHIZUKU, "dumpsys gfxinfo 的累计出帧只有 shell 或 root 读得到"),

    PROFILE("调度档位下发", PermLevel.ROOT, "写 scaling_max_freq 等内核节点，普通应用与 shell 都没有这个口子"),
    CPU_FREQ("CPU 频率上限", PermLevel.ROOT, "改的是各簇 scaling_max_freq，内核节点只有 root 或 Daemon 写得动"),
    GPU_FREQ("GPU 频率上限", PermLevel.ROOT, "kgsl 的 max_gpu_clock 与 devfreq 的 max_freq 都是内核节点，且抬频要由熔断线看护"),
    THERMAL_CFG("温控阈值策略", PermLevel.ROOT, "阈值要交给以 root 身份运行的 entropyd 才有人执行"),
    PROBE("CPU/GPU 极限探测", PermLevel.ROOT, "探测会短时抬频，必须由 Daemon 的熔断线程全程看护"),
    FREEZE("后台真·暂停", PermLevel.ROOT, "写 cgroup freezer 与 appops，属于 root 独占"),
    FPS_CAP("游戏帧率上限", PermLevel.ROOT, "setprop debug.sf.* / vendor.display.* 落在 root 属性域"),
    GAME_FOLLOW("按应用帧率档案与前台跟随", PermLevel.ROOT,
        "换档靠的就是上面那条 SF 属性写入；另外轮询前台包名要读 activity/window 的 dumpsys，普通应用拿不到"),
    TOUCH_HZ("触控采样率", PermLevel.ROOT, "vendor 私有节点，只有 Daemon 有权限遍历候选路径"),
    QUALITY("画面锐度与清晰度", PermLevel.ROOT, "写的是厂商后处理节点（如 /proc/mtkclear/clear_level），shell 与普通应用都碰不到"),
    RENDERER("渲染后端切换", PermLevel.ROOT, "setprop debug.hwui.renderer 同样在受保护属性域"),
    RUN_DAEMON("一键运行 entropyd", PermLevel.ROOT, "释放二进制到 /data/local/tmp 并 setsid 拉起，需要 su"),
    REQUEST_ROOT("请求 Root 授权", PermLevel.NORMAL, "这一步正是为了拿到 Root，所以任何等级都可以点");
}

/**
 * 权限等级的事实来源。
 *
 * 等级不是"读出来的字符串"，而是这几条独立事实的推论，所以事实要原样留着：
 * 权限页要按条显示"检测到 / 没检测到"，诊断模块也要拿同一个数。
 */
data class PermFacts(
    val root: Boolean = false,
    /** App 本身由 adb shell 拉起：uid 2000，此时 shell 级写操作可直接跑 */
    val adbShell: Boolean = false,
    val secureSettings: Boolean = false,
    val shizukuInstalled: Boolean = false,
    val shizukuVersion: String = "",
    /** binder 在不在。装了但没启动 = installed && !running，这一态要能单独显示 */
    val shizukuRunning: Boolean = false,
    /** 授权到手，命令可以走它的远端进程以 shell 身份执行 */
    val shizukuGranted: Boolean = false,
) {
    /**
     * Daemon 通道单列一条判据：entropyd 由 adb 拉起时本 App 可以完全没有 su，
     * 但它拿到的能力是满血的（Daemon 内部就是 root）。把这种情况报成"普通"
     * 会把一堆其实能用的功能灰掉 —— 那是假故障。
     *
     * shizukuGranted 与 adbShell 归在同一档：两者给的都是 shell uid，能力面一样。
     * secureSettings 也留在定级里，但它只够刷率那一条写入 —— 把它去掉会让原来靠
     * adb pm grant 用着的用户凭空退回"普通"，那是假降级。差的那部分能力写在 how 里，
     * 不靠等级数字去假装等价。
     */
    fun levelOf(path: HardwareBridge.Path): PermLevel = when {
        root || path == HardwareBridge.Path.DAEMON || path == HardwareBridge.Path.ROOT_SHELL -> PermLevel.ROOT
        adbShell || shizukuGranted || secureSettings -> PermLevel.SHIZUKU
        else -> PermLevel.NORMAL
    }
}
