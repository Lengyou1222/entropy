// File: app/src/main/java/com/entropy/overlay/SafeHud.kt
//
// 灵动岛：贴在屏幕顶部中央的一块"安全状态"显示。
//
// 三条改动的理由，写在这里是为了下次别再改回去：
//
// 1) 不可拖动，位置只有一个：顶部中央、状态栏与刘海的下方。
//    它是"抬眼确认设备安不安全"的东西，跟着手指到处跑就失去了锚点 ——
//    眼睛需要一个固定位置，游戏里的注意力也一样。
//    以前拖动带来的那一整套坐标钳制、节流落库、转屏纠偏，全部删了。
//
// 2) 说"安全 / 偏热 / 危险 / 熔断"，不只是报一个温度数。
//    温度是原始事实，用户要的是结论。结论仍然只由读到的数推出来：
//    没有温度来源时写"无读数"，绝不因为"没报警"就显示"安全"。
//
// 3) 尺寸由内容决定，服务只给上界（capWidthPx / capHeightPx），上界再按内容收。
//    窗口是 WRAP_CONTENT，所以本文件里不许再出现 fillMax*：铺满一条宽度无限的窗口
//    要么量成 0x0，要么把岛直接撑到上界 —— 两种都不是"装多少内容占多大地方"。
//    于是收起态那条胶囊只跟两样东西走：结论词多长、勾了几项读数；
//    展开态跟两样走：可用宽度排得出几列（不是写死两列）、几列就有几行。
//    宽度不够降列，高度不够就滚 —— 被裁掉或被藏掉的都会是用户自己勾出来的读数。
//    窗口即触摸区，岛外面不会留点不动的死边。
//
// 配色契约没有变：颜色只说温度这一件事（tempColor 三段插值），
// 读不到温度给中性灰而不是冷色 —— "绿"会被读成"温度正常"，而真相是"没读到"。
// 熔断是另一条独立事实，用状态词和一行说明表达，不把整块染红：
// 本机实测 36.0℃（正常）叠上历史熔断状态时，整块红会被读成"手机过热了"。
package com.entropy.overlay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.entropy.data.HudItem
import com.entropy.data.Palette
import com.entropy.data.ThemeMode
import com.entropy.hardware.HwSampler
import com.entropy.ui.theme.EntropyColors
import com.entropy.ui.theme.EntropyTheme
import com.entropy.ui.theme.LocalEntropyColors
import com.entropy.ui.theme.tempColor

/**
 * 灵动岛能对自己的窗口做的事。
 * 没有 onDrag：这一块的定位契约就是"永远在顶部中央"，能改的只有开与合。
 */
interface HudWindow {
    val expanded: Boolean
    fun setExpanded(value: Boolean)
    fun hide()
}

/**
 * 一帧显示数据。所有可空字段都保留 null 语义：
 * 读不到就显示"—"，绝不拿 0 顶替 —— "0 fps" 会被读成"卡在 0 帧"，
 * 而真相是"这台机器此刻没有可信的帧率来源"。
 */
@Immutable
data class HudReading(
    val tempC: Float? = null,
    val tempSource: String = "未取到温度",
    val zones: List<HwSampler.Zone> = emptyList(),
    val batteryC: Float? = null,
    val thermalStatus: Int = -1,
    val headroomS: Float? = null,
    val fps: Int? = null,
    val fpsNote: String = "未取到帧率",
    val cpuMaxMhz: Int? = null,
    val fused: Boolean = false,
    val alerting: Boolean = false,
    val alertAtC: Float = 60f,
)

/** 岛上的结论。word 给用户看，tone 是它的颜色，detail 解释这个结论从哪来 */
@Immutable
data class Verdict(val word: String, val tone: Color, val detail: String)

/** 岛要不要跟着"呼吸"：只有危险与熔断才让描边动起来，安全时纹丝不动 */
private fun Verdict.urgent() = word == "危险" || word == "熔断"

/**
 * 温度 -> 结论。
 *
 * 判据只用已经读到的数：提醒线（用户设定）、厂商 thermal 回调的状态、有没有熔断。
 * 距提醒线 4℃ 以内算"偏热"，这个余量是给升温速率留的 —— 温度是 1Hz 采的，
 * 高负载下 4℃ 大概只要十几秒就走完，等真越线才变色就晚了。
 */
