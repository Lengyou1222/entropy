// File: app/src/main/java/com/entropy/ui/components/Controls.kt
//
// 复用控件。这里的重点全在"手感"上：
//  滑杆每跨过一格都要给一次真实的触觉反馈，用户闭着眼睛也能知道自己在第几档。
//  Compose 的 Slider 本身不带这个语义，所以要自己算"跨越了几个刻度"再触发。
package com.entropy.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt
import com.entropy.data.EntropyStore
import com.entropy.hardware.SocFamily
import com.entropy.data.Feature

/**
 * 全站共用的动效参数。
 *
 * 以前每处各写一遍 `spring()` / `tween(300)`，于是同一个 App 里
 * 首页的展开是 240ms、权限页是 300ms、悬浮窗是另一套阻尼，观感就是"哪儿都在动，
 * 但没有任何两下是同一个节奏"。曲线统一用这条近 Material emphasized 的贝塞尔：
 * 起步快、收尾长，短距离位移也不会拖尾。
 */
object EntropyMotion {
    const val FAST = 150
    const val NORMAL = 230
    const val SLOW = 340

    val Ease = CubicBezierEasing(0.2f, 0f, 0.16f, 1f)
    val EaseOut = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** 位移与展开：轻微过冲，读起来像"落位"而不是"停住" */
    fun <T> settle(): androidx.compose.animation.core.SpringSpec<T> =
        spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)

    /**
     * 滑杆落档：阻尼给到 0.42，明显低于"平稳停止"所需的 0.7，
     * 于是跨档时 thumb 会冲过刻度再荡回来 —— 用户要的"弹一下"就是这个数。
     * stiffness 380 是试出来的：再高就成了抖，再低就变成晃。
     */
    fun sliderSpring() = spring<Float>(dampingRatio = 0.42f, stiffness = 380f)

    /** 数字跳动时的缩放回弹，比滑杆更急，只弹一次 */
    fun popSpring() = spring<Float>(dampingRatio = 0.34f, stiffness = 520f)

    fun fadeEnter() = fadeIn(tween(NORMAL, easing = EaseOut))
    fun fadeExit() = fadeOut(tween(FAST))
    fun slideEnter() = slideInHorizontally(tween(SLOW, easing = Ease)) { it / 14 }
    fun slideExit() = slideOutHorizontally(tween(FAST)) { it / 14 }
}

/**
 * 权限不够时的功能外壳：整块压灰 + 吃掉点击 + 就地说明差在哪一级。
 *
 * 一定要盖一层拦截，而不是只把颜色调淡：滑杆和按钮在 alpha 下照样能点，
 * "看起来不能用、点下去却真的往内核写"是最糟的组合。
 * 拦截层用 pointerInput 而不是 clickable —— 灰块上再来一次水波纹会被读成"能点"。
 */
@Composable
fun GatedBlock(
    store: EntropyStore,
    feature: Feature,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val locked = store.locked(feature)
    val scheme = MaterialTheme.colorScheme
    val dim by animateFloatAsState(
        targetValue = if (locked) 0.32f else 1f,
        animationSpec = tween(EntropyMotion.NORMAL, easing = EntropyMotion.Ease),
        label = "gateDim")
    Box(modifier) {
        Column(Modifier.fillMaxWidth().alpha(dim)) { content() }
        // 拦截层必须是 Box 的直接子项。放进 AnimatedVisibility 的话，
        // matchParentSize 会被 AnimatedVisibility 自己的内部布局当成无效 parentData，
        // 徽章塌成 wrap-content 停在左上角，正好压住卡片标题。
        if (locked) LockBadge(feature, Modifier.matchParentSize())
    }
}

