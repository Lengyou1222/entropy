// File: app/src/main/java/com/entropy/overlay/EntropyOverlay.kt
//
// 悬浮控制台的两套形态。
//
// 形态一 BALL：可拖动、可捏合缩放的"原子"。核 = 当前温度色，三条电子轨道 + 电子
//   沿轨道跑，轨道本身再整体慢转。基准直径 44dp —— 游戏里真正需要的是"随时能点开"，
//   不是"随时能看见"，再大就会吃掉准星附近那块区域。
//   点开是它自己的紧凑控制台（档位 + 读数 + 切形态），不是第二块面板。
//
// 形态二 PANEL：贴屏幕左侧的控制台，标签栏在左边竖着一排。
//   左侧栏是为了把"我在哪一节"变成一眼看得见的东西：以前十一张卡片从上到下排成一列，
//   改个温控阈值要先把画质和渲染后端全部滚过去，位置感全靠记忆。
//   现在六节，每节两到三张卡，切节有横向位移。
//
// 窗口尺寸由 OverlayService 给确定值，这里只按 chrome 留出的系统栏高度排版。
//
// 为什么用 ComposeView + WindowManager 而不是 SurfaceView：
//  ComposeView 能直接吃 MaterialTheme，弹簧动画、圆角、阴影都是现成的；
//  代价是要手动把生命周期与 SavedStateRegistry 挂到这个 View 上，
//  否则 Recomposer 解析不到 LifecycleOwner，addView 当场 FATAL
//  （IllegalStateException: ViewTreeLifecycleOwner not found，处理见 OverlayService）。
package com.entropy.overlay

import android.content.Context
import android.provider.Settings
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.entropy.data.EntropyStore
import com.entropy.data.Feature
import com.entropy.data.OverlayForm
import com.entropy.data.ThemeMode
import com.entropy.data.UiState
import com.entropy.hardware.HardwareBridge
import com.entropy.hardware.SocFamily
import com.entropy.shared.Proto
import com.entropy.ui.components.EntropyMotion
import com.entropy.ui.components.SocBadge
import com.entropy.ui.components.SpringSliderTrack
import com.entropy.ui.components.popScale
import com.entropy.ui.theme.EntropyTheme
import com.entropy.ui.theme.LocalEntropyColors
import com.entropy.ui.theme.tempColor
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** 窗口本身能做的事：形态、展开、拖动、唤出 App。其余动作全部走 EntropyStore，两边共用 */
interface OverlayWindow {
    val form: OverlayForm
    val isExpanded: Boolean
    fun setForm(which: OverlayForm)
    fun toggleForm()
    fun setExpanded(value: Boolean)
    fun launchUfo()
    fun openApp()
    fun hide()
    fun onDrag(dx: Float, dy: Float)
    /** 双指捏合：delta 是相邻事件之间的缩放比例，由服务累积并限幅 */
    fun onScale(delta: Float)
    /** 灵动岛（安全显示）开关。和控制台是两块窗，互不跟随 */
    fun toggleHud()
}

/** 系统栏留白（dp）。窗口开了 LAYOUT_NO_LIMITS，不让位的话首行卡片和底部操作栏都会被压住 */
data class OverlayChrome(val topDp: Float, val bottomDp: Float, val cutoutDp: Float = 0f)

/**
 * 悬浮窗真正交给 WindowManager 的那一层：里面只有一块 ComposeView，本身不画东西，
 * 只把两个触控信号报给服务 —— 这一下按在我身上（DOWN），或者按在了我外面（OUTSIDE）。
 * 控制台和灵动岛各一块。
 *
 * 为什么要多这一层，而不是直接给 ComposeView 挂 setOnTouchListener：
 * ComposeView 是 ViewGroup，子节点（AndroidComposeView）一旦吃掉按下，
 * ViewGroup.dispatchTouchEvent 就再也不会走到 View.dispatchTouchEvent，
 * 而 OnTouchListener 只在后者里被回调。实测那样挂只能收到 ACTION_OUTSIDE，
 * DOWN 一次都没有 —— "这一下落在谁身上"就问不出来了。
 * ComposeView 本身是 final，也继承不了。
 *
 * 套一层普通 FrameLayout 就能在事件下发给 ComposeView 之前先看一眼，然后原样交下去，
 * 面板里的滑杆、卡片、滚动一概不受影响。
 *
 * ACTION_OUTSIDE 不往下传：它只有 down、没有配对的 up，坐标实测恒为 (0,0)，
 * 交给 Compose 只会在左上角留下一个永远抬不起来的指针。
 *
 * 上报的是事件的 downTime 而不是收到的时刻：一次按下会同时派给"被点的窗"和
 * "盯着窗外那块窗"，两条通道谁先到应用手里并不固定（同一进程同一条 Looper，
 * 实测两种顺序都出现过）。同一次手势的 downTime 两边一致，拿它对齐才靠得住。
 *
 * 双击也在这里数，三种形态都一样，不在 Compose 里挂 detectTapGestures(onDoubleTap)：
 * 面板整块内容都在 verticalScroll 里，而滚动容器对每一次按下都无条件消费
 * （awaitFirstDown 收尾必 consume），挂在祖先上的检测器只看得到"已被消费"的 down，
 * 双击在面板里就永远不成立。小球这边则是另一回事：第一下就被 detectTapGestures 的
 * onTap 吃掉去展开卡片，内容和窗口尺寸一起换掉，第二下来到时已经没有配对关系可数了。
 * View 层这一圈在事件下发给 ComposeView 之前，看得见所有按下，也不用抢子节点的手势。
 *
 * 分工要说死：**双击只归这里，单击与长按只归 Compose**。两边都写 onDoubleTap 的话，
 * 一次双击会触发两回 toggleForm —— 换个样式又换回原样，看起来就是"双击没反应"。
 */
internal class OverlayRootView(
    context: Context,
    /** 这一下真的按在自己身上 */
    private val onInsideDown: (Long) -> Unit,
    /** 这一下按在了自己外面 */
    private val onOutsideTap: (Long) -> Unit,
    /** 同一块地方连着两下：切形态用 */
    private val onInsideDoubleTap: () -> Unit = {},
) : FrameLayout(context) {

    val content: ComposeView = ComposeView(context)

    private val touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val doubleTapSlopPx = 60f * resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var multiPointer = false
    private var lastUpAt = 0L
    private var lastUpX = 0f
    private var lastUpY = 0f

    init {
        addView(content, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /**
     * 换形态之后，之前记下的那一下作废。
     *
     * 同一块视图在小球、卡片、面板之间是复用的，不清的话"卡片上第二下"会和稍后面板上
     * 的任意一下拼成一次双击 —— 用户只是点了两个不相干的地方，控制台却自己缩回去了。
     *
     * 只有真的换了形态才清。小球点开成紧凑控制台不算换形态，那一次展开正是双击的第一下，
     * 把它抹掉的话双击切形态永远配不成对（这里以前就是错的，改之前实测过）。
     */
    fun forgetTap() {
        lastUpAt = 0L
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean =
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                multiPointer = false
                onInsideDown(event.downTime)
                super.dispatchTouchEvent(event)
            }
            // 第二根手指落下就不是"点"了：捏合的收尾同样是一个 UP，不能混进双击计数
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiPointer = true
                super.dispatchTouchEvent(event)
            }
            MotionEvent.ACTION_UP -> {
                // 抬起的位置离开过按下位置就算拖动：拖小球、拉滑杆收尾都是 UP，不能当点。
                // 按住太久也不能算点 —— 长按是另一套手势（收起 / 隐藏），
                // 让它参与配对的话，"长按收起"之后再随手点一下就会凭空换一次形态。
                val dx = event.x - downX
                val dy = event.y - downY
                val quick = event.eventTime - event.downTime <= TapMaxMs
                if (!multiPointer && quick && sqrt(dx * dx + dy * dy) <= touchSlopPx) {
                    val ex = event.x - lastUpX
                    val ey = event.y - lastUpY
                    val near = lastUpAt > 0L && sqrt(ex * ex + ey * ey) <= doubleTapSlopPx
                    if (near && event.eventTime - lastUpAt <= DoubleTapMs) {
                        lastUpAt = 0L
                        onInsideDoubleTap()
                    } else {
                        lastUpAt = event.eventTime
                        lastUpX = event.x
                        lastUpY = event.y
                    }
                }
                super.dispatchTouchEvent(event)
            }
            MotionEvent.ACTION_OUTSIDE -> {
                onOutsideTap(event.downTime)
                true
            }
            else -> super.dispatchTouchEvent(event)
        }

    private companion object {
        /** 两下之间最多隔这么久。和平台的 doubleTapTimeout 同档，比它稍松一点好按中 */
        const val DoubleTapMs = 320L

        /**
         * 一下按多久以内还算"点"。平台的长按阈值是 400ms
         * （ViewConfiguration.getLongPressTimeout，Compose 的长按手势用的就是它），
         * 按满这个时长的手势归长按那一套，不该再来参与双击配对。
         */
        const val TapMaxMs = 400L
    }
}

