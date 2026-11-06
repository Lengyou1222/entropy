// File: app/src/main/java/com/entropy/ui/NormalModeScreen.kt
//
// 日常模式：给不折腾的人用。三个预设档位 + 两根画质滑杆 + UFO 刷新率测试。
// 每一块都用 SectionCard 包起来，卡片之间留 14dp —— 这是 M3 里"分组但不分割"的标准做法。
//
// 权限不够的功能不隐藏：整块压灰、点不动、下面跟一句"要哪一档、为什么"。
// 藏起来的话用户分不清"这个 App 没做"和"这一档用不了"，那是最亏的两种误解叠在一起。
package com.entropy.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.entropy.data.EntropyStore
import com.entropy.data.Feature
import com.entropy.data.HudItem
import com.entropy.data.UiState
import com.entropy.hardware.HardwareBridge
import com.entropy.overlay.SafeHudService
import com.entropy.shared.Proto
import com.entropy.ui.components.GateHint
import com.entropy.ui.components.GatedBlock
import com.entropy.ui.components.NoticeBar
import com.entropy.ui.components.SectionCard
import com.entropy.ui.components.TickSlider
import com.entropy.ui.theme.LocalEntropyColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NormalModeScreen(
    store: EntropyStore,
    state: UiState,
    onStartOverlay: () -> Unit,
) {
    val entropy = LocalEntropyColors.current
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    // 滑杆是"本地草稿"，抬手才落库并下发：
    // 每移动 1px 就写一次 sysfs 会把 Daemon 打成刷屏，也会让触觉反馈变成噪音
    var sharp by remember { mutableStateOf(store.prefs.sharpness) }
    var clear by remember { mutableStateOf(store.prefs.clarity) }
    var showUfo by remember { mutableStateOf(false) }
    var hudPickerOpen by remember { mutableStateOf(false) }

    // 可启动应用列表要走 PackageManager 遍历，放 IO 上取，别卡住首帧
    var launchables by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(Unit) {
        launchables = withContext(Dispatchers.IO) { queryLaunchableApps(ctx) }
    }

    // 高度由内容决定，滚动交给首页那一个外层容器：这里再套一层 verticalScroll
    // 就是"滚动容器嵌滚动容器"，子项会拿到无限大高度约束
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ---------- 档位 ---------- //
        GatedBlock(store, Feature.PROFILE, Modifier.fillMaxWidth()) {
            SectionCard("一键性能模式", "档位直接下发给 Native Daemon，由它决定每一档 OPP 的落地方式") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PresetButton("日常均衡", Proto.PROFILE_BALANCED, state.profile, enabled = !state.busy) {
                        store.setProfile(it)
                    }
                    PresetButton("游戏极速", Proto.PROFILE_GAME, state.profile, enabled = !state.busy) {
                        store.setProfile(it)
                    }
                    PresetButton("极致省电", Proto.PROFILE_POWERSAVE, state.profile, enabled = !state.busy) {
                        store.setProfile(it)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    when (state.profile) {
                        Proto.PROFILE_GAME -> "游戏极速：允许跑到大核甜点频率，渲染线程绑定大核，降频需要连续 8 拍低负载。"
                        Proto.PROFILE_POWERSAVE -> "极致省电：频率上限压到硬件最大值的 55%，关闭预测性调频。"
                        else -> "日常均衡：跟随内核默认 governor，仅在唤醒延迟>2ms 时提前抬一档。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                GateHint(store, Feature.PROFILE)
            }
        }

        // ---------- 灵动岛 ---------- //
        SectionCard("灵动岛安全显示", "屏幕顶部中央的一块常驻窗：结论词（安全 / 偏热 / 危险 / 熔断）永久显示，" +
            "展开后的读数格由你勾选，描边跟着温度从冷青渐变到警示红。读不到真实帧率时显示「—」，不拿近似值糊弄你") {
            val hudOn = store.hudEnabled.value
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("显示灵动岛", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (hudOn) "已在屏幕顶部中央。点一下展开明细，长按关闭；它不可拖动，位置永远居中"
                        else "开启后贴在状态栏下方居中出现，关掉悬浮控制台也不影响它",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = hudOn, onCheckedChange = { on ->
                    if (on) SafeHudService.start(ctx) else SafeHudService.stop(ctx)
                })
            }
            Spacer(Modifier.height(10.dp))
            TickSlider(
                value = store.hudAlertMc.intValue / 1000,
                onValueChange = { store.setHudAlertMc(it * 1000) },
                range = 40..90, detents = 11,
                label = "过热提醒阈值",
                valueText = "${store.hudAlertMc.intValue / 1000} ℃",
                color = entropy.tempWarm,
            )
            Spacer(Modifier.height(10.dp))
            // 展开态要显示哪些读数。安全结论不在这排格子里 —— 它是这块岛存在的理由，
            // 没有开关，也不给"关掉它只看数据"这种选项
            Text("展开时显示哪些读数", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(7.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)) {
                HudItem.entries.forEach { item ->
                    HudPickPill(
                        text = item.label,
                        selected = store.hudItems.intValue and item.bit != 0,
                        onClick = { store.toggleHudItem(item) }
                    )
                }
            }
            Text(
                "安全结论永久显示。勾掉的只是展开态里的读数格；收起态那条胶囊会自动跟着变，" +
                    "全部勾掉会自动回到默认三项，不会给你一块空岛",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            val pkg = store.prefs.hudTargetPkg
            val label = launchables.firstOrNull { it.second == pkg }?.first
            // 指定帧率目标走 dumpsys gfxinfo 的累计出帧，那是 shell 级读取，
            // 普通档位灰掉它而不是藏掉它：温度提醒照样能用，只有帧率这一路没有来源
            GatedBlock(store, Feature.FPS_READ) {
                OutlinedButton(onClick = { hudPickerOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("帧率目标：${label ?: if (pkg.isEmpty()) "只信帧率探针" else pkg}")
                }
            }
            DropdownMenu(expanded = hudPickerOpen, onDismissRequest = { hudPickerOpen = false }) {
                DropdownMenuItem(
                    text = { Text("不指定（只信帧率探针）") },
                    onClick = { store.prefs.hudTargetPkg = ""; hudPickerOpen = false }
                )
                launchables.forEach { (name, p) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = {
                        store.prefs.hudTargetPkg = p
                        store.sampler.resetFps()
                        hudPickerOpen = false
                    })
                }
            }
            Text(
                when {
                    pkg.isNotEmpty() && state.path == HardwareBridge.Path.READONLY ->
                        "选定了帧率目标，但当前是只读路径：dumpsys 需要 Root 或 ADB 拉起 Daemon，" +
                            "拿不到就照实显示「—」。"
                    pkg.isNotEmpty() ->
                        "帧率来自该应用进程的累计出帧差分，约 1 秒一次；应用停在后台不出帧时会显示 0，那是事实。"
                    else ->
                        "未指定目标时只有 LD_PRELOAD 帧率探针能给出数值，探针不在线就是「—」。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // ---------- 画质 ---------- //
        GatedBlock(store, Feature.QUALITY, Modifier.fillMaxWidth()) {
            SectionCard("画质增强", "滑杆写的是厂商后处理节点；机型不支持时会自动置灰并保持当前值") {
                TickSlider(
                    value = sharp,
                    onValueChange = { sharp = it },
                    range = 0..100,
                    detents = 11,
                    label = "画面锐度",
                    valueText = "$sharp 档",
                )
                TickSlider(
                    value = clear,
                    onValueChange = { clear = it },
                    range = 0..100,
                    detents = 11,
                    label = "画面清晰度",
                    valueText = "$clear 档",
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        store.prefs.sharpness = sharp
                        store.prefs.clarity = clear
                        // 具体节点名因厂商而异，交给 Daemon 的 SET_NODE 白名单去试；
                        // 这里只表达"用户要这个强度"，不假装知道节点在哪
                        scope.launch { store.bridge.writeNode("/proc/mtkclear/clear_level", clear) }
                    }) { Text("应用画质") }
                    OutlinedButton(onClick = {
                        sharp = 20; clear = 15
                        store.prefs.sharpness = 20; store.prefs.clarity = 15
                    }) { Text("还原") }
                }
                GateHint(store, Feature.QUALITY)
            }
        }

        SectionCard("UFO 屏幕刷新率测试", "切换刷新率后跑一遍，页面同时给出实测 Hz、抖动与降档判定") {
            Text(
                "原理：画面里每个色块以固定像素/秒移动。屏幕实际刷新率如果是 " +
                    "60Hz，快速移动会出现明显的锯齿状抖动；144Hz 以上则平滑连续。" +
                    "这是 NVIDIA LDAT 之外的传统人工判定法。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = { showUfo = true },
                colors = ButtonDefaults.buttonColors(containerColor = entropy.tempCool)) {
                Text("启动 UFO 测试")
            }
        }

        OutlinedButton(onClick = onStartOverlay, modifier = Modifier.fillMaxWidth()) {
            Text("开启原子小球悬浮控制台")
        }

        if (state.lastMessage.isNotEmpty()) {
            NoticeBar(state.lastMessage,
                if (state.lastMessage.startsWith("失败")) entropy.warn else entropy.tempCool)
        }
        Spacer(Modifier.height(20.dp))
    }

    if (showUfo) UfoTestDialog(store = store, onClose = { showUfo = false })
}

/**
 * 列出"有启动图标"的应用，作为 HUD 的帧率目标候选。
 *
 * 不用 getInstalledApplications：那会把几十个没有任何界面的服务型包也列出来，
 * 用户在一个 300 项的清单里找自己的游戏是折磨。queryIntentActivities(LAUNCHER)
 * 天然只返回能真正跑起来、因而真正会出帧的进程。
 */
internal suspend fun queryLaunchableApps(ctx: Context): List<Pair<String, String>> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return runCatching {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(intent, 0).mapNotNull { ri ->
            val p = ri.activityInfo?.packageName ?: return@mapNotNull null
            ri.loadLabel(pm).toString() to p
        }.distinctBy { it.second }.sortedBy { it.first }
    }.getOrDefault(emptyList())
}

@Composable
private fun PresetButton(label: String, id: Int, current: Int, enabled: Boolean, onClick: (Int) -> Unit) {
    val selected = current == id
    Button(
        onClick = { onClick(id) },
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = if (selected)
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary)
        else
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                contentColor = MaterialTheme.colorScheme.onSurface),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

/**
 * 灵动岛读数项的勾选格。
 * 用自建的小胶囊而不是 M3 FilterChip：这里一排七项，FilterChip 的默认内边距
 * 在这个宽度下会把「越线余量」挤到下一行，看起来像界面坏了。
 */
@Composable
private fun HudPickPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) scheme.primary.copy(alpha = 0.18f)
                else scheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) scheme.primary else scheme.onSurface,
    )
}