fun verdictOf(r: HudReading, entropy: EntropyColors): Verdict = when {
    r.fused -> Verdict("熔断", entropy.warn, "调度已全量还原，120 秒后自动解锁")
    r.tempC == null -> Verdict("无读数", Color(0xFF8A8A93), r.tempSource)
    r.alerting || r.tempC >= r.alertAtC ->
        Verdict("危险", entropy.warn, "%.1f℃ 已达提醒线 %.0f℃".format(r.tempC, r.alertAtC))
    r.thermalStatus >= 3 ->
        Verdict("危险", entropy.warn, "厂商温控报出「${thermalStatusLabel(r.thermalStatus)}」")
    r.tempC >= r.alertAtC - 4f || r.thermalStatus == 2 ->
        Verdict("偏热", entropy.tempWarm, "距提醒线还有 %.1f℃".format(r.alertAtC - r.tempC))
    else ->
        Verdict("安全", entropy.tempCool, "%.1f℃ · 距提醒线还有 %.1f℃".format(r.tempC, r.alertAtC - r.tempC))
}

@Composable
fun SafeHud(
    win: HudWindow,
    reading: HudReading,
    items: List<HudItem>,
    themeMode: ThemeMode,
    accentArgb: Int,
    highlightArgb: Int,
    palette: Palette,
    capWidthPx: Int,
    capHeightPx: Int,
) {
    EntropyTheme(
        accentArgb = accentArgb,
        themeMode = themeMode,
        highlightArgb = highlightArgb,
        palette = palette,
    ) {
        val entropy = LocalEntropyColors.current
        val v = verdictOf(reading, entropy)
        // 有温度时颜色沿 tempColor 连续走（安全->偏热本来就是渐变），
        // 无读数与熔断这两件事不是温度，用结论色顶替
        val tint by animateColorAsState(
            targetValue = reading.tempC?.takeIf { !reading.fused }
                ?.let { tempColor(entropy, it) } ?: v.tone,
            animationSpec = tween(500), label = "islandTint")

        // 这里不再加状态栏 padding：窗口本身的 y 就已经压在状态栏/刘海下方，
        // 再让一次位会把 32dp 的胶囊挤成一条，字全被裁掉（实测就是这个原因）
        // 上界之外不给任何尺寸要求：这一层量多大，窗口就是多大
        val density = LocalDensity.current
        val capW = with(density) { capWidthPx.toDp() }
        val capH = with(density) { capHeightPx.toDp() }
        Box(Modifier.widthIn(max = capW).heightIn(max = capH), contentAlignment = Alignment.TopCenter) {
            AnimatedContent(
                targetState = win.expanded,
                transitionSpec = {
                    // 展开用"从小长大"而不是交叉淡入：灵动岛的读感是同一块东西在变形，
                    // 两帧各画一半会读成两块窗
                    if (targetState)
                        (scaleIn(initialScale = 0.84f, animationSpec = spring(
                            dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)) +
                            fadeIn(tween(160))) togetherWith fadeOut(tween(80))
                    else
                        fadeIn(tween(140)) togetherWith
                        (scaleOut(targetScale = 0.88f, animationSpec = tween(150)) + fadeOut(tween(110)))
                },
                label = "islandBody"
            ) { open ->
                if (open) Detail(win, reading, v, tint, items, capW, capH)
                else Compact(win, v, tint, reading, items)
            }
        }
    }
}

/** 岛的底板：内容量到多大就画多大，所以窗口即触摸区，不会在系统栏里留一块点不透的死边 */
@Composable
private fun Modifier.island(tint: Color, urgent: Boolean, expanded: Boolean): Modifier {
    val entropy = LocalEntropyColors.current
    val edge by animateColorAsState(
        targetValue = tint.copy(alpha = if (urgent) 0.95f else 0.5f),
        animationSpec = tween(450), label = "islandEdge")
    val fill by animateColorAsState(
        // 0xF2 而不是 0xE6：岛压在浅色游戏画面上时，90% 的黑底已经把结论词冲淡到读不清，
        // 而这块东西的全部价值就是那几个字
        targetValue = if (urgent) entropy.overlayScrim.copy(alpha = 0.97f)
                      else Color(0xF207070C),
        animationSpec = tween(450), label = "islandFill")
    // 收起态是整圆胶囊（用百分比而不是 999dp：窗口高度随分辨率变，
    // 固定 dp 半径在高分辨率上会变成"圆角矩形"而不是胶囊）
    // 展开态反过来，必须钉死成 dp：现在这张卡片是量内容出来的，
    // 按百分比算的话一张 257x279dp 的会得出 128dp 的圆角，
    // 最后一行字正好落在圆弧里被削掉半截
    val shape = if (expanded) RoundedCornerShape(20.dp) else RoundedCornerShape(percent = 50)
    return this
        .clip(shape)
        .background(fill)
        .border(1.dp, edge, shape)
}