/**
 * 收到 outside 通知之后，再等这么久才决定要不要收。
 * 一次按下派给两块窗的顺序不固定，先到的那个可能还没等到另一个记下这一笔；
 * 110ms 是把调度抖动和一次布局都算进去的余量。
 */
internal const val OutsideSettleMs = 110L

@Composable
fun OverlayRoot(
    win: OverlayWindow,
    store: EntropyStore,
    state: UiState,
    themeMode: ThemeMode,
    form: OverlayForm,
    expanded: Boolean,
    ballScale: Float,
    chrome: OverlayChrome,
    exiting: Boolean = false,
) {
    val prefs = store.prefs
    EntropyTheme(
        accentArgb = prefs.accentColor,
        themeMode = themeMode,
        dynamicColor = true,
        highlightArgb = store.highlightColor.intValue,
        palette = store.palette.value,
    ) {
        // 进出场都在这一层做，小球与面板共用一条进度。
        //
        // 为什么不是 AnimatedContent 交叉淡入：两种形态的窗口坐标系根本不是同一块 ——
        // 面板从 (0,0) 起整屏高，小球坐在用户拖到的位置上。服务改窗口尺寸是立刻生效的
        // （WindowManager 没有"把矩形动画过去"这回事），同帧画两份内容只会让小球
        // 先跳到屏幕中间再飞回去。所以只做"新内容自己进场"。
        // 这块窗口小球和面板共用，进度必须按形态重建：小球进场时它已经停在 1，
        // 只调 animateTo(1f) 会在同一帧就完成 —— 左半边直接"啪"地出现，右半边是新建窗口
        // 从零起步慢慢拉开，两边看着就是两套东西。右侧小面板本来每次都是新窗口、从 0 起，
        // 这一句把左边拉回同一个起点，两边才真的逐帧同步。
        val stage = remember(form) { Animatable(0f) }
        LaunchedEffect(form, exiting) {
            stage.animateTo(if (exiting) 0f else 1f, panelStageSpec(exiting))
        }
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val p = stage.value
                alpha = if (form == OverlayForm.PANEL) panelStageAlpha(p) else p
                if (form == OverlayForm.PANEL) {
                    // 贴左缘的东西从左缘长出来：origin 压在最左那条边上，只有右边界往外走。
                    // 不平移 —— 整块平移是推抽屉，两块方向还相反，看着就是两个不相干的东西
                    scaleX = PanelStretchFrom + (1f - PanelStretchFrom) * p
                    // 显式写平：graphicsLayer 的 lambda 只改自己碰到的属性，小球那一档留下的
                    // scaleY 会一路带进面板，竖直方向跟着一起缩，读起来像"放大"而不是"拉开"
                    scaleY = 1f
                    transformOrigin = TransformOrigin(0f, 0.5f)
                } else {
                    val s = 0.72f + 0.28f * p
                    scaleX = s
                    scaleY = s
                }
            }
        ) {
            when (form) {
                OverlayForm.PANEL -> PanelConsole(win, store, state, chrome)
                OverlayForm.BALL -> BallConsole(win, store, state, expanded, ballScale)
            }
        }
    }
}

/** 退场动画时长。服务侧等同样这段时间再 removeView，改这里要一起改 */
const val ExitAnimMs = 190

/**
 * 进场时长。比退场长一截是故意的：展开要有"被拉开"的过程感，收回去则要干脆，
 * 两边共用同一条曲线才不会让人看出这是两套代码在动。
 */
private const val EnterAnimMs = 420

/** 面板从自己那条屏幕边伸出来时的起始宽度比例。收得太狠会像放电影开场，太轻又看不出在动 */
private const val PanelStretchFrom = 0.78f

/**
 * 伸展专用的曲线：两头都收速，中段才走量。
 *
 * 不用 EntropyMotion.EaseOut —— 那条起步极快（前两成时间就走完七成），22% 的宽度差
 * 摊在上面等于两三帧到位，看着就是闪一下，这正是"生硬"的来处。这里起步斜率约 0.06、
 * 落位斜率 0，读起来才是从边上被拉开、自己停住。
 */
private val PanelStretchEase = CubicBezierEasing(0.34f, 0.02f, 0.18f, 1f)

/**
 * 两块面板共用的进度曲线。
 *
 * 用 tween + 同一条贝塞尔而不是 spring：弹簧的 stiffness 一高就是"啪"一下弹到位；
 * 弹簧还有一帧一帧的数值差异，两块面板同时动时会出现一边先到位，
 * 同一条 tween 曲线才能保证两边逐帧完全同步。
 */
private fun panelStageSpec(exiting: Boolean): AnimationSpec<Float> =
    if (exiting) tween(ExitAnimMs, easing = EntropyMotion.Ease)
    else tween(EnterAnimMs, easing = PanelStretchEase)

/**
 * 透明度走在几何前面一点。整块板子一边长大一边才从全透明里浮出来，
 * 读到的是"闪了一下"；alpha 先跟上、宽度慢慢拉开，才是"从边上伸出来"。
 */
private fun panelStageAlpha(p: Float): Float = (0.25f + 0.75f * p).coerceIn(0f, 1f)

// ------------------------------------------------------------------ //
// 形态一：原子小球                                                      //
// ------------------------------------------------------------------ //

