// File: app/src/main/java/com/entropy/hardware/Chipset.kt
//
// 芯片识别 + 节点映射。
//
// 为什么要在 App 侧也做一遍识别（Daemon 已经做了）：
//  UI 要展示"你的机器是天玑 9300，可调频率表 12 档"这类信息时不能每次都问 Daemon，
//  而且未授权（无 root、Daemon 没起来）时界面也得有内容可显示。
//  因此这里只用完全公开的 SystemProperties + sysfs 只读路径，任何权限都不需要。
package com.entropy.hardware

import android.os.Build
import android.util.Log

/**
 * SoC 家族。枚举值的判定顺序有讲究：先看高通独有的 /sys/devices/soc0，
 * 再看 MTK 的 cpufreq 伪文件，最后按 ro.hardware 前缀兜底。
 */
enum class SocFamily(val label: String) {
    QUALCOMM("高通骁龙"),
    MEDIATEK("联发科天玑"),
    EXYNOS("三星猎户座"),
    TENSOR("Google Tensor"),
    RIALSENSOR("紫光展锐"),
    UNKNOWN("未识别平台");

    companion object {
        /** 读 ro.* 属性。反射是官方 API 之外的唯一途径（@hide），但读取本身不敏感。 */
        fun prop(key: String, def: String = ""): String = try {
            val c = Class.forName("android.os.SystemProperties")
            val m = c.getMethod("get", String::class.java, String::class.java)
            (m.invoke(null, key, def) as String)
        } catch (e: Throwable) {
            Log.d("EntropyChip", "prop($key) failed: ${e.message}")
            def
        }

        /**
         * 平台判定。
         *
         * 优先用 API 31 起系统自己申报的 Build.SOC_MANUFACTURER / SOC_MODEL ——
         * 这是唯一"厂商亲口说的"依据，OEM 乱填 ro.hardware 时它一般还是对的。
         * 拿不到（老系统、或 ROM 把它编译成空串）才退回节点嗅探。
         * 顺序不能反：ro.hardware 在转译容器和魔改 ROM 上会给出假平台，
         * 徽标因此染错颜色比"识别不出来"更糟。
         */
        fun detect(): SocFamily {
            val declared = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                Build.SOC_MANUFACTURER else ""
            val declaredModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                Build.SOC_MODEL else ""
            val hw = prop("ro.hardware").lowercase()
            val board = prop("ro.product.board").lowercase()
            val model = prop("ro.soc.model", prop("ro.product.model"))
            val mfr = declared.lowercase()
            return when {
                // 三星既做 Exynos 也做骁龙机型，所以厂商名不够，必须型号里真有 Exynos
                mfr.contains("qualcomm") -> QUALCOMM
                mfr.contains("mediatek") -> MEDIATEK
                mfr.contains("unisoc") || declaredModel.contains("T7", true) -> RIALSENSOR
                mfr.contains("google") || declaredModel.contains("Tensor", true) -> TENSOR
                mfr.contains("samsung") && declaredModel.contains("exynos", true) -> EXYNOS
                java.io.File("/sys/devices/soc0/machine").canRead() ||
                    java.io.File("/sys/class/kgsl/kgsl-3d0").exists() ||
                    hw.contains("qcom") || board.contains("qcom") -> QUALCOMM
                java.io.File("/proc/mtk_cpufreq").exists() ||
                    java.io.File("/sys/kernel/mtk_cpu").exists() ||
                    hw.startsWith("mt") || hw.contains("mediatek") -> MEDIATEK
                board.contains("exynos") || model.contains("Exynos") -> EXYNOS
                model.contains("Tensor") || board.contains("zuma") || board.contains("tangor") -> TENSOR
                hw.contains("ums") || hw.contains("unisoc") || model.contains("T7") -> RIALSENSOR
                else -> UNKNOWN
            }
        }
    }
}

