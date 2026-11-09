// File: app/src/main/java/com/entropy/hardware/HardwareBridge.kt
//
// UI 与底层之间唯一的通道。
//
// 三条执行路径，按可用性自动降级，这是"Root / ADB / 普通模式三合一"的落点：
//   路径一 Daemon（最佳）：连上 entropyd，走 UDS 下发结构化请求。
//      —— 有 eBPF 信号、有熔断保护、有审计日志。
//   路径二 Root shell（Daemon 没起来但有 su）：直接写 sysfs / setprop。
//      —— 功能子集，且必须自己带上频率钳制，否则没有熔断网。
//   路径三 普通（都不可用）：只做只读探测 + 生成一段可复制的 ADB 命令给用户。
//      —— 绝不假装执行成功。骗人的"已应用"提示比失败更糟。
package com.entropy.hardware

import android.content.Context
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.Display
import com.entropy.BuildConfig
import com.entropy.shared.Proto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * `cmd game set --downscale` 的合法字面量，取自服务端自己的 help 输出。
 * 写成 0.90 这种等价但不常见的形式不保证被接受，所以档位一律从这里出，
 * 界面也直接摆这套字符串，避免"看着能点、发出去被拒"。
 */
val DOWNSCALE_LITERALS = listOf(
    "0.3", "0.35", "0.4", "0.45", "0.5", "0.55",
    "0.6", "0.65", "0.7", "0.75", "0.8", "0.85", "0.9",
)

class HardwareBridge(private val context: Context) {

    val shell = ShellRunner(context)
    val daemon = DaemonClient()

    val events: SharedFlow<DaemonClient.Event> get() = daemon.events

    enum class Path { DAEMON, ROOT_SHELL, READONLY }

    @Volatile var path: Path = Path.READONLY
        private set

    var profile: DeviceProfile = DeviceProber.probe(context)
        private set

    /** 熔断状态快照，UI 顶栏要用它把整块面板染红 */
    data class Safety(
        val fused: Boolean = false,
        val maxTempC: Float? = null,
        val reason: String = "",
        val daemonCpuPermille: Int = 0,
        val daemonState: Int = 0,
        val lastReapplyTs: Int = 0,
    )

    /**
     * 初始化。顺序很重要：
     *  1) 先探测权限；
     *  2) 有 root 就确保 Daemon 在跑（没跑就拉起）；
     *  3) 连 socket；连不上但拿到过 root 就走 ROOT_SHELL。
     *
     * reinstallDaemon=true 是"用户按了「一键运行」"：这台机器上可能躺着一个
     * 老版本 APK 释放下来的 entropyd，只判断 test -x 会让它永远活着，
     * 于是用户重装了 App 却还在跑旧的调度逻辑。显式重跑就把旧的停掉、重新释放。
     */
    suspend fun init(reinstallDaemon: Boolean = false): Path = withContext(Dispatchers.IO) {
        // CoroutineScope 自己也有个叫 context 的属性，这里显式点名类接收者，别赌解析顺序
        profile = DeviceProber.probe(this@HardwareBridge.context)
        val mode = shell.detect()
        path = when {
            mode == ShellRunner.Mode.ROOT && ensureDaemon(reinstallDaemon) && daemon.connect() -> Path.DAEMON
            mode == ShellRunner.Mode.ROOT -> Path.ROOT_SHELL
            else -> { if (daemon.connect()) Path.DAEMON else Path.READONLY }
        }
        Log.i(TAG, "execution path = $path, family=${profile.family}, soc=${profile.socModel}")
        path
    }

    /** 重新探测硬件（root 后能读到更多节点，所以拿到权限后要再跑一次） */
    suspend fun rescan(): DeviceProfile = withContext(Dispatchers.IO) {
        profile = DeviceProber.probe(this@HardwareBridge.context)
        profile
    }

    // ---------------------------------------------------------------- //
    // Daemon 生命周期                                                    //
    // ---------------------------------------------------------------- //

