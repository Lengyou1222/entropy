// File: app/src/main/java/com/entropy/overlay/OverlayPages.kt
//
// 控制台的图标语言，和右栏那四块小型标签页。
//
// 为什么图标是 Canvas 画而不是 vector 资源：
// 整套悬浮窗只有深色底板一种配色，图标必须跟着主题色与"选中/未选中"即时变色。
// vector 走 tint 也能做到，但六个节 + 四块页共十个图标要在 res/drawable 里养十个 xml，
// 还要担心 R8 把没用到的裁掉。画在 DrawScope 里，九个图标一屏读完，改一笔就全体生效。
//
// 右栏那四块（信息 / 我的 / 开关 / 排行）是"临时看一眼"的东西：再点一次同一个图标就收回，
// 左栏那一节的选择不会被带走。所以页面状态是一个可空的下标而不是第三个标签枚举。
package com.entropy.overlay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.entropy.data.EntropyStore
import com.entropy.data.Feature
import com.entropy.data.OverlayForm
import com.entropy.data.Palette
import com.entropy.data.UiState
import com.entropy.hardware.CpuRanker
import com.entropy.hardware.CpuRanking
import com.entropy.hardware.CpuTier
import com.entropy.hardware.HardwareBridge
import com.entropy.hardware.RankRow
import com.entropy.shared.Proto
import com.entropy.ui.components.EntropyMotion
import com.entropy.ui.theme.LocalEntropyColors

/** 十个图标：左栏六节 + 右栏四页 */
internal enum class Glyph { LIVE, SCHEDULE, VISUAL, THERMAL, SYSTEM, SKIN, INFO, GUIDE, QUICK, RANK }

/**
 * 图标本体。
 *
 * 全部画在一个居中的正方形里（宽盒子也能画），线宽随尺寸走 —— 22dp 和 16dp
 * 两种用法差 27%，固定 dp 线宽会让小的那组糊成一团。
 * 每个图形只用「描边 + 少量实心点」两种手段：在这个尺寸上，细节超过五六笔就读不出来了。
 */
@Composable
internal fun RailGlyph(kind: Glyph, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = minOf(size.width, size.height)
        val pad = w * 0.16f
        val x0 = (size.width - w) / 2f + pad
        val y0 = (size.height - w) / 2f + pad
        val span = w - 2f * pad
        val sw = (w * 0.11f).coerceAtLeast(1.5f)
        val line = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun n(x: Float, y: Float) = Offset(x0 + x * span, y0 + y * span)
        // Path 的 moveTo/lineTo 只收两个 Float，不收 Offset —— 这里必须自己拆开
        fun poly(vararg v: Pair<Float, Float>): Path = Path().apply {
            v.forEachIndexed { i, p ->
                val o = n(p.first, p.second)
                if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
            }
        }
        when (kind) {
            // 一条心电：实时 = 正在跳的数据
            Glyph.LIVE -> drawPath(
                poly(0f to .5f, .18f to .5f, .34f to .1f, .5f to .9f, .64f to .42f, .76f to .5f, 1f to .5f),
                color, style = line)

            // 三根滑杆，旋钮各在不同位置：能调的东西
            Glyph.SCHEDULE -> {
                listOf(.22f to .3f, .5f to .72f, .78f to .44f).forEach { (y, k) ->
                    drawLine(color, start = n(0f, y), end = n(1f, y), strokeWidth = sw, cap = StrokeCap.Round)
                    drawCircle(color, radius = sw * 1.5f, center = n(k, y))
                }
            }

            // 四角星 + 一颗小的：画质 / 增强
            Glyph.VISUAL -> {
                drawPath(
                    poly(.5f to 0f, .63f to .37f, 1f to .5f, .63f to .63f, .5f to 1f, .37f to .63f, 0f to .5f, .37f to .37f)
                        .apply { close() }, color)
                drawCircle(color, radius = sw * .75f, center = n(.06f, .04f))
            }

            // 温度计：管身描边 + 实心的球和液柱
            Glyph.THERMAL -> {
                drawRoundRect(color, topLeft = n(.34f, 0f), size = Size(span * .32f, span * .62f),
                    cornerRadius = CornerRadius(span * .16f), style = line)
                drawCircle(color, radius = span * .19f, center = n(.5f, .8f))
                drawLine(color, start = n(.5f, .3f), end = n(.5f, .76f), strokeWidth = sw, cap = StrokeCap.Round)
            }

            // 芯片：方框 + 四边引脚 + 里面的核
            Glyph.SYSTEM -> {
                drawRoundRect(color, topLeft = n(.2f, .2f), size = Size(span * .6f, span * .6f),
                    cornerRadius = CornerRadius(sw), style = line)
                drawRect(color, topLeft = n(.42f, .42f), size = Size(span * .16f, span * .16f),
                    style = Stroke(width = sw * .9f))
                listOf(.34f, .5f, .66f).forEach { k ->
                    drawLine(color, start = n(k, .02f), end = n(k, .2f), strokeWidth = sw * .8f, cap = StrokeCap.Round)
                    drawLine(color, start = n(k, .8f), end = n(k, .98f), strokeWidth = sw * .8f, cap = StrokeCap.Round)
                    drawLine(color, start = n(.02f, k), end = n(.2f, k), strokeWidth = sw * .8f, cap = StrokeCap.Round)
                    drawLine(color, start = n(.8f, k), end = n(.98f, k), strokeWidth = sw * .8f, cap = StrokeCap.Round)
                }
            }

            // 调色盘：一圈 + 三个色点（单色主题下点本身就是配色）
            Glyph.SKIN -> {
                drawCircle(color, radius = span * .48f, center = n(.5f, .5f), style = line)
                listOf(.3f to .32f, .62f to .3f, .4f to .66f).forEach { (a, b) ->
                    drawCircle(color, radius = sw * .95f, center = n(a, b))
                }
            }

            // 信息：圆里的 i
            Glyph.INFO -> {
                drawCircle(color, radius = span * .48f, center = n(.5f, .5f), style = line)
                drawCircle(color, radius = sw * .8f, center = n(.5f, .26f))
                drawLine(color, start = n(.5f, .44f), end = n(.5f, .76f), strokeWidth = sw, cap = StrokeCap.Round)
            }

            // 摊开的书：使用指南
            Glyph.GUIDE -> {
                drawPath(poly(.5f to .12f, .5f to .9f), color, style = line)
                drawPath(poly(.5f to .12f, .04f to .2f, .04f to .8f, .5f to .9f), color, style = line)
                drawPath(poly(.5f to .12f, .96f to .2f, .96f to .8f, .5f to .9f), color, style = line)
            }

            // 开关：轨道 + 偏一侧的滑块
            Glyph.QUICK -> {
                drawRoundRect(color, topLeft = n(0f, .3f), size = Size(span, span * .4f),
                    cornerRadius = CornerRadius(span * .2f), style = line)
                drawCircle(color, radius = span * .14f, center = n(.24f, .5f))
            }

            // 领奖台：三根柱子往右升高，最高那根顶上点一颗星 —— 排行
            Glyph.RANK -> {
                listOf(.06f to .70f, .38f to .46f, .70f to .22f).forEach { (x, top) ->
                    drawRoundRect(color, topLeft = n(x, top), size = Size(span * .24f, span * (1f - top)),
                        cornerRadius = CornerRadius(sw * .8f), style = line)
                }
                drawCircle(color, radius = sw * .85f, center = n(.82f, .06f))
            }
        }
    }
}