@Composable
private fun BallConsole(
    win: OverlayWindow,
    store: EntropyStore,
    state: UiState,
    expanded: Boolean,
    ballScale: Float,
) {
    val entropy = LocalEntropyColors.current
    val tempC = state.telemetry?.maxTempC
    val fused = state.safety.fused
    val tint = if (fused) entropy.warn else tempColor(entropy, tempC ?: 30f)

    // 弹簧参数：dampingRatio 0.55 会有轻微过冲，视觉上就是"有弹性的球"
    val pop by animateFloatAsState(
        targetValue = if (expanded) 1.08f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "ballPop"
    )

    Box(Modifier.pointerInput(Unit) {
        // 拖拽与捏合共用一个 transform 检测器：单指 pan 走 onDrag，双指 zoom 走 onScale。
        // 单击/双击/长按交给内层的 detectTapGestures —— 两个 pointerInput 并行运行，
        // 超过触摸阈值时 transform 会先消费掉，tap 自然不会误触发，这正是想要的互斥。
        detectTransformGestures { _, pan, zoom, _ ->
            if (pan != Offset.Zero) win.onDrag(pan.x, pan.y)
            if (zoom != 1f) win.onScale(zoom)
        }
    }, contentAlignment = Alignment.Center) {
        if (!expanded) {
            Box(
                Modifier
                    .size((44 * ballScale * pop).dp)
                    .pointerInput(Unit) {
                        // 这里不写 onDoubleTap：双击归 OverlayRootView 那一层数（见那里的注释）。
                        // 第一下由 onTap 展开卡片，第二下在 View 层配成对，才换得成整面控制台。
                        detectTapGestures(
                            onTap = { win.setExpanded(true) },
                            onLongPress = { win.hide() }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Atom(tint = tint, alert = fused, modifier = Modifier.fillMaxSize())
                // 数字只在球够大时画：1.0 倍率下核径只有约 13dp，塞两个汉字会变成糊的一团
                Text(
                    if (ballScale >= 1.15f && tempC != null) "%.0f".format(tempC) else "熵",
                    color = entropy.onScrim, fontSize = (10 * ballScale).sp, fontWeight = FontWeight.Bold
                )
            }
        } else {
            BallConsoleCard(win, store, state, tint)
        }
    }
}

/** 展开态：小球自己的紧凑控制台。宽度跟着窗口走，窗口由服务给 184dp */
@Composable
private fun BallConsoleCard(
    win: OverlayWindow,
    store: EntropyStore,
    state: UiState,
    tint: Color,
) {
    val entropy = LocalEntropyColors.current
    val tempC = state.telemetry?.maxTempC
    val appear by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 320f), label = "ballCard")
    Column(
        Modifier.fillMaxSize().alpha(appear)
            .clip(RoundedCornerShape(22.dp))
            // 和面板同一档不透明度：0.90 时底下 App 的正文能整行穿过来，卡片里的 10sp 小字直接读不了
            .background(entropy.overlayScrim.copy(alpha = 0.97f))
            .padding(11.dp)
            // 卡片底下这行提示写着"双击切控制台"，那双击就得真的管用。
            // 计数在 OverlayRootView 那一层（小球、卡片、面板三种形态都归它），这里只留长按：
            // 同一件事两层各写一遍的话，一次双击会触发两回 toggleForm，等于换了又换回来。
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { win.setExpanded(false) }
                )
            }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Atom(tint = tint, alert = state.safety.fused, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(7.dp))
            Column {
                Text("熵 · ${store.level.value.label}", color = entropy.onScrim,
                    fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                // 小球卡片只有这么宽，带字的徽标留给大面板，这里只放图形那半枚
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tempC?.let { "%.1f℃".format(it) } ?: "无读数",
                        color = tint, fontSize = 10.sp, maxLines = 1)
                    val fam = store.bridge.profile.family
                    if (fam != SocFamily.UNKNOWN) {
                        Spacer(Modifier.width(5.dp))
                        SocBadge(fam, withText = false)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text("收起", color = entropy.onScrim.copy(alpha = 0.6f), fontSize = 10.sp,
                modifier = Modifier.pointerInput(Unit) {
                    detectTapGestures(onTap = { win.setExpanded(false) })
                })
        }
        Spacer(Modifier.height(8.dp))
        ChipRow {
            Chip("均衡", state.profile == Proto.PROFILE_BALANCED,
                enabled = !store.locked(Feature.PROFILE)) { store.setProfile(Proto.PROFILE_BALANCED) }
            Chip("极速", state.profile == Proto.PROFILE_GAME,
                enabled = !store.locked(Feature.PROFILE)) { store.setProfile(Proto.PROFILE_GAME) }
            Chip("省电", state.profile == Proto.PROFILE_POWERSAVE,
                enabled = !store.locked(Feature.PROFILE)) { store.setProfile(Proto.PROFILE_POWERSAVE) }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (state.safety.fused) "熔断锁定中"
            else "CPU ${"%.2fG".format((state.telemetry?.cpuKhz?.maxOrNull() ?: 0) / 1_000_000.0)}" +
                " · ${state.telemetry?.fps ?: 0}fps",
            color = if (state.safety.fused) entropy.warn else entropy.onScrim.copy(alpha = 0.7f),
            fontSize = 10.sp
        )
        if (store.locked(Feature.PROFILE)) Text(
            "档位需要 ${Feature.PROFILE.min.label}：${Feature.PROFILE.why}",
            color = entropy.onScrim.copy(alpha = 0.45f), fontSize = 9.sp, maxLines = 2)
        Spacer(Modifier.height(4.dp))
        Text("长按收起 · 双击切控制台", color = entropy.onScrim.copy(alpha = 0.4f), fontSize = 9.sp)
        Box(Modifier.fillMaxWidth().height(1.dp).padding(top = 5.dp)
            .background(entropy.onScrim.copy(alpha = 0.12f)))
        Spacer(Modifier.height(5.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // weight 而不是 fillMaxWidth：一行两个 fillMaxWidth 的 Chip，
            // 第二个会被压成 0 宽，"隐藏"就凭空消失了
            Chip("控制台", false, Modifier.weight(1f)) { win.setForm(OverlayForm.PANEL) }
            Chip("隐藏", false, Modifier.weight(1f)) { win.hide() }
        }
    }
}

/**
 * 原子造型。
 *
 * 三层：外圈辉光（径向渐变，画出"能量"而不是一个实心圆）、三条倾斜电子轨道 + 电子
 * 与它的拖尾、中心的核。轨道整体慢转 + 电子各自快跑，两个频率差得足够远，
 * 眼睛才会读成"在转"而不是"在闪"。
 *
 * 系统里"动画时长 = 0"时不建无限动画，直接画一张静止的原子：
 * 关掉动画是用户的选择，悬浮窗不该绕过它 —— 无限动画在那种机器上会变成
 * 一条永不聊束的 60fps 重绘。
 */
@Composable
private fun Atom(tint: Color, alert: Boolean, modifier: Modifier = Modifier) {
    val entropy = LocalEntropyColors.current
    val glow by animateColorAsState(
        targetValue = if (alert) entropy.warn else tint,
        animationSpec = tween(360), label = "atomTint")
    val still = rememberAnimationsOff()

    val spin = if (still) 0f else infinite01(9_600, "spin")
    val rev = if (still) 0.22f else infinite01(2_800, "rev")
    val breathe = if (still) 1f else infiniteWave(2_200, 0.9f, 1.07f)

    Canvas(modifier) {
        val r = size.minDimension / 2f
        val c = center
        // 辉光比轨道半径略大，边界完全淡出，这样小球贴在任何游戏画面上都没有硬边
        drawCircle(
            Brush.radialGradient(
                colorStops = arrayOf(0f to glow.copy(alpha = 0.34f),
                    0.55f to glow.copy(alpha = 0.13f), 1f to Color.Transparent),
                center = c, radius = r),
            radius = r, center = c)

        val major = r * 0.82f
        val minor = r * 0.30f
        val orbitW = min(r * 0.055f, 2.4f)
        val electronR = r * 0.115f

        for (i in 0 until 3) {
            val tilt = i * 60f + spin * 360f
            val phase = rev * 360f + i * 120f
            rotate(degrees = tilt, pivot = c) {
                drawOval(
                    color = glow.copy(alpha = if (alert) 0.85f else 0.55f),
                    radiusX = major, radiusY = minor, center = c, strokeW = orbitW)
                // 拖尾：电子后面 5 个点，半径与透明度同时衰减，读起来是一条弧线而不是五个点
                for (k in 5 downTo 1) {
                    val a = Math.toRadians((phase - k * 9f).toDouble())
                    drawCircle(
                        color = glow.copy(alpha = 0.10f * (6 - k)),
                        radius = electronR * (1f - k * 0.13f),
                        center = Offset(c.x + major * cos(a).toFloat(), c.y + minor * sin(a).toFloat()))
                }
                val e = Math.toRadians(phase.toDouble())
                drawCircle(
                    color = Color.White.copy(alpha = 0.95f),
                    radius = electronR,
                    center = Offset(c.x + major * cos(e).toFloat(), c.y + minor * sin(e).toFloat()))
            }
        }

        val nr = r * 0.26f * breathe
        drawCircle(
            Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.92f), glow.copy(alpha = 0.95f), glow.copy(alpha = 0.55f)),
                center = Offset(c.x - nr * 0.28f, c.y - nr * 0.28f), radius = nr * 1.7f),
            radius = nr, center = c)
    }
}