    /**
     * 检查 /data/local/tmp/entropyd 是否存在并在跑；不在就从 APK assets 释放后拉起。
     *
     * 为什么把二进制放在 assets 里由 App 释放，而不是让 Magisk 模块负责：
     *  Magisk 模式当然存在（magisk/ 目录就是给它用的），但用户如果只是
     *  "装了 Magisk 但没刷模块"，那第一次运行也应该可用。两条路都留着，
     *  模块刷过的话文件已经在位，这里的释放逻辑会直接跳过。
     */
    private suspend fun ensureDaemon(reinstall: Boolean = false): Boolean {
        // 设备上那份二进制可能来自上一版 APK：进程还活着、字节流却是旧的，只判断 test -x
        // 会让用户装了新版仍跑旧调度逻辑（阈值、熔断这些改动全落空）。
        // 投放时把本 App 的 versionCode 写进 stamp，对不上就重投放并重启。
        // stamp 不存在 = 这份是 Magisk 模块或 adb 推的，按原样留着，不去覆盖别人放的文件。
        val stamp = shell.root("cat /data/local/tmp/entropy.stamp 2>/dev/null").out.trim()
        val outdated = stamp.isNotEmpty() && stamp != BuildConfig.VERSION_CODE.toString()
        var restaged = false
        if (reinstall || outdated || !shell.root("test -x /data/local/tmp/entropyd").ok) {
            // 设备上还没有二进制（或版本对不上、用户要求重跑）：从 APK assets 释放
            if (!stageBinaries()) return false
            restaged = true
        }
        if (daemonPid().isNotEmpty()) {
            if (!reinstall && !restaged) {
                grantSelfUid()
                return true
            }
            // 停掉正在跑的那一份，否则刚释放的新二进制永远不会被执行。
            // SIGTERM 走的是 entropyd 的正常退出路径：先把节点还原回备份值再退出，
            // 所以这一停不会把设备留在半抬频状态。
            // -x 按进程名匹配，不能用 -f：-f 会连"命令行里写着 entropyd"的这个 su shell 一起杀。
            shell.root("pkill -x entropyd; sleep 1")
        }
        grantSelfUid()
        shell.root(
            "setsid /data/local/tmp/entropyd --profile=balanced " +
            "> /dev/null 2>&1 < /dev/null &"
        )
        // Daemon 完成硬件扫描 + 备份需要一点时间，轮询而不是死等
        repeat(12) {
            if (daemonPid().isNotEmpty()) return true
            kotlinx.coroutines.delay(150)
        }
        // 12 次 × 150ms 还没起来：真的没起来，别把 false 当表达式"顺带"返回
        return false
    }

    /**
     * entropyd 的 PID，没在跑就返回空串。
     *
     * 不能只问 pidof：在带 ARM 转译（houdini / ndk_translation）的机型上，
     * /proc/<pid>/cmdline 的第一个 token 是翻译器本身（/system/bin/houdini64），
     * pidof 按 cmdline 匹配就永远找不到 "entropyd" —— 实测这台机器上 Daemon
     * 活得好好的、节点也写进去了，App 却因为这条判据认定"没起来"而退回直写。
     * pid 文件 + /proc/<pid> 是否存在与执行方式无关，所以两条都查、以后者为准。
     */
    private suspend fun daemonPid(): String {
        val r = shell.root(
            "p=\$(pidof entropyd 2>/dev/null | cut -d' ' -f1); " +
            "[ -z \"\$p\" ] && p=\$(cat /data/local/tmp/entropy.pid 2>/dev/null); " +
            "[ -n \"\$p\" ] && [ -d \"/proc/\$p\" ] && echo \"\$p\""
        )
        return r.out.trim().let { if (it.matches(Regex("^\\d{1,7}$"))) it else "" }
    }

    /**
     * 本项目 native 产物只编了这两个 ABI。
     * 公开给 UI：诊断模块要如实报告"将要投放的是哪一份"，
     * 自己再抄一遍判据的话，改了这里那边就成了假信息。
     */
    val nativeAbis = listOf("arm64-v8a", "armeabi-v7a")

    /** 本机可用的那一个；一个都不支持时仍报 arm64-v8a，让投放阶段如实失败而不是静默挑错的 */
    fun chosenNativeAbi(): String =
        nativeAbis.firstOrNull { android.os.Build.SUPPORTED_ABIS.contains(it) } ?: "arm64-v8a"

