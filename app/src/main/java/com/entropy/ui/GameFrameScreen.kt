// File: app/src/main/java/com/entropy/ui/GameFrameScreen.kt
//
// 游戏帧率页：按应用上限、前台跟随、面板争抢实况，以及帧率增强引擎。
//
// 这一页的文案比控件重要。用户来找的是"给和平精英解锁 90 帧"和"插帧到 120"，
// 而系统侧能做的只有**往下压**与**把节奏弄整齐**：
// 游戏类别的包可以走 GameManagerService 真·按应用锁帧，其它应用只有一条全局上限；
// 游戏自己的档位由厂商按机型白名单在游戏内放开。
// 如果把这一页做成一堆看起来能解锁帧率的按钮，用户点完发现没变化，
// 只会得出"这 App 是假的"的结论 —— 所以每一处做不到的地方都直接写出来，
// 并且不摆一个按下去什么也不会发生的开关。
package com.entropy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.entropy.data.EntropyStore
import com.entropy.data.Feature
import com.entropy.data.KNOWN_GAMES
import com.entropy.data.RateVerdict
import com.entropy.data.UiState
import com.entropy.data.decodeCaps
import com.entropy.data.judge
import com.entropy.data.syncFpsFor
import com.entropy.hardware.DOWNSCALE_LITERALS
import com.entropy.hardware.HardwareBridge
import com.entropy.ui.components.AppPickerDialog
import com.entropy.ui.components.GateHint
import com.entropy.ui.components.GatedBlock
import com.entropy.ui.components.KvRow
import com.entropy.ui.components.NoticeBar
import com.entropy.ui.components.SectionCard
import com.entropy.ui.theme.LocalEntropyColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 面板读不到时给的可锁档：这些值是市面上手游常见的档位，不是"本机支持"的承诺 */
private val FALLBACK_CHIPS = listOf(120, 90, 60, 45, 40, 30, 24)

/** AOSP 里 MatchContentFrameRateUserPreference 的三个档 */
private val MATCH_PREFS = listOf(0 to "关闭", 1 to "强光环境", 2 to "始终启用")

