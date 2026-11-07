// File: app/src/main/java/com/entropy/ui/PermissionScreen.kt
//
// 权限页 = 权限等级选择 + 功能门槛矩阵 + 底层列表。
//
// 为什么把"等级"提到最上面：以前这一页是一长串平铺的状态行，
// 用户看完仍然不知道自己现在这一档到底能改什么、要改这一件事还差哪一步。
// 现在先回答"我在哪一级"，再回答"这一级有什么、下一级多什么"，
// 最后才是 entropyd 与内核模块这些原始细节。
//
// 三档的判据全部来自真读（SystemRepository），没有任何一处按"用户点了哪个按钮"来染色：
// 选了 Root 但系统里确实没有 su，这一档就照实标"未达到"，功能也照旧灰着。
// 等级与功能门槛的对应关系只有 Feature 这一张表，界面里不许再抄第二份。
package com.entropy.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.entropy.data.Capability
import com.entropy.data.CapabilityKey
import com.entropy.data.EntropyStore
import com.entropy.data.Feature
import com.entropy.data.GrantedApp
import com.entropy.data.KernelModule
import com.entropy.data.PermLevel
import com.entropy.data.SuManager
import com.entropy.data.UiState
import com.entropy.hardware.HardwareBridge
import com.entropy.hardware.ShellRunner
import com.entropy.hardware.ShizukuBridge
import com.entropy.hardware.ShizukuState
import com.entropy.ui.components.CollapsibleHeader
import com.entropy.ui.components.EntropyMotion
import com.entropy.ui.components.GateHint
import com.entropy.ui.components.NoticeBar
import com.entropy.ui.theme.LocalEntropyColors
import kotlinx.coroutines.launch

/** 这一页能做的事：把 shell 级命令复制给用户。App 不代跑，也不假装跑成功 */
private const val GRANT_SECURE_CMD =
    "adb shell pm grant com.entropy android.permission.WRITE_SECURE_SETTINGS"

