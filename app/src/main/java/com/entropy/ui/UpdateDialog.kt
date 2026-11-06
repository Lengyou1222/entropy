// File: app/src/main/java/com/entropy/ui/UpdateDialog.kt
//
// 启动检查到新版本时的那一次提示。
//
// 一个对话框走完整条链：发现 -> 下载（带进度）-> 交给系统安装器。
// 每一步都留着"继续使用当前版本"这个出口 —— 升级是建议，不是拦路。
//
// 安装这一步不能自己 startActivity 就完事：Android 8+ 要先有"安装未知应用"的放行，
// 没放行时把用户送到那一页，回来再试。装不装始终是系统那个确认页说了算。
package com.entropy.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.entropy.update.UpdateChecker
import com.entropy.update.UpdateFlow
import com.entropy.update.UpdateFlow.Phase

@Composable
fun UpdatePromptDialog() {
    val ctx = LocalContext.current
    val r = UpdateFlow.release.value ?: return
    val phase = UpdateFlow.phase.value
    val pct = UpdateFlow.progress.floatValue

    val grantLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { UpdateFlow.install(ctx) }

    fun installNow() {
        if (UpdateChecker.canInstall(ctx)) UpdateFlow.install(ctx)
        else grantLauncher.launch(UpdateChecker.unknownSourcesIntent(ctx))
    }

    AlertDialog(
        onDismissRequest = { if (phase != Phase.DOWNLOADING) UpdateFlow.dismiss() },
        title = { Text(if (phase == Phase.READY) "新版已就绪" else "发现新版本 ${r.version}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "本机 ${UpdateChecker.currentVersion} · 安装包 ${sizeLabel(r.apkSize)} · " +
                        "来自 ${UpdateChecker.repo}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                when (phase) {
                    Phase.DOWNLOADING -> {
                        LinearProgressIndicator(progress = { pct }, modifier = Modifier.padding(bottom = 6.dp))
                        Text("已下载 ${(pct * 100).toInt()}%，别退出应用，下完会提示安装")
                    }
                    Phase.READY, Phase.FAILED -> Text(
                        UpdateFlow.note.value,
                        color = if (phase == Phase.FAILED) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface,
                    )
                    else -> {
                        Text(
                            if (r.notes.length > 600) r.notes.take(600) + "…" else r.notes,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                TextButton(
                    onClick = { UpdateFlow.openReleasePage(ctx) },
                    contentPadding = PaddingValues(0.dp),
                ) { Text("在 GitHub 上看完整说明", style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            when (phase) {
                Phase.READY -> Button(onClick = { installNow() }) { Text("立即安装") }
                Phase.DOWNLOADING -> {}
                Phase.FAILED -> Button(onClick = { UpdateFlow.startDownload(ctx) }) { Text("重新下载") }
                else -> Button(onClick = { UpdateFlow.startDownload(ctx) }) { Text("下载更新") }
            }
        },
        dismissButton = {
            TextButton(onClick = { UpdateFlow.dismiss() }) { Text("继续使用当前版本") }
        },
    )
}

private fun sizeLabel(bytes: Long): String = when {
    bytes <= 0 -> "大小未知"
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "${bytes / 1024} KB"
}