    /**
     * 把 assets/native/<abi>/ 下的二进制写进 /data/local/tmp。
     *
     * 为什么不用 `cp`：su 之后的 shell 属于 root，读不到本 App 私有目录里的 assets，
     * 所以只能由 App 自己读出字节流，通过 su 进程的 stdin 灌进去（cat > 文件）。
     * 逐字节流也避免了在 /sdcard 落地中间文件 —— 那里所有应用都能读。
     */
    private suspend fun stageBinaries(): Boolean {
        val names = listOf("entropyd", "entropy-monitor", "entropy-freezer")
        val abi = chosenNativeAbi()
        var ok = true
        names.forEach { name ->
            val assetPath = "native/$abi/$name"
            try {
                context.assets.open(assetPath).use { input ->
                    // 必须用 ProcessBuilder 的分参数形式：Runtime.exec(String) 会按空格切词，
                    // "su -c 'cat > f && chmod 755 f'" 会被切成 ["su","-c","'cat",">",…]，
                    // su 只收到 'cat 这半个 token，重定向与 chmod 全丢，
                    // 表现就是"点了没报错，但 /data/local/tmp 里什么都没有"。
                    val p = ProcessBuilder(
                        "su", "-c",
                        "cat > /data/local/tmp/$name && chmod 755 /data/local/tmp/$name"
                    ).redirectErrorStream(true).start()
                    // 写完即关流：cat 靠这个 EOF 才知道字节流结束了
                    p.outputStream.use { os -> input.copyTo(os, 8192) }
                    val log = p.inputStream.readBytes().decodeToString().trim()
                    if (!p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                        p.destroy(); Log.w(TAG, "stage $name timeout"); ok = false
                    } else if (p.exitValue() != 0) {
                        Log.w(TAG, "stage $name exit=${p.exitValue()}: ${log.take(200)}"); ok = false
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "stage $name failed: ${e.message}")
                ok = false
            }
        }
        if (ok) shell.root(
            "echo ${BuildConfig.VERSION_CODE} > /data/local/tmp/entropy.stamp; " +
            "chmod 600 /data/local/tmp/entropy.stamp"
        )
        return ok
    }

    /**
     * 把本 App 的 uid 加进 Daemon 白名单。
     * 这一步是刻意的显式授权：Daemon 是 root 权限的，
     * 白名单文件必须由 root 写（App 自己没有权限改它），
     * 所以"同意连接"这个动作在文件系统层面也是不可伪造的。
     */
    private suspend fun grantSelfUid() {
        val uid = Process.myUid()
        shell.root("echo $uid > /data/local/tmp/entropy.allow; chmod 600 /data/local/tmp/entropy.allow")
    }

    /** 给用户的 ADB 命令（无 root 场景），可直接整段复制 */
    fun adbSetupCommands(): String {
        val uid = Process.myUid()
        return """
            # 1) 推送原生组件
            adb push entropyd entropy-monitor entropy-freezer /data/local/tmp/
            adb shell chmod 755 /data/local/tmp/entropyd /data/local/tmp/entropy-monitor /data/local/tmp/entropy-freezer
            # 2) 授权本 App 连接 Daemon（uid=$uid）
            adb shell "echo $uid > /data/local/tmp/entropy.allow"
            # 3) 拉起 Daemon 与守护进程
            adb shell "setsid /data/local/tmp/entropyd --profile=balanced >/dev/null 2>&1 &"
            adb shell "setsid /data/local/tmp/entropy-monitor --profile=b >/dev/null 2>&1 &"
        """.trimIndent()
    }

    // ---------------------------------------------------------------- //
    // 动作下发                                                           //
    // ---------------------------------------------------------------- //

    suspend fun applyProfile(
        profileId: Int,
        cpuMaxKhz: Int = 0,
        gpuMaxKhz: Int = 0,
        predictive: Boolean = true,
    ): Boolean {
        val req = Proto.ProfileReq(profileId, cpuMaxKhz, gpuMaxKhz,
            if (profileId == Proto.PROFILE_GAME) 1200 else 400,
            if (predictive) 1 else 0)
        if (path == Path.DAEMON && daemon.send(Proto.CMD_APPLY_PROFILE, req.encode())) return true
        if (path == Path.ROOT_SHELL) return applyProfileViaShell(profileId)
        return false
    }

    /**
     * Daemon 缺席时的兜底。注意这里的 clampToSafe：
     * 没有 Daemon 就没有熔断线程，所以把上限硬压在硬件最大频率的 85%，
     * 并且不去动 scaling_min_freq —— 只降不升是这条路径唯一的安全准则。
     */
    private suspend fun applyProfileViaShell(id: Int): Boolean {
        val cap = when (id) {
            Proto.PROFILE_GAME -> 0.85f
            Proto.PROFILE_POWERSAVE -> 0.55f
            else -> 0.75f
        }
        profile.cpuClusters.forEach { c ->
            val policy = c.name.substringAfter("policy").substringBefore(" ").toIntOrNull() ?: return@forEach
            val target = (c.maxKhz * cap).toInt()
            shell.root("echo $target > $CPU_POLICY_BASE$policy/scaling_max_freq")
        }
        return true
    }

    /**
     * CPU / GPU 频率上限（kHz），0 = 这一项不设额外限制。两个值必须一起下发：
     * Daemon 的 on_profile 每次都把 cfg 清零再按请求里的非零字段覆盖，
     * 所以单发一个上限会把另一个悄悄清掉 —— 用户改 CPU，GPU 那边却恢复了满血，
     * 这种"改一个动两个"的行为比不提供给难解释。
     *
     * Daemon 在位时交给它：之后每一轮 tick 都按这两个上限下档，熔断锁定期内
     * 它自己会拒绝抬频，App 侧不必再防一遍。
     * 没有 Daemon 就直接写节点，吸附方向和 Daemon 的 snap_to_opp 取同一侧
     * （向下取最近一档）：上限的语义是"不许超过"，向上取档等于替用户多抬一档，
     * 两边算出不同的值时界面还会和实际跑的频率对不上。
     */
    suspend fun applyFreqCaps(cpuKhz: Int, gpuKhz: Int, profileId: Int): Boolean {
        val cpu = cpuKhz.coerceAtLeast(0)
        val gpu = gpuKhz.coerceAtLeast(0)
        if (path == Path.DAEMON) return applyProfile(profileId, cpu, gpu)
        if (path == Path.READONLY) return false
        var ok = false
        profile.cpuClusters.forEach { c ->
            val policy = c.name.substringAfter("policy").substringBefore(" ").toIntOrNull() ?: return@forEach
            val target = if (cpu == 0) c.maxKhz else snapDown(c.availableKhz, cpu, c.minKhz, c.maxKhz)
            if (writeNode("$CPU_POLICY_BASE$policy/scaling_max_freq", target)) ok = true
        }
        profile.gpuFreq?.let { g ->
            val target = if (gpu == 0) g.maxLimitKhz else snapDown(g.availKhz, gpu, g.loKhz, g.hiKhz)
            if (writeNode(g.maxNode, target)) ok = true
        }
        return ok
    }

    /** 取 <= khz 的最大合法档，两端各钳一次；表为空时退回原值 */
    private fun snapDown(table: List<Int>, khz: Int, lo: Int, hi: Int): Int {
        val clamped = khz.coerceIn(lo, hi)
        if (table.isEmpty()) return clamped
        return table.lastOrNull { it <= clamped } ?: table.first()
    }

    /**
     * 渲染器切换。
     * debug.hwui.renderer 取值：skia / skiagl / gl / vk。
     * 必须重启 SystemUI 与目标 App 才生效（HWUI 在进程启动时读一次属性），
     * 所以这个函数返回的就是"要不要立刻重启界面"的提示，UI 要如实告诉用户。
     */
    suspend fun setRenderer(which: Int): Boolean = when (which) {
        Proto.RENDERER_SKIA -> writeRenderer("skia")
        Proto.RENDERER_GLES -> writeRenderer("skiagl")
        Proto.RENDERER_VULKAN -> writeRenderer("vk")
        else -> writeRenderer("")
    }

    private suspend fun writeRenderer(v: String): Boolean {
        if (path == Path.DAEMON) {
            val ok = daemon.send(Proto.CMD_SET_LIMIT, Proto.LimitReq(renderer = vRendererCode(v)).encode())
            if (ok) return true
        }
        if (path == Path.READONLY) return false
        val r = shell.root("setprop debug.hwui.renderer ${if (v.isEmpty()) "''" else v}")
        // 回读校验：属性被 SELinux 静默拒绝时不能对用户说成功
        val back = shell.root("getprop debug.hwui.renderer")
        return r.ok && (v.isEmpty() || back.out.trim() == v)
    }

    private fun vRendererCode(v: String) = when (v) {
        "skia" -> Proto.RENDERER_SKIA
        "skiagl" -> Proto.RENDERER_GLES
        "vk" -> Proto.RENDERER_VULKAN
        else -> Proto.RENDERER_DEFAULT
    }

    /** 分辨率：走 wm size，这是官方接口，不需要 root 也不会 bootloop */
    suspend fun setResolution(w: Int, h: Int): Boolean {
        if (w !in 320..4096 || h !in 320..4096) return false
        if (path == Path.DAEMON && daemon.send(Proto.CMD_SET_LIMIT, Proto.LimitReq(width = w, height = h).encode()))
            return true
        val r = shell.plain("wm size ${w}x$h")
        return r.ok
    }

    suspend fun resetResolution(): Boolean {
        // 只 reset 分辨率。这里不能发 restore=1：那条分支连渲染器和帧率上限一起清，
        // 用户点的是"恢复分辨率"，不该发现自己刚被打消了锁帧
        val r = shell.plain("wm size reset && wm density reset")
        return r.ok
    }

    /** 刷新率：Settings.System 需要 WRITE_SECURE_SETTINGS（用 ADB 授予） */
    suspend fun setRefreshRate(mode: Int): Boolean {
        val r = shell.plain("settings put system user_refresh_rate_mode $mode")
        return r.ok
    }

    /** 通用 sysfs 写入，参数必须过 ShellRunner 的白名单校验 */
    suspend fun writeNode(pathStr: String, value: Int): Boolean {
        val spec = "$pathStr=$value"
        if (!shell.isSafeNodeSpec(spec)) {
            Log.w(TAG, "rejected unsafe node spec: $spec")
            return false
        }
        if (path == Path.DAEMON && daemon.send(Proto.CMD_SET_NODE, spec.toByteArray())) return true
        if (path == Path.READONLY) return false
        return shell.root("echo $value > $pathStr").ok
    }

    /**
     * 帧率上限。SF 的属性在不同 ROM 上名字不同，两个键都写，以属性回读为准 ——
     * Daemon 的 ack 只说明它执行过一次写，不说明 SurfaceFlinger 收下了这个值。
     */
    suspend fun setFpsLimit(fps: Int): Boolean {
        if (fps !in 24..240) return false
        if (path == Path.READONLY) return false
        if (path == Path.DAEMON)
            daemon.request(Proto.CMD_SET_LIMIT, Proto.LimitReq(fpsLimit = fps).encode())
        if (readFpsLimit() == fps) return true
        writeFpsProp(fps.toString())
        return readFpsLimit() == fps
    }

    /**
     * 取消帧率上限（"跟随系统"）。
     * 清空后必须回读：属性写不回去时机器会继续锁在旧上限，
     * 这时候对用户说"已恢复"，比不提供这个开关更糟。
     */
    suspend fun clearFpsLimit(): Boolean {
        if (path == Path.READONLY) return false
        // 走 fpsLimit 哨兵而不是 restore：restore 连 wm size/density 一起 reset，
        // 用户只是取消锁帧，不该顺手把他调好的分辨率也清掉
        if (path == Path.DAEMON)
            daemon.request(Proto.CMD_SET_LIMIT,
                Proto.LimitReq(fpsLimit = Proto.FPS_LIMIT_CLEAR).encode())
        if (readFpsLimit() == 0) return true
        writeFpsProp("")
        return readFpsLimit() == 0
    }

    /**
     * 属性里真实生效的上限：两个键里最小的那个非零值。
     * 0 = 确实没有上限，-1 = 读不到（只读通道，或 su 没起来）。
     * 这两个必须分开：getprop 失败时输出也是空串，把它当成"没有上限"，
     * 清失败就会显示成"已还原"，而这正是最不能被用户相信的那句话。
     */
    suspend fun readFpsLimit(): Int {
        if (path == Path.READONLY) return -1
        var min = 0
        var readable = false
        FPS_KEYS.forEach { k ->
            val r = shell.root("getprop $k")
            if (!r.ok) return@forEach
            readable = true
            val v = r.out.trim().toIntOrNull()
            if (v != null && v > 0) min = if (min == 0) v else minOf(min, v)
        }
        return if (readable) min else -1
    }

    private suspend fun writeFpsProp(value: String) {
        val v = if (value.isEmpty()) "''" else value
        FPS_KEYS.forEach { k ->
            val r = shell.root("setprop $k $v")
            if (!r.ok) Log.w(TAG, "setprop $k=$value failed: ${r.err.trim()}")
        }
    }

    /**
     * 系统侧干预（GameManager / DisplayManager / Settings）的统一结果。
     *
     * ok 只代表"服务端接了这条命令"，confirmed 才代表"回读拿到了期望值"。
     * 这两个状态必须分开：命令被接受但实际没生效（ROM 阉割、面板不支持、
     * 服务端只是把值存下来）是真实存在的中间态，把它显示成"已生效"就是最不该出现的那种谎。
     */
    data class SysOp(val ok: Boolean, val confirmed: Boolean, val detail: String)

    private fun sysFail(r: ShellRunner.Result): SysOp {
        // 服务端的拒绝理由（比如"这个包不是 game 类型"）要原样带回去给用户看：
        // 他换哪个应用能成，只有这句话能告诉他
        val why = (r.err + "\n" + r.out).lines().firstOrNull { it.isNotBlank() }?.trim()
            ?: "命令无输出（exit=${r.code}）"
        return SysOp(false, false, why)
    }

    /**
     * 这个包在 GameManagerService 里的当前配置。
     * ok=false 意味着没能和这个服务谈成（不是 game 类别、命令失败、没通道），
     * 这时候两个旋钮的值没有意义，UI 不能拿"读不到"当"没设过"来下发。
     */
    data class GameConfig(val fps: Int?, val scale: Double?, val ok: Boolean, val detail: String)

    suspend fun readGameConfig(pkg: String): GameConfig {
        if (!shell.isSafePackage(pkg)) return GameConfig(null, null, false, "包名不合法")
        if (path == Path.READONLY) return GameConfig(null, null, false, "没有 root 通道，读不到服务端配置")
        val r = shell.root("cmd game list-configs '$pkg'")
        if (!r.ok) return GameConfig(null, null, false, sysFail(r).detail)
        val fps = GAME_FPS.find(r.out)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
        val scale = GAME_SCALE.find(r.out)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it in 0.3..0.9 }
        return GameConfig(fps, scale, true, r.out.trim().lines().firstOrNull { it.isNotBlank() }?.trim() ?: "配置为空")
    }