/**
 * 一帧数据里取这一项的读数。
 * 取不到返回 null，由调用方显示"—"：把某一项悄悄藏掉等于改动用户勾选出来的布局，
 * 而"这一项此刻没有来源"本身就是岛要说的事。
 */
private fun HudItem.readout(r: HudReading): String? = when (this) {
    HudItem.FPS -> r.fps?.toString()
    HudItem.TEMP -> r.tempC?.let { "%.1f℃".format(it) }
    HudItem.CPU -> r.cpuMaxMhz?.let { "%.2fG".format(it / 1000.0) }
    HudItem.BATTERY -> r.batteryC?.let { "%.1f℃".format(it) }
    HudItem.THERMAL -> if (r.thermalStatus < 0) null else thermalStatusLabel(r.thermalStatus)
    HudItem.HEADROOM -> r.headroomS?.let { "%.0fs".format(it) }
    HudItem.ZONES -> if (r.zones.isEmpty()) null else
        r.zones.take(4).joinToString(" · ") { "${it.name} %.1f℃".format(it.celsius) }
}

/** 收起态那条 32dp 的胶囊塞不下热区明细那么长一串，那一类只在展开时出现 */
private val CompactItems = HudItem.entries - HudItem.ZONES

/**
 * 收起态：色点 + 结论词 + 勾出来的读数。
 *
 * 以前这里塞的是"36.5℃ · 距提醒线还有 6.5℃"那句依据。依据是展开态的事，
 * 收起态一眼要读的是"安不安全 + 我勾的那几项现在多少"；而且温度被单独勾成
 * 一格时，依据句里同一个数字会重复出现，更该让位。
 */
@Composable
private fun Compact(
    win: HudWindow,
    v: Verdict,
    tint: Color,
    r: HudReading,
    items: List<HudItem>,
) {
    val entropy = LocalEntropyColors.current
    val pulse by animateFloatAsState(
        targetValue = if (v.urgent()) 1f else 0.45f,
        animationSpec = tween(420), label = "compactEdge")
    val picked = items.filter { it in CompactItems }
    val readout = if (picked.isEmpty()) v.detail
        else picked.joinToString("  ") { "${it.label} ${it.readout(r) ?: "—"}" }
    Row(
        Modifier.island(tint, v.urgent(), false)
            .height(32.dp)
            .padding(horizontal = 11.dp)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { win.setExpanded(true) }, onLongPress = { win.hide() })
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(Modifier.size(7.dp).background(tint.copy(alpha = pulse), CircleShape))
        Text(v.word, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        // 不给 weight：这一条有多宽由"勾了几项、每项多长"决定，胶囊才跟着窗口一起收。
        // tnum 是等宽数字 —— 1Hz 刷新的读数里只有数字在换，等宽之后同一个位数不会
        // 因为 6 换成 1 就窄两像素，岛不会跟着呼吸
        Text(
            readout, color = entropy.onScrim.copy(alpha = 0.62f), fontSize = 9.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFeatureSettings = "tnum")
        )
    }
}

/**
 * 展开态：结论常驻在最上一行，下面跟着的就是用户勾出来的那几格读数。
 *
 * 排版从"两行大数 + 四行小字注脚"改成了一格一格的卡片。注脚那种写法把温度来源、
 * 帧率来源、热区、余量全挤成 8sp 的一坨，勾选生效与否根本看不出来；
 * 卡片是一格一项，勾了几格就是几格，用户改完偏好抬眼就能对上。
 * 整块可点（点一下收起、长按关闭）。
 *
 * 列数由可用宽度算出来，不写死两列。上一版就是写死 chunked(2)：两格 84dp 的下限
 * 加上间距和内边距，最少要 200dp，而控制台开着时岛被夹在两块面板中间，
 * 上界一度只有 120dp —— 内容比窗口宽，外面那层 clip 把右边一整列连字一起削掉，
 * 这就是"展开式渲染异常"的真凶。宽度不够就降成一列，宽屏上放得下就排三列。
 * 高度方向同理不硬裁：给上界装得下就正好，装不下改成能上下滚，
 * 因为被滚走的仍然是用户自己勾出来的读数，藏掉任何一格都等于改了他的布局。
 */
