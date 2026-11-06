// File: app/src/main/java/com/entropy/overlay/VolumeKeyGuide.kt
//
// 收起控制台后弹一次"这两颗键各管什么"的动画。
//
// 为什么不放一个"知道了"按钮：整块卡片本身就是按钮 —— 点哪儿都收起。
// 一块会自己走的提示上再挂一颗按钮，等于让用户去点一个八秒后就不存在的东西。
//
// 窗口必须接收触摸（见 OverlayService）：系统会给"穿透型"浮窗强加 80%
// 不透明度，底板于是挡不住底下的游戏画面。代价是这块矩形在 8 秒内吃掉一次
// 点击，那一下正好用来关掉它。
//
// 画面只有线条，三段按顺序读：一完整的手机 → 推到音量键那一侧 → 那颗键按一下。
// 上一版把手机侧过去画厚度、在屏里放一块会展开的控制台、再叠波纹与扫光，
// 每一样都要用户先解码一次"这是什麼"，才轮到"按哪里"。线条版把解码成本去掉，
// 只留轮廓、两枚键和一次按压。
//
// 文案不写"截获音量键"：熵听的是系统音量值的变化，音量本身照常跟着改，
// footer 那行小字就是把这个代价说清楚。
package com.entropy.overlay

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** 演示的焦点：0 = 没有键在被按，1 = 上键（加），2 = 下键（减） */
private const val FocusNone = 0
private const val FocusUp = 1
private const val FocusDown = 2

/**
 * 引导动画本体。窗口尺寸由 OverlayService 给死（ComposeView 在 WindowManager 里
 * 用 WRAP_CONTENT 会被测成 0x0），这里只管把内容填满。
 *
 * 只有三个量：appear 管入场，zoom 管"镜头"从整机推到按键，pressUp / pressDown 管那一下。
 * 镜头是画在 Canvas 里的（缩放 + 平移），不是 graphicsLayer 的缩放 —— 因为线宽要按
 * 缩放的反比补回去，放大以后还是同一支笔画出来的粗细；套在 layer 上做不到这件事。
 */
