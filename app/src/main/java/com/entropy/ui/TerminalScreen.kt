// File: app/src/main/java/com/entropy/ui/TerminalScreen.kt
//
// 命令终端：跑 shell，也跑本项目自带的三个二进制（entropyd / entropy-monitor / entropy-freezer）。
//
// 这一页是整个 App 里唯一把用户原文交给 shell 的地方，所以 UI 层承担两件事：
//
// 1) 风险分级可见。TerminalRunner.classify() 给 Safe / Caution / Forbidden：
//    Forbidden 直接不执行（连确认框都不给），Caution 要把拒绝的理由原样显示出来、
//    由用户点"仍要执行"才放行。理由必须露出来 —— 只弹一句"确定吗"是无效的，
//    连点三次的人会习惯性点确认，而这一行可能正在改内核节点。
//
// 2) 每条执行过的命令都进硬件记录。终端能改系统状态，出了事故时
//    "谁在什么时候跑了什么"是唯一能自证的材料，所以这里不留"本次不记录"的口子。
//
// Root 开关默认关闭：detect 出来不是 ROOT 时按钮照样能按，
// 结果会是 "no root" 这一行错误 —— 比让按钮凭空消失更容易让人明白发生了什么。
package com.entropy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.entropy.data.EntropyStore
import com.entropy.hardware.Risk
import com.entropy.hardware.TermEntry
import com.entropy.hardware.TerminalRunner
import com.entropy.ui.components.NoticeBar
import com.entropy.ui.theme.LocalEntropyColors
import kotlinx.coroutines.launch

/**
 * 项目自己的二进制与设备上真正用得上的排障命令。
 *
 * 写成字面路径而不是"由 App 拼出来"：二进制释放到哪条路径是 Daemon 侧的事实，
 * 猜错的结果是一条 `No such file`，用户以为自己按错了。这里给的是本机实测形式，
 * 路径不对时命令本身会照实报错，比 App 猜一个更诚实。
 */
private val QUICK_COMMANDS = listOf(
    "id" to "我是谁",
    "getenforce" to "SELinux",
    "ls -l /data/local/tmp/entropy*" to "二进制与日志",
    "ls -d /proc/[0-9]* | head" to "进程",
    "tail -n 40 /data/local/tmp/entropy.log" to "守护日志",
    "/data/local/tmp/entropy-monitor --once" to "单轮采集",
    "/data/local/tmp/entropy-freezer --status" to "冻结状态",
    "cat /sys/class/thermal/thermal_zone0/temp" to "热区读数",
    "dumpsys gfxinfo com.entropy | head -n 20" to "自身出帧",
)

@Composable
fun TerminalScreen(store: EntropyStore) {
    val term = store.terminal
    val entropy = LocalEntropyColors.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var input by remember { mutableStateOf("") }
    var asRoot by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Triple<String, String, Boolean>?>(null) }
    var refused by remember { mutableStateOf<Pair<String, String>?>(null) }
    val entries = term.log()

    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.size)
    }

    fun submit(raw: String) {
        when (val risk = term.classify(raw)) {
            // 拒绝必须落在这一页上：store.note() 的横幅只在首页出现，
            // 用户在终端点了「执行」却只看到输入框被清空，会以为命令已经跑过了
            is Risk.Forbidden -> {
                refused = raw to risk.why
                store.recorder.event("term", "拒绝执行 ${raw}：${risk.why}")
            }
            is Risk.Caution -> { refused = null; pending = Triple(raw, risk.why, asRoot) }
            Risk.Safe -> { refused = null; scope.launch { exec(store, term, raw, asRoot) } }
        }
    }

    Column(
        Modifier.fillMaxSize().imePadding()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "身份：" + if (asRoot) "su（Root）"
                          else "应用自身 shell（${store.bridge.shell.mode.name.lowercase()}）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text("以 Root 执行", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(6.dp))
            Switch(checked = asRoot, onCheckedChange = { asRoot = it })
        }

        Box(
            Modifier.weight(1f).fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                    RoundedCornerShape(16.dp)
                )
                .padding(vertical = 6.dp)
        ) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(entries.size) { i -> EntryRow(entries[i], entropy.warn, entropy.tempCool) }
                if (term.busy.value) item {
                    Text(
                        "执行中…", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
                item { Spacer(Modifier.height(6.dp)) }
            }
        }

        refused?.let { (cmd, why) ->
            NoticeBar("拒绝执行：$why", entropy.warn)
            Text(
                cmd, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2
            )
        }

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("输入一行命令", fontSize = 13.sp) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace, fontSize = 13.sp
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { if (input.isNotBlank()) { submit(input); input = "" } },
                enabled = !term.busy.value
            ) { Text("执行") }
            OutlinedButton(onClick = { term.clear() }) { Text("清屏") }
            if (term.commands().isNotEmpty()) {
                TextButton(onClick = { input = term.commands().last() }) { Text("上一条") }
            }
        }

        // 快捷命令比一屏宽，是横向滚动的。右缘不加个渐隐的话，
        // 被切掉半个的按钮看起来像排版出错，而不是"还能往右滑"
        val quickScroll = rememberScrollState()
        val quickMore by remember { derivedStateOf { quickScroll.canScrollForward } }
        Box(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(quickScroll)
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                QUICK_COMMANDS.forEach { (cmd, label) ->
                    OutlinedButton(
                        onClick = { input = cmd },
                        modifier = Modifier.height(34.dp)
                    ) { Text(label, fontSize = 11.sp, maxLines = 1) }
                }
            }
            if (quickMore) {
                Box(
                    Modifier.align(Alignment.CenterEnd).width(26.dp).fillMaxHeight()
                        .background(androidx.compose.ui.graphics.Brush.horizontalGradient(
                            listOf(Color.Transparent, MaterialTheme.colorScheme.background)))
                )
            }
        }

        pending?.let { (cmd, why, root) ->
            NoticeBar("这条会改系统状态：$why", entropy.tempWarm)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    pending = null
                    scope.launch { exec(store, term, cmd, root) }
                }) { Text("仍要执行") }
                OutlinedButton(onClick = { pending = null }) { Text("取消") }
            }
            Text(
                cmd, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2
            )
        }
    }
}

private suspend fun exec(store: EntropyStore, term: TerminalRunner, cmd: String, asRoot: Boolean) {
    val e = term.run(cmd, asRoot)
    // 执行记录同时进硬件日志：终端可以改内核状态，事后要能对上时间线
    store.recorder.event("term", "\$ ${e.cmd} -> code=${e.code} ${e.ms}ms")
    store.note(
        if (e.ok) "已执行：${e.cmd}"
        else "退出码 ${e.code}：${e.err.lineSequence().firstOrNull().orEmpty()}"
    )
}

@Composable
private fun EntryRow(e: TermEntry, warn: Color, okColor: Color) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .background(scheme.surface.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (e.asRoot) "# " else "$ ") + e.cmd,
                fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                color = scheme.primary, modifier = Modifier.weight(1f), maxLines = 2
            )
            Text(
                e.code.toString(), fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                color = if (e.ok) okColor else warn
            )
        }
        if (e.out.isNotBlank()) {
            Text(
                e.out.trim(), fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                color = scheme.onSurface, maxLines = 60
            )
        }
        if (e.err.isNotBlank()) {
            Text(
                e.err.trim(), fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                color = warn, maxLines = 12
            )
        }
        Text("${e.ms}ms", fontSize = 9.sp, color = scheme.onSurfaceVariant.copy(alpha = 0.6f))
    }
}
