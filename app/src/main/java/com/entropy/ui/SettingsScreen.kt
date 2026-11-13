// File: app/src/main/java/com/entropy/ui/SettingsScreen.kt
//
// 设置页。从"我的"右上角那颗齿轮进来。
//
// 这一页只放"会改变应用本身"的开关：外观、悬浮控制台形态、录屏与防录屏、更新通道。
// 硬件读写（档位、频率、温度线）不在这儿 —— 那些改的是设备，不是这个应用，
// 混进来会让"设置"变成第二个首页，用户找不到齿轮下面到底该有什么。
package com.entropy.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.entropy.data.EntropyStore
import com.entropy.data.OverlayForm
import com.entropy.data.Palette
import com.entropy.data.ThemeMode
import com.entropy.media.ScreenRecord
import com.entropy.overlay.OverlayService
import com.entropy.ui.components.HighlightColorCard
import com.entropy.ui.components.SectionHeader
import com.entropy.update.UpdateChecker
import com.entropy.update.UpdateFlow
import com.entropy.update.UpdateFlow.Phase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

private val RecordFpsChoices = listOf(30, 60, 90, 120, 144, 165, 240)

/** 第一项的 0 表示"交给编码器按分辨率与帧率估" */
private val RecordBitrateChoices = listOf(0 to "自动", 4 to "4 Mbps", 8 to "8 Mbps",
    12 to "12 Mbps", 20 to "20 Mbps", 32 to "32 Mbps")

@Composable
fun SettingsScreen(store: EntropyStore, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(13.dp))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.width(6.dp))
            Column {
                Text("设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("改的是这个应用自己：外观、浮窗、录屏、更新",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            settingsItems(store)
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private fun LazyListScope.settingsItems(store: EntropyStore) {
    item { SectionHeader("外观") }
    item { ThemeChooser(store) }
    item { PaletteChooser(store) }
    item {
        HighlightColorCard(
            title = "界面高亮色",
            subtitle = "覆盖主题的 primary 一族：滑杆、统计数字、卡片竖标与选中的页签一起变色。" +
                "温度梯度与熔断色不跟着走 —— 那三档是安全语义，不能被审美改写成绿色。",
            currentArgb = store.highlightColor.intValue,
            defaultArgb = 0xFFB02A5C.toInt(),
            allowFollowTheme = true,
            onApply = store::setHighlightColor,
        )
    }

    item { SectionHeader("悬浮控制台") }
    item { FormChooser(store) }
    item { VolumeGuideSwitch(store) }
    item {
        SettingsNote(
            "展开态是两块面板：左边大的装七节调节，右边小的装信息、我的、开关与排行，紧贴屏幕右缘。" +
                "灵动岛固定在顶部、不可拖动，开关在首页「日常」页签里。"
        )
    }

    item { SectionHeader("录屏") }
    item { FollowRefreshSwitch(store) }
    // 手选帧率只在"不跟随"时出现：两个入口同时摆在页面上，用户不知道该信哪个
    if (!store.recordFollowRefresh.value) {
        item { RecordFpsChooser(store) }
    }
    item { RecordBitrateChooser(store) }
    item {
        val hz = ScreenRecord.currentRefreshHz(LocalContext.current)
        SettingsNote(
            "当前屏幕刷新率：${if (hz > 0) "$hz Hz" else "读取失败"}" +
                (if (hz > ScreenRecord.MAX_FPS) "，超出可录上限，按 ${ScreenRecord.MAX_FPS} FPS 下发" else "") +
                "。录屏走系统 MediaProjection，" +
                "帧率能不能真跑到这一档由这台机器的编码器决定，跑不满时编码器会丢帧而不是把画面加速。"
        )
    }

    item { SectionHeader("防录屏") }
    item { PreventRecordSwitch(store) }
    item {
        SettingsNote(
            "开启后悬浮控制台、右侧小面板、灵动岛与音量键引导都会加上 FLAG_SECURE。" +
                "这几层是安全层，不安全的采集通道（录屏、截屏）拿不到它们的内容，" +
                "合成结果里对应区域渲染成黑块，而不是透明——平台就是这么画的，应用改不了。" +
                "画面本身照常显示，只有被采集时看不见。对第三方录屏软件同样有效，但它拦不住对着屏幕拍照。"
        )
    }

    item { SectionHeader("检查更新") }
    item { AutoUpdateSwitch(store) }
    item { UpdateStatusCard(store) }
    item {
        SettingsNote(
            "更新通道是 GitHub 上 ${UpdateChecker.repo} 的 Releases，公开仓库匿名就能读，" +
            "应用里不带任何凭据。GitHub 对匿名请求限 60 次/小时，所以只在启动时查一次，" +
            "页面里没有轮询；这一节的「立即检查」是你手动要结果时才发的那一个请求。" +
            "下载下来的包放在应用私有目录，安装确认页由系统弹，熵不会替你点。"
        )
    }
}

@Composable
private fun SettingsNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 6.dp)
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = { onChange(it) })
    }
}

