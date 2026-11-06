// File: app/src/main/java/com/entropy/data/SystemRepository.kt
//
// "权限"页与"我的"页要的数据全部从这里出，UI 与 ViewModel 不直接碰线程和文件。
//
// 三条规矩：
//  1. 所有 IO 走 Dispatchers.IO；
//  2. 判定只依据设备上真实读到的字节 —— su 的自报家门、/data/adb 的目录结构、
//     KernelSU 授权表里的字符串。读不到就明说读不到，不猜路径，也不编版本号；
//  3. 发给 shell 的命令全是固定字面量，没有任何 UI 传进来的字符串参与拼接，
//     所以这个类不存在注入面。要加参数时必须先过 ShellRunner 的白名单校验。
package com.entropy.data

import android.Manifest
import android.app.NotificationManager
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import com.entropy.BuildConfig
import com.entropy.hardware.DeviceProber
import com.entropy.hardware.ShellRunner
import com.entropy.hardware.ShizukuBridge
import com.entropy.hardware.ShizukuState
import com.entropy.overlay.OverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** su 的实现方。label 只用于展示，判定见 SystemRepository.suStatus() */
enum class SuManager(val label: String) {
    NONE("未授权"),
    KERNELSU("KernelSU"),
    MAGISK("Magisk"),
    APATCH("APatch"),
}

data class SuStatus(
    val manager: SuManager,
    /** su 自己打印的那一行原样保留，比二次解析更可信 */
    val banner: String,
    /** root 会话的 SELinux 域，例如 u:r:ksu:s0 */
    val selinuxCtx: String,
    val daemonPid: Int,
    val readable: Boolean,
) {
    val granted get() = manager != SuManager.NONE
    /** 状态卡的"工作中"：既要拿到 root，也要 entropyd 真在跑 */
    val working get() = granted && daemonPid > 0
}

data class KernelModule(
    val id: String,
    val name: String,
    val version: String,
    val disabled: Boolean,
    val pendingRemove: Boolean,
) {
    val stateLabel: String get() = when {
        pendingRemove -> "待移除"
        disabled -> "已禁用"
        else -> "已启用"
    }
}

/** 一个被授予 root 的应用。uid 来自 PackageManager，不是从授权表里猜的 */
data class GrantedApp(val pkg: String, val label: String, val uid: Int)

// Shizuku 的状态由 com.entropy.hardware.ShizukuBridge 自己报（类型也定义在那儿）：
// 装没装、binder 在不在、授没授权，三条探针都在那条通道上，仓库层不再自己 guess 安装状态。

/** 能力项的稳定标识。UI 靠它决定"点了去哪个设置页"，不靠匹配中文标题 */
enum class CapabilityKey { OVERLAY, NOTIFICATION, SECURE_SETTINGS, USAGE_STATS }

/** 本机能力的一行。detail 说清"没授予会怎样"，不写空泛的说明 */
data class Capability(
    val key: CapabilityKey,
    val title: String,
    val detail: String,
    val granted: Boolean,
    val canGrantInApp: Boolean,
)

data class SystemInfo(
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val hardware: String,
    val socModel: String,
    val brand: String,
    val model: String,
    val abi: String,
    val cores: Int,
    val ramTotalMb: Long,
    val android: String,
    val sdk: Int,
    val kernel: String,
    val screen: String,
    val uid: Int,
    val pid: Int,
)

class SystemRepository(private val context: Context, private val shell: ShellRunner) {