/**
 * 右栏四块小型标签页。
 *
 * 尺寸比左栏小一档（图标 16dp、条目 34dp），因为它做的事不一样：左栏是"我在哪一节"，
 * 右栏是"顺手叫出一块临时页面"。两栏做成一样宽一样高，屏幕上会出现两条并列的导航，
 * 谁也说不清哪个是主。
 *
 * mirror=true 时选中块的高亮朝右边圆角、左边平：这条栏不再贴在面板右缘，
 * 而是贴在右面板自己的左缘，圆角方向跟着翻过来才像是从内容里长出来的。
 */
internal enum class SideTab(val label: String, val icon: Glyph, val hint: String) {
    GUIDE("我的", Glyph.GUIDE, "CPU 身份与五档阶梯，外加每个功能怎么用"),
    INFO("信息", Glyph.INFO, "这台机器的硬件、通道与能力，一眼看全"),
    QUICK("开关", Glyph.QUICK, "最常用的几项，不用翻六节"),
    RANK("排行", Glyph.RANK, "整张型号表铺开，看自己这颗 U 排在第几"),
}

/** 条目高度与间距。宿主服务按这两数算窗口高度，所以不写 private */
internal const val SideItemH = 34
internal const val SideGap = 6

@Composable
internal fun SideRail(selected: Int, mirror: Boolean = false, onSelect: (Int) -> Unit) {
    val entropy = LocalEntropyColors.current
    val scheme = MaterialTheme.colorScheme
    val shape = if (mirror) RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp)
                else RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)
    Box(Modifier.width(36.dp).fillMaxHeight()) {
        Column(
            Modifier.align(Alignment.Center).fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(SideGap.dp, Alignment.CenterVertically)
        ) {
            SideTab.entries.forEachIndexed { i, t ->
                val on = i == selected
                val fg by animateColorAsState(
                    targetValue = if (on) scheme.primary else entropy.onScrim.copy(alpha = 0.55f),
                    animationSpec = tween(EntropyMotion.NORMAL), label = "sideFg")
                val bg by animateFloatAsState(
                    targetValue = if (on) 0.18f else 0f,
                    animationSpec = tween(EntropyMotion.NORMAL), label = "sideBg")
                val pop = pressScale(on)
                Box(
                    Modifier.fillMaxWidth().height(SideItemH.dp).scale(pop)
                        .clip(shape)
                        .background(scheme.primary.copy(alpha = bg))
                        .pointerInput(i) { detectTapGestures(onTap = { onSelect(i) }) },
                    contentAlignment = Alignment.Center
                ) { RailGlyph(t.icon, fg, Modifier.size(16.dp)) }
            }
        }
    }
}

/** 选中那一下给一点回弹：悬浮窗里没有水波纹，没有反馈就分不清"没点到"和"点开了" */
@Composable
private fun pressScale(on: Boolean): Float {
    val s by animateFloatAsState(
        targetValue = if (on) 1.12f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 520f), label = "sidePop")
    return s
}

// ------------------------------------------------------------------ //
// 页一：系统信息                                                        //
// ------------------------------------------------------------------ //

/**
 * 这台机器能给出去的事实。
 *
 * 分卡的依据是"报障时对方会先问哪一句"：机型与平台、走的是哪条通道、
 * 屏幕与刷新率、CPU 簇能跑到多少。读数全部来自 DeviceProfile 与 UiState，
 * 这里不推算、不美化 —— 读不到就写"未取到"。
 */
@Composable
internal fun SystemInfoPage(store: EntropyStore, state: UiState) {
    val entropy = LocalEntropyColors.current
    val p = store.bridge.profile
    PageTitle(SideTab.INFO.label, SideTab.INFO.hint)
    PanelCard("设备") {
        InfoLine("品牌", p.brand)
        InfoLine("型号", p.model)
        InfoLine("平台", p.family.label)
        InfoLine("SoC", p.socModel.ifEmpty { "未取到" })
        InfoLine("系统", "Android ${p.androidVersion} · API ${p.sdkInt}")
        InfoLine("内核", p.kernel.ifEmpty { "未取到" })
    }
    PanelCard("熵的通道") {
        val pathText = when (state.path) {
            HardwareBridge.Path.DAEMON -> "Daemon（可读全部热区）"
            HardwareBridge.Path.ROOT_SHELL -> "Root 直写"
            HardwareBridge.Path.READONLY -> "只读"
        }
        InfoLine("执行路径", pathText)
        InfoLine("权限档", store.level.value.label)
        InfoLine("连接", if (state.connected) "已连上 entropyd" else "未连接")
        InfoLine("eBPF", if (p.bpfSupported) "可用" else "不可用")
        InfoLine("cgroup v2", if (p.hasCgroupV2) "有" else "没有")
        InfoLine("熔断", if (state.safety.fused) "锁定中" else "未触发")
    }
    PanelCard("屏幕") {
        InfoLine("原生分辨率", "${p.nativeResolution.first} × ${p.nativeResolution.second}")
        InfoLine("刷新率档",
            if (p.supportedRefreshRates.isEmpty()) "未取到"
            else p.supportedRefreshRates.joinToString(" / "))
        InfoLine("渲染后端", when (store.prefs.renderer) {
            Proto.RENDERER_SKIA -> "Skia"
            Proto.RENDERER_GLES -> "OpenGLES"
            Proto.RENDERER_VULKAN -> "Vulkan"
            else -> "系统默认"
        })
    }
    PanelCard("CPU 簇") {
        if (p.cpuClusters.isEmpty()) InfoLine("读取", "没有可访问的 cpufreq 节点")
        p.cpuClusters.forEach { c ->
            InfoLine("${c.name} · ${c.cores.count()} 核", p.clusterRange(c))
        }
    }
    PanelCard("GPU 与热区") {
        InfoLine("GPU 节点", if (p.gpuNodes.isEmpty()) "未取到" else "${p.gpuNodes.size} 个")
        p.gpuNodes.take(3).forEach { SubLine(it) }
        InfoLine("热区", if (p.thermalZones.isEmpty()) "未取到（只读路径读不全）"
            else "${p.thermalZones.size} 个")
        p.thermalZones.take(4).forEach { SubLine(it) }
    }
}

