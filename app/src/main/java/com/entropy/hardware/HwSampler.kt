// File: app/src/main/java/com/entropy/hardware/HwSampler.kt
//
// 不带 Root 也能读的硬件采样，供安全显示悬浮窗与硬件记录共用。
//
// 三条取值链各自独立，全部"读不到就给 null"，不用 0 顶替：
//
// 1) 温度。/sys/class/thermal/thermal_zone*/temp 在本机是 444 + u:object_r:sysfs:s0，
//    普通应用直接 readText 就能拿到，因此先按文件读，失败才退回 shell。
//    另外电池温度走 BatteryManager 的粘性广播（0.1℃ 整数），它是任何机器都有的
//    公开读数，和热区温度不是一回事，所以两条都保留、各自标来源，不做"取最大就完事"。
//    PowerManager 的 thermalStatus / thermalHeadroom 同样免权限，但它是"系统准备降频"
//    的档位而不是温度，只有过热提醒要用它做二次确认。
//
// 2) 频率与内存。scaling_cur_freq 逐个核读；max_profile 那种聚合节点各家有无不一律，
//    所以只依赖每核节点，读不到的核直接跳过。
//
// 3) 帧率。没有 Root/ADB 通道时宁可给 null 也不给近似值 —— 本机的 LD_PRELOAD 探针
//    是唯一逐帧来源，其余情况用 dumpsys gfxinfo 的累计帧数在两次采样之间差分。
//    dumpsys 在应用身份下会被 SELinux 直接拒，连续三次拿不到结果就把这个目标包
//    标记为"取不到"，避免每秒再白跑一次几百毫秒的进程。
package com.entropy.hardware

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import java.io.File

class HwSampler(private val context: Context, private val shell: ShellRunner) {

    /** 一个热区读数：zone 的 type 名 + ℃ */
    data class Zone(val name: String, val celsius: Float)

    data class Sample(
        val ts: Long,
        /** 热区里的最高温度；一个都没读到为 null */
        val zoneMaxC: Float?,
        val zones: List<Zone>,
        /** 电池温度（粘性广播，任何机器都可读），读不到为 null */
        val batteryC: Float?,
        /** PowerManager.THERMAL_STATUS_*，API 29 以下为 -1 */
        val thermalStatus: Int,
        /** 距离降频还有多少秒，API 31 以下为 null */
        val headroomS: Float?,
        /** 各核当前频率(kHz)，读不到的核不出现在数组里 */
        val cpuKhz: IntArray,
        /** 帧率。null = 这台机器此刻没有可信来源，UI 要显示"—"而不是 0 */
        val fps: Int?,
        /** 帧率来源标识：gfxinfo 差分 / 首次采样 / 取不到的原因。探针来源由调用方覆盖 */
        val fpsSource: String,
        val memAvailMb: Long,
    )

    // ---- 帧率差分状态。按目标包名隔离，换目标立刻重置 ---- //
    private var fpsPkg = ""
    private var lastFrames = -1L
    private var lastAt = 0L
    private var failures = 0

    /** 标记某个目标包"取不到帧率"，直到换包或进程重启才再试 */
    private var blacklisted = ""

    /**
     * 采一次样。targetPkg 为帧率目标（前台应用）；空串表示只信 Daemon 探针。
     * shell.root()/shell.plain() 是挂起函数，所以这里跟着挂起，调用方给 IO 线程。
     */
    suspend fun sample(targetPkg: String): Sample {
        val zones = readZones()
        val (fps, fpsSource) = fpsFor(targetPkg)
        return Sample(
            ts = System.currentTimeMillis(),
            zoneMaxC = zones.map { it.celsius }.maxOrNull(),
            zones = zones,
            batteryC = batteryTempC(),
            thermalStatus = thermalStatus(),
            headroomS = headroomS(),
            cpuKhz = readCpuKhz(),
            fps = fps,
            fpsSource = fpsSource,
            memAvailMb = memAvailableMb(),
        )
    }

    /** 目标应用被切换或探针上线时清掉差分基线，否则第一次差值会是历史累计 */
    fun resetFps() {
        fpsPkg = ""; lastFrames = -1L; lastAt = 0L; failures = 0; blacklisted = ""
    }

    // ---------------------------------------------------------------- //
    // 温度                                                             //
    // ---------------------------------------------------------------- //

    /**
     * 遍历 thermal_zone* 目录。
     * 过滤条件是必须的：本机实测有恒为 0 的占位热区，个别机型没接上传感器的 zone
     * 会读出一个远大于物理可能的值，直接取最大就会得到"手机 250℃"这种假警报。
     */
    private fun readZones(): List<Zone> {
        val root = File(THERMAL_ROOT)
        val dirs = runCatching { root.listFiles()?.toList() }.getOrNull() ?: emptyList()
        return dirs.filter { it.name.startsWith("thermal_zone") }.mapNotNull { d ->
            val temp = readInt(File(d, "temp")) ?: return@mapNotNull null
            if (temp <= 0 || temp > 150_000) return@mapNotNull null
            val type = runCatching { File(d, "type").readText().trim() }.getOrNull()
                ?.takeIf { it.isNotEmpty() } ?: d.name
            Zone(type, temp / 1000f)
        }.sortedByDescending { it.celsius }
    }