/** peak_refresh_rate 的常用档，不是"本机一定支持"：面板不支持的档位服务端也会收下但不出效果 */
private val PEAK_CHIPS = listOf(120, 90, 60)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GameFrameScreen(store: EntropyStore, state: UiState) {
    val ctx = LocalContext.current
    val entropy = LocalEntropyColors.current
    val gf = store.gameFrame
    val rec = store.recorder
    val scope = rememberCoroutineScope()

    store.prefsTick.intValue
    val beat = rec.written.intValue

    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var installed by remember { mutableStateOf(emptySet<String>()) }
    /** 用户从选择框里手动加进来的包：速览表没有、也还没有档案，但行要先摆出来才能点档位 */
    var extra by remember { mutableStateOf<List<String>>(emptyList()) }
    var pickerOpen by remember { mutableStateOf(false) }
    var probe by remember { mutableStateOf(0) }
    /** 引擎页的目标：没手动选过就用当前前台 */
    var engPkg by remember { mutableStateOf("") }
    var engPickerOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val list = withContext(Dispatchers.IO) { queryLaunchableApps(ctx) }
        apps = list
        // 只验速览表里那十个包名：能出现在 queryLaunchableApps 结果里的本来就装着，
        // 用 getPackageInfo 再问一遍整台机器的应用是白白多起几十个 binder 调用
        installed = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            KNOWN_GAMES.filter { g ->
                runCatching { pm.getPackageInfo(g.pkg, 0) != null }.getOrDefault(false)
            }.map { it.pkg }.toSet()
        }
    }
    // 执行路径也当触发条件：进页面时 bridge.init 可能还没跑完，
    // 那一刻 SurfaceFlinger 的 dump 读不到，路径一变（只读 → Root/Daemon）就要重探一次，
    // 否则用户看到的是一张永远"还没探测到面板数据"的空页，除非他自己点按钮
    LaunchedEffect(probe, state.path) { gf.refresh() }

    val prefs = store.prefs
    val caps = decodeCaps(prefs.gameCaps).associateBy { it.pkg }
    val panel = gf.panel
    val fg = gf.foreground
    val fe = store.frameEngine
    val engineTarget = engPkg.ifEmpty { fg }
    // 目标一换就回读系统侧此刻的配置：这一页显示的是机器里真正生效的值，不是我们上次点过的
    LaunchedEffect(engineTarget) { fe.refreshTarget(engineTarget) }

    fun label(pkg: String): String =
        KNOWN_GAMES.firstOrNull { it.pkg == pkg }?.label
            ?: apps.firstOrNull { it.second == pkg }?.first
            ?: pkg

    fun installedOf(pkg: String) = pkg in installed || apps.any { it.second == pkg }

    // 面板算出来的整数分频排在最前：这一页真正想让用户选的就是它们
    val divisors = syncFpsFor(panel?.ceilingHz ?: 0.0)
    val chips = divisors + FALLBACK_CHIPS.filterNot { it in divisors }

    /** 实测帧率只在记录会话进行中可读，而且记的就是记录页选定的那个目标 */
    fun measuredOf(pkg: String): Int? {
        if (!rec.running.value || prefs.hudTargetPkg != pkg) return null
        return rec.preview().lastOrNull { it.kind == "sample" && it.fps.isNotEmpty() }
            ?.fps?.toIntOrNull()
    }

    fun setCapFor(pkg: String, fps: Int) {
        gf.setCap(pkg, fps)
        // 正在前台的游戏改完立刻上屏，不等下一轮轮询：
        // 用户是边打游戏边调的，慢两秒就会被当成"没生效"
        if (prefs.gameFollow && pkg == fg) scope.launch { gf.applyCap(fps) }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionCard("这一页改的是什么", "先讲边界，再给开关") {
            Bullet("声明了游戏类别的应用走 GameManagerService（cmd game set --fps），" +
                "它是真正的按应用通道，别的包不受影响。")
            Bullet("其余应用只有一条全局帧率上限，落在 debug.sf.max_refresh_rate / " +
                "vendor.display.max_frame_rate 这两个属性上。所谓\"按应用\"是前台跟随：" +
                "谁在前台就把这条全局上限换成谁的档案值。")
            Bullet("只能往下压，不能往上解锁。游戏里的 90/120 帧档是厂商按机型白名单在游戏内放开的，" +
                "系统侧锁 120 不会替你把 90 帧的选项变出来。")
            Bullet("不动游戏的文件，也不注入游戏进程。改 UE4 的 ini 或往游戏里 hook，" +
                "在腾讯 ACE/TpGuard 眼里和作弊是同一种特征，代价是账号。")
        }

        // ---------- 前台跟随 ---------- //
        GatedBlock(store, Feature.GAME_FOLLOW, Modifier.fillMaxWidth()) {
            SectionCard("前台跟随", "开着才会在应用之间自动换档；档案本身只是存着不动") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("按前台应用换帧率上限", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "兜底值：${if (prefs.fpsLimit == 0) "跟随系统" else "${prefs.fpsLimit} FPS"}" +
                                "（前台应用没有档案时用它，改全局上限在开发模式页）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = prefs.gameFollow,
                        enabled = !store.locked(Feature.GAME_FOLLOW),
                        onCheckedChange = { gf.setFollow(it) }
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(gf.line,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (gf.line.startsWith("换档失败")) entropy.tempHot else entropy.tempCool,
                    maxLines = 2)
                GateHint(store, Feature.GAME_FOLLOW)
            }
        }

        // ---------- 面板实况 ---------- //
        SectionCard("面板实况与争抢", "争抢不是猜出来的：面板能给的档、应用能申请的区间、属性里的上限，三个数对不上就是抢不过") {
            val p = panel
            if (p == null) {
                Text("还没探测到面板数据。当前是只读路径时这项可能读不到（SurfaceFlinger 的 dump 要 Root 或 shell）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                KvRow("当前模式", "id=${p.activeId}  ${p.modes.firstOrNull { it.id == p.activeId }
                    ?.let { "${it.width}x${it.height} @ %.0fHz".format(it.hz) } ?: "—"}")
                KvRow("面板可用档", p.modes.joinToString(" / ") { "%.0fHz".format(it.hz) }.ifEmpty { "—" })
                KvRow("应用可申请上限", if (p.appMaxHz > 0) "%.0f Hz".format(p.appMaxHz) else "未报告（退回当前档）")
                KvRow("合成器渲染上限", if (p.renderMaxHz > 0) "%.0f Hz".format(p.renderMaxHz) else "未报告")
                KvRow("属性回读到的上限", when {
                    gf.appliedFps < 0 -> "未回读"
                    gf.appliedFps == 0 -> "无（跟随系统）"
                    else -> "${gf.appliedFps} FPS"
                }, highlight = gf.appliedFps > 0)
                KvRow("前台应用", if (fg.isEmpty()) "未取到" else label(fg))
                val measured = remember(beat) { measuredOf(prefs.hudTargetPkg) }
                val capForFg = caps[fg]?.fps ?: prefs.fpsLimit
                if (measured != null) KvRow("实测出帧", "$measured fps")
                val verdict = judge(capForFg, p, measured)
                Spacer(Modifier.height(8.dp))
                Text(verdict.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (verdict) {
                        RateVerdict.OK -> entropy.tempCool
                        RateVerdict.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> entropy.tempHot
                    })
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { probe++ }) { Text("重新探测面板与属性") }
            Spacer(Modifier.height(8.dp))
            Text("ROM 的刷新率策略会在省电档、低电量、发热降档时自己改面板模式，" +
                "所以上面这几个数是要重读的，不是读一次就定死。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // ---------- 档案 ---------- //
        GatedBlock(store, Feature.GAME_FOLLOW, Modifier.fillMaxWidth()) {
            SectionCard("按应用档案", "点一个档位就是给这个游戏写上限；\"不锁\"是把这个游戏从档案里删掉") {
                val rows = (KNOWN_GAMES.filter { installedOf(it.pkg) || caps.containsKey(it.pkg) })
                    .map { RowModel(it.label, it.pkg, installedOf(it.pkg), it.tiers) } +
                    (caps.keys + extra).distinct()
                        .filter { pkg -> KNOWN_GAMES.none { it.pkg == pkg } }
                        .map { RowModel(label(it), it, installedOf(it), "") }
                // 正在前台的那个游戏如果还不在表里，顶到最前面：
                // 用户多半就是边打边来调的，不该让他先去找"选择应用"
                val listed = if (fg.isNotEmpty() && rows.none { it.pkg == fg })
                    listOf(RowModel(label(fg), fg, installedOf(fg), "当前在前台")) + rows else rows
                if (divisors.isNotEmpty()) {
                    Text("面板 %.0fHz 的整数分频排在每行最前：%s。锁在这些值上每一帧的显示时长完全一样；"
                        .format(panel?.ceilingHz ?: 0.0, divisors.joinToString(" / ")) +
                        "锁在非分频值上会出现忽长忽短的节奏，比低一档更难受。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                }
                listed.forEach { r ->
                    GameRow(
                        name = r.name,
                        note = r.tiers.ifEmpty { "自定义档案" },
                        installed = r.installed,
                        isForeground = r.pkg == fg,
                        cap = caps[r.pkg]?.fps,
                        chips = chips,
                        globalFallback = if (r.pkg in caps) null else prefs.fpsLimit.takeIf { it > 0 },
                        onSet = { setCapFor(r.pkg, it) },
                    )
                    Spacer(Modifier.height(10.dp))
                }
                if (listed.isEmpty()) Text(
                    "这台机器上还没有已安装的游戏，也没有档案。" +
                        "用下面的选择框可以按名字给任何应用建档案。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                val missing = KNOWN_GAMES.filter { it.pkg !in installed }.map { it.label }
                if (missing.isNotEmpty()) {
                    Text("表里列了但本机没找到：${missing.joinToString("、")}。" +
                        "包名对不上（渠道服、海外服）不算没装，用选择框按名字找。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedButton(onClick = { pickerOpen = true }) { Text("从已安装应用中选择") }
                if (gf.appliedFps > 0 && !prefs.gameFollow) {
                    Spacer(Modifier.height(8.dp))
                    NoticeBar("跟随没开，但属性上还压着一个 ${gf.appliedFps} FPS 的上限（可能是开发模式页设的）。" +
                        "档案只改表，不动这条属性。", entropy.tempWarm)
                }
                GateHint(store, Feature.GAME_FOLLOW)
            }
        }

        // ---------- 帧率增强引擎 ---------- //
        GatedBlock(store, Feature.GAME_FOLLOW, Modifier.fillMaxWidth()) {
            SectionCard(
                "帧率增强引擎",
                "三路 dumpsys 同时起、各自计时，再走系统里真存在的通道；做不到的不摆开关"
            ) {
                val isGame = engineTarget.isNotEmpty() && fe.isGameCategory(engineTarget)
                val rep = fe.report
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (engineTarget.isEmpty()) "目标：还没选，把要调的应用切到前台或用右边选一个"
                            else "目标：${label(engineTarget)}",
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                            maxLines = 2)
                        Text(
                            if (engineTarget.isEmpty()) "未选定目标" else fe.categoryText(engineTarget),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { engPickerOpen = true }) { Text("选目标") }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        enabled = engineTarget.isNotEmpty() && !fe.sampling,
                        onClick = { fe.sample(engineTarget); fe.refreshTarget(engineTarget) }
                    ) { Text(if (fe.sampling) "采证中…" else "并行采证三路") }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (rep == null) "三条通道各起一个 dumpsys 进程同时等；总耗时应接近最慢那一路，而不是三者相加"
                        else "总 ${rep.totalMs}ms · 最慢一路 ${rep.slowestMs}ms · 串起来要约 ${rep.serialMs}ms",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                }
                Spacer(Modifier.height(8.dp))
                rep?.channels?.forEach { c ->
                    // 没拿到数据的那一路点亮：原因就写在同一行，不用再去翻日志
                    KvRow(c.name, "${c.ms}ms · ${c.detail}", highlight = !c.ok)
                }
                rep?.render?.let { s ->
                    Spacer(Modifier.height(10.dp))
                    Text("应用侧（Choreographer 自己报的账）", style = MaterialTheme.typography.labelLarge)
                    KvRow("出帧 / Jank", "${s.frames} 帧，${s.jankyPct}%")
                    KvRow("帧时 p50 / p95 / p99", "${s.p50} / ${s.p95} / ${s.p99} ms")
                    KvRow("错过 Vsync / 慢 UI 线程", "${s.missedVsync} / ${s.slowUiThread}")
                    KvRow("高输入延迟 / 超截止时间", "${s.highInputLatency} / ${s.deadlineMissed}")
                }
                rep?.cadence?.let { c ->
                    Spacer(Modifier.height(10.dp))
                    Text("合成侧（真正上屏的节奏）", style = MaterialTheme.typography.labelLarge)
                    KvRow("实测出帧", "%.1f fps（样本 %d 条）".format(c.fps, c.samples))
                    KvRow("间隔抖动 / 节奏", "%.1f ms · %s".format(c.jitterMs, c.note), highlight = !c.even)
                }
                rep?.panel?.let { p ->
                    Spacer(Modifier.height(10.dp))
                    Text("面板侧（物理上限与此刻的限制）", style = MaterialTheme.typography.labelLarge)
                    KvRow("渲染帧率", "%.0f Hz".format(p.renderHz))
                    KvRow("自适应刷新率 ARR", when (p.arr) {
                        true -> "支持：内容帧率匹配才有物理意义"
                        false -> "不支持：面板只有固定档"
                        else -> "未报告"
                    }, highlight = p.arr == false)
                    if (p.thermal.isNotEmpty()) KvRow("热节流档位", p.thermal, highlight = true)
                    if (p.overrideHz > 0) KvRow("帧率覆盖", "%.0f Hz".format(p.overrideHz))
                }

                Spacer(Modifier.height(12.dp))
                Text("① 按应用锁帧（游戏类别专属，和上面的档案互不干扰）",
                    style = MaterialTheme.typography.labelLarge)
                if (isGame) {
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        chips.forEach { v ->
                            Chip("$v", fe.targetFps == v) { fe.applyFps(v) }
                        }
                        Chip("不锁", fe.targetFps == null && fe.targetOk) { fe.applyFps(0) }
                    }
                    if (!fe.targetOk) Text("服务端配置还没读到：${fe.targetNote}",
                        style = MaterialTheme.typography.bodySmall,
                        color = entropy.tempWarm, maxLines = 2)
                } else {
                    Text("这个包没声明游戏类别，服务端会拒绝按应用干预。" +
                        "要压它的帧率只能用上面的档案 + 前台跟随那条全局上限，整机一起受影响。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                }

                Spacer(Modifier.height(12.dp))
                Text("② 降渲染分辨率换帧率（游戏类别专属，画面会真的变糊）",
                    style = MaterialTheme.typography.labelLarge)
                if (isGame) {
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        DOWNSCALE_LITERALS.forEach { lit ->
                            Chip(lit, fe.scaleSelection == lit) { fe.applyScale(lit) }
                        }
                        Chip("不降", fe.scaleSelection == null && fe.targetOk) { fe.applyScale("disable") }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("帧率和缩放是服务端里的同一份配置：点其中一个，另一个会按当前回读到的值一起重新下发，" +
                        "所以不会出现\"取消降分辨率顺手把锁帧也清了\"。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                }

                Spacer(Modifier.height(12.dp))
                Text("③ 匹配内容帧率（把面板节奏对齐到视频帧率，消掉 3:2 抽动）",
                    style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    MATCH_PREFS.forEach { (v, t) -> Chip(t, fe.matchPref == v) { fe.applyMatchPref(v) } }
                }
                if (!fe.matchContentFeasible()) {
                    Spacer(Modifier.height(8.dp))
                    NoticeBar("本机面板不支持自适应刷新率：这一条改的是服务端的偏好，面板还是固定档，" +
                        "开了也不会有肉眼可见的变化。不是命令失败，是硬件没有这个能力。", entropy.tempWarm)
                }

                Spacer(Modifier.height(12.dp))
                Text("④ 面板帧率上限 peak_refresh_rate（整机生效，省电档常被它压着）",
                    style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    PEAK_CHIPS.forEach { hz -> Chip("$hz", fe.peakHz == "$hz") { fe.applyPeak(hz) } }
                    Chip("交回默认", fe.peakHz == null) { fe.clearPeak() }
                }
                Spacer(Modifier.height(6.dp))
                Text("回读当前：${fe.peakHz ?: "未设置"}。第一次改动前系统里的原值已经存下，" +
                    "\"交回默认\"按原值写回而不是删键 —— 直接删会把 OEM 预置值抹成系统默认。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)

                Spacer(Modifier.height(10.dp))
                Text(fe.op,
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        fe.op.startsWith("已生效") -> entropy.tempCool
                        fe.op.startsWith("被拒绝") || fe.op.startsWith("异常") -> entropy.tempHot
                        else -> entropy.tempWarm
                    }, maxLines = 3)
                GateHint(store, Feature.GAME_FOLLOW)

                Spacer(Modifier.height(10.dp))
                Text("为什么这里仍然没有\"插帧\"两个字", style = MaterialTheme.typography.labelLarge)
                Bullet("光学补帧要拿到别的应用的解码输出和运动矢量，还要改合成器的上屏时序。" +
                    "应用层两个入口都没有，厂商的 MEMC 也只开在自家游戏空间里，接口不对外。")
                Bullet("这里能做的是把节奏弄整齐：锁到面板整数分频、降渲染分辨率、让面板跟着内容帧率走。" +
                    "每一条都带属性或服务端回读，回读不上就显示\"已下发、未确认\"，不写\"已生效\"。")
            }
        }

        if (state.path == HardwareBridge.Path.READONLY) {
            NoticeBar("当前是只读路径：档案会保存在本机，但帧率上限写不进系统。" +
                "在权限页拿到 Root（或让 adb 把 entropyd 拉起来）之后自动接上。", entropy.tempWarm)
        }
        Spacer(Modifier.height(24.dp))
    }

    if (pickerOpen) {
        AppPickerDialog(
            apps = apps,
            current = fg,
            title = "给哪个应用建档案",
            noneLabel = null,
            onDismiss = { pickerOpen = false },
            onPick = { pkg ->
                pickerOpen = false
                // 速览表里已有、或已经有档案的包不必再加进 extra：
                // 那一行本来就会画出来，重复加只会得到两行一样的
                if (pkg.isNotEmpty() && extra.none { it == pkg } &&
                    !caps.containsKey(pkg) && KNOWN_GAMES.none { it.pkg == pkg }) extra = extra + pkg
            },
        )
    }

    if (engPickerOpen) {
        AppPickerDialog(
            apps = apps,
            current = engineTarget,
            title = "帧率增强引擎对哪个应用下手",
            noneLabel = "清空目标",
            onDismiss = { engPickerOpen = false },
            onPick = { pkg ->
                engPickerOpen = false
                engPkg = pkg
            },
        )
    }
}

private data class RowModel(val name: String, val pkg: String, val installed: Boolean, val tiers: String)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GameRow(
    name: String,
    note: String,
    installed: Boolean,
    isForeground: Boolean,
    cap: Int?,
    chips: List<Int>,
    globalFallback: Int?,
    onSet: (Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (isForeground) scheme.primary.copy(alpha = 0.07f) else Color.Transparent)
            .padding(horizontal = if (isForeground) 10.dp else 0.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isForeground) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f, fill = false), maxLines = 1,
                softWrap = false, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            Text(if (installed) note else "本机未安装",
                style = MaterialTheme.typography.labelSmall, maxLines = 1,
                color = if (installed) scheme.onSurfaceVariant else scheme.error)
        }
        Text(
            when {
                cap != null -> "上限 ${cap} FPS"
                globalFallback != null -> "无档案，跟全局兜底 ${globalFallback} FPS"
                else -> "无档案，不锁"
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (cap != null) scheme.primary else scheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            chips.forEach { v ->
                Chip(text = "$v", selected = cap == v, onClick = { onSet(v) })
            }
            Chip(text = "不锁", selected = cap == null, onClick = { onSet(0) })
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.clip(RoundedCornerShape(11.dp))
            .background(
                if (selected) scheme.primary.copy(alpha = 0.20f)
                else scheme.surfaceVariant.copy(alpha = 0.45f))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) scheme.primary else scheme.onSurface)
    }
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        // padding 在尺寸之前：先让出顶部 6dp，圆点才落在第一行文字的中间，
        // 写在 background 之后会把 4x4 的方块本身压没
        Box(Modifier.padding(top = 6.dp).width(4.dp).height(4.dp)
            .background(MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(9.dp))
        Text(text, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f))
    }
}
