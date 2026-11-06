// File: app/src/main/java/com/entropy/ui/RecordScreen.kt
//
// 硬件记录页：开一台按节拍的采样记录仪，并把 CSV 交出去。
//
// 记录器本体（HwRecorder）跑在自己的 IO 协程上，退出这一页不会打断它，
// 所以这里的 UI 只是"看仪表"，不是"握着仪表"。这一页被销毁时什么都不用做。
//
// 录屏不在这一页：它出的是画面而不是按节拍的读数，两者挤在一格里谁都不好找开关，
// 已经拆到「录制」（RecordingScreen）。帧率、码率、防录屏这些偏好仍留在设置页，
// 因为它们同时喂编码器和悬浮窗，不属于任何一页。
//
// 导出走两条路而不是让用户去翻 /data/data：
//  1) 复制到系统「下载」目录 —— API 29+ 用 MediaStore，不需要任何存储权限；
//  2) 分享 Intent —— 交给 FileProvider 的 content://，微信/邮件/文件管理器都能接住。
// 两条都保留是因为个别国产 ROM 改过 MediaStore，insert 会返回 null，
// 这时必须有第二条能走通的路，否则"导出"这个按钮就是骗人的。
package com.entropy.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.entropy.data.EntropyStore
import com.entropy.data.Feature
import com.entropy.data.HwRow
import com.entropy.data.UiState
import com.entropy.hardware.HardwareBridge
import com.entropy.ui.components.AppPickerDialog
import com.entropy.ui.components.FpsChart
import com.entropy.ui.components.FpsPoint
import com.entropy.ui.components.GateHint
import com.entropy.ui.components.GatedBlock
import com.entropy.ui.components.NoticeBar
import com.entropy.ui.components.SectionCard
import com.entropy.ui.components.StatBox
import com.entropy.ui.components.TickSlider
import com.entropy.ui.theme.LocalEntropyColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RecordScreen(store: EntropyStore, state: UiState) {
    val ctx = LocalContext.current
    val entropy = LocalEntropyColors.current
    val scope = rememberCoroutineScope()
    val rec = store.recorder

    var files by remember { mutableStateOf<List<RecordFile>>(emptyList()) }
    var refresh by remember { mutableStateOf(0) }
    var busyText by remember { mutableStateOf("") }
    var pickerOpen by remember { mutableStateOf(false) }
    var launchables by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    LaunchedEffect(Unit) {
        launchables = withContext(Dispatchers.IO) { queryLaunchableApps(ctx) }
    }

    LaunchedEffect(refresh, rec.running.value) {
        files = withContext(Dispatchers.IO) {
            rec.files().map { RecordFile(it.name, it.length(), Date(it.lastModified())) }
        }
    }

    // 这一行读的是快照状态，位置很关键：写在函数体里整页才会跟着每一拍重算。
    // 只在"硬件记录"那张卡里读的话，Compose 的失效范围就到那一层 lambda 为止，
    // 曲线和最近读数会一直停在开始记录那一刻。
    val beat = rec.written.intValue
    val points = remember(beat) {
        rec.preview().mapNotNull { r -> r.fps.toIntOrNull()?.let { FpsPoint(r.ts, it) } }
    }
    val lo = points.minOfOrNull { it.fps }
    val hi = points.maxOfOrNull { it.fps }
    val targetPkg = store.prefs.hudTargetPkg
    val targetLabel = launchables.firstOrNull { it.second == targetPkg }?.first ?: targetPkg

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionCard("硬件记录", "按节拍把温度、帧率、频率与事件写成 CSV。切后台、锁屏都不会打断，" +
            "只有你点停止或进程被杀才停。") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("正在记录", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (rec.running.value) "已写 ${rec.written.intValue} 条 · 间隔 ${store.prefs.recordIntervalMs}ms"
                        else "未在记录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = rec.running.value, onCheckedChange = { on ->
                    if (on) rec.start(store.prefs.recordIntervalMs) else rec.stop()
                    refresh++
                })
            }
            // 执行路径单独占一行：塞在开关左边那半栏里会被挤成"…不可 / 用）"两行，
            // 断在括号中间看着像排版坏了
            Spacer(Modifier.height(6.dp))
            Text(
                "执行路径：${pathText(state.path)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            TickSlider(
                value = store.prefs.recordIntervalMs,
                onValueChange = { store.prefs.recordIntervalMs = it },
                range = 500..10_000, detents = 20,
                label = "采样间隔", valueText = "${store.prefs.recordIntervalMs} ms",
                enabled = !rec.running.value,
            )
            Text(
                "间隔太短会把 dumpsys 打成刷屏（每拍一次都要起一个进程），" +
                    "500ms 是这台机器实测还能跟得住的下限。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // ---------- 记哪个应用 ---------- //
        GatedBlock(store, Feature.FPS_READ, Modifier.fillMaxWidth()) {
            SectionCard("记录目标应用",
                "选定一个游戏之后，帧率列记的就是这个应用的出帧差分。" +
                    "它和安全显示灵动岛是同一个目标，改哪边另一边都跟着变。") {
                OutlinedButton(onClick = { pickerOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("目标：${targetLabel.ifEmpty { "未指定（只信帧率探针）" }}", maxLines = 1)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        targetPkg.isEmpty() ->
                            "未指定时只有 LD_PRELOAD 帧率探针能给出数值，探针不在线整条曲线就是空的。"
                        state.path == HardwareBridge.Path.READONLY ->
                            "已选定目标，但当前是只读路径：dumpsys gfxinfo 要 Root 或 Daemon 才拿得到。"
                        else ->
                            "应用压在后台不出帧时会记成 0，那是事实而不是丢帧。" +
                                "换目标不必重启记录，下一拍就按新应用重算差分基线。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                GateHint(store, Feature.FPS_READ)
            }
        }

        // ---------- 曲线 ---------- //
        SectionCard("帧率曲线", "画的是内存里这一段（最多 600 拍），导出成 CSV 的才是全量") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatBox("最低值", lo?.let { "$it fps" } ?: "—", entropy.tempHot, Modifier.weight(1f))
                StatBox("最高值", hi?.let { "$it fps" } ?: "—", entropy.tempCool, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            FpsChart(points)
            Spacer(Modifier.height(6.dp))
            Text("${points.size} 拍有帧率读数",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionCard("导出", "复制或分享当前（或最近一份）CSV，不需要存储权限") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    scope.launch {
                        busyText = "正在导出…"
                        val r = rec.exportToDownloads()
                        busyText = r.path
                        store.note("导出 ${r.name} ${r.bytes / 1024}KB：${r.path}")
                        refresh++
                    }
                }) { Text("导出到下载") }
                OutlinedButton(onClick = {
                    val i = rec.shareIntent()
                    if (i == null) store.note("没有可分享的文件：先点开始记录")
                    else ctx.startActivity(Intent.createChooser(i, "导出硬件记录"))
                }) { Text("分享") }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = {
                scope.launch {
                    busyText = "正在取守护日志尾部…"
                    val r = rec.exportDaemonLog()
                    busyText = r.path
                    refresh++
                }
            }) { Text("导出守护日志尾部") }
            if (busyText.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(busyText, style = MaterialTheme.typography.bodySmall,
                    color = entropy.tempCool, maxLines = 4)
            }
        }

        SectionCard("记录文件", "落在应用私有目录，导出时才复制出去") {
            if (files.isEmpty()) Text("目录是空的。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            files.forEach { f ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(f.name, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f), maxLines = 2)
                    Text("${f.bytes / 1024}KB", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        rec.clearFiles()
                        refresh++
                    }
                }) { Text("清空") }
                OutlinedButton(onClick = { refresh++ }) { Text("刷新") }
            }
            Text("目录：${rec.logDir.absolutePath}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }

        SectionCard("最近读数", "内存里只留最后 600 条；没开始记录时这里也看得到刚才的事件") {
            val rows = rec.preview().takeLast(12).reversed()
            if (rows.isEmpty()) Text("还没有读数。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            rows.forEach { PreviewRow(it) }
        }

        if (rec.notice.value.isNotEmpty()) NoticeBar(rec.notice.value, entropy.tempWarm)
        Spacer(Modifier.height(24.dp))
    }

    if (pickerOpen) {
        AppPickerDialog(
            apps = launchables,
            current = targetPkg,
            title = "记录哪个应用",
            onDismiss = { pickerOpen = false },
            onPick = { pkg ->
                store.prefs.hudTargetPkg = pkg
                pickerOpen = false
            },
        )
    }
}