/** 键值行下面挂的一条明细（节点名、热区名）。它们比键值长，只能整行放 */
@Composable
private fun SubLine(text: String) {
    val entropy = LocalEntropyColors.current
    Text(text, color = entropy.onScrim.copy(alpha = 0.5f), fontSize = 8.sp, maxLines = 1,
        modifier = Modifier.fillMaxWidth().padding(start = 74.dp, top = 1.dp))
}

/** 一行键值。值单行截断：内核串和热区名都长，换行会把这张卡拉成一屏只装它 */
@Composable
private fun InfoLine(key: String, value: String) {
    val entropy = LocalEntropyColors.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(key, color = entropy.onScrim.copy(alpha = 0.5f), fontSize = 9.sp, maxLines = 1,
            modifier = Modifier.width(74.dp))
        Text(value, color = entropy.onScrim, fontSize = 9.sp, maxLines = 1,
            lineHeight = 12.sp, modifier = Modifier.weight(1f))
    }
}

// ------------------------------------------------------------------ //
// 页二：我的（使用指南）                                                 //
// ------------------------------------------------------------------ //

/**
 * CPU 身份卡：一句结论 + 本机读到的硬事实 + 五档阶梯上自己在哪。
 *
 * 三块各有分工：结论给"我这颗 U 算什么水平"，事实给"这个结论是从哪些读数来的"，
 * 阶梯给"另外四档长什么样"。判定来源必须印在卡上 —— 型号表命中和按频率估算是两回事，
 * 把它们说成同一种确定性，用户就会拿一个估算值去判断该不该抬频。
 *
 * 阶梯五格不是一次画出来的：逐格点亮（每格错开 90ms），被选中的那一格最后亮、
 * 也亮得最足。这一档不会中途变化，所以动画要做的不是"跟着数据动"，而是把视线送到位。
 */