@Composable
private fun Detail(
    win: HudWindow,
    reading: HudReading,
    v: Verdict,
    tint: Color,
    items: List<HudItem>,
    capW: Dp,
    capH: Dp,
) {
    val on = LocalEntropyColors.current.onScrim
    // 底板、点击区、内容框是同一块：底板跟着内容收，点哪儿都算点在岛上
    Box(
        Modifier.island(tint, v.urgent(), true)
            .heightIn(max = capH)
            .verticalScroll(rememberScrollState())
            .pointerInput(Unit) {
                detectTapGestures(onTap = { win.setExpanded(false) }, onLongPress = { win.hide() })
            }
    ) {
        Column(
            Modifier.padding(start = 13.dp, end = 13.dp, top = 10.dp, bottom = 13.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(tint, CircleShape))
                Spacer(Modifier.width(7.dp))
                Text(v.word, color = tint, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                // 依据紧跟结论词：这一行回答"凭什么说安全"，不需要用户再往下找
                Text(v.detail, color = on.copy(alpha = 0.55f), fontSize = 9.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val tiles = items.filter { it != HudItem.ZONES }
            if (tiles.isNotEmpty()) {
                // 一格最少 84dp（Tile 里的下限），列间距 6dp，整块左右内边距 26dp
                val cols = (((capW - 26.dp + 6.dp) / (TileW + 6.dp)).toInt()).coerceIn(1, 3)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    tiles.chunked(cols).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { item ->
                                // 一格一行字，宽度自己量；84dp 的下限是让勾一项和勾四项
                                // 的格子看着一样大，而不是"帧率"格窄、"电池温度"格宽
                                Tile(item.label, item.readout(reading) ?: "—",
                                    if (item == HudItem.TEMP) tint else on)
                            }
                            // 最后一行不满时补空位：否则"温度"这格会跟着剩下的字数缩窄，
                            // 同一个岛里同一批格子忽宽忽窄，看着就像排版坏了
                            repeat(cols - row.size) { Spacer(Modifier.width(TileW)) }
                        }
                    }
                }
            }
            // 热区明细只在勾选时出现：它天然是长句，挤在卡片下面而不是占一格
            if (HudItem.ZONES in items) HudItem.ZONES.readout(reading)?.let {
                Text("热区 $it", color = on.copy(alpha = 0.5f),
                    fontSize = 8.sp, lineHeight = 11.sp, maxLines = 2,
                    modifier = Modifier.widthIn(max = (capW - 26.dp).coerceAtMost(230.dp)))
            }
            val notes = buildList {
                if (HudItem.TEMP in items || HudItem.ZONES in items) add("温度来源 ${reading.tempSource}")
                if (HudItem.FPS in items) add("帧率来源 ${reading.fpsNote}")
                if (HudItem.HEADROOM in items || HudItem.BATTERY in items)
                    add("提醒线 %.1f℃".format(reading.alertAtC))
            }
            if (notes.isNotEmpty()) Text(notes.joinToString(" · "), color = on.copy(alpha = 0.42f),
                fontSize = 8.sp, lineHeight = 11.sp, maxLines = 3,
                modifier = Modifier.widthIn(max = (capW - 26.dp).coerceAtMost(230.dp)))
            Text("点一下收起 · 长按关闭",
                color = on.copy(alpha = 0.3f), fontSize = 8.sp, maxLines = 1, lineHeight = 10.sp)
        }
    }
}

/** 一格的宽度下限，排版算列数时要用同一个数 */
private val TileW = 84.dp

/**
 * 一格读数。宽度有个 84dp 的下限：只勾一项时那格不该窄成一小块，
 * 勾满时又各自跟着自己的字数走，岛整体就是"有几格多宽"。
 * 颜色仍然只归温度（tint 只对 TEMP 生效）—— 帧率给红会被读成"帧率出问题了"。
 */
@Composable
private fun Tile(label: String, value: String, color: Color) {
    val on = LocalEntropyColors.current.onScrim
    Column(
        Modifier.widthIn(min = TileW)
            .clip(RoundedCornerShape(10.dp))
            .background(on.copy(alpha = 0.07f))
            .padding(horizontal = 9.dp, vertical = 6.dp)
    ) {
        Text(value, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            style = TextStyle(fontFeatureSettings = "tnum"))
        Text(label, color = on.copy(alpha = 0.45f), fontSize = 8.sp, maxLines = 1)
    }
}

/** PowerManager.THERMAL_STATUS_* 的人话。-1 = 这台机器（API<29）没给 */
fun thermalStatusLabel(status: Int): String = when (status) {
    -1 -> "无（需 API 29+）"
    0 -> "正常"
    1 -> "轻度"
    2 -> "中等"
    3 -> "严重"
    4 -> "危险"
    5 -> "紧急"
    6 -> "即将关机"
    else -> "未知($status)"
}