/** 拦截层 + 右上角说明。文案直接来自 Feature，界面里不许再抄一份"需要 Root" */
@Composable
private fun BoxScope.LockBadge(feature: Feature, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(EntropyMotion.NORMAL)) }
    Box(
        modifier
            .alpha(appear.value)
            .pointerInput(feature) { detectTapGestures { } },
    ) {
        Text(
            "需 ${feature.min.label}",
            color = scheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 14.dp, end = 16.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(scheme.surfaceVariant)
                .padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/** 一句话解释"这项功能为什么这一档用不了"，置灰的卡片下面都跟着它 */
@Composable
fun GateHint(store: EntropyStore, feature: Feature) {
    if (!store.locked(feature)) return
    Text(
        "${feature.title}：${feature.why}。升到「${feature.min.label}」后自动解锁。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp)
    )
}


/**
 * 带刻度与触觉反馈的滑杆，弹簧手感见 SpringSliderTrack。
 *
 * detents 是"档位总数、含两端"，所以 0..100 分成 10 档就写 10。
 * 换算发生在轨道里：0.42 的阻尼、380 的刚度是实测出来的数，
 * 再软就晃、再硬就又变回瞬移。
 */
@Composable
fun TickSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange,
    /** 档位总数，含两端 */
    detents: Int,
    label: String,
    valueText: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    val haptic = LocalHapticFeedback.current
    // 记住上一次落在第几格，用来判断这次是否"跨过了一格"
    val lastDetent = remember { androidx.compose.runtime.mutableIntStateOf(-1) }
    // 区间两端按大小取，不按传入顺序：调用方的上下限来自 sysfs（GPU 的 min/max 是两个
    // 独立可写节点），被别的调频工具改过之后完全可能倒挂。倒挂的 IntRange 是空区间，
    // 拿去 coerceIn 会抛 IllegalArgumentException —— 拖动的那一下就是崩溃现场。
    val lo = minOf(range.first, range.last)
    val hi = maxOf(range.first, range.last)
    val span = (hi - lo).toFloat()
    val slots = (detents - 1).coerceAtLeast(1)
    // 只有一档时 span 为 0，stepSize 再跟着成 0，下面 f*span/stepSize 就是 0/0=NaN。
    // 给 1 让算式退化成"永远落在 lo"，而不是把 NaN 喂进弹簧和 Canvas。
    val stepSize = if (span > 0f) span / slots else 1f
    val pop = popScale(value)

    Column(modifier = modifier) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(valueText, style = MaterialTheme.typography.labelLarge, color = color,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.scale(pop))
        }
        SpringSliderTrack(
            frac = if (span > 0f) ((value - lo) / span).coerceIn(0f, 1f) else 0f,
            onScrub = { f ->
                val snapped = (lo + (f * span / stepSize).roundToInt() * stepSize)
                    .roundToInt().coerceIn(lo, hi)
                val idx = ((snapped - lo) / stepSize).roundToInt()
                if (idx != lastDetent.value) {
                    lastDetent.value = idx
                    // LongPress 的震动比 TextHandleFeedback 更"实"，接近物理旋钮的顿挫
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                if (snapped != value) onValueChange(snapped)
            },
            detents = detents,
            enabled = enabled,
            color = color,
            inactiveColor = inactiveColor,
        )
    }
}

private val SliderThumbR = 8.dp
private val SliderTrackH = 5.dp

/**
 * 弹簧滑杆的轨道本体。
 *
 * 不用 Material 的 Slider，是因为它在拖动期间把 thumb 钉死在手指上、只有松手才有动画，
 * 于是每一格都是"咔"地瞬移 —— 那正是"生硬"的来源。这里 thumb 的位置自己是一个
 * Animatable：目标档位一变，弹簧就把它拽过去，低阻尼下会冲过刻度再荡回来，
 * 跨档因此变成有质量的位移。手指只能停在档位上，所以档位之间的值永远不会出现。
 */