    /**
     * 按应用下发。fps=0 表示不锁，scaleLit="disable" 表示不降。
     *
     * 两个旋钮必须一起发：`cmd game set` 写的是一整份 custom 配置，
     * 只给一个旗标时另一个会按"未设置"处理 —— 于是"取消降分辨率"会顺手把锁帧也清了。
     * 调用方（FrameEngine）负责把当前另一档的真实值带上，带上不确定的那档宁可先重读。
     */
    suspend fun setGameConfig(pkg: String, fps: Int, scaleLit: String): SysOp {
        if (!shell.isSafePackage(pkg)) return SysOp(false, false, "包名不合法")
        if (fps !in 0..240) return SysOp(false, false, "帧率要在 0..240 之间（0 = 不锁）")
        if (scaleLit != "disable" && scaleLit !in DOWNSCALE_LITERALS)
            return SysOp(false, false, "渲染缩放只接受 ${DOWNSCALE_LITERALS.joinToString("/")} 或 disable")
        if (path == Path.READONLY) return SysOp(false, false, "没有 root 通道，命令发不出去")
        val r = shell.root("cmd game set --fps $fps --downscale $scaleLit '$pkg'")
        if (!r.ok) return sysFail(r)
        val now = readGameConfig(pkg)
        val scaleWant = if (scaleLit == "disable") null else scaleLit.toDouble()
        val got = now.scale
        val fpsOk = if (fps == 0) now.fps == null else now.fps == fps
        val scaleOk = if (scaleWant == null) got == null else got != null && abs(got - scaleWant) < 0.005
        val what = "${if (fps == 0) "不锁帧" else "锁 $fps fps"}、" +
            if (scaleLit == "disable") "不降渲染分辨率" else "渲染缩放 $scaleLit"
        return if (fpsOk && scaleOk) SysOp(true, true, "$pkg 已生效：$what")
        else SysOp(true, false, "$pkg 已下发：$what；但回读是 " +
            "${if (now.fps == null) "没锁" else "${now.fps}fps"}、" +
            "${if (now.scale == null) "没降" else "缩放 ${now.scale}"}" +
            if (!now.ok) "（这一档读不回来：${now.detail}）" else "")
    }