@Composable
fun PermissionScreen(vm: SystemViewModel, store: EntropyStore, state: UiState) {
    val ctx = LocalContext.current
    val s by vm.state.collectAsState()
    val entropy = LocalEntropyColors.current
    val scope = rememberCoroutineScope()
    val level = store.level.value

    // 默认停在"当前真正达到的那一档"，用户切到别的档只是预览它的要求。
    // 等级一变就跟着对齐：刚授权完 Root 时，页面上还停着一张"未达到"的说明卡最误导人。
    var viewed by rememberSaveable { mutableStateOf(level) }
    LaunchedEffect(level) { viewed = level }

    var capsOpen by rememberSaveable { mutableStateOf(false) }
    var grantedOpen by rememberSaveable { mutableStateOf(false) }
    var modulesOpen by rememberSaveable { mutableStateOf(false) }
    var matrixOpen by rememberSaveable { mutableStateOf(true) }

    val rerun = { vm.refresh() }
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { rerun() }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { rerun() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item { LevelSelector(level, viewed) { viewed = it } }
        item { LevelDetail(viewed, level, s, store, state, vm, scope) }

        if (state.lastMessage.isNotEmpty()) item {
            NoticeBar(state.lastMessage,
                if (state.lastMessage.startsWith("失败") ||
                    state.lastMessage.startsWith("Root 未授予")) entropy.warn
                else entropy.tempCool)
        }
        if (s.notice.isNotEmpty()) item { NoticeBar(s.notice, entropy.tempWarm) }

        item {
            CollapsibleHeader("功能门槛", matrixOpen, Feature.entries.size) { matrixOpen = !matrixOpen }
        }
        if (matrixOpen) items(Feature.entries.sortedBy { it.min.rank }) { f ->
            FeatureRow(f, unlocked = level.rank >= f.min.rank)
        }

        item { CollapsibleHeader("本机能力", capsOpen, s.capabilities.size) { capsOpen = !capsOpen } }
        if (capsOpen) items(s.capabilities, key = { it.title }) { cap ->
            CapabilityRow(cap) {
                when (cap.key) {
                    CapabilityKey.OVERLAY -> settingsLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${ctx.packageName}")))
                    CapabilityKey.NOTIFICATION ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    CapabilityKey.USAGE_STATS ->
                        settingsLauncher.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    CapabilityKey.SECURE_SETTINGS -> ctx.copyText(GRANT_SECURE_CMD)
                }
            }
        }

        item { CollapsibleHeader("已授予 Root 的应用", grantedOpen, s.grantedApps.size) {
            grantedOpen = !grantedOpen } }
        if (grantedOpen) {
            if (s.grantedApps.isEmpty()) item {
                EmptyRow(if (s.su.manager == SuManager.KERNELSU) "授权表为空"
                    else "当前 Root 实现的授权表未纳入解析（见上方说明）")
            } else items(s.grantedApps, key = { it.pkg }) { GrantedAppRow(it) }
        }

        item { CollapsibleHeader("内核模块", modulesOpen, s.modules.size) { modulesOpen = !modulesOpen } }
        if (modulesOpen) {
            if (s.modules.isEmpty()) item {
                EmptyRow(if (s.su.granted) "/data/adb/modules 下没有模块" else "需要 Root 才能读取")
            } else items(s.modules, key = { it.id }) { ModuleRow(it) }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ------------------------------------------------------------------ //
// 等级选择                                                             //
// ------------------------------------------------------------------ //

@Composable
private fun LevelSelector(current: PermLevel, viewed: PermLevel, onSelect: (PermLevel) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("权限等级", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            StatusPill("当前：${current.label}", current.rank > 0, scheme.primary)
        }
        Spacer(Modifier.height(2.dp))
        Text("选一档看它解锁什么、还差什么。灰掉的功能是这一档真的改不动，不是界面没做。",
            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        // 四张卡一行放：每张只有 76dp，去掉内边距剩 52dp，而"Shizuku / ADB"这种
        // 拉丁字母加斜杠的标签要 84dp —— 标签和说明全被切在半句上。改成两列一行，
        // 一张卡 160dp，标签一行放得下，caption 也不用挤成三行
        PermLevel.entries.chunked(2).forEachIndexed { i, row ->
            if (i > 0) Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { l ->
                    LevelCard(
                        level = l,
                        isCurrent = current == l,
                        reached = current.rank >= l.rank,
                        selected = viewed == l,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelect(l) }
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LevelCard(
    level: PermLevel,
    isCurrent: Boolean,
    reached: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val entropy = LocalEntropyColors.current
    // 选中态用背景浓度而不是边框宽度：三张卡挤在一行时，
    // 2dp 边框在 0.5 缩放的平板上基本看不出来，浓度差才是稳定的视觉信号
    val fill by animateFloatAsState(
        targetValue = when { selected -> 0.30f; isCurrent -> 0.16f; else -> 0.06f },
        animationSpec = tween(EntropyMotion.NORMAL, easing = EntropyMotion.Ease), label = "levelFill")
    val fg by animateFloatAsState(
        targetValue = if (reached) 1f else 0.55f,
        animationSpec = tween(EntropyMotion.NORMAL), label = "levelFg")

    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        border = if (selected) CardDefaults.outlinedCardBorder() else null,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(
            Modifier.fillMaxWidth()
                .background((if (reached) scheme.primary else entropy.warn).copy(alpha = fill))
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (reached) Icons.Filled.CheckCircle else Icons.Filled.Lock,
                    null,
                    tint = if (reached) scheme.primary else scheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(level.label, style = MaterialTheme.typography.titleSmall,
                    maxLines = 2, lineHeight = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).alpha(fg))
            }
            Spacer(Modifier.height(4.dp))
            Text(level.caption, style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant, maxLines = 2, modifier = Modifier.alpha(fg))
            if (isCurrent) {
                Spacer(Modifier.height(4.dp))
                Text("当前所处", style = MaterialTheme.typography.labelSmall,
                    color = scheme.primary, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun LevelDetail(
    viewed: PermLevel,
    current: PermLevel,
    s: SystemUiState,
    store: EntropyStore,
    state: UiState,
    vm: SystemViewModel,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val entropy = LocalEntropyColors.current
    val reached = current.rank >= viewed.rank
    val unlocked = Feature.entries.count { it.min == viewed }

    AnimatedContent(
        targetState = viewed,
        transitionSpec = {
            // 往高档走从下方进，退回低档从上方进：位移方向和"往上升级"这件事一致
            val up = targetState.rank > initialState.rank
            (fadeIn(tween(EntropyMotion.NORMAL)) +
                slideInVertically(tween(EntropyMotion.SLOW, easing = EntropyMotion.Ease)) {
                    if (up) it / 3 else -it / 3 }) togetherWith
                fadeOut(tween(EntropyMotion.FAST))
        },
        label = "levelDetail"
    ) { target ->
        Card(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = scheme.surfaceVariant.copy(alpha = 0.30f))
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${target.label} · 多解锁 $unlocked 项",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        maxLines = 2, lineHeight = 21.sp, modifier = Modifier.weight(1f))
                    StatusPill(if (reached) "已达到" else "未达到",
                        reached, if (reached) entropy.tempCool else entropy.warn)
                }
                Spacer(Modifier.height(6.dp))
                Text(target.how, style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant)

                Spacer(Modifier.height(12.dp))
                FactRow("Root / su",
                    if (s.facts.root) "已授予 · ${s.su.manager.label}" else "未授予", s.facts.root)
                FactRow("entropyd 通道", when (state.path) {
                    HardwareBridge.Path.DAEMON -> "已连接，熔断线程在跑"
                    HardwareBridge.Path.ROOT_SHELL -> "Root 直写，无熔断线程"
                    HardwareBridge.Path.READONLY -> "未运行"
                }, state.path != HardwareBridge.Path.READONLY)
                // 直接订阅桥上的状态，不用 s.shizuku 那份快照：授权成功的回调就在桥上，
                // 而 ViewModel 只在 refresh() 那一刻读一次。用快照的话，用户在 Shizuku 点完
                // 「始终允许」回到这一页，看到的还是"本应用未授权"——明明刚授权成功，
                // 却像没生效，接下来只会重复点那个已经不需要的按钮。
                val sz by ShizukuBridge.state.collectAsState()
                val szName = if (sz.versionName.isBlank()) "" else " ${sz.versionName}"
                // 四态分开报，绝不压成一句"Shizuku 不可用"：
                // 装了但没启动、启动但没授权、授权但 binder 断了 —— 用户要做的动作完全不同，
                // 合并成一句会让人去重启一个本来正常的服务。
                FactRow("Shizuku", when {
                    !sz.installed -> "未安装（改用下面的 adb 那条）"
                    !sz.running -> "已安装$szName · 未运行：先在 Shizuku 里启动它"
                    !sz.granted -> "服务在跑 · 本应用未授权"
                    else -> "已授权$szName · API ${sz.apiVersion} · uid ${sz.uid}"
                }, sz.granted)
                FactRow("WRITE_SECURE_SETTINGS",
                    if (s.facts.secureSettings) "已授予" else "未授予", s.facts.secureSettings)
                // 这一行吃 ShellRunner 的当前 mode，不吃 ViewModel 的 facts 快照：
                // mode 才是"下一条命令实际走哪条通道"的唯一来源，而 facts 是上一次 refresh 的读数。
                // 实测过错位——Shizuku 授权回调把 mode 抬成 SHIZUKU，而 su 其实可用、
                // detect() 随后已落回 ROOT，界面却还拿着旧 facts 显示成另一个身份。
                val shellMode = store.bridge.shell.mode
                FactRow("命令执行身份", shellIdentityLabel(shellMode, sz, s.info?.uid ?: -1),
                    shellIdentityOk(shellMode))

                if (target == PermLevel.SHIZUKU) {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            // 没运行也允许点：requestGrant() 会把"装了但没跑"这句原话说出来，
                            // 比把按钮灰在那儿让用户自己猜卡在哪一步有用。已授权时才真的无事可做。
                            onClick = { store.requestShizuku() },
                            enabled = sz.installed && !sz.granted,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        ) { Text("请求 Shizuku 授权") }
                        OutlinedButton(
                            onClick = {
                                if (!ShizukuBridge.openManager(ctx))
                                    store.note("打不开 Shizuku：它可能没装，或被系统停用了")
                            },
                            enabled = sz.installed,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        ) { Text("打开 Shizuku") }
                    }
                    // 拒绝过一次之后 Shizuku 大概率不再弹确认页，这时"请求授权"点了也没回响；
                    // 把这条路明说，比让用户反复点一个看起来没反应的按钮好。
                    if (sz.deniedPersistent && !sz.granted) {
                        Spacer(Modifier.height(8.dp))
                        Text("之前在 Shizuku 里拒绝过本应用，它可能不再弹确认页。" +
                            "请点「打开 Shizuku」，在它的权限页里把「熵」允许。",
                            style = MaterialTheme.typography.bodySmall, color = entropy.warn)
                    }
                }
                if (target == PermLevel.SHIZUKU && !s.facts.secureSettings && !sz.granted) {
                    Spacer(Modifier.height(10.dp))
                    CommandBlock(GRANT_SECURE_CMD) { ctx.copyText(GRANT_SECURE_CMD) }
                }
                if (target == PermLevel.ROOT && !s.facts.root) {
                    Spacer(Modifier.height(10.dp))
                    Text("熵不自动调用 su：授权与否由管理器的弹窗决定，这里不替它下结论。",
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }

                Spacer(Modifier.height(14.dp))
                val runLocked = store.locked(Feature.RUN_DAEMON)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            scope.launch { store.runDaemon(reinstall = true); vm.refresh() }
                        },
                        enabled = !state.busy && !runLocked,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text(if (state.busy) "执行中…" else "一键运行 entropyd") }
                    OutlinedButton(
                        onClick = { store.requestRoot() },
                        enabled = !state.busy && !s.facts.root,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text("请求 Root") }
                }
                if (runLocked) GateHint(store, Feature.RUN_DAEMON)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { vm.refresh() }) {
                    Text(if (s.loading) "读取中…" else "重新检测权限")
                }
            }
        }
    }
}

/** 一行事实：值 + 达成点。达成与否只看读到的东西，不看用户点过什么 */
@Composable
private fun FactRow(key: String, value: String, ok: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val entropy = LocalEntropyColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape)
            .background(if (ok) entropy.tempCool else scheme.onSurfaceVariant.copy(alpha = 0.45f)))
        Spacer(Modifier.width(9.dp))
        // 两边都得带权重：只给 key 加权、value 裸着，Row 会先按整行宽度量 value，
        // 于是"已安装 Zygote Next · 未运行：先在 Shizuku 里启动它"这一句把宽度吃干，
        // key 那一列量到 0dp，左边"Root / su""命令执行身份"这些名字整行凭空消失。
        Text(key, style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f, fill = false),
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(value, style = MaterialTheme.typography.bodySmall,
            color = if (ok) scheme.onSurface else scheme.onSurfaceVariant,
            fontWeight = if (ok) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.weight(1.8f, fill = false),
            textAlign = TextAlign.End, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** 可复制的命令块。命令是固定字面量，没有一处拼接用户输入 */
@Composable
private fun CommandBlock(cmd: String, onCopy: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(10.dp)
    ) {
        Text(cmd, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = scheme.onSurface)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCopy) { Text("复制") }
        }
    }
}