@Composable
private fun FollowRefreshSwitch(store: EntropyStore) {
    SwitchRow(
        title = "录屏帧率跟随刷新率",
        subtitle = "按当前屏幕真正在跑的 Hz 录制，而不是机型标称的最高档",
        checked = store.recordFollowRefresh.value,
        onChange = store::setRecordFollowRefresh,
    )
}

@Composable
private fun PreventRecordSwitch(store: EntropyStore) {
    SwitchRow(
        title = "防录屏",
        subtitle = "给熵的浮窗与灵动岛加 FLAG_SECURE。录屏和截屏里这几块区域会渲染成黑块——" +
            "系统不让不安全的内容看见安全层，做成透明做不到，但内容确实传不出去。",
        checked = store.preventRecord.value,
        onChange = store::setPreventRecord,
    )
}

@Composable
private fun AutoUpdateSwitch(store: EntropyStore) {
    SwitchRow(
        title = "启动时检查更新",
        subtitle = "每次打开应用查一次 Releases。关掉以后不会再有提示，只能回到这里手动点",
        checked = store.autoUpdateCheck.value,
        onChange = store::setAutoUpdateCheck,
    )
}

/**
 * 更新状态卡。
 *
 * 它只报这一次进程里真实发生过的事：没查过就说没查过，查失败就把失败原因原样摊开。
 * 这一节存在的意义不是"替代启动时那个弹窗"，而是弹窗被"继续使用当前版本"关掉之后，
 * 用户改主意时还得有个地方能把已经下好的包装上、或者重新下一次。
 */
@Composable
private fun UpdateStatusCard(store: EntropyStore) {
    val ctx = LocalContext.current
    val phase = UpdateFlow.phase.value
    val pct = UpdateFlow.progress.floatValue
    val remote = UpdateFlow.release.value?.version
    val at = UpdateFlow.checkedAt.longValue

    val grantLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { UpdateFlow.install(ctx) }

    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Kv("本机版本", "${UpdateChecker.currentVersion}（版本码 ${UpdateChecker.currentCode}）")
        if (remote != null) Kv("远端最新", remote)
        if (at > 0L) Kv("上次查询", TIME_FORMAT.format(Date(at)))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val busy = phase == Phase.CHECKING || phase == Phase.DOWNLOADING
            ModeButton(
                if (phase == Phase.CHECKING) "正在查询…" else "立即检查",
                selected = false,
                modifier = Modifier.weight(1f),
                onClick = { if (!busy) UpdateFlow.check(ctx, auto = false) },
            )
            when (phase) {
                Phase.READY -> ModeButton(
                    "立即安装", selected = true, modifier = Modifier.weight(1f),
                    onClick = {
                        if (UpdateChecker.canInstall(ctx)) UpdateFlow.install(ctx)
                        else grantLauncher.launch(UpdateChecker.unknownSourcesIntent(ctx))
                    },
                )
                Phase.AVAILABLE, Phase.FAILED ->
                    if (UpdateFlow.release.value != null) ModeButton(
                        "下载更新", selected = true, modifier = Modifier.weight(1f),
                        onClick = { UpdateFlow.startDownload(ctx) },
                    )
                else -> Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(8.dp))
        if (phase == Phase.DOWNLOADING) {
            LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
        }
        Text(
            when (phase) {
                Phase.IDLE -> "这个应用打开后还没查过更新" +
                    (if (!store.autoUpdateCheck.value) "（启动检查已关）" else "")
                Phase.CHECKING -> "正在向 GitHub 查询最新的 Release…"
                else -> UpdateFlow.note.value
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (phase == Phase.FAILED) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "来源：${UpdateChecker.repo} · 在 GitHub 上看看",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { UpdateFlow.openReleasePage(ctx) }
        )
    }
}