@Composable
internal fun CpuIdentityCard(store: EntropyStore, state: UiState) {
    val entropy = LocalEntropyColors.current
    val scheme = MaterialTheme.colorScheme
    val p = store.bridge.profile
    val r = remember(p.socModel, p.model, p.cpuClusters) { CpuRanker.rank(p) }
    val rows = CpuTier.entries.size
    val clock = staggeredReveal(rows)
    val reveal = alphaOf(clock, r.index, rows)

    PanelCard("CPU 身份 · 你在哪一档") {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            // 等级胶囊：无结论时写"未评级"而不是硬配一档颜色
            val label = r.tier?.label ?: "未评级"
            val strength = if (r.tier == null) 0.30f else 1f - r.index * 0.16f
            Box(
                Modifier.alpha(reveal).clip(RoundedCornerShape(999.dp))
                    .background(scheme.primary.copy(alpha = 0.22f * strength + 0.06f))
                    .border(1.dp, scheme.primary.copy(alpha = 0.5f * strength), RoundedCornerShape(999.dp))
                    .padding(horizontal = 9.dp, vertical = 3.dp)
            ) {
                Text(label, color = if (r.tier == null) entropy.onScrim.copy(alpha = 0.55f)
                else scheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Column(Modifier.weight(1f)) {
                Text(r.chipName, color = entropy.onScrim, fontSize = 11.sp, maxLines = 1,
                    fontWeight = FontWeight.Medium)
                Text(
                    when (r.basis) {
                        CpuRanking.Basis.MODEL -> "型号表命中 · 表内自评 ${r.score}/100"
                        CpuRanking.Basis.ESTIMATED -> "型号表没有 · 按频率与核数估算 ${r.score}/100"
                        CpuRanking.Basis.NONE -> r.reason
                    },
                    color = entropy.onScrim.copy(alpha = 0.45f), fontSize = 8.sp, maxLines = 2,
                    lineHeight = 11.sp)
            }
        }
        Spacer(Modifier.height(7.dp))
        InfoLine("平台", p.family.label)
        InfoLine("SoC 型号", p.socModel.ifEmpty { "未取到" })
        InfoLine("簇 / 核", "${p.cpuClusters.size} 簇 · " +
                "${p.cpuClusters.sumOf { it.cores.count() }} 核")
        val lo = p.cpuClusters.minOfOrNull { it.minKhz } ?: 0
        val hi = p.cpuClusters.maxOfOrNull { it.maxKhz } ?: 0
        InfoLine("频率上限", if (hi > 0) "%.2f ~ %.2f GHz".format(lo / 1e6, hi / 1e6) else "未取到")
        val liveKhz = state.telemetry?.cpuKhz?.maxOrNull() ?: 0
        InfoLine("当前最高频", if (liveKhz > 0) "%.2f GHz".format(liveKhz / 1e6) else "—（还没采样）")
        Spacer(Modifier.height(8.dp))
        // 阶梯：五格从上到下由强到弱，选中那格亮起并把自己的话说完
        CpuTier.entries.forEachIndexed { i, t ->
            val on = i == r.index
            val a = alphaOf(clock, i, rows)
            val fg by animateColorAsState(
                targetValue = if (on) scheme.primary else entropy.onScrim.copy(alpha = 0.42f),
                animationSpec = tween(EntropyMotion.NORMAL), label = "tierFg")
            Row(
                Modifier.fillMaxWidth().alpha(a)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (on) scheme.primary.copy(alpha = 0.14f) else Color.Transparent)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 序号槽：阶梯的顺序就是这五档的强弱，数字比色阶直白；
                // 选中那格把数字换成"本机"，一眼就知道这一格说的是自己
                Text(if (on) "本机" else "${i + 1}", color = fg, fontSize = 9.sp,
                    maxLines = 1, modifier = Modifier.width(if (on) 20.dp else 12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.label, color = fg, fontSize = 10.sp, maxLines = 1,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                    if (on) Text(t.blurb, color = entropy.onScrim.copy(alpha = 0.7f),
                        fontSize = 9.sp, lineHeight = 12.sp)
                }
            }
        }
        if (r.tier == null) Text("没有结论就别按等级调档：先看上面哪一行读数没取到。",
            color = entropy.onScrim.copy(alpha = 0.4f), fontSize = 8.sp,
            modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * 逐格点亮的时钟：0 → rows*perMs 的一条匀速进度。
 *
 * 只有一条 Animatable，各格按自己的窗口从里面切一段 —— 五格各起一个动画的话，
 * 切页时同一帧要排五次动画启动，页面越往后越容易看见它们互相抢帧。
 */
@Composable
private fun staggeredReveal(rows: Int, perMs: Int = StaggerPerMs): Float {
    val total = (rows * perMs).toFloat()
    val clock = remember { Animatable(0f) }
    LaunchedEffect(rows) {
        if (total > 0f) clock.animateTo(total, tween(total.toInt(), easing = LinearEasing))
    }
    return clock.value
}

/** 第 i 格在自己的窗口里走到哪儿了（0 = 还没轮到，1 = 完全亮起） */
private fun alphaOf(clock: Float, i: Int, rows: Int, perMs: Int = StaggerPerMs): Float {
    if (rows <= 0) return 1f
    // 没结论时不挑某一格，整条阶梯一起淡入
    if (i < 0) return (clock / (rows * perMs)).coerceIn(0.35f, 1f)
    return ((clock - i * perMs) / perMs).coerceIn(0f, 1f)
}

private const val StaggerPerMs = 90

private data class Chapter(
    val title: String,
    val short: String,
    /** 属于哪一类。分类写在这里而不是按序号切段：加一节不用回头改四张边界表 */
    val cat: String,
    val lead: String,
    val steps: List<String>,
)

/**
 * 大纲。先分类，再在类里分节：一个人学这东西的顺序是"先弄清能改什么 → 每天怎么用它 →
 * 想榨干它 → 出问题往哪儿查"，扁平的十四节把这四段混成一条，找一节要横扫一排 chip。
 *
 * 每一节的措辞按"点哪里、会怎样、怎么确认真的生效"写，不写原理。原理在 App 的对应卡片里
 * 已经说过一遍，而这一块是用户正在打游戏、抬眼看面板时读的东西。
 * 最后一步几乎都留了"改坏了怎么回去"：浮窗里最容易慌的是"我刚才那一下到底改了什么"。
 */
private val Chapters = listOf(
    Chapter(
        "先弄清自己在哪一档", "权限档", "上手",
        "每项能力挂在权限档上：只读 / Root / Daemon。改不动不是坏了，是差这一步。",
        listOf(
            "控制台顶部「熵」字右边那颗胶囊就是当前档位。写「普通」时，下面所有滑杆和档位都是灰的。",
            "要拿 Root：切到「系统」节，点「请求 Root」。手机上会弹授权框，允许之后胶囊会变成「Root」。",
            "再点「一键运行 entropyd」，让它以 root 身份常驻。走到这一步才是「Daemon」档，能读全部热区。",
            "没拿到 Root 也能用：提醒线、灵动岛、硬件记录、诊断页、录制这些只读功能全都可以用。",
            "灰掉的项下面都有一行「需 Root · 原因」，那就是这一项差多少权限的准确说明。",
            "确认：「系统」节的执行路径写成 Daemon（可读全部热区）才算到位；只写 Root 说明守护进程没起来。",
            "装过 Shizuku 的设备会多出一档：命令跑在 Shizuku 那一侧，权限页按它实际读到的 uid 说话。",
        ),
    ),
    Chapter(
        "控制台的开、收、藏", "控制台", "上手",
        "两种形态：整面大面板和原子小球。点空白处一下，大面板就收回小球。",
        listOf(
            "在应用首页打开「悬浮控制台」，屏幕上出现一颗原子小球。它就是控制台收起来的样子。",
            "点小球 → 弹出它自己的紧凑控制台（档位 + 读数 + 切形态）。够用，且只占一小块。",
            "要整面：在紧凑控制台里选「左侧控制台」，面板会贴住屏幕左侧展开，左边是六节。",
            "点面板外面的空白处会把大面板收回小球。按在面板里、右半那块小面板里、灵动岛上都不算点外面 —— 同一下手势会被认出来。",
            "另外三条收起来的路：面板顶部右侧的「小球」（收成那颗球）、「隐藏」（彻底关掉浮窗）、音量减键。",
            "双击小球或紧凑控制台的空白处会换成整面面板；在整面面板上双击不会换回去，那是防误触。",
            "小球可以拖动、可以双指捏合改大小（0.7~1.4 倍）；控制台整面不可拖动，位置固定在左侧。",
            "右侧那块小面板（我的 / 信息 / 开关 / 排行）是大面板的三分之二，贴在右缘正中间，上下都不碰屏幕边。",
        ),
    ),
    Chapter(
        "音量键怎么开怎么关", "音量键", "上手",
        "游戏里不想切出去调档位的办法：音量加键打开控制台，音量减键关闭控制台。",
        listOf(
            "按音量加键，控制台出现；按音量减键，控制台收起。一次按键只管一件事，不用猜现在算开还是算关。",
            "它不是劫持按键：熵截不到 KeyEvent，听的是系统发出的音量变化广播，所以音量本身照常变。",
            "音量已经顶到格、或已经压到底也算：那一下值不动，但广播照发，方向按当前档位贴着哪一头判。",
            "长按音量键连跳很多格时只认第一下，不会一路跳一路开合。",
            "接电话或通知音把媒体音量压低又弹回时，如果这一压一弹发生在同一次判定里，会被当成一次回弹而不作数。",
            "完全隐藏控制台后会弹一次引导动画，教这两颗键各干什么。不想再看，在应用「我的-悬浮控制台」里关掉。",
            "按了没反应，先确认悬浮窗权限还在（系统更新和清理软件都可能把它收回）。",
            "确认：音量条本身照常弹出、音量照常变，只有控制台跟着出现或消失 —— 这两件事同时成立才说明监听是通的。",
        ),
    ),
    Chapter(
        "灵动岛看什么", "灵动岛", "上手",
        "顶部中央一块常驻窗。它只回答一个问题：这台机器现在安不安全。",
        listOf(
            "应用首页「灵动岛安全显示」打开后，状态栏下方正中出现一块胶囊。",
            "点一下展开、再点收起、长按直接关掉。它不可拖动，位置永远在顶部居中。",
            "左边那个词是结论：安全 / 偏热 / 危险 / 熔断 / 无读数。结论永久显示，不能关。",
            "展开后的格子显示什么由你勾：帧率、温度、CPU 频率、电池温度、热档、越线余量、热区明细。",
            "格子的列数由当时剩多宽决定：控制台开着时窄下去就一列一列排，宽屏上排到三列，谁也不会被裁掉。",
            "内容比给定的高度还长时这一屏能上下滚，被滚走的仍然是你自己勾出来的读数，不会被藏掉。",
            "颜色只说温度这一件事：从冷青到暖再到警示红。读不到温度时是「无读数」灰，不会假装正常。",
            "关掉悬浮控制台不影响它。两块窗各自有各自的开关。",
        ),
    ),
    Chapter(
        "三条温度线各管什么", "三条线", "日常",
        "提醒线只报警、软上限开始收手、熔断线是全量还原。三者别混。",
        listOf(
            "读数取所有热区里的最大值。真实机型上占住这个位置的通常是 SoC / GPU 结温，打游戏时 60~80℃ 是常态，不是事故。",
            "过热提醒线（40~90℃）：越线只是变色 + 发一条通知，不动任何调度节点。",
            "软上限：Daemon 开始往上压温度的那个阈值，真正影响性能的是它。",
            "熔断线：最后一道闸。越过就把调度全部还原成系统默认，并锁 120 秒才允许再动。",
            "顺序要留余量：提醒线 < 软上限 < 熔断线。反过来写 Daemon 会整份拒收，应用这边会自己把软上限压到熔断线下面 1℃。",
            "熔断线最高只能设到 ${Proto.FUSE_MAX_MC / 1000}℃，应用、Daemon、命令行三处各钳一次，改不到更高。",
            "这条线是让熵自己收手，不是替硬件做保护：内核温控在 90℃ 上下本来就会自己降频，它永远比这一条先动。",
            "提醒是「上升沿 + 迟滞 + 冷却」：越线报一次，要回落 2℃ 才解除，解除后 2 分钟内再冲也不重发。",
            "确认：改完在控制台「温控」节看那三个数有没有跟着变；不一致通常是通道断了，回「系统」节重新一键运行。",
        ),
    ),
    Chapter(
        "调度档位改了什么", "档位", "日常",
        "均衡 / 极速 / 省电三个档，写的是 CPU、GPU 的调度节点。",
        listOf(
            "「调度」节里点一下 Chip 就下发，不用确认。当前档会亮成主题色。",
            "「重发」是把现在这一档再写一遍 —— 怀疑被系统或厂商温控改回去的时候用它。",
            "它不碰游戏本体：不改配置文件、不注入进程，那些是封号特征，也是这台机器不该被碰的地方。",
            "温度接近熔断线时，Daemon 会压住继续抬频的请求，此时档位看着没变、实际已经被收手了。",
            "渲染后端（默认 / Skia / GLES / Vulkan）改完要重启 SystemUI 和目标应用才生效。",
            "确认：「记录」页记一段，看 CPU 频率上限有没有停在你要的那档；没停住就点一次「重发」。",
            "退回：切回「均衡」再点重发，就是出厂那套默认值。",
        ),
    ),
    Chapter(
        "画质、帧率与刷新率", "画质", "日常",
        "四项都在「画质」节，能改的范围由机型决定，改不动会自己变灰。",
        listOf(
            "渲染分辨率：原生 / 3/4 / 2/3 / 1/2。掉帧严重又不在意清晰度时，先降这一项最有效。",
            "游戏帧率上限：0 是跟随系统。写 60/90/120 会落到系统属性里，需要 Root 或 Daemon。",
            "刷新率档：自动 / 60 / 90 / 120。和游戏自己的高帧率开关是两件事，冲突时以应用内设置为准。",
            "触控采样率 120~300Hz：写的是厂商私有节点，机型没有这类节点时整条会灰掉。",
            "画面锐度与清晰度写的是厂商后处理节点（例如 /proc/mtkclear/clear_level），不支持的机型自动置灰并保持当前值。",
            "确认：切到目标应用后看灵动岛那格帧率；写的是 120 而读数一直是 60，通常是游戏内的帧率开关还压着。",
        ),
    ),
    Chapter(
        "CPU 与 GPU 频率上限", "上限", "日常",
        "在档位之下再压一刀：只压最高频率，不动调度策略，用来降温而不改变手感。",
        listOf(
            "「调度」节下面的频率上限卡片：按簇给 CPU 定一个最高频率，GPU 同理。",
            "滑到 0 是清掉这一项，交回系统与调度档位管；写数字才会落到 scaling_max_freq。",
            "它和档位叠着生效：谁低听谁。档位是极速、上限压到 1.6GHz，实际就停在 1.6GHz。",
            "温度越线时 Daemon 会在这条上限之下继续往下收，不会因为你设了上限就放任过热。",
            "确认：「系统信息」页的 CPU 簇那一卡写着每簇的当前范围，改完立刻能对上。",
            "改完手感不对就把这一项归 0，别去动熔断线 —— 那是最后一道闸，不是调音台。",
        ),
    ),
    Chapter(
        "录屏与防录屏", "录制", "日常",
        "录屏的帧率跟屏幕刷新率走；防录屏开了以后，浮窗和灵动岛都不会出现在成片里。",
        listOf(
            "底部导航的「录制」页：开始 / 停止、时长、当前帧率、文件都在这块。",
            "帧率默认跟着屏幕刷新率，也可以手动钉在某个值；上限 240。",
            "系统会弹一次录屏授权框，那是 MediaProjection 本身要求的，不是额外的权限。",
            "成片存在应用自己的目录里，页上那行「输出目录」写的就是实际路径；要留在相册里就点「存入相册」。",
            "「防录屏」打开后，悬浮控制台、右侧小面板、灵动岛在成片与截屏里都不出现 —— 要录下这些面板就别开它。",
            "确认：录一段回看，面板在不在里面一目了然；在的话说明防录屏是关着的。",
        ),
    ),
    Chapter(
        "外观、配色与图标", "外观", "日常",
        "颜色只影响读感，不影响任何一项能力；默认是樱吹雪。",
        listOf(
            "应用里「我的」页右上角的齿轮 → 设置页 → 外观：主题、配色、高亮色都在这儿。",
            "配色现在有 ${Palette.entries.size} 套可选，其中「跟随系统」用的是 Material You 自己那套。",
            "面板上的「外观」节能直接切形态、切小球大小，不用回 App。",
            "高亮色是给熔断、越线那两处用的，改之前想想你还能不能一眼认出红色是警语。",
            "换配色不会断通道；但改系统字体大小会让应用与守护进程之间那条连接重连一次，那一下会显示熔断锁定，120 秒后自己解开。",
        ),
    ),
    Chapter(
        "极限探测要不要跑", "探测", "进阶",
        "跑一遍才知道这台机器的频率能稳定到哪，后面的调度以它为上限。",
        listOf(
            "「系统」节 → 极限探测 → 开始探测。过程会短时抬频，全程由 Daemon 的熔断线程看护。",
            "温度或失速越线会立刻中止并还原，不会出现「探测把手机烧了」那种事。",
            "随时可以再点一次「中止探测」。",
            "结果里的「甜点频率」是性价比最高的那一档，「极限频率」只用来标出边界。",
            "需要 Root + Daemon 才点得动；只读档下这一块是灰的，并写明原因。",
            "跑之前把后台清一清：探测那几十秒里别人也在抬频，结果会偏低而不是偏危险。",
        ),
    ),
    Chapter(
        "游戏页与插帧", "插帧", "进阶",
        "按应用记住帧率上限，切到那个应用时自动生效。",
        listOf(
            "应用首页的「游戏」页：给单个应用设帧率上限，不想要了就选「不锁」。",
            "「前台跟随」打开后，切到某个应用就自动套它的档案；没档案的应用走全局兜底。",
            "多线程插帧用的是系统自己的真实通道（SurfaceFlinger / 厂商刷新率属性），" +
                "不改游戏文件、不往游戏进程里注东西 —— 那是腾讯 ACE 与 TpGuard 认的封号特征。",
            "通道没就绪时这一项会明确灰掉，不会给你一个「看着像开了」的开关。",
            "游戏停在前台之外时不出帧，此时帧率显示 0 是事实，不是读数坏了。",
        ),
    ),
    Chapter(
        "记录、诊断与报障", "报障", "排障",
        "出问题先留证据：一份 CSV + 一张诊断截图，一次就能定位。",
        listOf(
            "「记录」页打开记录开关，选好间隔（500ms~60s）与是否只跟某个应用的帧率。",
            "曲线同时给最低值与最高值 —— 卡顿看的是最低，不是平均。",
            "「我的-诊断」里平台、内核、通道、热区读数是给报表用的，报障时把它和 CSV 一起发出来。",
            "报障说三件事就够：机型与平台、当前档位（只读还是 Daemon）、哪个功能点了没反应。",
            "执行路径写着「只读」时，很多项本来就点不动，那不是新 bug。",
        ),
    ),
    Chapter(
        "连不上、点不动、被锁住", "连接", "排障",
        "三种表现各有各的原因，先看控制台的「系统」节，别看手机设置。",
        listOf(
            "「连接」写着未连接：在「系统」节再点一次「一键运行 entropyd」。装了新版 APK 而设备上是旧二进制时，这一步会重新投放并重启。",
            "写着熔断锁定 · 120 秒后解锁：这是刚才那次操作越了线或通道断过，等到时间自己就解开，不用重启手机。",
            "改完阈值界面和实际行为不一致：也是这条连接的问题，重连之后「温控」节三个数会重新对齐。",
            "按钮整片是灰的且没有报错：权限档不够，看那颗胶囊写的是不是「普通」。",
            "悬浮窗整块不见了：先确认「显示在其他应用上层」还在，清理类软件会收回它。",
            "只读档下这些本来就该灰：滑杆、档位、上限、探测、渲染后端。不是坏了。",
        ),
    ),
)

/**
 * 大纲式指南：先挑一类，再在这一类里挑一节。
 *
 * 十四节平铺成一排 chip 的时候，找"录制"和找"三条线"要花一样的力气，而它们的用处根本不一样。
 * 分成四类之后第一排只有四个选择，第二排是当前类里的那几节；上一节 / 下一节也只在类里走，
 * 跨类得自己点第一排 —— 这样"翻完了一类"是有边界的，不会翻着翻着跳到别的事上去。
 *
 * 一次只展开一节。两块同时展开时，这块页在窄面板里会高到要滚两屏，
 * 而"翻下一节"的手势本来就是把上一节合上 —— 状态跟着直觉走，不需要额外说明。
 * 记住的是"当前第几节"，不是"每节各自的开合"，所以 rememberSaveable 只存一个 Int。
 *
 * 大纲之上还有 CPU 身份卡：这一页以前只有说明书，"我的"两个字里最该有的
 * "我这台机器是什么"反倒没有。具体从上到下的顺序与理由写在函数体里。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GuidePage(store: EntropyStore, state: UiState) {
    var open by rememberSaveable { mutableIntStateOf(0) }
    val entropy = LocalEntropyColors.current
    // 顺序：影像 → 页名 → CPU 身份 → 大纲。
    // 影像在最上方：这一页打开第一眼就该看见它在动，不用先滚。
    // 页名紧跟其后 —— 它是这一节的说明，不再充当整页的门面。
    ClipCard()
    PageTitle(SideTab.GUIDE.label, SideTab.GUIDE.hint)
    CpuIdentityCard(store, state)
    val ch = Chapters[open]
    val cats = Chapters.map { it.cat }.distinct()
    PanelCard("大纲 · 先挑一类，再挑一节") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            cats.forEach { name ->
                Chip(name, selected = name == ch.cat) {
                    open = Chapters.indexOfFirst { it.cat == name }
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Text("${ch.cat} · ${Chapters.count { it.cat == ch.cat }} 节",
            color = entropy.onScrim.copy(alpha = 0.45f), fontSize = 9.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Chapters.forEachIndexed { i, c ->
                if (c.cat == ch.cat) {
                    // 序号是整份大纲里的序号：翻到第几节要能对上第几节，
                    // 在类里重新从 1 数会让人以为有两节"权限档"
                    Chip("${i + 1} ${c.short}", selected = open == i) { open = i }
                }
            }
        }
    }
    // 翻节用 AnimatedContent 而不是让文字自己换一遍：这一节的正文长短差很多（三行到七行），
    // 硬换会让下面的按钮行往上顶一格，视线丢一次。这里新旧两页交叉位移，卡片高度跟着补间，
    // 读起来是"翻过去一页"。方向按节的先后：往后翻内容从下面进来，和手指往上下滑一致。
    var prev by remember { mutableIntStateOf(open) }
    val forward = open >= prev
    LaunchedEffect(open) { prev = open }
    AnimatedContent(
        targetState = open,
        transitionSpec = {
            (fadeIn(tween(EntropyMotion.NORMAL, easing = EntropyMotion.EaseOut)) +
                slideInVertically(tween(EntropyMotion.SLOW, easing = EntropyMotion.Ease)) {
                    if (forward) it / 5 else -it / 5 }) togetherWith
                fadeOut(tween(EntropyMotion.FAST))
        },
        label = "chapter"
    ) { idx ->
        val c = Chapters[idx]
        val back = prevChapterInCat(idx)
        val next = nextChapterInCat(idx)
        PanelCard("第 ${idx + 1} 节 · ${c.title}") {
            Text(c.lead, color = entropy.onScrim.copy(alpha = 0.7f), fontSize = 10.sp,
                lineHeight = 14.sp, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            val clock = staggeredReveal(c.steps.size, perMs = 46)
            c.steps.forEachIndexed { i, s ->
                Row(
                    Modifier.fillMaxWidth().alpha(alphaOf(clock, i, c.steps.size, 46))
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Box(
                        Modifier.size(14.dp).clip(CircleShape)
                            .background(entropy.onScrim.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("${i + 1}", color = entropy.onScrim.copy(alpha = 0.6f), fontSize = 8.sp)
                    }
                    Text(s, color = entropy.onScrim.copy(alpha = 0.85f), fontSize = 10.sp,
                        lineHeight = 14.sp, modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("上一节", false, enabled = back >= 0) { if (back >= 0) open = back }
                Chip("下一节", false, enabled = next >= 0) { if (next >= 0) open = next }
                Chip("回到开头", false, enabled = idx != 0) { open = 0 }
            }
        }
    }
}

/** 同类里的前一节；到边了返回 -1，让 chip 自己灰掉而不是跳去别类 */
private fun prevChapterInCat(i: Int): Int {
    val p = i - 1
    return if (p >= 0 && Chapters[p].cat == Chapters[i].cat) p else -1
}

private fun nextChapterInCat(i: Int): Int {
    val n = i + 1
    return if (n <= Chapters.lastIndex && Chapters[n].cat == Chapters[i].cat) n else -1
}

// ------------------------------------------------------------------ //
// 页三：快捷开关                                                        //
// ------------------------------------------------------------------ //

/** 最常用的几项。每项都只做"点一下就改"的事，需要滑的才用 OverlaySlider */
@Composable
internal fun QuickSwitchPage(win: OverlayWindow, store: EntropyStore, state: UiState) {
    val entropy = LocalEntropyColors.current
    val ctx = LocalContext.current
    PageTitle(SideTab.QUICK.label, SideTab.QUICK.hint)
    PanelCard("显示") {
        ToggleRow("灵动岛", store.hudEnabled.value) { on ->
            if (on) SafeHudService.start(ctx) else SafeHudService.stop(ctx)
        }
        // 浮窗本身不需要开关：这块页就是浮窗里的，能看见它就说明浮窗开着。
        // 真正会想切的是"隐藏后还弹不弹那套音量键开合的引导"
        ToggleRow("隐藏后的按键引导", store.prefs.showVolumeGuide) {
            store.prefs.showVolumeGuide = it
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("原子小球", store.overlayForm.value == OverlayForm.BALL, Modifier.weight(1f)) {
                win.setForm(OverlayForm.BALL)
            }
            Chip("左侧控制台", store.overlayForm.value == OverlayForm.PANEL, Modifier.weight(1f)) {
                win.setForm(OverlayForm.PANEL)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("隐藏浮窗", false, Modifier.weight(1f)) { win.hide() }
            Chip("打开应用", false, Modifier.weight(1f)) { win.openApp() }
        }
    }
    PanelCard("档位") {
        val locked = store.locked(Feature.PROFILE)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("省电", state.profile == Proto.PROFILE_POWERSAVE, Modifier.weight(1f), enabled = !locked) {
                store.setProfile(Proto.PROFILE_POWERSAVE)
            }
            Chip("均衡", state.profile == Proto.PROFILE_BALANCED, Modifier.weight(1f), enabled = !locked) {
                store.setProfile(Proto.PROFILE_BALANCED)
            }
            Chip("极速", state.profile == Proto.PROFILE_GAME, Modifier.weight(1f), enabled = !locked) {
                store.setProfile(Proto.PROFILE_GAME)
            }
        }
        LockNote(Feature.PROFILE, locked)
    }
    PanelCard("通道") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(if (state.busy) "运行中…" else "运行 entropyd", false, Modifier.weight(1f),
                enabled = !state.busy && !store.locked(Feature.RUN_DAEMON)) { store.startDaemon() }
            Chip("请求 Root", false, Modifier.weight(1f)) { store.requestRoot() }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("刷新冻结状态", false, Modifier.weight(1f)) { store.refreshFreezer() }
            Chip("全部解冻", false, Modifier.weight(1f), enabled = !store.locked(Feature.FREEZE)) {
                store.resetAll()
            }
        }
        if (state.lastMessage.isNotEmpty()) Text(
            state.lastMessage, color = entropy.onScrim.copy(alpha = 0.55f), fontSize = 9.sp,
            maxLines = 2, lineHeight = 12.sp)
    }
    PanelCard("两条线") {
        OverlaySlider(
            label = "过热提醒线",
            value = store.hudAlertMc.intValue,
            range = 40_000..90_000, detents = 11,
            valueText = { "%.1f℃".format(it / 1000f) },
            color = entropy.tempWarm,
            onCommit = { store.setHudAlertMc(it) }
        )
        Spacer(Modifier.height(8.dp))
        val capLocked = store.locked(Feature.FPS_CAP)
        OverlaySlider(
            label = "游戏帧率上限",
            value = store.prefs.fpsLimit,
            range = 0..240, detents = 25,
            valueText = { if (it == 0) "跟随系统" else "$it FPS" },
            enabled = !capLocked,
            onCommit = { store.setFpsLimit(it) }
        )
        LockNote(Feature.FPS_CAP, capLocked)
    }
}

/**
 * 面板自己的开关行。
 * 没用 Material 的 Switch：它的轨道宽度在深色底板上会顶破这栏的 96dp，
 * 而且 M3 的开关在悬浮窗里没有正确的容器色，画出来像一块贴歪了的胶带。
 */
@Composable
private fun ToggleRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val entropy = LocalEntropyColors.current
    val scheme = MaterialTheme.colorScheme
    val knob by animateFloatAsState(
        targetValue = if (on) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 480f), label = "knob")
    val track by animateColorAsState(
        targetValue = if (on) scheme.primary else entropy.onScrim.copy(alpha = 0.18f),
        animationSpec = tween(200), label = "track")
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = entropy.onScrim.copy(alpha = 0.8f), fontSize = 10.sp,
            modifier = Modifier.weight(1f))
        Box(
            Modifier.size(width = 34.dp, height = 18.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(track)
                .pointerInput(on) { detectTapGestures(onTap = { onChange(!on) }) }
        ) {
            Box(
                Modifier.offset(x = (16.dp * knob))
                    .padding(2.dp).size(14.dp).clip(CircleShape)
                    .background(if (on) scheme.onPrimary else entropy.onScrim.copy(alpha = 0.7f))
            )
        }
    }
}