    /** 粘性广播拿电池温度，单位 0.1℃；不需要任何权限，也拿不到就返回 null */
    private fun batteryTempC(): Float? {
        val intent: Intent = runCatching {
            context.registerReceiver(null as android.content.BroadcastReceiver?,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return null
        val t = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        return if (t in 100..1500) t / 10f else null
    }

    /**
     * 注意方法名是 getCurrentThermalStatus()：PowerManager 里没有 thermalStatus 这个 getter，
     * 写错只会得到"Unresolved reference"，而它看起来更像是 API 版本问题。
     */
    private fun thermalStatus(): Int =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) -1 else
            runCatching { (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus }
                .getOrDefault(-1)

    /** getThermalHeadroom 需要显式给 threshold 参数（0 = 第一档降频阈值），没有无参重载 */
    private fun headroomS(): Float? =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) null else runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val h = pm.getThermalHeadroom(0)
            if (h.isFinite()) h else null
        }.getOrNull()

    // ---------------------------------------------------------------- //
    // 频率 / 内存                                                       //
    // ---------------------------------------------------------------- //

    private fun readCpuKhz(): IntArray {
        val cpus = File("/sys/devices/system/cpu").listFiles() ?: return IntArray(0)
        return cpus.asSequence()
            .mapNotNull { f ->
                if (!f.name.matches(Regex("cpu[0-9]+"))) return@mapNotNull null
                readInt(File(f, "cpufreq/scaling_cur_freq"))
            }.toList().toIntArray()
    }

    private fun memAvailableMb(): Long = runCatching {
        File("/proc/meminfo").bufferedReader().use { r ->
            generateSequence { r.readLine() }
                .firstOrNull { it.startsWith("MemAvailable:") }
                ?.filter { it.isDigit() }?.toLongOrNull()?.div(1024) ?: 0L
        }
    }.getOrDefault(0L)

    // ---------------------------------------------------------------- //
    // 帧率                                                             //
    // ---------------------------------------------------------------- //

    /**
     * 返回"帧率 + 来源说明"。null 帧率一律带一句原因，UI 才能既显示"—"又答得上来"为什么没有"。
     *
     * dumpsys gfxinfo 里的 "Total frames rendered" 是该进程累计出帧数，
     * 两次采样相减除以真实间隔就是这段时间的平均帧率。目标应用停在后台不出帧时
     * 差值是 0，那是事实，不是取不到，所以照实返回 0 而不是 null。
     */
    private suspend fun fpsFor(pkg: String): Pair<Int?, String> {
        if (pkg.isEmpty()) return null to "未指定帧率目标"
        if (blacklisted == pkg) return null to "gfxinfo 不可用（无 Root 通道，或该应用未运行）"

        val now = System.currentTimeMillis()
        if (fpsPkg != pkg) { fpsPkg = pkg; lastFrames = -1L; lastAt = 0L; failures = 0 }

        val r = runCatching {
            // 应用身份跑 dumpsys 会被 SELinux 拒掉，只有拿到 su 才有回程；
            // 先按当前 shell 身份试一次，失败就在下面计入 failures。
            if (shell.mode == ShellRunner.Mode.ROOT) shell.root("dumpsys gfxinfo $pkg", 3)
            else shell.plain("dumpsys gfxinfo $pkg", 3)
        }.getOrNull()

        val frames = r?.out?.let { FRAMES_LINE.find(it)?.groupValues?.get(1)?.toLongOrNull() }
        if (frames == null) {
            if (++failures >= 3) {
                blacklisted = pkg
                Log.i(TAG, "gfxinfo unavailable for $pkg (${r?.err?.take(80) ?: "no result"})")
            }
            return null to "gfxinfo 采集中（第 $failures 次未取得读数）"
        }
        failures = 0
        if (lastFrames < 0 || lastAt == 0L || now <= lastAt) {
            lastFrames = frames; lastAt = now
            return null to "gfxinfo 首次采样，需两次读数才能算帧率"
        }
        val fps = ((frames - lastFrames) * 1000 / (now - lastAt)).toInt().coerceAtLeast(0)
        lastFrames = frames; lastAt = now
        return fps to "gfxinfo 累计帧数差分"
    }

    private fun readInt(f: File): Int? =
        runCatching { f.readText().trim().toIntOrNull() }.getOrNull()

    private companion object {
        const val TAG = "EntropySampler"
        const val THERMAL_ROOT = "/sys/class/thermal"
        val FRAMES_LINE = Regex("Total frames rendered:\\s*(\\d+)")
    }
}