@Composable
private fun StatusPill(text: String, ok: Boolean, color: Color) {
    Text(text, color = color, style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 9.dp, vertical = 3.dp))
}

// ------------------------------------------------------------------ //
// 功能矩阵                                                             //
// ------------------------------------------------------------------ //

/**
 * 一项功能一行。
 * 达不到门槛的行不是隐藏而是灰着留在原位：用户要能在同一张列表里看见
 * "这项功能存在、只是我这一档用不了"，隐藏会让功能看起来像没做。
 */
@Composable
private fun FeatureRow(f: Feature, unlocked: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val entropy = LocalEntropyColors.current
    val alpha by animateFloatAsState(
        targetValue = if (unlocked) 1f else 0.45f,
        animationSpec = tween(EntropyMotion.NORMAL), label = "featureAlpha")
    Row(
        Modifier.fillMaxWidth().alpha(alpha)
            .clip(RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (unlocked) Icons.Filled.CheckCircle else Icons.Filled.Lock, null,
            tint = if (unlocked) entropy.tempCool else scheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(f.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                maxLines = 1)
            Text(if (unlocked) f.why else "${f.min.label}才改得动：${f.why}",
                style = MaterialTheme.typography.bodySmall, fontSize = 11.sp,
                lineHeight = 15.sp, color = scheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (unlocked) "可用" else "需 ${f.min.label}",
            style = MaterialTheme.typography.labelMedium,
            color = if (unlocked) scheme.primary else scheme.onSurfaceVariant,
            fontWeight = if (unlocked) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
    }
}

// ------------------------------------------------------------------ //
// 底层列表                                                             //
// ------------------------------------------------------------------ //

@Composable
private fun CapabilityRow(cap: Capability, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ListRow(
        title = cap.title,
        subtitle = cap.detail,
        status = if (cap.granted) "已授予"
                 else if (cap.canGrantInApp) "去授予" else "仅 Shizuku/ADB",
        ok = cap.granted,
        onClick = if (cap.granted && !cap.canGrantInApp) null else onClick
    )
}

@Composable
private fun GrantedAppRow(app: GrantedApp) {
    ListRow(title = app.label, subtitle = app.pkg, status = "uid ${app.uid}", ok = true, onClick = null)
}

@Composable
private fun ModuleRow(m: KernelModule) {
    ListRow(
        title = m.name,
        subtitle = "${m.id} · ${m.version.ifEmpty { "无版本标注" }}",
        status = m.stateLabel,
        ok = !m.disabled && !m.pendingRemove,
        onClick = null
    )
}

@Composable
private fun ListRow(
    title: String,
    subtitle: String,
    status: String,
    ok: Boolean,
    onClick: (() -> Unit)?,
) {
    val scheme = MaterialTheme.colorScheme
    val entropy = LocalEntropyColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape)
            .background(if (ok) Color(0xFF34C759) else entropy.warn))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                maxLines = 1)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp, color = scheme.onSurfaceVariant, fontSize = 12.sp)
        }
        Spacer(Modifier.width(10.dp))
        Text(status, style = MaterialTheme.typography.labelMedium,
            color = if (ok) scheme.primary else scheme.onSurfaceVariant,
            fontWeight = if (ok) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
    }
}