    /**
     * 一次 root 会话把身份、/data/adb 结构、模块表、entropyd 状态全问完。
     * 合成一条而不是拆成四条：每次 su 都是一次新进程 + 一次策略检查，
     * 连着发四次会有几百毫秒纯开销，页面也就跟着卡一下。
     */
    suspend fun suStatus(): SuStatus = withContext(Dispatchers.IO) {
        // SHIZUKU 也要重新探测：这一档是 resyncShizuku() 抬上来的，它只说明"Shizuku 能用"，
        // 完全不说明这台机器有没有 su。沿用"只有 NONE 才 detect"会让已 root 的机器
        // 在权限页显示"Root / su 未授予"（实测就是这样，Shizuku 授权抢在 detect 之前落了档）。
        val mode = if (shell.mode == ShellRunner.Mode.ROOT || shell.mode == ShellRunner.Mode.ADB_SHELL)
            shell.mode else shell.detect()
        if (mode != ShellRunner.Mode.ROOT)
            return@withContext SuStatus(SuManager.NONE, "", "", 0, readable = false)

        val dump = shell.root(DUMP_CMD, 12)
        val banner = dumpBanner()
        var ctx = ""
        var pid = 0
        var adbEntries = ""
        dump.out.lineSequence().forEach { line ->
            when {
                line.startsWith("CTX:") -> ctx = line.substring(4).trim()
                line.startsWith("PID:") -> pid = line.substring(4).trim().split(' ').firstOrNull()?.toIntOrNull() ?: 0
                line.startsWith("ADB:") -> adbEntries = line.substring(4)
            }
        }
        SuStatus(
            manager = classify(banner, adbEntries, ctx),
            banner = banner,
            selinuxCtx = ctx,
            daemonPid = pid,
            readable = dump.ok,
        )
    }

    /**
     * su 的版本横幅。
     * 只在已经确认 ROOT 之后调用：detect() 之所以给出 ROOT，
     * 就是因为 su 已经答过一次"我是 0"，此时再问 -v 不会再弹授权框。
     */
    private suspend fun dumpBanner(): String {
        val r = shell.plain("su -v 2>/dev/null", 3)
        return r.out.trim().ifEmpty { r.err.trim() }.take(48)
    }

    /**
     * 三个实现都能定论：su 的横幅里带自己的名字；带不出来时按 /data/adb 的目录特征分。
     * 顺序有讲究 —— KernelSU 也兼容刷 Magisk 模块，所以 magisk 目录不能先判。
     */
    private fun classify(banner: String, adbEntries: String, ctx: String): SuManager = when {
        banner.contains("KernelSU", true) || ctx.contains("ksu") -> SuManager.KERNELSU
        banner.contains("APatch") || adbEntries.contains("apd,") || adbEntries.contains(",apd") -> SuManager.APATCH
        banner.contains("Magisk", true) || ctx.contains("magisk") ||
            adbEntries.contains("magisk") -> SuManager.MAGISK
        adbEntries.contains("ksud") || adbEntries.contains("ksu,") || adbEntries.contains(",ksu") ->
            SuManager.KERNELSU
        else -> SuManager.NONE
    }

    /** 内核模块状态。Magisk / KernelSU / APatch 共用 /data/adb/modules 这套目录约定 */
    suspend fun modules(): List<KernelModule> = withContext(Dispatchers.IO) {
        if (shell.mode != ShellRunner.Mode.ROOT) return@withContext emptyList()
        shell.root(MODULE_CMD, 12).out.lineSequence()
            .filter { it.startsWith("MOD|") }
            .mapNotNull { line ->
                val f = line.split('|')
                if (f.size < 4) return@mapNotNull null
                val flags = f.getOrElse(4) { "" }
                KernelModule(
                    id = f[1], name = f[2].ifEmpty { f[1] }, version = f[3],
                    disabled = flags.contains("off"), pendingRemove = flags.contains("rm")
                )
            }
            .toList()
    }