/** 轨道/电子画在旋转坐标系里，所以要能在 rotate{} 内直接用长短半轴画椭圆 */
private fun DrawScope.drawOval(
    color: Color, radiusX: Float, radiusY: Float, center: Offset, strokeW: Float,
) = drawOval(
    color = color,
    topLeft = Offset(center.x - radiusX, center.y - radiusY),
    size = Size(radiusX * 2f, radiusY * 2f),
    style = Stroke(width = strokeW, cap = androidx.compose.ui.graphics.StrokeCap.Round)
)

/** 0..1 匀速循环 */
@Composable
private fun infinite01(periodMs: Int, label: String): Float {
    val t = rememberInfiniteTransition(label = "atom")
    val v by t.animateFloat(0f, 1f,
        infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Restart), label = label)
    return v
}

/** 正负往返的波，用来做核的呼吸 */
@Composable
private fun infiniteWave(periodMs: Int, from: Float, to: Float): Float {
    val t = rememberInfiniteTransition(label = "atom")
    val v by t.animateFloat(from, to,
        infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Reverse), label = "breathe")
    return v
}

/** 用户在开发者选项里把动画时长关掉了吗（scale < 1%） */
@Composable
private fun rememberAnimationsOff(): Boolean {
    val ctx = LocalContext.current
    return remember {
        runCatching {
            Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f) < 0.01f
    }
}

// ------------------------------------------------------------------ //
// 形态二：左侧标签栏控制台                                                //
// ------------------------------------------------------------------ //

/**
 * 六节。分节的原则是"同一节里的东西会一起被改"：
 * 改了软上限必然要盯温度，所以实时与温控相邻；画质类五项一次全在同一节里。
 * 每节两到三张卡，保证不换页就能看完，也不至于长到要滚两屏。
 *
 * hint 是这一节的一句话定位，显示在内容区的标题下面。节名以前只在左栏那两个字里，
 * 图标栏之后栏上只剩图形，节名与说明就挪到内容区 —— 用户看的是内容，不是导航。
 */
internal enum class PanelTab(val label: String, val icon: Glyph, val hint: String) {
    LIVE("实时", Glyph.LIVE, "每一项都来自真节点，取不到就是 —"),
    SCHEDULE("调度", Glyph.SCHEDULE, "档位与渲染后端，改的是 CPU/GPU 调度"),
    VISUAL("画质", Glyph.VISUAL, "分辨率、刷新率、帧率与清晰度"),
    THERMAL("温控", Glyph.THERMAL, "软上限、熔断线、提醒线三者的关系"),
    SYSTEM("系统", Glyph.SYSTEM, "entropyd、Root、极限探测与后台冻结"),
    SKIN("外观", Glyph.SKIN, "主题、原子小球尺寸与形态"),
}

/**
 * 切页动画的 targetState 一律用枚举本身而不是"当前第几"的那个 Int：
 * AnimatedContent 在切换的瞬间会同时组合新旧两页，如果页面自己去读外层那个变量，
 * 退场那一帧会渲染成进场的内容，淡出动画里就看见文字自己变了一遍。
 * 页码随 targetState 一起传进来，各画各的。
 */