@Composable
private fun EmptyRow(reason: String) {
    Text(reason, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp))
}

/**
 * 写操作最终落在哪个 uid 上。
 *
 * Shizuku 授权之后本应用的进程 uid 没变，变的是命令的执行身份（远端进程），
 * 所以这一行叫"命令执行身份"而不是"本 App 身份"—— 后者会让人以为授权完了自己就成 shell 了。
 *
 * 远端那个身份不固定：Shizuku 由 adb 拉起时是 shell(2000)，由 root 拉起时是 root(0)。
 * 这台机器上就是 root 启动的，读到的 uid 是 0 —— 所以措辞跟着 [ShizukuState.uid] 走，
 * 不写死"shell"。写死的那一版在实测里显示成"Shizuku 远端 shell（uid 0）"，自相矛盾。
 */
private fun shellIdentityLabel(mode: ShellRunner.Mode, sz: ShizukuState, appUid: Int): String =
    when (mode) {
        ShellRunner.Mode.ROOT -> "ROOT"
        ShellRunner.Mode.ADB_SHELL -> "ADB SHELL（uid 2000）"
        ShellRunner.Mode.SHIZUKU ->
            if (sz.uid == 0) "Shizuku 远端 root（uid 0）"
            else "Shizuku 远端 shell（uid ${if (sz.uid >= 0) sz.uid else 2000}）"
        // NONE 就是老老实实在本进程里跑：哪怕 Shizuku 那边显示已授权也一样。
        // 授权被撤回后桥上的缓存还是 true，而这里的 mode 已经掉回 NONE —— 说"普通应用"才对得上。
        ShellRunner.Mode.NONE -> "普通应用（uid $appUid）"
    }

/** 三条远端通道都算"够用了"；NONE 里只有真的能走 Shizuku 才算 */
private fun shellIdentityOk(mode: ShellRunner.Mode): Boolean = mode != ShellRunner.Mode.NONE

private fun Context.copyText(text: String) {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("entropy", text))
}
