// File: app/src/main/java/com/entropy/ui/RecordingScreen.kt
//
// 录制页：把整块屏录成 mp4，并把成片交出去。
//
// 这一页是从"记录"里分出来的：记录那台按节拍的采样记录仪（HwRecorder）与录屏
// 没有关系，两件不相干的事挤在同一格里，谁都找不到自己要点的那个开关。
//
// 页面不持有录制器。ScreenRecord 是进程级单例，RecordService 起停都不影响这里
// 读到的事实，所以这一页被销毁时什么都不用做，正在录的也不会被打断。
// 悬浮控制台左栏的「录制」节读写的是同一份状态，两边改哪边另一边都跟着变。
//
// 帧率与码率仍然在设置页：它们决定的是编码器 configure 的入参，和悬浮窗、HUD
// 共用同一份偏好。这一页放的是"每次录之前会想改一下"的那几项 —— 声音、分辨率、
// 定时停止 —— 以及录起来之后的暂停与成片处理。
package com.entropy.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.entropy.data.EntropyStore
import com.entropy.data.UiState
import com.entropy.media.ScreenRecord
import com.entropy.ui.components.SectionCard
import com.entropy.ui.components.StatBox
import com.entropy.ui.theme.LocalEntropyColors
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RecordingScreen(store: EntropyStore, state: UiState) {
    val ctx = LocalContext.current
    val entropy = LocalEntropyColors.current
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var clips by remember { mutableStateOf<List<File>>(emptyList()) }
    var clipBusy by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    val projLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) ScreenRecord.start(ctx, r.resultCode, data)
        else ScreenRecord.note.value = "已取消授权，没有开始录制"
    }
    // 声音是可选的：权限被拒也继续发录屏授权，服务会退回只录画面并写明原因
    val audioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { projLauncher.launch(ScreenRecord.captureIntent(ctx)) }

    val startCapture = {
        if (store.recordAudio.intValue != ScreenRecord.AUDIO_NONE &&
            !ScreenRecord.hasAudioPermission(ctx)) {
            audioLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        } else {
            projLauncher.launch(ScreenRecord.captureIntent(ctx))
        }
    }

    // 收尾那一拍 recording 由 true 变 false，effect 重跑一次，刚封好的 mp4 就出现在列表里
    LaunchedEffect(ScreenRecord.recording.value, refresh) {
        clips = withContext(Dispatchers.IO) { ScreenRecord.files(ctx) }
    }

    val on = ScreenRecord.recording.value
    val paused = ScreenRecord.paused.value
    val sec = ScreenRecord.elapsedSec.intValue

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ---------- 屏幕录制 ---------- //
        SectionCard("屏幕录制",
            "把整块屏录成 mp4。帧率默认跟着屏幕当前真正在跑的 Hz，" +
                "开启设置里的「防录屏」后，熵自己的浮窗与灵动岛在成片里渲染成黑块。") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            on && paused -> "已暂停 ${ScreenRecord.mmss(sec)}"
                            on -> "正在录制 ${ScreenRecord.mmss(sec)}"
                            else -> "未在录制"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        if (on) "${ScreenRecord.usedSize.value} · 目标 ${ScreenRecord.usedFps.intValue} FPS · " +
                            ScreenRecord.audioLabel(ScreenRecord.audioMode.intValue) +
                            (if (store.preventRecord.value) " · 浮窗与灵动岛已加 FLAG_SECURE"
                             else " · 浮窗与灵动岛会被录进去")
                        else if (store.recordFollowRefresh.value) {
                            val hz = ScreenRecord.currentRefreshHz(ctx)
                            val cap = ScreenRecord.clampFps(hz)
                            if (hz > 0) "目标：跟随当前刷新率（$hz Hz${if (cap < hz) "，可录上限 $cap FPS" else ""}）"
                            else "目标：跟随刷新率，但这一台读不到当前 Hz，录制时退回 ${store.recordFps.intValue} FPS"
                        } else "目标：固定 ${store.recordFps.intValue} FPS",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(10.dp))
                if (on) OutlinedButton(onClick = { ScreenRecord.stop(ctx) }) { Text("停止") }
                else Button(onClick = { startCapture() }) { Text("开始") }
            }
            if (on) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatBox("已写", ScreenRecord.sizeText(ScreenRecord.writtenBytes.longValue),
                        entropy.tempCool, Modifier.weight(1f))
                    StatBox("实际帧率", "%.1f".format(ScreenRecord.realFps.floatValue),
                        entropy.tempWarm, Modifier.weight(1f))
                    StatBox("声音", ScreenRecord.audioLabel(ScreenRecord.audioMode.intValue),
                        scheme.onSurfaceVariant, Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = {
                    if (paused) ScreenRecord.resume(ctx) else ScreenRecord.pause(ctx)
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (paused) "继续录制" else "暂停（暂停那段不进成片）")
                }
            }
            ScreenRecord.note.value?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = entropy.tempCool, maxLines = 3)
            }
        }

        // ---------- 声音 ---------- //
        SectionCard("声音",
            "麦克风录的是这台机器周围的声音；系统内录取的是别的应用正在播的声音，" +
                "要 Android 10 起，而且被录的应用自己允许采集才有声音 —— 多数游戏把它关着。") {
            val mode = store.recordAudio.intValue
            Choices(
                listOf("不录声音", "麦克风", "系统内录"),
                selected = mode,
                enabled = listOf(true, true, ScreenRecord.internalSupported()),
            ) { store.setRecordAudio(it) }
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    mode == ScreenRecord.AUDIO_NONE -> "成片只有画面。"
                    !ScreenRecord.hasAudioPermission(ctx) ->
                        "还没有麦克风权限：点「开始」时会先问一次，拒绝就只录画面。"
                    mode == ScreenRecord.AUDIO_INTERNAL && !ScreenRecord.internalSupported() ->
                        "这台机器是 Android ${android.os.Build.VERSION.RELEASE}，没有系统内录通道，请改用麦克风。"
                    mode == ScreenRecord.AUDIO_INTERNAL ->
                        "内录与麦克风都要麦克风权限。录不到声音时先换麦克风试一次，能录到就说明是对方应用不允许采集。"
                    else -> "麦克风已授权，录起来之后这一档不能改，要换先停止。"
                },
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            if (on) Text("正在录制，改这一档要等下一次开始。",
                style = MaterialTheme.typography.labelSmall, color = entropy.tempWarm)
        }

        // ---------- 画面与时长 ---------- //
        SectionCard("画面与时长",
            "缩放按边长走，50% 就是长宽各一半、体积约四分之一。定时停止到点自己收尾，" +
                "长时间挂着录不怕忘。") {
            Text("分辨率", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            val scale = store.recordScalePct.intValue
            Choices(listOf("原生", "75%", "50%"), selected = listOf(100, 75, 50).indexOf(scale)) {
                store.setRecordScalePct(listOf(100, 75, 50)[it])
            }
            Spacer(Modifier.height(12.dp))
            Text("定时停止", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            val mins = listOf(0, 1, 3, 5, 10)
            Choices(mins.map { if (it == 0) "不限" else "$it 分钟" },
                selected = mins.indexOf(store.recordAutoStopMin.intValue).coerceAtLeast(0)) {
                store.setRecordAutoStopMin(mins[it])
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "帧率 ${if (store.recordFollowRefresh.value) "跟随刷新率" else "${store.recordFps.intValue} FPS"} · " +
                    "码率 ${if (store.recordBitrateMbps.intValue == 0) "自动" else "${store.recordBitrateMbps.intValue} Mbps"}" +
                    "（这两项在「我的 → 设置 → 录屏」里改）",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            if (on) Text("正在录制，这两档要等下一次开始才生效。",
                style = MaterialTheme.typography.labelSmall, color = entropy.tempWarm)
        }

        // ---------- 成片 ---------- //
        SectionCard("成片", "播放与分享都经 content:// 交出去，不需要存储权限；删除只动这个目录里的文件。") {
            if (clips.isEmpty()) Text("还没有成片。",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            clips.forEach { f ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.name, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                softWrap = false, overflow = TextOverflow.Ellipsis)
                            Text("${DATE.format(Date(f.lastModified()))} · " +
                                String.format(Locale.US, "%.1f MB", f.length() / 1024.0 / 1024.0),
                                style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        TextButton(onClick = {
                            val i = ScreenRecord.playIntent(ctx, f)
                            if (i == null) clipBusy = "这台机器没有能接住视频的应用"
                            else runCatching { ctx.startActivity(i) }
                                .onFailure { clipBusy = "打不开播放器：${it.message}" }
                        }) { Text("播放") }
                        TextButton(onClick = {
                            val i = ScreenRecord.shareIntent(ctx, f)
                            if (i == null) clipBusy = "分享失败：拿不到文件的 content:// 地址"
                            else ctx.startActivity(Intent.createChooser(i, "分享录屏"))
                        }) { Text("分享") }
                        TextButton(onClick = {
                            scope.launch {
                                clipBusy = withContext(Dispatchers.IO) { ScreenRecord.saveToGallery(ctx, f) }
                            }
                        }) { Text("存入相册") }
                        if (pendingDelete == f.absolutePath) {
                            TextButton(onClick = {
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) { ScreenRecord.delete(ctx, f) }
                                    clipBusy = if (ok) "已删除 ${f.name}" else "删除失败：${f.name}"
                                    pendingDelete = null
                                    refresh++
                                }
                            }) { Text("确认删除", color = entropy.warn) }
                            TextButton(onClick = { pendingDelete = null }) { Text("取消") }
                        } else {
                            TextButton(onClick = { pendingDelete = f.absolutePath }) { Text("删除") }
                        }
                    }
                }
            }
            if (clipBusy.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(clipBusy, style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant, maxLines = 2)
            }
            Spacer(Modifier.height(6.dp))
            Text("目录：${ScreenRecord.outDir(ctx).absolutePath}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = scheme.onSurfaceVariant, maxLines = 2)
        }

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 一排档位。三枚一行，放不下的换行 —— 与设置页的 ModeButton 同一套观感，
 * 选中实心、未选描边，不用 FilterChip：它的内边距在 360dp 宽的机器上会把中文标签挤成两行。
 */
@Composable
private fun Choices(
    labels: List<String>,
    selected: Int,
    enabled: List<Boolean> = List(labels.size) { true },
    onPick: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.chunked(3).forEachIndexed { row, chunk ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chunk.forEachIndexed { i, label ->
                    val idx = row * 3 + i
                    val on = idx == selected
                    val can = enabled.getOrElse(idx) { true }
                    if (on) Button(
                        onClick = { onPick(idx) }, enabled = can,
                        shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)
                    ) { Text(label, maxLines = 1) }
                    else OutlinedButton(
                        onClick = { onPick(idx) }, enabled = can,
                        shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)
                    ) { Text(label, maxLines = 1) }
                }
                repeat(3 - chunk.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private val DATE = SimpleDateFormat("MM-dd HH:mm", Locale.US)