    /**
     * 内容帧率匹配（AOSP 的 display 开关）。这是本项目里唯一和"插帧"沾边、
     * 又真实存在的系统能力：它把面板刷新率对齐到视频内容帧率，
     * 消掉 24fps 内容配 60Hz 面板的 3:2 cadence 抖动。
     *
     * 它不生成中间帧，所以界面上只能叫"匹配内容帧率"，不能叫插帧。
     * 取值 0/1/2 按 AOSP 的枚举含义标注，超范围服务端会直接报错。
     */
    suspend fun setMatchContentFrameRate(pref: Int): SysOp {
        if (pref !in 0..2) return SysOp(false, false, "只接受 0/1/2")
        if (path == Path.READONLY) return SysOp(false, false, "没有 root 通道，命令发不出去")
        val r = shell.root("cmd display set-match-content-frame-rate-pref $pref")
        if (!r.ok) return sysFail(r)
        val now = readMatchContentFrameRate()
        return if (now == pref) SysOp(true, true, "已设为 ${MATCH_CFR_LABEL[pref]}")
        else SysOp(true, false, "命令已被接受，但回读是 ${now?.let { MATCH_CFR_LABEL[it] } ?: "读不到"}")
    }

    suspend fun readMatchContentFrameRate(): Int? {
        if (path == Path.READONLY) return null
        val r = shell.root("cmd display get-match-content-frame-rate-pref")
        if (!r.ok) return null
        return r.out.trim().substringAfterLast(":").trim().toIntOrNull()?.takeIf { it in 0..2 }
    }