/** 一次性的设备硬件画像，供"硬件体检报告"页面直接渲染 */
data class DeviceProfile(
    val family: SocFamily,
    val socModel: String,
    val brand: String,
    val model: String,
    val androidVersion: String,
    val sdkInt: Int,
    val kernel: String,
    val cpuClusters: List<Cluster>,
    val gpuNodes: List<String>,
    /** GPU 频率上限那一侧的实测情况；这台机器没有可读的上限节点时为 null */
    val gpuFreq: GpuFreq?,
    val thermalZones: List<String>,
    val nativeResolution: Pair<Int, Int>,
    val supportedRefreshRates: List<Int>,
    val hasCgroupV2: Boolean,
    val bpfSupported: Boolean,
) {
    data class Cluster(
        val name: String,
        val cores: IntRange,
        val availableKhz: List<Int>,
        val curKhz: Int
    ) {
        val minKhz get() = availableKhz.minOrNull() ?: curKhz
        val maxKhz get() = availableKhz.maxOrNull() ?: curKhz
    }

    /** 人类可读的 OPP 范围，例如 "0.60 ~ 3.30 GHz" */
    fun clusterRange(c: Cluster) =
        String.format("%.2f ~ %.2f GHz", c.minKhz / 1_000_000.0, c.maxKhz / 1_000_000.0)

    /**
     * GPU 上限节点与它的合法档位，单位一律 kHz。
     *
     * 只收录"读得到值"的节点：sysfs 里存在但被 SELinux 挡住的 max_freq 会让界面
     * 多出一根永远推不动的滑杆，那种东西不如不显示。
     */
    data class GpuFreq(
        val maxNode: String,
        val availKhz: List<Int>,
        val curMaxKhz: Int,
        val minKhz: Int,
    ) {
        val maxLimitKhz get() = availKhz.maxOrNull() ?: curMaxKhz

        /**
         * 给滑杆和吸附用的有序区间。
         *
         * min_gpu_clock 与 max_gpu_clock 是两个各自独立可写的 sysfs 节点， avail 表又可能
         * 整个读不到（SELinux 挡住时 readKhzList 返回空表，两端就各自回退到不同的节点）。
         * 于是 minKhz > maxLimitKhz 是真会出现的状态，直接拿去 coerceIn 会抛
         * "Cannot coerce value to an empty range" —— 用户拖一下 GPU 上限就崩。
         * 这里只把顺序摆正，不改任何实测值：读到的还是那两个数，谁大谁小由它们自己说。
         */
        val loKhz get() = minOf(minKhz, maxLimitKhz)
        val hiKhz get() = maxOf(minKhz, maxLimitKhz)
    }
}

object DeviceProber {

    private fun readFile(path: String): String = try {
        java.io.File(path).readText().trim()
    } catch (e: Exception) { "" }

    /**
     * 走 WindowManager 而不是 Context.getDisplay()：Application Context 没有窗口
     * token，后者在界面还没起来时会直接抛 "no associated token and no active display"，
     * 而 HardwareBridge 是在 Application 级单例的字段初始化里就探测硬件的。
     */
    @Suppress("DEPRECATION")
    private fun displayOf(context: android.content.Context?): android.view.Display? =
        runCatching {
            (context?.getSystemService(android.content.Context.WINDOW_SERVICE)
                as? android.view.WindowManager)?.defaultDisplay
        }.getOrNull()

    /**
     * @param context 只有拿到 Context 才可能问到 Display。不传就退化成硬编码的
     *  1080x2400 + 空刷新率表，而分辨率降档是按这个尺寸算的 —— 非 1080x2400 的机器
     *  于是会下发一组物理上不存在的模式（实测 900x1600 的屏幕被写成 540x1200）。
     */
    fun probe(context: android.content.Context? = null): DeviceProfile {
        val family = ChipsetInfo.family()
        val clusters = readClusters()
        val zones = readThermalZones()
        val display = displayOf(context)
        val rates = ChipsetInfo.readRefreshRates(display)
        val res = ChipsetInfo.readNativeResolution(display)
        return DeviceProfile(
            family = family,
            socModel = ChipsetInfo.socModel(),
            brand = Build.BRAND,
            model = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            kernel = System.getProperty("os.version") ?: "",
            cpuClusters = clusters,
            gpuNodes = ChipsetInfo.gpuNodesFor(family),
            gpuFreq = ChipsetInfo.gpuFreq(),
            thermalZones = zones,
            nativeResolution = res,
            supportedRefreshRates = rates,
            hasCgroupV2 = java.io.File("/sys/fs/cgroup/cgroup.controllers").exists(),
            bpfSupported = java.io.File("/sys/kernel/btf/vmlinux").exists(),
        )
    }
}

private object ChipsetInfo {
    fun family() = SocFamily.detect()
    fun socModel(): String =
        SocFamily.prop("ro.soc.model").ifEmpty { SocFamily.prop("ro.product.model") }

    /**
     * 各平台的 GPU 频率节点命名差异极大，这里给出"按存在性筛选"的候选表，
     * UI 只显示真实存在的那些，避免展示一堆永远灰着的开关。
     */
    fun gpuNodesFor(f: SocFamily): List<String> = buildList {
        when (f) {
            SocFamily.QUALCOMM -> {
                add("/sys/class/kgsl/kgsl-3d0/max_gpu_clock")
                add("/sys/class/kgsl/kgsl-3d0/min_gpu_clock")
                add("/sys/class/kgsl/kgsl-3d0/gpu_available_frequencies")
            }
            SocFamily.MEDIATEK -> {
                add("/sys/class/misc/mtkgpufreq/gpufreq0/max_freq")
                add("/sys/kernel/mtk_gpufreq/gpufreq0/max_freq")
            }
            SocFamily.EXYNOS, SocFamily.TENSOR -> {
                add("/sys/devices/platform/11c00000.mali/le_opengl_server/max_freq")
            }
            else -> {}
        }
        // devfreq 是跨平台通用形态，所有平台都试一次
        add("/sys/class/devfreq")
    }.filter { java.io.File(it).exists() }

