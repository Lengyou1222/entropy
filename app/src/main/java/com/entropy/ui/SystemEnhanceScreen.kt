// File: app/src/main/java/com/entropy/ui/SystemEnhanceScreen.kt
//
// 系统增强页。
//
// 关于"温控欺骗开关"：这一项被换成了"温控阈值策略"。
// 伪造温度给 thermal 子系统（LD_PRELOAD 劫持 read 返回假 36℃、丢弃降频 write）
// 会让 SoC 与锂电池失去唯一的过热保护 —— 降频不是性能惩罚，而是热设计的执行机构。
// 这里提供的是同样能保住性能、但不摘保险丝的做法：
//   把"我们主动降档的软上限"和"绝对熔断线"设为可调项，
//   让 Daemon 在厂商冷却介入之前就先退让，性能曲线更平滑，硬件仍然受保护。
package com.entropy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.entropy.data.EntropyStore
import com.entropy.data.Feature
import com.entropy.data.UiState
import com.entropy.hardware.HardwareBridge
import com.entropy.shared.Proto
import com.entropy.ui.components.agoText
import com.entropy.ui.components.GateHint
import com.entropy.ui.components.HighlightColorCard
import com.entropy.ui.components.GatedBlock
import com.entropy.ui.components.KvRow
import com.entropy.ui.components.NoticeBar
import com.entropy.ui.components.SectionCard
import com.entropy.ui.components.TickSlider
import com.entropy.ui.theme.LocalEntropyColors
import com.entropy.ui.theme.tempColor
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun SystemEnhanceScreen(store: EntropyStore, state: UiState) {
    val prefs = store.prefs
    val entropy = LocalEntropyColors.current
    val scope = rememberCoroutineScope()

    var showWarn by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ---------- 高亮警示色 ---------- //
        // 这一支管"危险"语义：过热、熔断、越限提醒都染它，所以没有"跟随方案"这个出口
        HighlightColorCard(
            title = "高亮警示色",
            subtitle = "温度越限与熔断事件会用这个色染色，请选一个在你游戏画面上足够扎眼的颜色。" +
                "它和「我的 · 外观」里的界面高亮色是两回事：那一个改的是全站强调色，" +
                "这一个只改报警。",
            currentArgb = prefs.accentColor,
            defaultArgb = 0xFFFF453A.toInt(),
            onApply = { prefs.accentColor = it },
        )

        // ---------- 温控策略（取代"温控欺骗"） ---------- //
        // 阈值只是"存下来"的偏好，真正执行它的是以 root 身份跑的 entropyd；
        // 低权限时整块压灰，但下面那两条滑杆仍各自保留 path 判据 ——
        // su 已授予、Daemon 还在拉起的几秒里，等级已经是 ROOT 而通道还没通，
        // 只看等级会把还能不能下发这件事说反。
        GatedBlock(store, Feature.THERMAL_CFG, Modifier.fillMaxWidth()) {
            SectionCard("温控阈值策略", "决定 Daemon 在什么温度主动退让、在什么温度熔断还原") {
                TickSlider(
                    value = prefs.softTempMc,
                    onValueChange = { store.setSoftTemp(it) },
                    range = 40_000..90_000, detents = 11,
                    label = "软上限（主动降一档）",
                    valueText = "%.1f℃".format(prefs.softTempMc / 1000f),
                    enabled = state.path != HardwareBridge.Path.READONLY
                )
                TickSlider(
                    value = prefs.fuseTempMc,
                    onValueChange = {
                        // 绝对上限在 App、Daemon、命令行三处各钳一次，任何一处被绕过
                        // 都不会让设备失去保护。这条线看的是所有热区的最大值，
                        // 真实机型上那通常是结温，所以 50℃ 早已不是危险线
                        val v = it.coerceAtMost(Proto.FUSE_MAX_MC)
                        if (v >= 90_000) showWarn = true
                        store.setFuseTemp(v)
                    },
                    range = 45_000..Proto.FUSE_MAX_MC, detents = 11,
                    label = "绝对熔断（全量还原）",
                    valueText = "%.1f℃".format(prefs.fuseTempMc / 1000f),
                    color = entropy.warn,
                    enabled = state.path != HardwareBridge.Path.READONLY
                )
                Text(
                    "读数取所有热区的最大值，负载起来 60~80℃ 是常态；" +
                    "熔断线只决定熵什么时候收手，内核自己的降频永远比它先动。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                GateHint(store, Feature.THERMAL_CFG)
            }
        }

        // ---------- 真·暂停 ---------- //
        GatedBlock(store, Feature.FREEZE, Modifier.fillMaxWidth()) {
            SectionCard("后台真·暂停（cgroup freezer）", "冻结后台应用的调度时间片，解冻后进程状态完全保留") {
                KvRow("已冻结应用数", "${state.frozenApps}")
                KvRow("累计节省 CPU", "%.1f s".format(state.savedCpuMs / 1000f))
                KvRow("机制", "cgroup v2 freezer + appops RUN_ANY_IN_BACKGROUND")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { store.refreshFreezer() }) { Text("刷新状态") }
                    OutlinedButton(onClick = { scope.launch { store.bridge.resetAll() } }) { Text("全部解冻") }
                }
                Text(
                    "说明：本项目不 hook 框架层的墓碑管理。冻结与后台限制都走内核 freezer " +
                        "和官方 appops 接口，系统看得见发生了什么，用户在设置里也能撤销。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                GateHint(store, Feature.FREEZE)
            }
        }

        // ---------- 守护进程 ---------- //
        SectionCard("守护进程状态", "entropy-monitor 负责看护调度核心与被覆盖的策略") {
            val hb = state.heartbeat
            KvRow("运行状态", when (hb?.state ?: 0) {
                0 -> "空闲"; 1 -> "调度中"; 2 -> "探测中"; 3 -> "熔断锁定"; else -> "未知"
            })
            KvRow("运行时长", hb?.let { "%d:%02d".format(it.uptimeS / 60, it.uptimeS % 60) } ?: "—")
            // 150‰ 与 Daemon 的自检预算是同一个数（safety_fuse.h 的 ENT_SELF_CPU_BUDGET_PERMILLE）：
            // 真 ARM 机上约 1%，带 ARM 转译的机型实测 5.6%，写 1% 会让慢机器天天显示成"超标"
            KvRow("自身 CPU 占用", hb?.let { "%.2f%%".format(it.cpuPermille / 10.0) }
                ?: "—（预算 < 15%）", highlight = (hb?.cpuPermille ?: 0) > 150)
            KvRow("最近一次重下档", hb?.let { agoText(it.lastReapplyAgoS) } ?: "—")
            KvRow("信号源", if (state.path == HardwareBridge.Path.DAEMON)
                "eBPF / schedstat（见 Daemon 日志）" else "不可用")
            Text(
                "策略被厂商温控覆盖时，monitor 会重新下档；但如果覆盖的原因是温度越限，" +
                    "monitor 会退让而不是对抗 —— 这条判据写在 thermal_monitor.c 的 check_overrides 里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (state.safety.fused) {
            // reason 只有 Daemon 明确报了原因才有；空的时候不能拼成"熔断中：。"
            val why = state.safety.reason
            NoticeBar("熔断中${if (why.isEmpty()) "" else "：$why"}。设备需自然散热，120 秒后自动解锁。",
                entropy.warn)
        }
        Spacer(Modifier.height(20.dp))
    }

    if (showWarn) {
        AlertDialog(
            onDismissRequest = { showWarn = false },
            title = { Text("硬件安全提示", color = entropy.warn, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("你把熔断线上调到了 ${"%.1f℃".format(prefs.fuseTempMc / 1000f)}。")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "SoC 结温与电池温度是两件事，但热是累积的：长时间贴近保护线上限运行，" +
                            "会显著加速电池容量衰减，并使主板焊点疲劳。" +
                            "熵不会屏蔽厂商的强制降频 —— 即便你把阈值调到最高，" +
                            "内核热保护一旦动作，调度器只会退让。")
                    Spacer(Modifier.height(8.dp))
                    Text("建议：日常使用保持在 85℃ 以下。", fontWeight = FontWeight.SemiBold)
                }
            },
            confirmButton = {
                Button(onClick = { showWarn = false },
                    colors = ButtonDefaults.buttonColors(containerColor = entropy.warn)) {
                    Text("我已知晓风险")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    store.setFuseTemp(85_000); showWarn = false
                }) { Text("改回默认 85℃") }
            }
        )
    }
}