    /**
     * 面板此刻被用户/工具指定的档位（`cmd display get-user-preferred-display-mode`）。
     * 只读不写：写这条会同时改分辨率，风险是"黑屏"级别的，
     * 而这一页要回答的是"帧率被谁压住了"，不需要替用户换分辨率。
     */
    suspend fun readUserPreferredDisplayMode(): String? {
        if (path == Path.READONLY) return null
        val r = shell.root("cmd display get-user-preferred-display-mode")
        if (!r.ok) return null
        val v = r.out.trim().substringAfterLast(":").trim()
        return if (v.isEmpty() || v == "null") null else v
    }

    /**
     * 面板帧率上限（settings 里的 peak_refresh_rate）。
     * 这台机器上这个键本来就有值（实测 60），所以改动前先存原值，
     * 还原时按原值写回 —— 只会 delete 的"还原"会把 OEM 预置值抹成系统默认，
     * 用户点一下还原，被改的东西反而更多。
     */
    suspend fun setPeakRefreshRate(hz: Int): SysOp = setSettingWithBackup(PEAK_RATE_KEY, hz.toString())

    suspend fun clearPeakRefreshRate(): SysOp = restoreSetting(PEAK_RATE_KEY)

    suspend fun readPeakRefreshRate(): String? = readSetting(PEAK_RATE_KEY)