@Composable
fun VolumeKeyGuide(onDismiss: () -> Unit = {}) {
    val scheme = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val appear = remember { Animatable(0f) }
    // 0 = 看整机，1 = 贴到音量键那一侧
    val zoom = remember { Animatable(0f) }
    val pressUp = remember { Animatable(0f) }
    val pressDown = remember { Animatable(0f) }
    var focus by remember { mutableIntStateOf(FocusNone) }
    // 系统把动画时长调成 0 是用户的选择：那就停在信息量最大的那一帧——
    // 按键特写、加减号都在，两行图例照样把话说完，不再排一条永远不会走完的时间线
    val still = remember {
        runCatching {
            Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f) < 0.01f
    }

    LaunchedEffect(still) {
        if (still) {
            appear.snapTo(1f)
            zoom.snapTo(1f)
            return@LaunchedEffect
        }
        appear.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
        while (true) {
            // ---- 第一段：一台完整的手机 ---- //
            zoom.animateTo(0f, tween(260, easing = FastOutSlowInEasing))
            delay(940)
            // ---- 第二段：推到按键那一侧 ---- //
            zoom.animateTo(1f, tween(640, easing = FastOutSlowInEasing))
            delay(200)
            // ---- 第三段：按一下 ---- //
            focus = FocusUp
            pressUp.animateTo(1f, tween(80))
            delay(600)
            pressUp.animateTo(0f, tween(230))
            delay(260)
            focus = FocusDown
            pressDown.animateTo(1f, tween(80))
            delay(600)
            pressDown.animateTo(0f, tween(230))
            delay(420)
            focus = FocusNone
            // 拉回整机留一拍：这一拍是"刚才那两枚键就在这台手机上"，
            // 不拉回去的话特写就是全部，用户记不住位置
            zoom.animateTo(0f, tween(560, easing = FastOutSlowInEasing))
            delay(760)
        }
    }

    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                // 入场只动缩放，不动整卡 alpha：给整张卡片套 alpha 层会把底板
                // 一起带到半透明（实测 0.8 时底下的正文能读出来），而这块动画
                // 是压在游戏画面上的，底板必须是死的
                .graphicsLayer {
                    val s = 0.94f + 0.06f * appear.value
                    scaleX = s
                    scaleY = s
                    cameraDistance = 14f * density
                    alpha = 1f
                }
                .clip(RoundedCornerShape(20.dp))
                .background(scheme.surface)
                .border(1.dp, scheme.outline.copy(alpha = 0.30f), RoundedCornerShape(20.dp))
        ) {
            Column(
                Modifier.fillMaxSize().alpha(appear.value),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(Modifier.size(width = 196.dp, height = 112.dp)) {
                    // clipToBounds 不是洁癖：Canvas 默认不裁，镜头推近时机身会画出这块 112dp
                    // 的取景框、压在下面两行图例上。裁掉之后"放大的是这一小块"才读得出来
                    Canvas(Modifier.fillMaxSize().clipToBounds()) {
                        drawPhoneGuide(
                            canvas = size, accent = scheme.primary,
                            muted = scheme.onSurfaceVariant, ink = scheme.onSurface,
                            paper = scheme.surface,
                            pose = Pose(zoom.value, pressUp.value, pressDown.value),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                KeyRow(
                    label = "音量加键", action = "打开控制台",
                    on = focus == FocusUp, idle = focus == FocusNone,
                )
                Spacer(Modifier.height(4.dp))
                KeyRow(
                    label = "音量减键", action = "关闭控制台",
                    on = focus == FocusDown, idle = focus == FocusNone,
                )
                Spacer(Modifier.height(8.dp))
                // 可点的东西得说出来：这块卡片一出现就压在游戏上，
                // 不写明的话用户只会等它自己走。音量照常变这件事也在这里讲完
                Text(
                    "点一下收起 · 音量本身照常变",
                    fontSize = 9.sp,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.60f),
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

/** 画一帧要用的全部姿态 */
private data class Pose(
    val zoom: Float,
    val pressUp: Float,
    val pressDown: Float,
)

/**
 * 手机轮廓 + 侧边两枚键，加一次镜头推进。
 *
 * 镜头做法：以两枚键中间那点为支点放大，再把那个点平移回画布正中。
 * 于是"推近"读起来是相机在动，而不是手机在变大——机身会自己走出画框被裁掉。
 *
 * 线宽一律除以缩放，所以推近之后线条不会变粗；变粗的话那一侧的键看起来是
 * 另一个更重的物体，而不是同一台机器被看近了。
 *
 * 推进之前先在被推的那块区域画一个细框（alpha 随 zoom 淡出），作用是让第二段的
 * 落点有预告：没有它，画面直接放大，用户要先弄清"放大的是哪一块"。
 *
 * 所有尺寸都按画布最短边换算，窗口尺寸以后改了图形也不会散架。
 */
private fun DrawScope.drawPhoneGuide(
    canvas: Size,
    accent: Color,
    muted: Color,
    ink: Color,
    paper: Color,
    pose: Pose,
) {
    val unit = canvas.minDimension
    val bodyW = unit * 0.40f
    val bodyH = bodyW * 1.78f
    val cx = canvas.width * 0.5f
    val cy = canvas.height * 0.5f
    val left = cx - bodyW / 2f
    val top = cy - bodyH / 2f
    val corner = bodyW * 0.17f

    // 音量键贴在右侧轮廓外一点点：正面朝人时它们就该看得见，
    // 所以不做侧翻、不画厚度。键本身画成一颗胶囊，宽度要够加减号待得进去——
    // 符号压在胶囊外面会读成删除线，而不是"这一颗是加键"
    val keyT = unit * 0.052f
    val keyH = unit * 0.100f
    // 间隔要大于胶囊两端的圆帽半径之和，否则推近以后两颗键会糊成一粒，
    // 而"这是两颗键"正是这块动画唯一要交代的事
    val keyGap = unit * 0.072f
    val keyX = left + bodyW + unit * 0.030f
    val upY = top + bodyH * 0.30f
    val downY = upY + keyH + keyGap

    val scale = 1f + 1.85f * pose.zoom
    val pivot = Offset(keyX, (upY + downY + keyH) / 2f)
    val line = unit * 0.013f / scale

    translate(cx - pivot.x, cy - pivot.y) {
        scale(scale, pivot) {
            // 机身
            drawRoundRect(
                color = muted.copy(alpha = 0.62f),
                topLeft = Offset(left, top),
                size = Size(bodyW, bodyH),
                cornerRadius = CornerRadius(corner, corner),
                style = Stroke(width = line),
            )
            // 屏幕只留一条内圈线：再多的内容就把"这是一台手机"说得太满
            val inset = bodyW * 0.085f
            drawRoundRect(
                color = muted.copy(alpha = 0.20f),
                topLeft = Offset(left + inset, top + inset),
                size = Size(bodyW - inset * 2f, bodyH - inset * 2f),
                cornerRadius = CornerRadius(corner * 0.72f, corner * 0.72f),
                style = Stroke(width = line * 0.8f),
            )

            // 落点框：推进前指着这一侧，推到位就淡掉。框要同时含住轮廓边和两颗键，
            // 只框住键的话预告的是"键"，而镜头给的其实是"机身这一侧"
            val hint = (1f - pose.zoom).coerceIn(0f, 1f)
            if (hint > 0.02f) {
                val m = unit * 0.042f
                val bx = keyX - m - unit * 0.010f
                drawRoundRect(
                    color = accent.copy(alpha = 0.42f * hint),
                    topLeft = Offset(bx, upY - m),
                    size = Size(keyT * 0.5f + m * 2f + unit * 0.010f, (downY + keyH) - upY + m * 2f),
                    cornerRadius = CornerRadius(unit * 0.03f, unit * 0.03f),
                    style = Stroke(width = line * 0.9f),
                )
            }

            listOf(Key(upY, pose.pressUp, true), Key(downY, pose.pressDown, false)).forEach { k ->
                // 按下去向机身内缩一点并同时变亮：只变亮看起来像在闪，不像被按到
                val x = keyX - unit * 0.011f * k.press
                drawLine(
                    color = if (k.press > 0.01f) accent.copy(alpha = 0.55f + 0.45f * k.press)
                    else muted.copy(alpha = 0.62f),
                    start = Offset(x, k.top - keyT * 0.5f),
                    end = Offset(x, k.top + keyH - keyT * 0.5f),
                    strokeWidth = keyT,
                    cap = StrokeCap.Round,
                )
                val midY = k.top + keyH / 2f - keyT * 0.5f
                // 加减号：镜头推近到读得清的程度才画，而且收在胶囊里面
                if (pose.zoom > 0.5f) drawPlusMinus(
                    center = Offset(x, midY),
                    arm = keyT * 0.30f,
                    color = when {
                        k.press > 0.01f -> paper
                        else -> ink.copy(alpha = 0.72f * ((pose.zoom - 0.5f) / 0.5f))
                    },
                    plus = k.plus,
                    stroke = line * 0.85f,
                )
                // 方向指示：上加下减，只在按下去那一瞬出现，指在胶囊外侧
                if (k.press > 0.01f) drawChevron(
                    center = Offset(x + keyT * 0.5f + unit * 0.052f, midY),
                    size = unit * 0.026f, up = k.plus,
                    color = accent.copy(alpha = 0.85f * k.press),
                    stroke = line,
                )
            }
        }
    }
}

/** 一枚键在这一帧里的状态：位置、按压力度、是不是加键 */
private data class Key(val top: Float, val press: Float, val plus: Boolean)

/** 键上的加 / 减号：两条互相垂直的短棒，比字形稳（Canvas 里画文字还要带 Painter，不值） */
private fun DrawScope.drawPlusMinus(
    center: Offset, arm: Float, color: Color, plus: Boolean, stroke: Float,
) {
    if (color.alpha < 0.02f) return
    drawLine(color, Offset(center.x - arm, center.y), Offset(center.x + arm, center.y),
        strokeWidth = stroke, cap = StrokeCap.Round)
    if (plus) drawLine(color, Offset(center.x, center.y - arm), Offset(center.x, center.y + arm),
        strokeWidth = stroke, cap = StrokeCap.Round)
}

/** 按键外侧的方向指示：上加键指上、下减键指下，按下去那一瞬才出现 */
private fun DrawScope.drawChevron(
    center: Offset, size: Float, up: Boolean, color: Color, stroke: Float,
) {
    val dir = if (up) -1f else 1f
    val p = Path().apply {
        moveTo(center.x - size, center.y - dir * size * 0.55f)
        lineTo(center.x, center.y + dir * size * 0.55f)
        lineTo(center.x + size, center.y - dir * size * 0.55f)
    }
    drawPath(p, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/**
 * 图例一行：左边是键名，右边是它干的事。
 *
 * 演示到哪一步，那一行就整行亮起（底色 + 文字 + 左边的指示点一起动），
 * 另一行退到背景。两行同时看得见才叫"加=开、减=关"，只亮一行等于让用户自己猜另一颗。
 */
@Composable
private fun KeyRow(label: String, action: String, on: Boolean, idle: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val bg by androidx.compose.animation.animateColorAsState(
        targetValue = if (on) scheme.primary.copy(alpha = 0.16f) else Color.Transparent,
        animationSpec = tween(200), label = "rowBg")
    val ink by androidx.compose.animation.animateColorAsState(
        targetValue = when {
            on -> scheme.primary
            idle -> scheme.onSurface
            else -> scheme.onSurfaceVariant
        }, animationSpec = tween(200), label = "rowInk")
    val dot by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (on) 1f else 0.45f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 480f), label = "rowDot")
    Row(
        Modifier.width(188.dp).height(24.dp)
            .clip(RoundedCornerShape(8.dp)).background(bg)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(5.dp * dot).clip(RoundedCornerShape(999.dp)).background(ink))
        Spacer(Modifier.width(7.dp))
        Text(label, color = ink, fontSize = 11.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
        Spacer(Modifier.width(6.dp))
        Text(action, color = ink.copy(alpha = if (on) 0.9f else 0.55f), fontSize = 11.sp,
            fontWeight = if (on) FontWeight.Medium else FontWeight.Normal)
    }
}
