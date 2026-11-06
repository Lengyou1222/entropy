// File: app/src/main/java/com/entropy/ui/ProfileScreen.kt
//
// "我的"页：三个页签把原来十二屏长的键值列表压成三段。
//
//  应用 —— 这是什么包、长什么样；设置收在右上角齿轮里，见 SettingsScreen
//  设备 —— Root 现状 + 硬件配置
//  诊断 —— 使用情况 / 指令集 / 兼容性检查 / 常见问题（DiagnosticsModule）
//
// 页签只决定内容，不改数据来源：所有数字仍全部来自 SystemRepository（在 ViewModel 里取），
// 这一层只负责排版。读不到的项一律显示"读取失败"而不是留空或显示 0 ——
// 0 MB 内存和"没读到内存"是两回事，后者才是用户需要知道的事实。
//
// 顶部卡 + 分段控件固定在 Column 里，下面 AnimatedContent 各自持有一个 LazyColumn。
// 不把整页塞进同一个 LazyColumn：那样换页签时滚动位置会停在上一页的中段，
// 而两页的长短差很多，看起来像"内容被截断了"。
package com.entropy.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.entropy.data.EntropyStore
import com.entropy.ui.components.EntropyMotion
import com.entropy.ui.components.KvRow
import com.entropy.ui.components.SectionHeader

private val Tabs = listOf("应用", "设备", "诊断")

@Composable
fun ProfileScreen(store: EntropyStore, vm: SystemViewModel, onOpenSettings: () -> Unit) {
    val s by vm.state.collectAsState()
    val pkgName = LocalContext.current.packageName
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(8.dp))
            HeaderCard(onOpenSettings)
            Spacer(Modifier.height(12.dp))
            ProfileTabs(tab) { tab = it }
        }
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                (EntropyMotion.fadeEnter() + EntropyMotion.slideEnter()) togetherWith
                    (EntropyMotion.fadeExit() + EntropyMotion.slideExit())
            },
            label = "profileTab",
            modifier = Modifier.fillMaxSize()
        ) { sel ->
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                when (sel) {
                    0 -> appItems(s, pkgName)
                    1 -> deviceItems(s)
                    else -> item { DiagnosticsModule(store, vm) }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

// LazyColumn 的 content 不是 @Composable lambda（只有 item{} 里的才是），
// 所以这两个函数不能标 @Composable：状态一律由调用方读好后传进来。
private fun LazyListScope.appItems(s: SystemUiState, pkgName: String) {
    val info = s.info
    item { SectionHeader("应用信息") }
    item { KvRow("名称", info?.appName ?: "读取中") }
    item { KvRow("包名", pkgName) }
    item {
        Text(
            "外观、悬浮控制台、录屏与防录屏、检查更新的设置都在右上角齿轮里。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp)
        )
    }
}

private fun LazyListScope.deviceItems(s: SystemUiState) {
    val info = s.info
    item { SectionHeader("Root 权限") }
    item { KvRow("管理器", s.su.manager.label, highlight = s.su.granted) }
    item { KvRow("su 版本", s.su.banner.ifEmpty { if (s.su.granted) "未打印" else "无授权" }) }
    item { KvRow("SELinux 域", s.su.selinuxCtx.ifEmpty { "未读取" }) }
    item { KvRow("本应用 UID", info?.uid?.toString() ?: "读取中") }
    item { KvRow("本应用 PID", info?.pid?.toString() ?: "读取中") }
    item { KvRow("entropyd", if (s.su.daemonPid > 0) "运行中 · PID ${s.su.daemonPid}" else "未运行") }

    item { SectionHeader("硬件配置") }
    item { KvRow("CPU 平台", info?.hardware ?: "读取中") }
    item { KvRow("SoC 型号", info?.socModel ?: "读取中") }
    item { KvRow("核心数", info?.cores?.toString() ?: "读取中") }
    item { KvRow("运行内存", info?.let { if (it.ramTotalMb > 0) "${it.ramTotalMb} MB" else "读取失败" } ?: "读取中") }
    item { KvRow("Android", info?.let { "${it.android}（API ${it.sdk}）" } ?: "读取中") }
    item { KvRow("内核版本", info?.kernel?.ifEmpty { "读取失败" } ?: "读取中") }
    item { KvRow("屏幕", info?.screen ?: "读取中") }
    item { KvRow("ABI", info?.abi ?: "读取中") }
}

/**
 * 分段控件。选中块用动画位移而不是"重画两块底色"：
 * 换页签时视线跟着滑块走，"内容为什么变了"是看得出来的。
 */
@Composable
private fun ProfileTabs(selected: Int, onSelect: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(42.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        val cell = maxWidth / Tabs.size
        val shift by animateDpAsState(cell * selected, EntropyMotion.settle(), label = "segShift")
        Box(
            Modifier.offset(x = 3.dp + shift).width(cell - 6.dp).fillMaxHeight()
                .clip(RoundedCornerShape(11.dp)).background(scheme.surface)
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            Tabs.forEachIndexed { i, t ->
                val on = selected == i
                val color by animateColorAsState(
                    if (on) scheme.primary else scheme.onSurfaceVariant,
                    tween(EntropyMotion.NORMAL, easing = EntropyMotion.Ease), label = "segText")
                Box(Modifier.weight(1f).fillMaxHeight().clickable { onSelect(i) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(t, color = color, style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

/** 应用图标 + 名字。图标从 PackageManager 取，不在 XML 里二次硬编码资源 id */
@Composable
private fun HeaderCard(onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val icon = remember(ctx.packageName) {
        runCatching {
            ctx.packageManager.getApplicationIcon(ctx.packageName)
                .toBitmap(144, 144).asImageBitmap()
        }.getOrNull()
    }
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Box {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Image(icon, contentDescription = null, modifier = Modifier.size(52.dp).clip(RoundedCornerShape(15.dp)))
                } else {
                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(15.dp)).background(scheme.primary)) {
                        Text("熵", color = scheme.onPrimary, modifier = Modifier.padding(13.dp),
                            fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("熵 Entropy", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("性能调度与硬件极限探测", style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant)
                }
            }
            // 齿轮压在卡片右上角而不是做成一条列表项：设置这一层跟"我是谁"同级，
            // 塞进「应用」页签的内容里，切到设备页签就找不着了
            Box(
                Modifier.align(Alignment.TopEnd).padding(7.dp).size(38.dp)
                    .clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpenSettings),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Settings, contentDescription = "设置",
                    tint = scheme.onSurfaceVariant, modifier = Modifier.size(21.dp))
            }
        }
    }
}