    /** 面板帧率下限。抬它会一直顶着高刷跑，耗电是实打实的，界面上不许写成"免费提升流畅度" */
    suspend fun setMinRefreshRate(hz: Int): SysOp = setSettingWithBackup(MIN_RATE_KEY, hz.toString())

    suspend fun clearMinRefreshRate(): SysOp = restoreSetting(MIN_RATE_KEY)

    suspend fun readMinRefreshRate(): String? = readSetting(MIN_RATE_KEY)

    private suspend fun setSettingWithBackup(key: String, value: String): SysOp {
        if (path == Path.READONLY) return SysOp(false, false, "没有 root 通道，写不进去")
        stashOriginal(key)
        val (scope, name) = key.split("/", limit = 2)
        val r = shell.root("settings put $scope $name $value")
        if (!r.ok) return sysFail(r)
        return if (readSetting(key) == value) SysOp(true, true, "$name 已设为 $value")
        else SysOp(true, false, "$name 命令已被接受，但回读是 ${readSetting(key) ?: "空"}")
    }

    private suspend fun restoreSetting(key: String): SysOp {
        if (path == Path.READONLY) return SysOp(false, false, "没有 root 通道，写不回去")
        val orig = hwSp().getString(key, null)
            ?: return SysOp(false, false, "本机没存到改动前的原值，不敢替你猜一个")
        val (scope, name) = key.split("/", limit = 2)
        val r = if (orig.isEmpty()) shell.root("settings delete $scope $name")
                else shell.root("settings put $scope $name $orig")
        if (!r.ok) return sysFail(r)
        val now = readSetting(key)
        return if (now == orig || (orig.isEmpty() && now == null))
            SysOp(true, true, if (orig.isEmpty()) "$name 已交回系统默认" else "$name 已还原为 $orig")
        else SysOp(true, false, "$name 写回命令已被接受，但当前是 ${now ?: "空"}")
    }

    /** 存原值只在第一次动这个键时发生；空串代表"原本就没设过"，还原时走 delete */
    private fun stashOriginal(key: String) {
        val sp = hwSp()
        if (sp.contains(key)) return
        sp.edit().putString(key, readSetting(key) ?: "").apply()
    }

    /** 读走 ContentResolver：不需要 root、不等 shell 超时，所以探测路径上也能用 */
    private fun readSetting(key: String): String? = try {
        val (scope, name) = key.split("/", limit = 2)
        val cr = context.contentResolver
        when (scope) {
            "system" -> Settings.System.getString(cr, name)
            "global" -> Settings.Global.getString(cr, name)
            else -> null
        }
    } catch (e: Exception) {
        Log.w(TAG, "readSetting $key failed: ${e.message}")
        null
    }

    private fun hwSp() = context.getSharedPreferences("entropy_hw", Context.MODE_PRIVATE)

    /**
     * 触控采样率。节点是 vendor 私有的、命名各家不同，所以 App 侧只发意图，
     * 由 Daemon 去遍历候选路径 —— 在没有 Daemon 的路径上直接返回失败，
     * 不猜路径：猜错会写坏别的东西，而用户看到"已应用"更不会去查。
     */
    suspend fun setTouchHz(hz: Int): Boolean {
        if (hz !in 60..500) return false
        return path == Path.DAEMON &&
            daemon.send(Proto.CMD_SET_LIMIT, Proto.LimitReq(touchSampleHz = hz).encode())
    }

    suspend fun startProbe(req: Proto.ProbeReq): Boolean =
        path == Path.DAEMON && daemon.send(Proto.CMD_START_PROBE, req.encode())

    suspend fun stopProbe(): Boolean =
        path == Path.DAEMON && daemon.send(Proto.CMD_STOP_PROBE, ByteArray(0))

