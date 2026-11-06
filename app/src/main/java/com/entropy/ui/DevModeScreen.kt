// File: app/src/main/java/com/entropy/ui/DevModeScreen.kt
//
// 开发模式：硬件直调 + 体检报告。
//
// 交互约定：所有滑杆都遵循"本地草稿 -> 显式应用"，
// 因为这一页的每一项都会真的写内核，误触代价远高于日常页。
package com.entropy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.entropy.data.EntropyStore
import com.entropy.data.Feature
import com.entropy.data.UiState
import com.entropy.hardware.DeviceProfile
import com.entropy.hardware.HardwareBridge
import com.entropy.shared.Proto
import com.entropy.ui.components.GateHint
import com.entropy.ui.components.GatedBlock
import com.entropy.ui.components.Gauge
import com.entropy.ui.components.KvRow
import com.entropy.ui.components.NoticeBar
import com.entropy.ui.components.SectionCard
import com.entropy.ui.components.TickSlider
import com.entropy.ui.theme.LocalEntropyColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DevModeScreen(store: EntropyStore, state: UiState) {
    val prefs = store.prefs
    val entropy = LocalEntropyColors.current
    val scope = rememberCoroutineScope()

    val prof = state.probe
    val p: DeviceProfile = store.bridge.profile

    // 分辨率候选：按面板原生分辨率等比降档，避免给出物理上不存在的模式
    val (natW, natH) = p.nativeResolution
    val resPresets = remember(natW, natH) {
        listOf(
            "原生 %dx%d".format(natW, natH) to (natW to natH),
            "3/4 缩放" to (natW * 3 / 4 to natH * 3 / 4),
            "2/3 缩放" to (natW * 2 / 3 to natH * 2 / 3),
            "1/2 缩放" to (natW / 2 to natH / 2),
        )
    }
    var resIdx by remember { mutableStateOf(0) }
    var touchHz by remember { mutableStateOf(prefs.touchHz) }
    var fpsLimit by remember { mutableStateOf(prefs.fpsLimit) }
    var cpuCap by remember { mutableStateOf(prefs.cpuMaxKhz) }
    var gpuCap by remember { mutableStateOf(prefs.gpuMaxKhz) }
    var renderer by remember { mutableStateOf(prefs.renderer) }
    var rendererOpen by remember { mutableStateOf(false) }
    var showAdb by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (state.path != HardwareBridge.Path.DAEMON) {
            NoticeBar(
                "本页所有写操作都需要 Root 或 ADB 拉起 Daemon。当前路径：${state.path} · " +
                    "权限等级：${store.level.value.label}",
                entropy.tempWarm)
        }

        // ---------- 硬件直调 ---------- //
        SectionCard("底层硬件直调", "写内核节点与系统属性，应用后立即生效但可能被厂商温控回改") {
            GatedBlock(store, Feature.RESOLUTION) {
                Text("屏幕分辨率", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    resPresets.forEachIndexed { i, (label, _) ->
                        OutlinedButton(onClick = { resIdx = i }) {
                            Text(label, fontWeight = if (resIdx == i) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            TickSlider(
                value = touchHz, onValueChange = { touchHz = it },
                range = 120..300, detents = 7,
                label = "触控采样率", valueText = "$touchHz Hz",
                enabled = !store.locked(Feature.TOUCH_HZ)
            )
            TickSlider(
                // 0 表示"跟随系统"，所以滑杆左端固定为 0，右端给到 240
                value = fpsLimit, onValueChange = { fpsLimit = it },
                range = 0..240, detents = 25,
                label = "游戏帧率上限",
                valueText = if (fpsLimit == 0) "跟随系统" else "$fpsLimit FPS",
                enabled = !store.locked(Feature.FPS_CAP)
            )
            // 两根滑杆分属两个 Feature，所以各自给一行说明，不用整块合并的 GateHint
            if (store.locked(Feature.TOUCH_HZ)) GateHint(store, Feature.TOUCH_HZ)
            if (store.locked(Feature.FPS_CAP)) GateHint(store, Feature.FPS_CAP)
            Spacer(Modifier.height(10.dp))
            GatedBlock(store, Feature.RENDERER) {
                OutlinedButton(onClick = { rendererOpen = true }) {
                    Text("渲染后端：" + when (renderer) {
                        Proto.RENDERER_SKIA -> "Skia (skia)"
                        Proto.RENDERER_GLES -> "OpenGL ES 3.2 (skiagl)"
                        Proto.RENDERER_VULKAN -> "Vulkan 1.3 (vk)"
                        else -> "系统默认"
                    })
                }
            }
            DropdownMenu(expanded = rendererOpen, onDismissRequest = { rendererOpen = false }) {
                listOf(
                    "系统默认" to Proto.RENDERER_DEFAULT,
                    "Skia（2D 光栅，最稳）" to Proto.RENDERER_SKIA,
                    "OpenGL ES 3.2（skiagl）" to Proto.RENDERER_GLES,
                    "Vulkan 1.3（部分 ROM 已裁剪）" to Proto.RENDERER_VULKAN,
                ).forEach { (label, code) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = {
                        renderer = code; rendererOpen = false
                    })
                }
            }
            Spacer(Modifier.height(12.dp))
            // 这一排写操作里门槛最低的是分辨率（Shizuku/ADB）：
            // 连那一档都够不到时，四个写入没有一个能落地，整排一起灰
            GatedBlock(store, Feature.RESOLUTION) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = {
                        prefs.renderer = renderer
                        prefs.touchHz = touchHz
                        scope.launch {
                            val (w, h) = resPresets[resIdx].second
                            store.bridge.setResolution(w, h)
                            store.bridge.setRenderer(renderer)
                            // 这里必须走 store 而不是 bridge：0 的语义是"清除上限"，
                            // bridge 的 24..240 校验会把它当非法值挡回，"跟随系统"就永远清不掉
                            store.setFpsLimit(fpsLimit)
                            // 触控采样率是 vendor 私有节点，路径因机型而异，
                            // 因此这里只发"意图"，由 Daemon 去遍历候选节点，
                            // App 端不去猜路径 —— 猜错会写坏别的东西
                            val touchOk = store.bridge.setTouchHz(touchHz)
                            if (!touchOk) store.note("触控采样率未生效：需要 Daemon 运行，且该机型可能没有暴露节点")
                            prefs.resPreset = resIdx
                        }
                    }) { Text("应用") }
                    TextButton(onClick = {
                        scope.launch { store.bridge.resetResolution() }
                    }) { Text("还原分辨率") }
                    OutlinedButton(onClick = { showAdb = !showAdb }) { Text("ADB 命令") }
                }
            }
            if (showAdb) {
                Spacer(Modifier.height(8.dp))
                Text(
                    store.bridge.adbSetupCommands(),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                        .padding(10.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                )
            }
        }

        // ---------- 频率上限 ---------- //
        run {
            val cpuLo = p.cpuClusters.minOfOrNull { it.minKhz } ?: 0
            val cpuHi = p.cpuClusters.maxOfOrNull { it.maxKhz } ?: 0
            val gpu = p.gpuFreq
            val cpuLocked = store.locked(Feature.CPU_FREQ)
            val gpuLocked = store.locked(Feature.GPU_FREQ)
            SectionCard("CPU / GPU 频率上限",
                "只压不抬：越过硬件 OPP 表上限的值会被内核按最近一档收下，超频不在本项目的提供范围内") {
                if (cpuHi <= cpuLo) {
                    Text("没有可访问的 cpufreq 节点", style = MaterialTheme.typography.bodySmall)
                } else {
                    TickSlider(
                        value = if (cpuCap > 0) cpuCap.coerceIn(cpuLo, cpuHi) else cpuHi,
                        onValueChange = { cpuCap = it },
                        range = cpuLo..cpuHi, detents = 13,
                        label = "CPU 上限",
                        valueText = if (cpuCap == 0) "不限制"
                        else "%.2f GHz".format(cpuCap / 1_000_000.0),
                        enabled = !cpuLocked
                    )
                }
                Spacer(Modifier.height(10.dp))
                if (gpu == null) {
                    Text("这台机器没有可读的 GPU 频率节点（kgsl 与 devfreq 都没暴露可写的上限值）",
                        style = MaterialTheme.typography.bodySmall)
                } else {
                    val gLo = gpu.minKhz
                    val gHi = gpu.maxLimitKhz
                    TickSlider(
                        value = if (gpuCap > 0) gpuCap.coerceIn(gLo, gHi) else gHi,
                        onValueChange = { gpuCap = it },
                        range = gLo..gHi, detents = 13,
                        label = "GPU 上限",
                        valueText = if (gpuCap == 0) "不限制" else "${gpuCap / 1000} MHz",
                        enabled = !gpuLocked
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("当前节点上限 ${gpu.curMaxKhz / 1000} MHz · 可用 ${gpu.availKhz.size} 档",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(
                        onClick = {
                            prefs.cpuMaxKhz = cpuCap
                            prefs.gpuMaxKhz = gpuCap
                            scope.launch { store.setFreqCaps(cpuCap, gpuCap) }
                        },
                        enabled = !cpuLocked && !gpuLocked
                    ) { Text("应用") }
                    TextButton(onClick = {
                        cpuCap = 0; gpuCap = 0
                        scope.launch { store.setFreqCaps(0, 0) }
                    }, enabled = !cpuLocked && !gpuLocked) { Text("全部不限制") }
                }
                if (cpuLocked) GateHint(store, Feature.CPU_FREQ)
                if (gpuLocked) GateHint(store, Feature.GPU_FREQ)
            }
        }

        // ---------- 体检报告 ---------- //
        SectionCard(
            "硬件体检报告",
            if (prof == null) "尚未探测。探测会短时抬频，全程由熔断线程看护，超过阈值会立即中止并还原。"
            else "探测完成度 ${prof.done}%；${prof.note}"
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Gauge(
                    fraction = pctOf(prof?.cpuSweetKhz, p.cpuClusters.maxOfOrNull { it.maxKhz }),
                    label = "CPU 甜点",
                    value = ghz(prof?.cpuSweetKhz),
                    color = entropy.tempCool
                )
                Gauge(
                    fraction = pctOf(prof?.cpuAbsLimitKhz, p.cpuClusters.maxOfOrNull { it.maxKhz }),
                    label = "CPU 极限",
                    value = ghz(prof?.cpuAbsLimitKhz),
                    color = entropy.tempWarm
                )
                Gauge(
                    fraction = pctOf(prof?.gpuSweetKhz, 1_000_000),
                    label = "GPU 甜点",
                    value = mhz(prof?.gpuSweetKhz),
                    color = entropy.tempHot
                )
            }
            Spacer(Modifier.height(12.dp))
            KvRow("强制降频温度阈值", prof?.throttleTempMc?.let { "%.1f℃".format(it / 1000f) } ?: "未观测")
            KvRow("最高能效比", prof?.let { "%.2f".format(it.bestPerfPerWatt / 1000.0) } ?: "—")
            KvRow("熔断保护线", "%.1f℃".format(prefs.fuseTempMc / 1000f), highlight = true)
            KvRow("SoC", "${p.family.label} · ${p.socModel}")
            KvRow("Cluster 数", "${p.cpuClusters.size}")
            KvRow("eBPF 可用", if (p.bpfSupported) "是（存在 vmlinux BTF）" else "否，退回 schedstat 采样")
            KvRow("cgroup v2", if (p.hasCgroupV2) "是" else "否（freezer 走 v1）")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = { store.probe() },
                    enabled = !state.probing && !store.locked(Feature.PROBE)) {
                    Text(if (state.probing) "探测中 ${state.probe?.done ?: 0}%" else "开始极限探测")
                }
                if (state.probing) {
                    OutlinedButton(onClick = { store.stopProbe() }) { Text("中止") }
                }
                // "一键应用极限配置"用的是甜点频率，而不是极限频率 ——
                // 极限值只用于展示"这台机器能稳在哪"，长期跑极限没有意义且发热严重
                TextButton(
                    onClick = {
                        prof?.cpuSweetKhz?.let { khz ->
                            // 带上当前 GPU 上限：Daemon 每次收到 profile 请求都会先清零 cfg，
                            // 不带过去就等于点一下"应用甜点配置"把用户的 GPU 上限抹掉
                            scope.launch {
                                store.bridge.applyProfile(Proto.PROFILE_GAME, khz, prefs.gpuMaxKhz)
                            }
                        }
                    },
                    enabled = prof != null && !state.safety.fused && !store.locked(Feature.PROFILE)
                ) { Text("应用甜点配置") }
            }
            // 探测与下档分属两个 Feature，各说一次：只灰一个按钮却不解释，
            // 用户会以为是自己没点准
            if (store.locked(Feature.PROBE)) GateHint(store, Feature.PROBE)
            if (store.locked(Feature.PROFILE)) GateHint(store, Feature.PROFILE)
            if (state.safety.fused) {
                Spacer(Modifier.height(8.dp))
                NoticeBar("熔断锁定期内禁止抬频，120 秒后自动解锁", entropy.warn)
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

private fun pctOf(v: Int?, denom: Int?): Float =
    if (v == null || denom == null || denom <= 0) 0f else (v.toFloat() / denom).coerceIn(0f, 1f)

private fun ghz(khz: Int?): String = khz?.let { "%.2fG".format(it / 1_000_000.0) } ?: "—"
private fun mhz(khz: Int?): String = khz?.let { "%dM".format(it / 1000) } ?: "—"