@Composable
fun SpringSliderTrack(
    /** 当前档位落在轨道上的比例，0..1 */
    frac: Float,
    onScrub: (Float) -> Unit,
    modifier: Modifier = Modifier,
    /** 档位总数，含两端；<=2 时不画中间刻度 */
    detents: Int = 2,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    onScrubFinished: (() -> Unit)? = null,
) {
    val slots = (detents - 1).coerceAtLeast(1)
    // NaN 一旦进了 Animatable，弹簧会把它一路带到 Canvas 的坐标里，画出来是整条轨道消失。
    // 调用方算比例时可能除以 0，所以在入口处挡一次，而不是要求每个调用方都记得判。
    val safe = if (frac.isFinite()) frac.coerceIn(0f, 1f) else 0f
    val pos = remember { Animatable(safe) }
    LaunchedEffect(safe) { pos.animateTo(safe, EntropyMotion.sliderSpring()) }
    var dragging by remember { mutableStateOf(false) }
    // 抓住时涨一圈、松手弹回：这条弹簧比轨道更"软"，按下那一下才有实体感
    val grow by animateFloatAsState(
        targetValue = if (dragging) 1.3f else 1f,
        animationSpec = spring(dampingRatio = 0.36f, stiffness = 520f),
        label = "thumbGrow",
    )

    Canvas(
        modifier
            .fillMaxWidth()
            .height(34.dp)
            .semantics {
                // 没有 Role.Slider 这个常量（Compose 的 Role 只有 Button/Checkbox/Switch/
                // Tab/Image/Handle 那几档），所以这里只把百分比写进 stateDescription：
                // 读屏软件会念"百分之四十"，比给一个错的 role 实在
                stateDescription = "${(safe * 100).roundToInt()}%"
                if (!enabled) contentDescription = "不可调整"
            }
            .pointerInput(enabled, slots) {
                awaitEachGesture {
                    if (!enabled) return@awaitEachGesture
                    val thumbR = SliderThumbR.toPx()
                    val usable = (size.width - thumbR * 2).coerceAtLeast(1f)
                    // 把指针横坐标换算成"第几档"，再反过来给出比例：
                    // 中间值一出生就被丢掉，滑杆因此永远只会停在刻度上
                    fun idxAt(x: Float) = (((x - thumbR) / usable).coerceIn(0f, 1f) * slots)
                        .roundToInt().coerceIn(0, slots)
                    // 1.7 里 awaitFirstDown 只剩 (pass) 这一个签名，
                    // 老写法 requireUnmodified=false 那版已经只是二进制兼容壳，参数名都变了
                    val down = awaitFirstDown()
                    // 按下那一下不能立刻 consume：外层列表的滚动也要在同一批事件里认领。
                    // 先等手指走出判定距离，横的多就归滑杆，竖的多就整段让给外层滚动 ——
                    // 设置页里三根滑杆连着铺满一屏，吞掉竖划的话页面就再也滚不动了。
                    val slop = 10.dp.toPx()
                    var claimed = false
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Main)
                            .changes.firstOrNull { it.id == down.id } ?: break
                        if (!claimed) {
                            if (change.isConsumed) break
                            val dx = abs(change.position.x - down.position.x)
                            val dy = abs(change.position.y - down.position.y)
                            if (dy > slop && dy >= dx) break
                            if (change.pressed && dx <= slop) continue
                            claimed = true
                            dragging = true
                        }
                        change.consume()
                        onScrub(idxAt(change.position.x).toFloat() / slots)
                        if (!change.pressed) break
                    }
                    dragging = false
                    if (claimed) onScrubFinished?.invoke()
                }
            }
    ) {
        val cy = size.height / 2
        val thumbR = SliderThumbR.toPx()
        val trackH = SliderTrackH.toPx()
        val usable = (size.width - thumbR * 2).coerceAtLeast(1f)
        val x = thumbR + pos.value * usable
        val round = CornerRadius(trackH / 2, trackH / 2)
        drawRoundRect(inactiveColor, Offset(thumbR, cy - trackH / 2), Size(usable, trackH), round)
        drawRoundRect(color, Offset(thumbR, cy - trackH / 2),
            Size((x - thumbR).coerceAtLeast(0f), trackH), round)
        if (detents > 2) {
            val tick = thumbR * 0.17f
            for (i in 1 until slots) {
                val tx = thumbR + usable * i / slots
                // 已经走过的那段用底色点（压在彩色轨道上才看得见），
                // 前面那段用主色半透明点（压在灰轨上才看得见）
                drawCircle(
                    if (i.toFloat() / slots <= pos.value) inactiveColor else color.copy(alpha = 0.45f),
                    radius = tick, center = Offset(tx, cy)
                )
            }
        }
        drawCircle(color.copy(alpha = 0.16f), radius = thumbR * grow * 1.85f, center = Offset(x, cy))
        drawCircle(color, radius = thumbR * grow, center = Offset(x, cy))
    }
}

/**
 * 数值变化时弹一下的缩放系数（1.14 → 1）。
 *
 * 第一次组合不弹：不然每次进页面、每次重组都会看见数字凭空跳一次，
 * 那是噪声，不是反馈。
 */
@Composable
fun popScale(key: Any?): Float {
    val s = remember { Animatable(1f) }
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(key) {
        if (!seen) { seen = true; return@LaunchedEffect }
        s.snapTo(1.14f)
        s.animateTo(1f, EntropyMotion.popSpring())
    }
    return s.value
}


/**
 * 通用信息卡：标题 + 内容 + 可选右侧操作。
 *
 * 卡面用 surfaceContainerLow 实色，而不是"透明容器 + elevation"：
 * 透明卡面的 elevation 只会投出一圈没有卡身的阴影，浅色主题下看起来
 * 就是卡片外面套了一条灰边。层次交给卡面的明度差 + 发丝描边 + 顶部
 * 那层极浅强调色渐变，三样已经够区分，不需要阴影。
 */