    /**
     * 一键还原。Daemon 只知道自己动过的那些 sysfs 节点，SF 属性、settings、
     * cmd game / cmd display 这几路都是 App 侧直接写的，所以不能让"Daemon 成功"提前 return：
     * 那样用户按下还原，机器上还留着一半改动，而界面已经显示"已还原"了。
     */
    suspend fun resetAll(): Boolean {
        if (path == Path.READONLY) return false
        if (path == Path.DAEMON) daemon.send(Proto.CMD_RESET_DEFAULT, ByteArray(0))
        shell.plain("wm size reset; wm density reset")
        shell.root("setprop debug.hwui.renderer ''")
        writeFpsProp("")
        clearPeakRefreshRate()
        clearMinRefreshRate()
        profile.cpuClusters.forEach { c ->
            val policy = c.name.substringAfter("policy").substringBefore(" ").toIntOrNull() ?: return@forEach
            shell.root("echo ${c.maxKhz} > $CPU_POLICY_BASE$policy/scaling_max_freq")
        }
        profile.gpuFreq?.let { shell.root("echo ${it.maxLimitKhz} > ${it.maxNode}") }
        return true
    }

    suspend fun freeze(pkg: String, enable: Boolean): Boolean {
        if (!shell.isSafePackage(pkg)) return false
        val type = if (enable) Proto.CMD_FREEZE_PKG else Proto.CMD_UNFREEZE_PKG
        if (path == Path.DAEMON && daemon.send(type, pkg.toByteArray())) return true
        val cmd = if (enable) "entropy-freezer --freeze" else "entropy-freezer --unfreeze"
        return shell.root("/data/local/tmp/$cmd '$pkg'").ok
    }

    suspend fun setMonitorCfg(cfg: Proto.MonitorCfg): Boolean {
        // UI 侧同样做一次上限钳制：把请求值压进安全区，越界直接拒。
        // 上限是 Proto.FUSE_MAX_MC，Daemon 与命令行各自再钳一次，三处必须同数
        if (cfg.fuseTempMc > Proto.FUSE_MAX_MC || cfg.softLimitMc > cfg.fuseTempMc) return false
        if (path == Path.DAEMON) return daemon.send(Proto.CMD_SET_MONITOR, cfg.encode())
        if (path == Path.ROOT_SHELL)
            return shell.root("/data/local/tmp/entropy-monitor " +
                "--interval=${cfg.monitorIntervalMs} --yield=${cfg.softLimitMc} --once").ok
        return false
    }

    /** 后台应用数量与节省的 CPU 时间，来自 entropy-freezer --status 的文本输出 */
    suspend fun freezerStatus(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val r = shell.root("/data/local/tmp/entropy-freezer --status")
        if (!r.ok) 0 to 0L
        else {
            val tasks = Regex("frozen tasks=(\\d+)").find(r.out)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val cpu = Regex("accumulated_cpu=(\\d+)ms").find(r.out)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            tasks to cpu
        }
    }

    /** 守护进程侧的真实温度读数（注意：不是伪造值，本项目不做温度欺骗） */
    suspend fun readLiveTemps(): List<Pair<String, Int>> = withContext(Dispatchers.IO) {
        profile.thermalZones.mapNotNull { line ->
            val name = line.substringAfter("=").trim()
            val idx = line.substringBefore("=").trim().removePrefix("thermal_zone")
            val v = shell.plain("cat /sys/class/thermal/thermal_zone$idx/temp").out.trim().toIntOrNull()
            if (v != null && v > 0) name to v else null
        }
    }

    fun release() { daemon.shutdown() }

    private companion object {
        const val TAG = "EntropyBridge"

        /** 每簇的 cpufreq 目录，policyN 的 N 从 Cluster.name 里取 */
        const val CPU_POLICY_BASE = "/sys/devices/system/cpu/cpufreq/"

        /** SF 的帧率上限键，不同 ROM 认的不同，成对写、成对回读 */
        val FPS_KEYS = listOf("debug.sf.max_refresh_rate", "vendor.display.max_frame_rate")

        /** 带 scope 前缀：写的时候才知道该走 settings put system 还是 global */
        val PEAK_RATE_KEY = "system/peak_refresh_rate"
        val MIN_RATE_KEY = "system/min_refresh_rate"

        // list-configs 的排版各 ROM 不一样（缩进、字段名大小写都可能变），
        // 所以只按"字段名 + 数字"松匹配，匹配不到就当读不到，绝不猜 0
        val GAME_FPS = Regex("(?i)fps[^0-9-]{0,12}(\\d{1,3})")
        val GAME_SCALE = Regex("(?i)(?:scale|resolution)[^0-9]{0,12}(0?\\.\\d{1,2}|[0-9]\\.\\d{1,2})")

        /** 0/1/2 的含义按 AOSP 的 MatchContentFrameRateUserPreference 枚举标注 */
        val MATCH_CFR_LABEL = mapOf(
            0 to "关闭",
            1 to "强光环境下启用",
            2 to "始终启用",
        )
    }
}