private data class RecordFile(val name: String, val bytes: Long, val at: Date)

private val TIME = SimpleDateFormat("HH:mm:ss", Locale.US)

@Composable
private fun PreviewRow(row: HwRow) {
    val scheme = MaterialTheme.colorScheme
    val isEvent = row.kind != "sample"
    Row(
        Modifier.fillMaxWidth()
            .padding(vertical = 3.dp, horizontal = 6.dp)
            .background(
                if (isEvent) scheme.tertiary.copy(alpha = 0.10f) else scheme.surfaceVariant.copy(alpha = 0.35f),
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Text(TIME.format(Date(row.ts)), style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace, color = scheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(
            if (isEvent) row.kind else "温${row.tempC.ifEmpty { "—" }} 帧${row.fps.ifEmpty { "—" }} " +
                "CPU${row.cpuMhz.ifEmpty { "—" }}M",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f), maxLines = 3,
        )
    }
    Text(row.detail, style = MaterialTheme.typography.labelSmall,
        color = scheme.onSurfaceVariant, maxLines = 2,
        modifier = Modifier.padding(start = 14.dp, bottom = 3.dp))
}

private fun pathText(p: HardwareBridge.Path) = when (p) {
    HardwareBridge.Path.DAEMON -> "Daemon（可读全部热区）"
    HardwareBridge.Path.ROOT_SHELL -> "Root 直写"
    HardwareBridge.Path.READONLY -> "只读（部分读数不可用）"
}