@Composable
fun SectionCard(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val tint = accent ?: scheme.primary
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.14f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.10f), Color.Transparent)))
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 标题左边一道竖标：扫一眼就知道这张卡归谁管，比换个字重管用
                Box(
                    Modifier
                        .size(width = 3.dp, height = 15.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(tint)
                )
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

/**
 * 环形仪表。用于"硬件体检报告"里的甜点频率/温度阈值展示。
 * 用 drawArc 而不是第三方图表库，是因为悬浮窗也要复用同一套控件，
 * 多一个依赖就多一份 .so 与包体，Canvas 三行就够的东西不值得。
 */
@Composable
fun Gauge(
    fraction: Float,
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // Canvas 的 lambda 是 DrawScope 而非 composable，主题色必须在外面取好
        val track = MaterialTheme.colorScheme.surfaceVariant
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(84.dp)) {
                val stroke = 10.dp.toPx()
                val d = size.minDimension - stroke
                val tl = androidx.compose.ui.geometry.Offset(stroke / 2 + (size.width - d) / 2,
                    stroke / 2 + (size.height - d) / 2)
                drawArc(
                    color = track,
                    startAngle = 135f, sweepAngle = 270f, useCenter = false,
                    topLeft = tl, size = Size(d, d),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
                )
                drawArc(
                    color = color,
                    startAngle = 135f, sweepAngle = 270f * fraction.coerceIn(0f, 1f), useCenter = false,
                    topLeft = tl, size = Size(d, d),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                )
            }
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 一行键值，用于体检报告的明细列表。
 *
 * 两边都必须带权重：以前只给 key 加了 weight、value 裸着，
 * 于是值一长（"3 簇 · 8 核 · 12 档 OPP"这种）就把整行顶到右边去，
 * 在诊断页里表现为文字贴死屏幕边缘、逗号都看不见。
 * fill=false 让窄的那侧不白白占位，超长的才截断，两端都留出 14dp 的缝。
 */
@Composable
fun KvRow(key: String, value: String, highlight: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(key, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(value, style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(2f, fill = false),
            textAlign = TextAlign.End,
            maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 把"距今多少秒"写成人话。负数是 Daemon 明确给出的"从来没发生过"，
 * 不能当成 0 —— 显示"0s 前"会让人以为刚动过，而那其实是启动后一次都没重下过档。
 */
fun agoText(secondsAgo: Int): String = when {
    secondsAgo < 0 -> "从未"
    secondsAgo < 60 -> "${secondsAgo}s 前"
    secondsAgo < 3600 -> "${secondsAgo / 60}min 前"
    else -> "${secondsAgo / 3600}h 前"
}

/** 厂商标识：短名 + 一个近似品牌色。UNKNOWN 不给色，界面直接不画这一位 */
private data class VendorMark(val short: String, val argb: Long)

private fun markOf(family: SocFamily): VendorMark? = when (family) {
    SocFamily.QUALCOMM -> VendorMark("骁龙", 0xFFD83530)
    SocFamily.MEDIATEK -> VendorMark("天玑", 0xFF1F6FEB)
    SocFamily.EXYNOS -> VendorMark("猎户座", 0xFF1428A0)
    SocFamily.TENSOR -> VendorMark("Tensor", 0xFF4285F4)
    SocFamily.RIALSENSOR -> VendorMark("展锐", 0xFF00A6A0)
    SocFamily.UNKNOWN -> null
}

/**
 * SoC 厂商标识（悬浮窗"熵"字旁边那枚）。
 *
 * 画的是"一颗带引脚的芯片 + 厂商中文简称"，不是官网那个 logo：
 * 骁龙/天玑的图形标是注册商标，把它们的图截进 APK 当界面元素属于侵权，
 * 一个放在 GitHub 上的项目不能这么干。品牌色 + 简称已经足够一眼分出"高通还是天玑"，
 * 色值取的是公开视觉上的近似值，不是官方规范色。
 */
@Composable
fun SocBadge(
    family: SocFamily,
    modifier: Modifier = Modifier,
    withText: Boolean = true,
) {
    val mark = markOf(family) ?: return
    val c = Color(mark.argb)
    Row(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .background(c.copy(alpha = 0.16f))
            .padding(start = 4.dp, top = 2.dp, end = if (withText) 7.dp else 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 芯片本体 + 左右各两根引脚：12dp 见方，悬浮窗和小球卡片都塞得下
        Canvas(Modifier.size(12.dp)) {
            val body = size.minDimension * 0.58f
            val left = (size.minDimension - body) / 2
            val pin = size.minDimension * 0.21f
            val stroke = (size.minDimension * 0.10f).coerceAtLeast(0.8f)
            listOf(0.32f, 0.68f).forEach { t ->
                val y = size.height * t
                drawLine(c, Offset(left - pin, y), Offset(left, y), strokeWidth = stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(c, Offset(left + body, y), Offset(left + body + pin, y), strokeWidth = stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
            drawRoundRect(c, Offset(left, left), Size(body, body),
                CornerRadius(body * 0.22f, body * 0.22f))
        }
        if (withText) {
            Spacer(Modifier.width(4.dp))
            Text(mark.short, color = c, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp)
    )
}

/**
 * 分组标题 + 折叠开关。
 *
 * 指示符用 Canvas 画三角形，不用 Icons.Filled.ChevronRight：
 * material-icons-core 只打包了很小一部分图标，依赖不在那一小组里的向量名，
 * 换个 Compose 版本就是 Unresolved reference。三角形自己画，六行代码换一个确定性。
 */
/** count=null 表示这一组没有"条数"可显示（例如折叠起来的问答条目） */
@Composable
fun CollapsibleHeader(
    title: String,
    expanded: Boolean,
    count: Int? = null,
    onToggle: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // 箭头只画一次，靠旋转表达状态：画两个不同的 Path 会让"展开/收起"变成硬切，
    // 而这一组下面是长列表，指示器动一下才能看出是自己在切换而不是页面重画了
    val turn by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = EntropyMotion.settle(), label = "chevron")
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onToggle)
            .padding(start = 4.dp, end = 14.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(Modifier.size(14.dp).rotate(turn)) {
            val r = minOf(size.width, size.height) / 2f
            val cx = size.width / 2f
            val cy = size.height / 2f
            drawPath(
                Path().apply {
                    moveTo(cx - r, cy - r * 0.55f); lineTo(cx + r, cy - r * 0.55f)
                    lineTo(cx, cy + r * 0.85f); close()
                },
                scheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(9.dp))
        Text(title, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f), maxLines = 2)
        // 折叠状态下这个数字就是"收起的那组里有多少条"，不展开也知道后面藏了多少东西
        if (count != null) Text(
            "$count",
            style = MaterialTheme.typography.labelMedium,
            color = if (count > 0) scheme.primary else scheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
    }
}

/** 权限/状态提示条 */
@Composable
fun NoticeBar(text: String, color: Color) {
    Row(
        Modifier.fillMaxWidth()
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(6.dp).height(6.dp).background(color, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/** 极值/单值方块：数字要先于说明被读到，所以字号压过外面的正文 */
@Composable
fun StatBox(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium,
            color = color, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/**
 * 应用选择框（记录目标、按应用帧率档案共用）。
 *
 * 用可过滤的对话框而不是首页那种 DropdownMenu：这里要面对的是"整台机器上装了多少个应用"，
 * 下拉菜单展开后是一屏滚不到头的列表，而用户心里想的名字通常就两三个字。
 *
 * noneLabel 传 null 时不画那个"清空"按钮：给某个游戏新建档案的场景里，
 * "不指定"选完什么都不发生，点了等于没点，那种按钮不该存在。
 */
@Composable
fun AppPickerDialog(
    apps: List<Pair<String, String>>,
    current: String,
    title: String,
    noneLabel: String? = "不指定",
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val shown = apps.filter {
        query.isBlank() ||
            it.first.contains(query, ignoreCase = true) ||
            it.second.contains(query, ignoreCase = true)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("按名称或包名过滤") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                LazyColumn(Modifier.fillMaxWidth().height(280.dp)) {
                    items(shown, key = { it.second }) { (name, pkg) ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onPick(pkg) }
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(name, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f), maxLines = 1,
                                softWrap = false, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.width(8.dp))
                            if (pkg == current) Text("当前",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (shown.isEmpty()) item {
                        Text("没有匹配的应用。清空上面的过滤框试试。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(10.dp))
                    }
                }
            }
        },
        confirmButton = {
            if (noneLabel != null) TextButton(onClick = { onPick("") }) { Text(noneLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