    /**
     * KernelSU 的 root 授权表。
     *
     * /data/adb/ksu/.allowlist 是定长二进制记录，步长和字段随 KSU 版本变过，
     * 所以这里不解析结构：只把其中"像包名"的 ASCII 片段取出来，
     * 再和 PackageManager 的真实安装列表求交集。
     * 交集为空就是空 —— 这个函数不可能凭空造出一个应用，也不会因为格式变更而崩溃。
     * Magisk 的授权表是 SQLite（需要 sqlite3 或 magisk --sqlite），本函数返回空列表，
     * 由 UI 如实写明"该 Root 实现的授权表未能读取"，而不是显示一张空表冒充"没有授权"。
     */
    suspend fun grantedApps(manager: SuManager): List<GrantedApp> = withContext(Dispatchers.IO) {
        if (manager != SuManager.KERNELSU || shell.mode != ShellRunner.Mode.ROOT)
            return@withContext emptyList()
        val raw = shell.root("cat $KSU_ALLOWLIST 2>/dev/null", 8).out
        if (raw.isEmpty()) return@withContext emptyList()
        val candidates = Regex("[A-Za-z0-9._-]{4,}").findAll(raw)
            .map { it.value }.filter { it.contains('.') && it[0] != '.' }.toSet()
        val pm = context.packageManager
        pm.getInstalledPackages(0).asSequence()
            .filter { candidates.contains(it.packageName) }
            .map { info ->
                // SDK 36 起 PackageInfo.applicationInfo 是 @Nullable，
                // 拿不到就只能退回包名展示，uid 用 -1 表示"未知"而不是猜 0
                val app = info.applicationInfo
                GrantedApp(
                    pkg = info.packageName,
                    label = app?.let {
                        runCatching { it.loadLabel(pm).toString() }.getOrDefault(info.packageName)
                    } ?: info.packageName,
                    uid = app?.uid ?: -1
                )
            }
            .sortedBy { it.label }
            .toList()
    }

    /**
     * WRITE_SECURE_SETTINGS 是否已在系统侧生效。
     * 这是 Shizuku/ADB 那一档的真实门槛：由谁授予不重要，checkPermission 只认结果。
     */
    val secureSettingsGranted: Boolean
        get() = context.packageManager.checkPermission(
            Manifest.permission.WRITE_SECURE_SETTINGS, context.packageName
        ) == PackageManager.PERMISSION_GRANTED

    /** Shizuku 本体装没装。查不到就是没装，不把 NameNotFound 当成"未运行" */
    suspend fun shizuku(): ShizukuState = withContext(Dispatchers.IO) { ShizukuBridge.refresh() }

    /** 把散在各处的判据收成一份事实，等级由 EntropyStore 从这份事实推 */
    suspend fun permFacts(shizuku: ShizukuState): PermFacts = withContext(Dispatchers.IO) {
        PermFacts(
            root = shell.mode == ShellRunner.Mode.ROOT,
            adbShell = shell.mode == ShellRunner.Mode.ADB_SHELL,
            secureSettings = secureSettingsGranted,
            shizukuInstalled = shizuku.installed,
            shizukuVersion = shizuku.versionName,
            shizukuRunning = shizuku.running,
            shizukuGranted = shizuku.granted,
        )
    }

