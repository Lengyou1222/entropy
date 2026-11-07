// File: app/src/main/java/com/entropy/ui/RecordingScreen.kt
//
// 录制页：把整块屏录成 mp4，并把成片交出去。
//
// 这一页是从"记录"里分出来的：记录那台按节拍的采样记录仪（HwRecorder）与录屏
// 没有关系，两件不相干的事挤在同一格里，谁都找不到自己要点的那个开关。
//
// 页面不持有录制器。ScreenRecord 是进程级单例，RecordService 起停都不影响这里
// 读到的事实，所以这一页被销毁时什么都不用做，正在录的也不会被打断。
//
// 帧率、码率、防录屏这三个开关仍然在设置页：它们决定的是编码器 configure 的入参，
// 和悬浮窗、HUD 共用同一份偏好，不在这里重复一份 UI。
package com.entropy.ui

import android.app.Activity
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.entropy.data.EntropyStore
import com.entropy.data.UiState
import com.entropy.media.ScreenRecord
import com.entropy.ui.components.SectionCard
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
    val scope = rememberCoroutineScope()

    var clips by remember { mutableStateOf<List<File>>(emptyList()) }
    var clipBusy by remember { mutableStateOf("") }

    val projLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) ScreenRecord.start(ctx, r.resultCode, data)
        else ScreenRecord.note.value = "已取消授权，没有开始录制"
    }

    // 收尾那一拍 recording 由 true 变 false，effect 重跑一次，刚封好的 mp4 就出现在列表里
    LaunchedEffect(ScreenRecord.recording.value) {
        clips = withContext(Dispatchers.IO) { ScreenRecord.files(ctx) }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ---------- 屏幕录制 ---------- //
        SectionCard("屏幕录制",
            "把整块屏录成 mp4。帧率默认跟着屏幕当前真正在跑的 Hz，" +
                "开启设置里的「防录屏」后，熵自己的浮窗与灵动岛在成片里渲染成黑块。") {
            val on = ScreenRecord.recording.value
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (on) "正在录制 ${mmss(ScreenRecord.elapsedSec.intValue)}" else "未在录制",
                        style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (on) "${ScreenRecord.usedSize.value} · 目标 ${ScreenRecord.usedFps.intValue} FPS · " +
                            (if (store.preventRecord.value) "浮窗与灵动岛已加 FLAG_SECURE"
                             else "浮窗与灵动岛会被录进去")
                        else if (store.recordFollowRefresh.value) {
                            val hz = ScreenRecord.currentRefreshHz(ctx)
                            val cap = ScreenRecord.clampFps(hz)
                            if (hz > 0) "目标：跟随当前刷新率（$hz Hz${if (cap < hz) "，可录上限 $cap FPS" else ""}）"
                            else "目标：跟随刷新率，但这一台读不到当前 Hz，录制时退回 ${store.recordFps.intValue} FPS"
                        } else "目标：固定 ${store.recordFps.intValue} FPS",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(10.dp))
                if (on) OutlinedButton(onClick = { ScreenRecord.stop(ctx) }) { Text("停止") }
                else Button(onClick = {
                    // 授权框必须由 Activity 发起，Compose 这里只能交出一个 launcher；
                    // 结果码和 data Intent 一起塞给服务，服务再取 MediaProjection
                    projLauncher.launch(ScreenRecord.captureIntent(ctx))
                }) { Text("开始") }
            }
            ScreenRecord.note.value?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = entropy.tempCool, maxLines = 3)
            }
            Spacer(Modifier.height(10.dp))
            if (clips.isEmpty()) Text("还没有成片。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            clips.forEach { f ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            softWrap = false, overflow = TextOverflow.Ellipsis)
                        Text("${DATE.format(Date(f.lastModified()))} · " +
                            String.format(Locale.US, "%.1f MB", f.length() / 1024.0 / 1024.0),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = {
                        scope.launch {
                            clipBusy = withContext(Dispatchers.IO) { ScreenRecord.saveToGallery(ctx, f) }
                        }
                    }) { Text("存入相册") }
                }
            }
            if (clipBusy.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(clipBusy, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            Spacer(Modifier.height(6.dp))
            Text("目录：${ScreenRecord.outDir(ctx).absolutePath}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }

        Spacer(Modifier.height(24.dp))
    }
}

private val DATE = SimpleDateFormat("MM-dd HH:mm", Locale.US)

private fun mmss(sec: Int) = String.format(Locale.US, "%d:%02d", sec / 60, sec % 60)