    /**
     * 找出可写的 GPU 频率上限节点。
     *
     * 顺序和 Daemon 的 ent_scan_gpu 保持一致（kgsl 优先，再退到 devfreq），
     * 单位换算也用同一条"看位数猜"的规则 —— 两边算出不同的 kHz，
     * 界面显示 719 MHz 而实际下发 719000 kHz 这种事早晚会发生一次。
     */
    fun gpuFreq(): DeviceProfile.GpuFreq? {
        val kgsl = java.io.File("/sys/class/kgsl/kgsl-3d0/max_gpu_clock")
        val node = if (readKhz(kgsl) > 0) kgsl else {
            java.io.File("/sys/class/devfreq").listFiles { f -> f.isDirectory }
                ?.sortedBy { it.name }
                ?.map { java.io.File(it, "max_freq") }
                ?.firstOrNull { readKhz(it) > 0 }
                ?: return null
        }
        val cur = readKhz(node)
        val availFile = if (node.name == "max_gpu_clock")
            java.io.File(node.parentFile, "gpu_available_frequencies")
        else java.io.File(node.parentFile, "available_frequencies")
        val avail = readKhzList(availFile)
        val minFile = if (node.name == "max_gpu_clock")
            java.io.File(node.parentFile, "min_gpu_clock")
        else java.io.File(node.parentFile, "min_freq")
        val min = avail.minOrNull() ?: readKhz(minFile).takeIf { it > 0 } ?: cur
        return DeviceProfile.GpuFreq(node.absolutePath, avail, cur, min)
    }

    /** sysfs 频率节点的位数单位不统一（Hz / kHz 都有），>5_000_000 一律按 Hz 折半 */
    private fun readKhz(f: java.io.File): Int {
        val raw = runCatching {
            if (f.canRead()) f.readText().trim().toIntOrNull() else null
        }.getOrNull() ?: return 0
        return if (raw <= 0) 0 else if (raw > 5_000_000) raw / 1000 else raw
    }

    private fun readKhzList(f: java.io.File): List<Int> = runCatching {
        if (!f.canRead()) return emptyList()
        f.readText().trim().split(Regex("\\s+"))
            .mapNotNull { it.toIntOrNull() }
            .filter { it > 0 }
            .map { if (it > 5_000_000) it / 1000 else it }
            .distinct().sorted()
    }.getOrDefault(emptyList())

    /**
     * 面板支持的刷新率。
     * 走 Display.getSupportedModes()（API 19，minSdk 24 之上安全）：
     * getSupportedRefreshRates() 要 API 30，直接调用会在 8.0~10 上 NoSuchMethodError，
     * 而反射版永远落到 catch 分支 —— 结果都是"任何机型只显示当前刷新率"。
     */
    fun readRefreshRates(display: android.view.Display?): List<Int> {
        if (display == null) return emptyList()
        val modes: Array<android.view.Display.Mode> = display.supportedModes ?: emptyArray()
        val rates = modes.map { (it.refreshRate + 0.5f).toInt() }
            .filter { it > 0 }.distinct().sorted()
        return if (rates.isNotEmpty()) rates else listOf((display.refreshRate + 0.5f).toInt())
    }

    /**
     * 面板原生像素尺寸。用 Mode.physicalWidth/Height —— 它是自然方向的物理像素，
     * 转屏不变；getRealMetrics 给的是当前方向尺寸，拿它算降档比例会在横屏下算反。
     */
    fun readNativeResolution(display: android.view.Display?): Pair<Int, Int> {
        if (display == null) return 1080 to 2400
        val m = runCatching { display.mode }.getOrNull()
        val w = m?.physicalWidth ?: 0
        val h = m?.physicalHeight ?: 0
        if (w > 0 && h > 0) return w to h
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(dm)
        return if (dm.widthPixels > 0 && dm.heightPixels > 0) dm.widthPixels to dm.heightPixels
        else 1080 to 2400
    }
}

private fun readClusters(): List<DeviceProfile.Cluster> {
    val base = java.io.File("/sys/devices/system/cpu/cpufreq")
    val dirs = base.listFiles { f -> f.isDirectory && f.name.startsWith("policy") }?.sortedBy { it.name }
        ?: return emptyList()
    return dirs.mapIndexed { i, d ->
        val avail = try {
            java.io.File(d, "scaling_available_frequencies").readText()
                .trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }.sorted()
        } catch (e: Exception) { emptyList() }
        val related = try {
            java.io.File(d, "related_cpus").readText().trim().split(",").mapNotNull { it.toIntOrNull() }
        } catch (e: Exception) { emptyList() }
        val cur = try {
            java.io.File("/sys/devices/system/cpu/cpu${related.firstOrNull() ?: 0}",
                "cpufreq/scaling_cur_freq").readText().trim().toInt()
        } catch (e: Exception) { avail.firstOrNull() ?: 0 }
        DeviceProfile.Cluster(
            name = "policy${i} (${d.name})",
            cores = if (related.isEmpty()) 0..0 else related.min()..related.max(),
            availableKhz = avail,
            curKhz = cur
        )
    }
}

private fun readThermalZones(): List<String> {
    val base = java.io.File("/sys/class/thermal")
    val dirs = base.listFiles { f -> f.name.startsWith("thermal_zone") } ?: return emptyList()
    return dirs.mapNotNull { d ->
        val type = try { java.io.File(d, "type").readText().trim() } catch (e: Exception) { null }
        type?.let { "${d.name} = $it" }
    }.sorted()
}