@Composable
private fun Kv(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(key, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RecordFpsChooser(store: EntropyStore) {
    val cur = store.recordFps.intValue
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("录制帧率", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        RecordFpsChoices.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp)) {
                row.forEach { f ->
                    ModeButton("$f FPS", cur == f, Modifier.weight(1f)) { store.setRecordFps(f) }
                }
            }
        }
    }
}

/** 0 是"自动"：按分辨率与帧率估一个码率，绝大多数情况够用 */
@Composable
private fun RecordBitrateChooser(store: EntropyStore) {
    val cur = store.recordBitrateMbps.intValue
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("码率", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        RecordBitrateChoices.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp)) {
                row.forEach { (mbps, label) ->
                    ModeButton(label, cur == mbps, Modifier.weight(1f)) {
                        store.setRecordBitrateMbps(mbps)
                    }
                }
            }
        }
    }
}

/**
 * 明暗模式：跟随系统 / 白色（浅色） / 黑色（深色）。
 * 写在应用内而不是只跟随系统，是因为有人只在打游戏时把系统留在浅色，
 * 却希望调度面板是黑的。
 */
@Composable
private fun ThemeChooser(store: EntropyStore) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                ModeButton(mode.label, store.themeMode.value == mode, Modifier.weight(1f)) {
                    store.setThemeMode(mode)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "当前：${store.themeMode.value.label}。主界面与悬浮控制台同时生效。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 配色方案。十七项排成 2 列而不是铺一行：
 * 360dp 宽的机器上一行放四枚，每个只剩 70dp，中文标签会被挤成两行。
 */
@Composable
private fun PaletteChooser(store: EntropyStore) {
    val cur = store.palette.value
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Palette.entries.chunked(2).forEachIndexed { row, pair ->
            if (row > 0) Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { p ->
                    ModeButton(p.label, cur == p, Modifier.weight(1f)) { store.setPalette(p) }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "当前：${cur.label}。固定色板的意义是「不被壁纸左右」：" +
                "红色在本应用里是过热与熔断的语义色，整屏红会分不清哪块在报警。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 隐藏控制台后弹不弹"按音量键打开"的引导动画。
 * 关掉的只是那一段动画：音量键的开关能力本身不受影响，否则就是在惩罚熟练用户。
 */
@Composable
private fun VolumeGuideSwitch(store: EntropyStore) {
    SwitchRow(
        title = "隐藏时提示音量键",
        subtitle = "控制台收起后弹一次「音量加键打开、音量减键关闭」的动画。按键本身照常调音量。",
        checked = store.showVolumeGuide.value,
        onChange = store::setShowVolumeGuide,
    )
}

/** 悬浮窗形态也在应用内改：不用先把小球点出来再双击切档 */
@Composable
private fun FormChooser(store: EntropyStore) {
    val ctx = LocalContext.current
    val form = store.overlayForm.value
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeButton(OverlayForm.BALL.label, form == OverlayForm.BALL, Modifier.weight(1f)) {
                OverlayService.setForm(ctx, OverlayForm.BALL)
            }
            ModeButton(OverlayForm.PANEL.label, form == OverlayForm.PANEL, Modifier.weight(1f)) {
                OverlayService.setForm(ctx, OverlayForm.PANEL)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "控制台正在显示时立刻重排窗口；未显示时下次开启生效。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ModeButton(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    if (selected) Button(onClick = onClick, shape = RoundedCornerShape(12.dp), modifier = modifier) { Text(text) }
    else OutlinedButton(onClick = onClick, shape = RoundedCornerShape(12.dp), modifier = modifier) { Text(text) }
}