    /**
     * 本机能力清单。全部是"问系统要答案"，不靠 try/catch 猜：
     * 悬浮窗走 Settings.canDrawOverlays，通知走 NotificationManager，
     * 签名权限走 PackageManager.checkPermission，使用情况走 AppOpsManager。
     */
    suspend fun capabilities(): List<Capability> = withContext(Dispatchers.IO) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifGranted =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) nm.areNotificationsEnabled() else true
        val secureGranted = secureSettingsGranted

        buildList {
            add(Capability(
                CapabilityKey.OVERLAY, "显示在其他应用上层",
                if (OverlayService.canDraw(context)) "悬浮控制台可以常驻" else "没有它无法开启悬浮窗",
                OverlayService.canDraw(context), canGrantInApp = true
            ))
            add(Capability(
                CapabilityKey.NOTIFICATION, "通知权限",
                if (notifGranted) "常驻通知可见" else "Android 13+ 需要它才能挂住前台服务",
                notifGranted, canGrantInApp = true
            ))
            add(Capability(
                CapabilityKey.SECURE_SETTINGS, "写入安全设置",
                if (secureGranted) "可直接改刷新率等 secure 设置"
                else "只能由 ADB 授予：pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS",
                secureGranted, canGrantInApp = false
            ))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
                val usageGranted = try {
                    ops.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
                    ) == AppOpsManager.MODE_ALLOWED
                } catch (e: Exception) { false }
                add(Capability(
                    CapabilityKey.USAGE_STATS, "使用情况访问",
                    if (usageGranted) "可按前台应用自动切档" else "没有它无法识别当前在跑哪个游戏",
                    usageGranted, canGrantInApp = true
                ))
            }
        }
    }

    /** "我的"页的硬件画像。全部来自 Build / procfs / Display，任何权限都不需要 */
    suspend fun systemInfo(): SystemInfo = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val appInfo = pm.getApplicationInfo(context.packageName, 0)
        val profile = DeviceProber.probe()
        SystemInfo(
            appName = pm.getApplicationLabel(appInfo).toString(),
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            hardware = Build.HARDWARE,
            socModel = profile.socModel.ifEmpty { Build.MODEL },
            brand = Build.BRAND,
            model = Build.MODEL,
            abi = Build.SUPPORTED_ABIS.joinToString(", "),
            cores = Runtime.getRuntime().availableProcessors(),
            ramTotalMb = readMemTotalKb() / 1024,
            android = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
            kernel = readKernel(),
            screen = "${profile.nativeResolution.first}x${profile.nativeResolution.second}" +
                " · ${profile.supportedRefreshRates.joinToString("/") { "${it}Hz" }}",
            uid = Process.myUid(),
            pid = Process.myPid(),
        )
    }

    private fun readMemTotalKb(): Long = try {
        java.io.File("/proc/meminfo").bufferedReader().use { r ->
            generateSequence { r.readLine() }
                .firstOrNull { it.startsWith("MemTotal:") }
                ?.filter { it.isDigit() || it == ' ' }?.trim()?.toLongOrNull() ?: 0L
        }
    } catch (e: Exception) { 0L }

    /** os.version 给的就是 uname -r 的那串，比再起一个 shell 便宜 */
    private fun readKernel(): String =
        (System.getProperty("os.version") ?: "").ifEmpty {
            runCatching { java.io.File("/proc/version").readText().trim() }
                .getOrDefault("")
                .removePrefix("Linux version ").substringBefore(' ')
        }

    private companion object {
        const val KSU_ALLOWLIST = "/data/adb/ksu/.allowlist"

        // 命令里的 $d / $() 都是 shell 自己的语法，没有一处拼接 Kotlin 变量
        // PID 的判据与 HardwareBridge.daemonPid() 一致：转译机型上 pidof 匹配不到，
        // 必须回退到 pid 文件，否则这张卡会一直显示"entropyd 不在运行"。
        val DUMP_CMD = """
            echo "CTX:${'$'}(id -Z)"
            echo "ADB:${'$'}(ls /data/adb 2>/dev/null | tr '\n' ',')"
            pid=${'$'}(pidof entropyd 2>/dev/null | cut -d' ' -f1)
            [ -z "${'$'}pid" ] && pid=${'$'}(cat /data/local/tmp/entropy.pid 2>/dev/null)
            [ -n "${'$'}pid" ] && [ -d "/proc/${'$'}pid" ] || pid=""
            echo "PID:${'$'}pid"
        """.trimIndent()

        val MODULE_CMD = """
            for d in /data/adb/modules/*; do
              [ -f "${'$'}d/module.prop" ] || continue
              name=${'$'}(grep -m1 '^name=' "${'$'}d/module.prop" | cut -d= -f2-)
              ver=${'$'}(grep -m1 '^version=' "${'$'}d/module.prop" | cut -d= -f2-)
              flags=""
              [ -f "${'$'}d/disable" ] && flags="${'$'}flags off"
              [ -f "${'$'}d/remove" ] && flags="${'$'}flags rm"
              echo "MOD|${'$'}(basename "${'$'}d")|${'$'}name|${'$'}ver|${'$'}flags"
            done
        """.trimIndent()
    }
}
