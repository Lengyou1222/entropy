// File: app/src/main/java/com/entropy/ui/HomeScreen.kt
//
// 首页 = 仪表盘 + 原有三块功能屏（日常 / 开发 / 系统增强）。
//
// 整页只有一个滚动容器：状态条、仪表盘卡、页签、功能屏内容全在里面。
// 早先的做法是外层不滚动、把功能屏塞进 weight(1f) 让它自己滚，
// 结果上半区（状态条 + 仪表盘 + 页签）在平板横屏下能占到一半高度，
// 下面那一小条视口根本装不下功能屏的内容，又没有任何办法把固定区推走。
//
// 代价是子屏不能再自带 verticalScroll —— 滚动容器嵌滚动容器会给子项
// 无限大高度约束，Compose 直接抛
// "Vertically scrollable component was measured with an infinity maximum height constraints"。
// 所以日常/开发/系统增强三块屏现在都只是普通的 Column，高度由内容决定。
package com.entropy.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.entropy.data.EntropyStore
import com.entropy.data.Feature
import com.entropy.data.UiState
import com.entropy.hardware.HardwareBridge
import com.entropy.shared.Proto
import com.entropy.ui.components.EntropyMotion
import com.entropy.ui.components.GateHint
import com.entropy.ui.components.NoticeBar
import com.entropy.ui.theme.LocalEntropyColors
import com.entropy.ui.theme.tempColor

/** initialSection：从悬浮窗/通知带进来的页签，0=日常 1=开发 2=系统增强 */
@Composable
fun HomeScreen(
    store: EntropyStore,
    state: UiState,
    initialSection: Int,
    onStartOverlay: () -> Unit,
) {
    var section by rememberSaveable { mutableIntStateOf(initialSection) }
    val scroll = rememberScrollState()
    // 换页签时回到顶部：三块屏长短差很多，停在上一页的中段会让新页只剩半截
    LaunchedEffect(section) { scroll.scrollTo(0) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
    ) {
        StatusBar(state)
        OverviewCard(store, state, onStartOverlay)
        TabRow(
            selectedTabIndex = section,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            listOf("日常", "开发", "系统增强").forEachIndexed { i, t ->
                Tab(selected = section == i, onClick = { section = i },
                    text = { Text(t, style = MaterialTheme.typography.labelLarge) })
            }
        }
        AnimatedContent(
            targetState = section,
            transitionSpec = {
                (EntropyMotion.fadeEnter() + EntropyMotion.slideEnter()) togetherWith
                    (EntropyMotion.fadeExit() + EntropyMotion.slideExit())
            },
            label = "section"
        ) { sel ->
            when (sel) {
                0 -> NormalModeScreen(store, state, onStartOverlay)
                1 -> DevModeScreen(store, state)
                else -> SystemEnhanceScreen(store, state)
            }
        }
    }
}

/** 顶部状态条：执行路径 + 真实温度 + 熔断警示 */
@Composable
fun StatusBar(state: UiState) {
    val tempC = state.telemetry?.maxTempC
    val scheme = MaterialTheme.colorScheme
    val entropy = LocalEntropyColors.current
    AnimatedVisibility(visible = true,
        enter = slideInVertically(spring(dampingRatio = 0.4f)) { -it } + fadeIn()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Bolt, null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("熵", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                Text(
                    when (state.path) {
                        HardwareBridge.Path.DAEMON -> "Daemon 已连接"
                        HardwareBridge.Path.ROOT_SHELL -> "Root 直写（无熔断保护）"
                        HardwareBridge.Path.READONLY -> "只读模式"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.path == HardwareBridge.Path.DAEMON) scheme.primary else scheme.error
                )
                Spacer(Modifier.weight(1f))
                if (tempC != null) {
                    Text("%.1f℃".format(tempC),
                        style = MaterialTheme.typography.labelLarge,
                        color = tempColor(entropy, tempC),
                        fontWeight = FontWeight.SemiBold)
                }
            }
            if (state.path != HardwareBridge.Path.DAEMON) {
                Spacer(Modifier.height(8.dp))
                NoticeBar(
                    when (state.path) {
                        HardwareBridge.Path.ROOT_SHELL ->
                            "Daemon 未运行，已退回 Root 直写。此路径没有独立熔断线程，频率上限被硬压在 85%。"
                        else -> "无 Root，仅可读取硬件信息。用 ADB 拉起 Daemon 后功能与 Root 模式等价。"
                    },
                    if (state.path == HardwareBridge.Path.ROOT_SHELL) entropy.tempWarm else entropy.warn
                )
            }
            if (state.safety.fused) {
                Spacer(Modifier.height(8.dp))
                // Daemon 只在真正越线时带 reason；心跳恢复默认调度时是空串。
                // 直接插值会渲染成"安全熔断：，已还原默认调度"这种半截话。
                val why = state.safety.reason
                NoticeBar(
                    if (why.isEmpty()) "安全熔断已触发，已还原默认调度"
                    else "安全熔断：$why，已还原默认调度",
                    entropy.warn)
            }
        }
    }
}

/** 仪表盘卡：三个读数 + 一行档位，最后一个动作按钮。读数缺失时显示破折号而不是 0 */
@Composable
private fun OverviewCard(store: EntropyStore, state: UiState, onStartOverlay: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val entropy = LocalEntropyColors.current
    val t = state.telemetry
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Metric("温度", t?.maxTempC?.let { "%.1f℃".format(it) } ?: "—",
                    t?.maxTempC?.let { tempColor(entropy, it) } ?: scheme.onSurface)
                Metric("CPU", t?.cpuKhz?.maxOrNull()?.let { "%.2fG".format(it / 1_000_000.0) } ?: "—",
                    scheme.onSurface)
                Metric("帧率", if ((t?.fps ?: 0) > 0) "${t!!.fps}" else "—", scheme.onSurface)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(10.dp).clip(CircleShape)
                        .background(if (state.safety.fused) entropy.warn else scheme.primary)
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (state.profile) {
                        Proto.PROFILE_GAME -> "档位：游戏极速"
                        Proto.PROFILE_POWERSAVE -> "档位：极致省电"
                        else -> "档位：日常均衡"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Text(store.overlayForm.value.label,
                    style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onStartOverlay, modifier = Modifier.fillMaxWidth()) {
                Text("开启悬浮控制台")
            }
            // 这条按钮会释放二进制并 setsid 拉起进程，没有 su 点了只会失败，
            // 所以置灰而不只是隐藏失败提示
            TextButton(
                onClick = { store.startDaemon() },
                enabled = !store.locked(Feature.RUN_DAEMON),
                modifier = Modifier.padding(start = 0.dp)
            ) {
                Text("重新探测执行路径")
            }
            GateHint(store, Feature.RUN_DAEMON)
        }
    }
}

@Composable
private fun Metric(label: String, value: String, color: Color) {
    Column {
        Text(value, color = color, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