@Composable
private fun PanelConsole(
    win: OverlayWindow,
    store: EntropyStore,
    state: UiState,
    chrome: OverlayChrome,
) {
    // 只读不赋值：这一行的作用是给本组合建立"任何偏好被写过"的读依赖，
    // 主界面改的渲染后端、悬浮窗改的帧率上限，两边才会立刻变色。
    // 少了它，面板会一直显示改动之前的值（prefs 存的是普通值，不是快照状态）。
    store.prefsTick.intValue
    val entropy = LocalEntropyColors.current
    // 0.94 时底下 App 的字仍能从卡片缝隙里透出来，面板里本来字就小，读起来像重影
    val scrim = entropy.overlayScrim.copy(alpha = 0.97f)
    var tab by rememberSaveable { mutableIntStateOf(0) }

    // 进出场的淡入与位移都在 OverlayRoot 那层做，这里不再自己 alpha 一次：
    // 两处叠在一个窗口上会让面板比小球慢半拍，而它俩本来该是同一块东西的两种形状
    Column(
        Modifier
            .fillMaxSize()
            // 这一层故意不吃点击。整块窗口都是面板自己身上，按在哪儿都不该收 ——
            // 以前这里挂着一个 detectTapGestures(onTap=收成小球)，卡片缝隙、栏间空白
            // 全算成"点空白收起"，想切右栏却按偏一点，整块面板就没了。
            // 现在只有窗口外那一下会收：窗口宽度就是 panelWidthPx()，窗外由
            // FLAG_WATCH_OUTSIDE_TOUCH 送进 OverlayService.onOutsideTap。
            // 四角一起圆：窗口上下都让开了边距，这块是浮在屏幕上的卡片，不再是贴在左缘的一条
            .clip(RoundedCornerShape(20.dp))
            .background(scrim)
            .padding(top = chrome.topDp.dp + chrome.cutoutDp.dp + 6.dp,
                bottom = chrome.bottomDp.dp + 6.dp)
    ) {
        PanelHeader(win, store, state)

        Row(Modifier.weight(1f).fillMaxWidth()) {
            PanelRail(selected = tab, dimmed = false) { i -> tab = i }
            AnimatedContent(
                targetState = PanelTab.entries[tab.coerceIn(0, PanelTab.entries.lastIndex)],
                // weight 必须挂在 AnimatedContent 自己身上：它是 Row 的直接孩子。
                // 挂到里面那个 Column 上，Compose 只会在中间那层 Box 之外找不到这份
                // RowColumnParentData，内容区于是按内容自己撑宽，把右栏整条挤出窗口
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    // 往下一节就往右进，往上一节就往左进：方向和手指在竖栏上的移动一致
                    val forward = targetState.ordinal >= initialState.ordinal
                    (EntropyMotion.fadeEnter() + slideInHorizontally(
                        tween(EntropyMotion.NORMAL, easing = EntropyMotion.Ease)) {
                        if (forward) it / 6 else -it / 6 }) togetherWith EntropyMotion.fadeExit()
                },
                label = "panelBody"
            ) { p ->
                Column(
                    Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PageTitle(p.label, p.hint)
                    when (p) {
                        PanelTab.LIVE -> LiveSection(store, state)
                        PanelTab.SCHEDULE -> ScheduleSection(store, state)
                        PanelTab.VISUAL -> VisualSection(store, state)
                        PanelTab.THERMAL -> ThermalSection(store, state)
                        PanelTab.SYSTEM -> SystemSection(win, store, state)
                        PanelTab.SKIN -> SkinSection(win, store, state)
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        // ---- 底部固定操作栏：拇指够得到的位置 ---- //
        Row(
            Modifier.fillMaxWidth().background(entropy.onScrim.copy(alpha = 0.10f)).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            BarButton("还原") { store.resetAll() }
            BarButton("灵动岛") { win.toggleHud() }
            BarButton("UFO") { win.launchUfo() }
        }
    }
}

/**
 * 右侧小面板的根。
 *
 * 它是独立窗口，所以 EntropyTheme 必须在这里再给一遍 —— CompositionLocal 不跨窗口，
 * 左面板那一层覆盖不到这块画布。漏掉的话这块会退回 Material 默认色，
 * 两块面板看起来像两个应用。
 */
@Composable
fun SideConsoleRoot(
    win: OverlayWindow,
    store: EntropyStore,
    state: UiState,
    themeMode: ThemeMode,
    chrome: OverlayChrome,
    exiting: Boolean = false,
) {
    val prefs = store.prefs
    EntropyTheme(
        accentArgb = prefs.accentColor,
        themeMode = themeMode,
        dynamicColor = true,
        highlightArgb = store.highlightColor.intValue,
        palette = store.palette.value,
    ) {
        val stage = remember { Animatable(0f) }
        LaunchedEffect(exiting) {
            stage.animateTo(if (exiting) 0f else 1f, panelStageSpec(exiting))
        }
        // 和左面板同一条曲线、同一个幅度，只是锚点换到右缘：这块贴的是右缘，
        // 就该从右缘长出来。两边逐帧同步，展开时才读得出是"一块控制台的左右两半"
        Box(Modifier.fillMaxSize().graphicsLayer {
            alpha = panelStageAlpha(stage.value)
            scaleX = PanelStretchFrom + (1f - PanelStretchFrom) * stage.value
            transformOrigin = TransformOrigin(1f, 0.5f)
        }) {
            SideConsole(win, store, state, chrome)
        }
    }
}

@Composable
private fun SideConsole(
    win: OverlayWindow,
    store: EntropyStore,
    state: UiState,
    chrome: OverlayChrome,
) {
    // 和左面板同理：这块也直接读 prefs，改完偏好要立刻在这块窗口上看得见
    store.prefsTick.intValue
    val entropy = LocalEntropyColors.current
    val scrim = entropy.overlayScrim.copy(alpha = 0.97f)
    var side by rememberSaveable { mutableIntStateOf(0) }

    Column(
        Modifier
            .fillMaxSize()
            // 四角一起圆：这块现在垂直居中在右缘，上下都不贴屏幕边了
            .clip(RoundedCornerShape(20.dp))
            .background(scrim)
            .padding(top = chrome.topDp.dp + chrome.cutoutDp.dp + 6.dp,
                bottom = chrome.bottomDp.dp + 6.dp)
    ) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            // 标签栏放这一块的左缘：两块面板的导航都朝屏幕中线那侧，
            // 拇指从左面板移到右面板时不用绕到屏幕边上去点
            SideRail(selected = side, mirror = true) { side = it }
            AnimatedContent(
                targetState = SideTab.entries[side.coerceIn(0, SideTab.entries.lastIndex)],
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    val forward = targetState.ordinal >= initialState.ordinal
                    (EntropyMotion.fadeEnter() + slideInHorizontally(
                        tween(EntropyMotion.NORMAL, easing = EntropyMotion.Ease)) {
                        if (forward) -it / 6 else it / 6 }) togetherWith EntropyMotion.fadeExit()
                },
                label = "sideBody",
            ) { t ->
                Column(
                    Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 标题由各页自己画：这四页原本住在左面板的右栏里，那边没有标题位
                    when (t) {
                        SideTab.INFO -> SystemInfoPage(store, state)
                        SideTab.GUIDE -> GuidePage(store, state)
                        SideTab.QUICK -> QuickSwitchPage(win, store, state)
                        SideTab.RANK -> CpuRankingPage(store, state)
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun PanelHeader(win: OverlayWindow, store: EntropyStore, state: UiState) {
    val entropy = LocalEntropyColors.current
    val tempC = state.telemetry?.maxTempC
    val fused = state.safety.fused
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 10.dp, top = 2.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Atom(tint = if (fused) entropy.warn else tempColor(entropy, tempC ?: 30f),
            alert = fused, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(7.dp))
        Text("熵", color = entropy.onScrim, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Spacer(Modifier.width(6.dp))
        // 平台徽标紧跟在"熵"字后面：这台机器是高通还是天玑，决定了下面一堆节点
        // 能不能调，用户报障时最先要说的也是这个
        SocBadge(store.bridge.profile.family)
        Spacer(Modifier.width(6.dp))
        // 等级徽标就放在标题旁边：悬浮窗里灰掉的按钮必须能就地解释，
        // 而不是让用户回 App 里翻是哪一档
        Text(store.level.value.label, color = entropy.onScrim.copy(alpha = 0.6f), fontSize = 10.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(entropy.onScrim.copy(alpha = 0.10f))
                .padding(horizontal = 7.dp, vertical = 2.dp))
        Spacer(Modifier.weight(1f))
        Text(tempC?.let { "%.1f℃".format(it) } ?: "—",
            color = if (fused) entropy.warn else tempColor(entropy, tempC ?: 30f),
            fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(9.dp))
        // 用文字而不是箭头字形：这个动作是"收成原子小球"，
        // 单个箭头符号没人能猜出它代表什么
        Text("小球", color = entropy.onScrim.copy(alpha = 0.6f), fontSize = 12.sp,
            modifier = Modifier.pointerInput(Unit) {
                detectTapGestures(onTap = { win.setForm(OverlayForm.BALL) })
            })
        Spacer(Modifier.width(9.dp))
        Text("隐藏", color = entropy.onScrim.copy(alpha = 0.6f), fontSize = 12.sp,
            modifier = Modifier.pointerInput(Unit) {
                detectTapGestures(onTap = { win.hide() })
            })
    }
}

/**
 * 左侧竖排标签栏：图标在上，节名在图标下方那一格。
 *
 * 选中项只留图标、文字淡出，节名交给内容区的标题去说 —— 同一屏里"实时"出现两次
 * 是浪费，而且选中项那格本来就在眼睛的正上方，图标比两个字更好认。
 *
 * 关键是布局不许跳：图标槽固定 22dp，文字槽固定 11dp，选中时只淡出文字而不删除它。
 * 早前一版把 Text 直接从 Column 里摘掉，整列内容高度变了 11dp，图标跟着往上顶了半格，
 * 六次点击下来栏子像在用脚抖。淡出+放大是同一格里的变化，读起来才是"这一项被选中了"。
 *
 * 指示条仍是一根独立绘制的短杠在条目之间滑：六个条目各亮一块背景，
 * 不如一根杠移动来得清楚 —— 用户要的是"当前这一节换了位置"。
 * 条目高度由字号算出来（见 RailLabelSp），指示条的位置跟着同一个值走才算得准。
 */
private const val RailGap = 4
private const val RailGlyphH = 22
private const val RailLabelSp = 9f

@Composable
private fun PanelRail(selected: Int, dimmed: Boolean, onSelect: (Int) -> Unit) {
    val entropy = LocalEntropyColors.current
    val scheme = MaterialTheme.colorScheme
    // 条目高度由字号推出来，不写死 50dp：标签是 sp，系统"字体大小"一上调整行就跟着长，
    // 而写死的格子不会 —— 换台 1.3 倍字体的机器实测"调度"被削成两道横杠。
    // 2 倍是行高余量：中文字体的 ascent+descent 比西文宽，按西文的 1.4 倍仍会切到笔画。
    val itemH = with(LocalDensity.current) {
        RailGlyphH.dp + 3.dp + 6.dp + (RailLabelSp * 2f).sp.toDp()
    }
    Box(
        Modifier.width(54.dp).fillMaxHeight()
            .verticalScroll(rememberScrollState())
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(RailGap.dp)) {
            PanelTab.entries.forEachIndexed { i, t ->
                val on = i == selected && !dimmed
                val fg by animateColorAsState(
                    targetValue = if (on) scheme.primary else entropy.onScrim.copy(alpha = 0.6f),
                    animationSpec = tween(EntropyMotion.NORMAL), label = "railFg")
                val bg by animateFloatAsState(
                    targetValue = if (on) 0.16f else 0f,
                    animationSpec = tween(EntropyMotion.NORMAL), label = "railBg")
                val glyph by animateFloatAsState(
                    targetValue = if (on) 1f else 0.78f,
                    animationSpec = spring(dampingRatio = 0.55f, stiffness = 420f), label = "railGlyph")
                val labelAlpha by animateFloatAsState(
                    targetValue = if (on) 0f else 1f,
                    animationSpec = tween(EntropyMotion.NORMAL), label = "railLabel")
                Box(
                    Modifier.fillMaxWidth().height(itemH)
                        .clip(RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp))
                        .background(scheme.primary.copy(alpha = bg))
                        .pointerInput(i) { detectTapGestures(onTap = { onSelect(i) }) },
                ) {
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(Modifier.size(RailGlyphH.dp), contentAlignment = Alignment.Center) {
                            RailGlyph(t.icon, fg, Modifier.fillMaxSize().scale(glyph))
                        }
                        Spacer(Modifier.height(3.dp))
                        // 标签不再套一层固定高度的槽：它留在树里只做 alpha 淡出，
                        // 高度自始至终不变，图标照样不会被挤下去；而条目高度是按字号算的，
                        // 字也就没有可以被裁掉的那一刀
                        Text(t.label, color = fg, fontSize = RailLabelSp.sp, maxLines = 1,
                            softWrap = false, modifier = Modifier.alpha(labelAlpha))
                    }
                }
            }
        }
        val barTop by animateDpAsState(
            targetValue = (itemH + RailGap.dp) * selected + (itemH - 26.dp) / 2,
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 320f), label = "railBar")
        Box(Modifier.offset(y = barTop).size(width = 3.dp, height = 26.dp)
            .align(Alignment.TopEnd)
            .clip(RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp))
            .background(if (dimmed) scheme.primary.copy(alpha = 0.3f) else scheme.primary))
    }
}

/**
 * 内容区标题：左栏那两个字搬到了这里。
 * 只有一行节名加一句定位，不做成大标题 —— 面板半屏宽，抬头三行说明没人会读。
 */
@Composable
internal fun PageTitle(text: String, hint: String) {
    val entropy = LocalEntropyColors.current
    Column {
        Text(text, color = entropy.onScrim, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(hint, color = entropy.onScrim.copy(alpha = 0.45f), fontSize = 9.sp, maxLines = 2,
            lineHeight = 12.sp)
    }
}

// ------------------------------------------------------------------ //
// 六节内容                                                             //
// ------------------------------------------------------------------ //

@Composable
private fun LiveSection(store: EntropyStore, state: UiState) {
    val entropy = LocalEntropyColors.current
    val t = state.telemetry
    val tempC = t?.maxTempC
    PanelCard("实时遥测") {
        MetricGrid(
            listOf(
                MetricData("温度", tempC?.let { "%.1f℃".format(it) } ?: "—",
                    tempC?.let { tempColor(entropy, it) } ?: entropy.onScrim),
                MetricData("CPU", "%.2fG".format((t?.cpuKhz?.maxOrNull() ?: 0) / 1_000_000.0), entropy.onScrim),
                MetricData("FPS", "${t?.fps ?: 0}", entropy.onScrim),
                MetricData("GPU", "${t?.gpuBusy ?: 0}%", entropy.onScrim),
                MetricData("功率", t?.let { "%.1fW".format(it.powerMw / 1000f) } ?: "—", entropy.onScrim),
                MetricData("帧耗", t?.let { "%.1fms".format(it.renderMs / 1000f) } ?: "—", entropy.onScrim),
            )
        )
        if (state.safety.fused) {
            val why = state.safety.reason
            Text(if (why.isEmpty()) "熔断锁定中，已还原调度，120 秒后解锁"
                 else "熔断锁定：$why · 120 秒后解锁",
                color = entropy.warn, fontSize = 10.sp)
        } else {
            Text("安全 · 当前 %.1f℃ 档".format(store.hudAlertMc.intValue / 1000f),
                color = entropy.tempCool, fontSize = 10.sp)
        }
    }
    PanelCard("守护进程") {
        val hb = state.heartbeat
        MetricGrid(listOf(
            MetricData("状态", when (hb?.state ?: 0) {
                0 -> "空闲"; 1 -> "调度中"; 2 -> "探测中"; 3 -> "熔断"; else -> "未知"
            }, entropy.onScrim),
            MetricData("时长", hb?.let { "%d:%02d".format(it.uptimeS / 60, it.uptimeS % 60) } ?: "—",
                entropy.onScrim),
            MetricData("自身 CPU", hb?.let { "%.1f%%".format(it.cpuPermille / 10.0) } ?: "—", entropy.onScrim),
        ))
    }
}

@Composable
private fun ScheduleSection(store: EntropyStore, state: UiState) {
    val entropy = LocalEntropyColors.current
    val prefs = store.prefs
    val locked = store.locked(Feature.PROFILE)
    PanelCard("调度档位") {
        ChipRow {
            Chip("均衡", state.profile == Proto.PROFILE_BALANCED, enabled = !locked) {
                store.setProfile(Proto.PROFILE_BALANCED)
            }
            Chip("极速", state.profile == Proto.PROFILE_GAME, enabled = !locked) {
                store.setProfile(Proto.PROFILE_GAME)
            }
            Chip("省电", state.profile == Proto.PROFILE_POWERSAVE, enabled = !locked) {
                store.setProfile(Proto.PROFILE_POWERSAVE)
            }
            Chip("重发", false, enabled = !locked) { store.setProfile(state.profile) }
        }
        LockNote(Feature.PROFILE, locked)
    }
    FreqCapCard(store, state)
    PanelCard("渲染后端") {
        val rl = store.locked(Feature.RENDERER)
        ChipRow {
            Chip("默认", prefs.renderer == Proto.RENDERER_DEFAULT, enabled = !rl) {
                store.setRenderer(Proto.RENDERER_DEFAULT)
            }
            Chip("Skia", prefs.renderer == Proto.RENDERER_SKIA, enabled = !rl) {
                store.setRenderer(Proto.RENDERER_SKIA)
            }
            Chip("GLES", prefs.renderer == Proto.RENDERER_GLES, enabled = !rl) {
                store.setRenderer(Proto.RENDERER_GLES)
            }
            Chip("VK", prefs.renderer == Proto.RENDERER_VULKAN, enabled = !rl) {
                store.setRenderer(Proto.RENDERER_VULKAN)
            }
        }
        Text("改后端要重启 SystemUI 与目标 App 才生效",
            color = entropy.onScrim.copy(alpha = 0.4f), fontSize = 9.sp)
        LockNote(Feature.RENDERER, rl)
    }
}

/**
 * 频率上限卡。
 *
 * 滑杆区间取本机 OPP 表的最小/最大档，而不是 0 起：0 在语义上是"不设限"，
 * 把它画进同一根滑杆会让用户以为拉到最左端能把 CPU 停在 0 kHz。
 * "不限制"因此单独做成一个 chip，写 0 交给 Daemon 按档位自己算。
 */
@Composable
private fun FreqCapCard(store: EntropyStore, state: UiState) {
    val entropy = LocalEntropyColors.current
    val prefs = store.prefs
    val prof = store.bridge.profile
    val cpuLo = prof.cpuClusters.minOfOrNull { it.minKhz } ?: 0
    val cpuHi = prof.cpuClusters.maxOfOrNull { it.maxKhz } ?: 0
    val gpu = prof.gpuFreq
    val cpuLocked = store.locked(Feature.CPU_FREQ)
    val gpuLocked = store.locked(Feature.GPU_FREQ)
    PanelCard("频率上限") {
        if (cpuHi <= cpuLo) {
            Text("没有可访问的 cpufreq 节点", color = entropy.onScrim.copy(alpha = 0.45f), fontSize = 9.sp)
        } else {
            OverlaySlider(
                label = "CPU 上限",
                value = if (prefs.cpuMaxKhz > 0) prefs.cpuMaxKhz.coerceIn(cpuLo, cpuHi) else cpuHi,
                range = cpuLo..cpuHi, detents = 13,
                valueText = { "%.2f GHz".format(it / 1_000_000.0) },
                enabled = !cpuLocked,
                onCommit = { store.setCpuMaxKhz(it) }
            )
            Spacer(Modifier.height(4.dp))
            ChipRow {
                Chip("不限制", prefs.cpuMaxKhz == 0, enabled = !cpuLocked) { store.setCpuMaxKhz(0) }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (gpu == null) {
            Text("这台机器没有可读的 GPU 频率节点", color = entropy.onScrim.copy(alpha = 0.45f), fontSize = 9.sp)
        } else {
            val gLo = gpu.minKhz
            val gHi = gpu.maxLimitKhz
            if (gHi <= gLo) {
                Text("GPU 频率表只有一档，没有可改的上限",
                    color = entropy.onScrim.copy(alpha = 0.45f), fontSize = 9.sp)
            } else {
                OverlaySlider(
                    label = "GPU 上限",
                    value = if (prefs.gpuMaxKhz > 0) prefs.gpuMaxKhz.coerceIn(gLo, gHi) else gHi,
                    range = gLo..gHi, detents = 13,
                    valueText = { "${it / 1000} MHz" },
                    enabled = !gpuLocked,
                    onCommit = { store.setGpuMaxKhz(it) }
                )
                Spacer(Modifier.height(4.dp))
                ChipRow {
                    Chip("不限制", prefs.gpuMaxKhz == 0, enabled = !gpuLocked) { store.setGpuMaxKhz(0) }
                }
            }
        }
        // 上限只压不抬：越过档位默认值时 Daemon 会按默认走，这里不假装能超频
        Text("只设上限，不超频；熔断锁定期内一律禁止抬频",
            color = entropy.onScrim.copy(alpha = 0.4f), fontSize = 9.sp)
        LockNote(Feature.CPU_FREQ, cpuLocked)
        LockNote(Feature.GPU_FREQ, gpuLocked)
    }
}

@Composable
private fun VisualSection(store: EntropyStore, state: UiState) {
    val entropy = LocalEntropyColors.current
    val prefs = store.prefs
    PanelCard("分辨率 / 刷新率") {
        val locked = store.locked(Feature.RESOLUTION)
        OverlaySlider(
            label = "渲染分辨率",
            value = prefs.resPreset,
            range = 0..3, detents = 4,
            valueText = { listOf("原生", "3/4", "2/3", "1/2")[it] },
            enabled = !locked,
            onCommit = { store.setResolutionPreset(it) }
        )
        Spacer(Modifier.height(6.dp))
        val refreshLocked = store.locked(Feature.REFRESH_RATE)
        ChipRow {
            // 写的是 user_refresh_rate_mode 这个上限提示，面板不支持的那几档会被系统压回
            // 自己可用的最高档 —— 所以这里按档位一路给到 240，而不是只列本机面板报出来的模式
            listOf(0 to "自动", 60 to "60Hz", 90 to "90Hz", 120 to "120Hz",
                    144 to "144Hz", 165 to "165Hz", 240 to "240Hz").forEach { (v, l) ->
                Chip(l, false, enabled = !refreshLocked) { store.setRefreshMode(v) }
            }
        }
        LockNote(Feature.RESOLUTION, locked)
        LockNote(Feature.REFRESH_RATE, refreshLocked)
    }
    PanelCard("帧率 / 触控") {
        val capLocked = store.locked(Feature.FPS_CAP)
        OverlaySlider(
            label = "游戏帧率上限",
            value = prefs.fpsLimit,
            range = 0..240, detents = 25,
            valueText = { if (it == 0) "跟随系统" else "$it FPS" },
            enabled = !capLocked,
            onCommit = { store.setFpsLimit(it) }
        )
        Spacer(Modifier.height(8.dp))
        val touchLocked = store.locked(Feature.TOUCH_HZ)
        OverlaySlider(
            label = "触控采样率",
            value = prefs.touchHz,
            range = 120..300, detents = 7,
            valueText = { "$it Hz" },
            enabled = !touchLocked,
            onCommit = { store.setTouchHz(it) }
        )
        LockNote(Feature.FPS_CAP, capLocked)
        LockNote(Feature.TOUCH_HZ, touchLocked)
    }
    PanelCard("清晰度") {
        val locked = store.locked(Feature.QUALITY)
        OverlaySlider(
            label = "厂商后处理清晰度",
            value = prefs.clarity,
            range = 0..100, detents = 21,
            valueText = { "$it 档" },
            enabled = !locked,
            onCommit = { store.setClarity(it) }
        )
        LockNote(Feature.QUALITY, locked)
    }
}

@Composable
private fun ThermalSection(store: EntropyStore, state: UiState) {
    val entropy = LocalEntropyColors.current
    val prefs = store.prefs
    val locked = store.locked(Feature.THERMAL_CFG)
    PanelCard("温控阈值") {
        OverlaySlider(
            label = "软上限",
            value = prefs.softTempMc,
            range = 40_000..90_000, detents = 11,
            valueText = { "%.1f℃".format(it / 1000f) },
            color = entropy.tempWarm,
            enabled = !locked,
            // nudge 只接受增量，这里用"目标值 - 当前值"把它当绝对设置用，
            // 免得为了滑杆再给 store 加一套重复的写入接口
            onCommit = { store.nudgeSoftLimit(it - prefs.softTempMc) }
        )
        Spacer(Modifier.height(8.dp))
        OverlaySlider(
            label = "熔断线",
            value = prefs.fuseTempMc,
            range = 45_000..Proto.FUSE_MAX_MC, detents = 11,
            valueText = { "%.1f℃".format(it / 1000f) },
            color = entropy.warn,
            enabled = !locked,
            onCommit = { store.nudgeFuse(it - prefs.fuseTempMc) }
        )
        Text("熔断线上限 ${Proto.FUSE_MAX_MC / 1000}℃：App、Daemon、命令行三处各钳一次",
            color = entropy.onScrim.copy(alpha = 0.4f), fontSize = 9.sp)
        LockNote(Feature.THERMAL_CFG, locked)
    }
    PanelCard("过热提醒线") {
        OverlaySlider(
            label = "灵动岛提醒",
            value = store.hudAlertMc.intValue,
            range = 40_000..90_000, detents = 11,
            valueText = { "%.1f℃".format(it / 1000f) },
            color = entropy.tempWarm,
            onCommit = { store.setHudAlertMc(it) }
        )
        Text("提醒只改颜色和通知，不动任何调度节点",
            color = entropy.onScrim.copy(alpha = 0.4f), fontSize = 9.sp)
    }
}

@Composable
private fun SystemSection(win: OverlayWindow, store: EntropyStore, state: UiState) {
    val entropy = LocalEntropyColors.current
    PanelCard("entropyd 与 Root") {
        Text(
            when (state.path) {
                HardwareBridge.Path.DAEMON -> "Daemon 通道已连接，熔断线程在跑"
                HardwareBridge.Path.ROOT_SHELL -> "Root 直写：无熔断线程，上限硬压在 85%"
                HardwareBridge.Path.READONLY -> "只读：改不了任何节点"
            },
            color = if (state.path == HardwareBridge.Path.DAEMON) entropy.tempCool else entropy.tempWarm,
            fontSize = 10.sp
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(if (state.busy) "运行中…" else "一键运行", false, Modifier.weight(1f),
                enabled = !state.busy && !store.locked(Feature.RUN_DAEMON)) { store.startDaemon() }
            Chip("请求 Root", false, Modifier.weight(1f)) { store.requestRoot() }
        }
        LockNote(Feature.RUN_DAEMON, store.locked(Feature.RUN_DAEMON))
        if (state.lastMessage.isNotEmpty()) Text(
            state.lastMessage, color = entropy.onScrim.copy(alpha = 0.55f), fontSize = 9.sp, maxLines = 2)
    }
    PanelCard("极限探测") {
        val prof = state.probe
        val locked = store.locked(Feature.PROBE)
        Text(
            if (prof == null) "还没探测过。探测会短时抬频，全程由熔断线程看护。"
            else "甜点 ${prof.cpuSweetKhz / 1000}M · 极限 ${prof.cpuAbsLimitKhz / 1000}M · 完成 ${prof.done}%",
            color = entropy.onScrim.copy(alpha = 0.6f), fontSize = 9.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(if (state.probing) "中止探测" else "开始探测", state.probing, Modifier.weight(1f),
                enabled = !locked || state.probing) {
                if (state.probing) store.stopProbe() else store.probe()
            }
            Chip("打开熵", false, Modifier.weight(1f)) { win.openApp() }
        }
        LockNote(Feature.PROBE, locked)
    }
    PanelCard("后台冻结") {
        Text("已冻结 ${state.frozenApps} 个 · 省 ${"%.1fs".format(state.savedCpuMs / 1000f)} CPU",
            color = entropy.onScrim.copy(alpha = 0.6f), fontSize = 10.sp)
        Spacer(Modifier.height(6.dp))
        val locked = store.locked(Feature.FREEZE)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("刷新状态", false, Modifier.weight(1f)) { store.refreshFreezer() }
            Chip("全部解冻", false, Modifier.weight(1f), enabled = !locked) { store.resetAll() }
        }
        LockNote(Feature.FREEZE, locked)
    }
}

@Composable
private fun SkinSection(win: OverlayWindow, store: EntropyStore, state: UiState) {
    PanelCard("主题") {
        ChipRow {
            ThemeMode.entries.forEach { m ->
                Chip(m.label, store.themeMode.value == m) { store.setThemeMode(m) }
            }
        }
    }
    PanelCard("原子小球") {
        // 倍率没有整型语义，滑杆按百分数走（70~140），下发前再除回 100
        OverlaySlider(
            label = "尺寸",
            value = (store.ballScale.value * 100).roundToInt(),
            range = 70..140, detents = 15,
            valueText = { "%.2fx".format(it / 100f) },
            onCommit = { store.setBallScale(it / 100f) }
        )
        Spacer(Modifier.height(6.dp))
        Text("双指捏合小球也能改；控制台不可拖动，整屏高贴左侧",
            color = LocalEntropyColors.current.onScrim.copy(alpha = 0.4f), fontSize = 9.sp)
    }
    PanelCard("形态") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("原子小球", store.overlayForm.value == OverlayForm.BALL, Modifier.weight(1f)) {
                win.setForm(OverlayForm.BALL)
            }
            Chip("左侧控制台", store.overlayForm.value == OverlayForm.PANEL, Modifier.weight(1f)) {
                win.setForm(OverlayForm.PANEL)
            }
        }
    }
}

/** 置灰说明：一句话说清"这项要哪一档、为什么"，不重复功能名 */
@Composable
internal fun LockNote(feature: Feature, locked: Boolean) {
    if (!locked) return
    Text("需 ${feature.min.label} · ${feature.why}",
        color = LocalEntropyColors.current.onScrim.copy(alpha = 0.45f), fontSize = 9.sp, maxLines = 2)
}

// ------------------------------------------------------------------ //
// 复用控件                                                            //
// ------------------------------------------------------------------ //

@Composable
internal fun PanelCard(title: String, content: @Composable () -> Unit) {
    val entropy = LocalEntropyColors.current
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(entropy.onScrim.copy(alpha = 0.06f))
            .padding(10.dp)
    ) {
        Text(title, color = entropy.onScrim.copy(alpha = 0.5f), fontSize = 9.sp,
            fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

internal data class MetricData(val label: String, val value: String, val color: Color)

/** 遥测读数：一行三个。半屏宽度放不下更多，硬塞成一行会把数字压成竖排 */
@Composable
internal fun MetricGrid(items: List<MetricData>) {
    items.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { Metric(it.label, it.value, it.color) }
        }
    }
}

/**
 * 卡片里的档位行。
 * 用 Row 的话四五个中文 Chip 会被等比压扁，"120 Hz" 能竖排成一列字；
 * FlowRow 让放不下的 Chip 换到下一行，Chip 宽度始终由文字决定。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChipRow(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) { content() }
}

/**
 * 面板里的滑杆。
 *
 * 三个和主界面滑杆不同的地方，都是悬浮窗这个宿主逼出来的：
 *
 * 1) 拖动只改本地草稿，抬手才下发。
 *    主界面的 TickSlider 每跨一格就直接写偏好，因为在 App 里无所谓；
 *    这里一次拖动能跨过几十格，全下发的话 Daemon 会被刷屏，
 *    而且 sysfs 写失败的回执会盖掉用户还在进行中的操作。
 *    草稿用 remember(value) 存：拖动期间 value 不变所以不会被打断，
 *    下发之后 value 变了又会自动对齐到真正落地的值（被钳过也算）。
 *
 * 2) 颜色全部从 scrim 那套取，不用主题默认色。
 *    滑杆坐在半透明底板上，默认的下轨道颜色在游戏画面上会直接消失。
 *
 * 3) 权限不够时不是"隐藏滑杆"，而是压淡并留住原位：
 *    用户要能在控制台里看见这项存在，只是这一档改不动。
 */
@Composable
internal fun OverlaySlider(
    label: String,
    value: Int,
    range: IntRange,
    /** 档位总数，含两端 */
    detents: Int,
    valueText: (Int) -> String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
    onCommit: (Int) -> Unit,
) {
    val entropy = LocalEntropyColors.current
    var draft by remember(value) { mutableIntStateOf(value) }
    val span = (range.last - range.first).toFloat()
    val slots = (detents - 1).coerceAtLeast(1)
    val step = span / slots
    val dim by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.35f, animationSpec = tween(200), label = "sliderDim")
    val pop = popScale(draft)

    Column(modifier.fillMaxWidth().alpha(dim)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = entropy.onScrim.copy(alpha = 0.5f), fontSize = 9.sp,
                modifier = Modifier.weight(1f))
            Text(valueText(draft), color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.scale(pop))
        }
        // 和游戏里的手势一样：轨道自己带弹簧，所以这里只负责"草稿值 → 提交"这一段。
        // 松手才 onCommit 是有意保留的：拖动途中每一档都往内核写，
        // 手指划过去就会连着下十几个档，厂商温控会把它当成抖动
        SpringSliderTrack(
            frac = if (span > 0f) ((draft - range.first) / span).coerceIn(0f, 1f) else 0f,
            onScrub = { f ->
                if (enabled) draft = (range.first + (f * span / step).roundToInt() * step)
                    .roundToInt().coerceIn(range)
            },
            onScrubFinished = { if (enabled && draft != value) onCommit(draft) },
            detents = detents,
            enabled = enabled,
            color = color,
            inactiveColor = entropy.onScrim.copy(alpha = 0.18f),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun Metric(label: String, value: String, color: Color) {
    val entropy = LocalEntropyColors.current
    Column {
        Text(value, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = entropy.onScrim.copy(alpha = 0.45f), fontSize = 9.sp, maxLines = 1)
    }
}

/**
 * 一个档位按钮。
 * selected 只管"高亮成主题色"，enabled 才管"点不点得动"：
 * 两者合成一个参数的话，未选中的档位就再也点不动了。
 * 按压有 0.94 的回弹：悬浮窗里没有 Material 组件的水波纹反馈，
 * 不给这点缩放的话，用户分不清"没点到"和"点了但被权限挡住"。
 */
@Composable
internal fun Chip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val entropy = LocalEntropyColors.current
    val scheme = MaterialTheme.colorScheme
    val pressed = remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed.value) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 520f), label = "chipPress")
    val bg by animateColorAsState(
        targetValue = when {
            selected && enabled -> scheme.primary
            selected -> entropy.onScrim.copy(alpha = 0.22f)
            else -> entropy.onScrim.copy(alpha = 0.12f)
        }, animationSpec = tween(180), label = "chipBg")
    Box(
        Modifier
            .then(modifier)
            .scale(if (enabled) scale else 1f)
            .clip(RoundedCornerShape(9.dp))
            .background(bg)
            .padding(horizontal = 9.dp, vertical = 5.dp)
            .pointerInput(enabled) {
                if (enabled) detectTapGestures(
                    onPress = {
                        pressed.value = true
                        tryAwaitRelease()
                        pressed.value = false
                    },
                    onTap = { onClick() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(text,
            color = when {
                !enabled -> entropy.onScrim.copy(alpha = 0.3f)
                selected -> scheme.onPrimary
                else -> entropy.onScrim.copy(alpha = 0.85f)
            },
            fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun BarButton(text: String, onClick: () -> Unit) {
    val entropy = LocalEntropyColors.current
    val pressed = remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed.value) 0.9f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 500f), label = "barPress")
    Box(
        Modifier
            .size(width = 68.dp, height = 34.dp)
            .scale(scale)
            .clip(RoundedCornerShape(11.dp))
            .background(entropy.onScrim.copy(alpha = 0.10f))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed.value = true
                    tryAwaitRelease()
                    pressed.value = false
                }, onTap = { onClick() })
            },
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = entropy.onScrim, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}