// ------------------------------------------------------------------ //
// 页四：CPU 排行                                                       //
// ------------------------------------------------------------------ //

/**
 * 整张型号表铺开成榜，再把自己钉进去。
 *
 * 数据源就是 CpuRanker 那张表，评级和榜单必须是同一份：否则会出现
 * 「身份卡说我在夯档，榜上却找不到自己」这种自相矛盾。
 *
 * 三种落位的措辞不一样，不许混：
 * - 命中型号表 —— 名次是在榜里数出来的，那一行直接标「本机」。
 * - 表里没有、但有频率读数 —— 按估算分数插进该在的位置，标「本机 · 估算」，
 *   并且写清楚这个名次是插出来的，不是查出来的。
 * - 判不出来（虚拟平台 / 读不到 cpufreq）—— 不插行。榜照排，名次那栏说明为什么没有。
 *
 * 动画按「逐档点亮」走，共五下，不逐行点亮：这页六十多行，逐行错开要么把动画拉到好几秒，
 * 要么每帧重算六十多个 alpha —— 前者没人看完，后者在悬浮窗这种合成环境里看得见掉帧。
 */
@Composable
internal fun CpuRankingPage(store: EntropyStore, state: UiState) {
    val entropy = LocalEntropyColors.current
    val scheme = MaterialTheme.colorScheme
    val p = store.bridge.profile
    val r = remember(p.socModel, p.model, p.cpuClusters) { CpuRanker.rank(p) }
    val blocks = remember(r) { CpuRanker.board(r) }
    val total = blocks.sumOf { it.rows.size }
    val mine = blocks.asSequence().flatMap { it.rows.asSequence() }.firstOrNull { it.mine }
    val clock = staggeredReveal(blocks.size)

    PageTitle(SideTab.RANK.label, SideTab.RANK.hint)

    PanelCard("本机名次") {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                if (mine != null) "第 ${mine.rank} 名" else "未上榜",
                color = if (mine != null) scheme.primary else entropy.onScrim.copy(alpha = 0.6f),
                fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text("/ 榜上 $total 颗", color = entropy.onScrim.copy(alpha = 0.45f), fontSize = 9.sp,
                maxLines = 1, modifier = Modifier.padding(bottom = 3.dp))
            Spacer(Modifier.weight(1f))
            val tier = r.tier
            val strength = if (tier == null) 0.30f else 1f - tier.ordinal * 0.16f
            Box(Modifier.clip(RoundedCornerShape(999.dp))
                    .background(scheme.primary.copy(alpha = 0.22f * strength + 0.06f))
                    .border(1.dp, scheme.primary.copy(alpha = 0.5f * strength), RoundedCornerShape(999.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text(tier?.label ?: "未评级",
                    color = if (tier == null) entropy.onScrim.copy(alpha = 0.55f) else scheme.primary,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(r.chipName, color = entropy.onScrim, fontSize = 11.sp, maxLines = 1,
            fontWeight = FontWeight.Medium, overflow = TextOverflow.Ellipsis)
        Text(
            when (r.basis) {
                CpuRanking.Basis.MODEL -> "型号表命中：表内自评 ${r.score}/100，名次是数出来的。"
                CpuRanking.Basis.ESTIMATED ->
                    "型号表里没有这颗 U：按频率与核数估到 ${r.score}/100，名次是插出来的，不是查出来的。"
                CpuRanking.Basis.NONE -> "${r.reason}。榜上这 $total 颗仍按表排，只是不给你插一个名次。"
            },
            color = entropy.onScrim.copy(alpha = 0.45f), fontSize = 8.sp, lineHeight = 11.sp)
        if (mine != null) {
            Spacer(Modifier.height(4.dp))
            Text("压过 ${(total - mine.rank) * 100 / total}% 的上榜平台，身位 ${(total - mine.rank)} 颗。",
                color = entropy.onScrim.copy(alpha = 0.7f), fontSize = 9.sp, maxLines = 1)
        }
    }

    PanelCard("五档分布") {
        CpuTier.entries.forEachIndexed { i, t ->
            val n = blocks.firstOrNull { it.tier == t }?.rows?.size ?: 0
            val on = t == r.tier
            val fg by animateColorAsState(
                targetValue = if (on) scheme.primary else entropy.onScrim.copy(alpha = 0.5f),
                animationSpec = tween(EntropyMotion.NORMAL), label = "distFg")
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("${i + 1} ${t.label}", color = fg, fontSize = 9.sp, maxLines = 1,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.width(52.dp))
                Box(Modifier.weight(1f).height(4.dp).clip(CircleShape)
                        .background(entropy.onScrim.copy(alpha = 0.10f))) {
                    Box(Modifier.fillMaxWidth(if (total > 0) n.toFloat() / total else 0f)
                            .fillMaxHeight().clip(CircleShape)
                            .background(if (on) scheme.primary else entropy.onScrim.copy(alpha = 0.30f)))
                }
                Text("$n", color = fg, fontSize = 9.sp, maxLines = 1,
                    modifier = Modifier.width(16.dp), textAlign = TextAlign.End)
            }
        }
    }

    blocks.forEachIndexed { gi, b ->
        Column(Modifier.fillMaxWidth().alpha(alphaOf(clock, gi, blocks.size))) {
            PanelCard("${gi + 1} · ${b.tier.label} · ${b.rows.size} 颗") {
                Text(b.tier.blurb, color = entropy.onScrim.copy(alpha = 0.4f), fontSize = 8.sp,
                    maxLines = 2, lineHeight = 11.sp)
                Spacer(Modifier.height(4.dp))
                b.rows.forEach { row -> RankLine(row) }
            }
        }
    }

    PanelCard("这张榜怎么来的") {
        listOf(
            "榜就是熵自带的那张型号表：按公开的代差、核数与频率档把移动平台排成一串，只收核对过代号的。",
            "同一档里的先后只是这张表的排序，不代表真实性能差；要改调度，看的是跨档那一步。",
            "表里没有的型号不硬挤进来：它按频率与核数估算一个分数插在对应位置，行末标「本机 · 估算」。",
            "分数不是跑分，也不参与任何调度决策 —— 它唯一的作用是给这五档定阶梯。",
        ).forEach {
            Text(it, color = entropy.onScrim.copy(alpha = 0.6f), fontSize = 8.sp, lineHeight = 11.sp,
                modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

/**
 * 榜上一行。
 *
 * 名字那一栏吃剩余宽度并省略号收尾：型号串的长度不受我们控制（厂商会在里面夹前缀），
 * 让它换行会把整张榜拉成一列高低不齐的垛子，读不动。
 */
@Composable
private fun RankLine(row: RankRow) {
    val entropy = LocalEntropyColors.current
    val scheme = MaterialTheme.colorScheme
    val on = row.mine
    val fg = if (on) scheme.primary else entropy.onScrim.copy(alpha = 0.72f)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .background(if (on) scheme.primary.copy(alpha = 0.14f) else Color.Transparent)
            .padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text("${row.rank}", color = if (on) fg else entropy.onScrim.copy(alpha = 0.4f),
            fontSize = 8.sp, maxLines = 1, modifier = Modifier.width(16.dp), textAlign = TextAlign.End)
        Text(row.chip.name, color = fg, fontSize = 9.sp, maxLines = 1, softWrap = false,
            overflow = TextOverflow.Ellipsis, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f))
        Box(Modifier.width(22.dp).height(3.dp).clip(CircleShape)
                .background(entropy.onScrim.copy(alpha = 0.10f))) {
            Box(Modifier.fillMaxWidth(row.chip.score / 100f).fillMaxHeight().clip(CircleShape)
                    .background(if (on) scheme.primary else entropy.onScrim.copy(alpha = 0.32f)))
        }
        Text("${row.chip.score}", color = if (on) fg else entropy.onScrim.copy(alpha = 0.4f),
            fontSize = 8.sp, maxLines = 1, modifier = Modifier.width(14.dp), textAlign = TextAlign.End)
        if (on) Text(if (row.estimated) "本机·估算" else "本机", color = scheme.primary, fontSize = 7.sp,
            maxLines = 1, modifier = Modifier.clip(RoundedCornerShape(999.dp))
                .background(scheme.primary.copy(alpha = 0.18f))
                .padding(horizontal = 4.dp, vertical = 1.dp))
    }
}
