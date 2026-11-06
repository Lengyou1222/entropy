// File: app/src/main/java/com/entropy/overlay/OverlayService.kt
//
// 悬浮控制台的宿主：一个 foreground Service + 一块 WindowManager 画布。
//
// 四件容易踩坑的事，这里都按"必须显式处理"来写：
//
// 1) ComposeView 挂在非 Activity 窗口上没有 LifecycleOwner / SavedStateRegistryOwner。
//    必须在 addView 之前把这两个 owner 写成 View 的 tag：
//    AbstractComposeView.onAttachedToWindow 会先解析父 CompositionContext，
//    而它读的是 ViewTreeLifecycleOwner.get(view) —— 这一刻 setContent 的 lambda
//    还没跑，CompositionLocal 里的 provides 根本来不及生效，
//    结果是 FATAL IllegalStateException: ViewTreeLifecycleOwner not found。
//    tag 要打在"挂进 WindowManager 的那一层"上：Compose 取的是 contentChild
//    （从 ComposeView 往上走到没有 View 父亲为止），现在那一层是 OverlayRootView，
//    只打在里层的 ComposeView 上照样 FATAL。
//    CompositionLocal 那层保留，是为了 rememberSaveable 之类走 Local 查询的路径
//    （tag 与 Local 是两条独立的取值链，少一条都会在某天炸）。
//
// 2) 窗口尺寸跟着形态走，而且跟着屏幕走。
//    收起的小球贴着球本身；点开的紧凑控制台按屏幕钳一次，别超出可视区；
//    左侧控制台是整屏高、宽 0.44 倍屏（下限 300dp）的矩形。
//    如果一上来就开一块全屏窗口，NOT_TOUCH_MODAL 也救不了：整块屏幕的触摸
//    都会先进我们的窗口，游戏里"点不动"就是这么来的。
//
// 3) 形态只有一个来源：EntropyStore.overlayForm。
//    以前服务自己存一份 dockMode、偏好里存一份 dock_mode，只在服务恰好收到
//    start 命令时才对齐一次，于是"应用里切成大面板、悬浮窗却还是小球"这种
//    切换异常就有了生存空间。现在服务用 snapshotFlow 订阅同一个快照状态，
//    谁改的都一样，改完立刻重排窗口。
//
// 4) 音量键开合。
//    音量加键打开控制台，音量减键关闭控制台 —— 一颗键只管一件事，不用记"现在算开还是算关"。
//    没有 root 时拿不到 KeyEvent（那是系统输入焦点的东西，第三方应用截不到，也不该截），
//    只能退一步去听"音量这件事发生了"：主通道是音量广播，备用通道是监听 Settings 里的媒体音量值。
//    它不消费按键，系统音量照常变。所以文案写的是"音量加键打开"，而不是"截获音量键"。
//    三条已知的不完美，都在 onVolumeStep 里按能修的程度处理了：
//      · 顶到格 / 触到底的那一下值不变 —— 广播照样会发，读一次当前档位就知道是按到了头；
//      · 接电话、通知音会把媒体音量压低再弹回（ducking），那是一负一正两次信号 ——
//        用一小段确认窗口把"又走回原值"的那一次判成回弹，两边都不作数；
//      · 实测（MuMu / API 35）Settings 那个 volume_music 常年不动，所以它只当备份，
//        真靠它做主通道的话音量键就是死的。
package com.entropy.overlay

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalSavedStateRegistryOwner
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.entropy.EntropyApp
import com.entropy.MainActivity
import com.entropy.R
import com.entropy.data.EntropyStore
import com.entropy.data.OverlayForm
import com.entropy.ui.theme.EntropyTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class OverlayService : LifecycleService(), OverlayWindow, SavedStateRegistryOwner {

    private val store by lazy { EntropyStore.get(this) }
    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    /**
     * 展开态是"只有这块画布关心"的局部状态：窗口的宽高要跟着变，
     * 但它既不该进全局偏好，也不该让主界面为一次点开而重组。
     * 形态（小球 / 大面板）不一样，那是用户在两边都会改的东西，放在 store 里。
     */
    private val expanded = mutableStateOf(false)

    /**
     * 正在退场。窗口不是点了就没了：立刻 removeView 等于把动画剪掉，
     * 用户看到的是"啪一下消失"，而音量减键的反馈本来就该是"收起来"这个动作。
     * 这一位只驱动动画，真正的移除由 hideJob 延时之后执行。
     */
    private val exiting = mutableStateOf(false)
    private var hideJob: Job? = null
    /** 正在等"这一下到底落在谁身上"的那次判定，见 onOutsideTap */
    private var outsideJob: Job? = null

    /** 主窗口那层 FrameLayout，内容在它的 content 里 */
    private var view: OverlayRootView? = null

    /**
     * 紧贴右边缘那块小面板。只在 PANEL 形态存在，和主窗口一起建、一起拆 ——
     * 它是主窗口的附属，没有"单独开右面板"这种状态。
     */
    private var sideView: OverlayRootView? = null
    private val sideParams = WindowManager.LayoutParams()

    /** 上一次排窗口时是什么形态。只有它变了才让双击配对重新起算，见 applyLayout */
    private var appliedForm: OverlayForm? = null
    /** 收起控制台后那块一次性的引导窗，和主窗口互不影响 */
    private var guideView: ComposeView? = null
    private val params = WindowManager.LayoutParams()
    private var posX = 0
    private var posY = 0
    private var volumeObserver: ContentObserver? = null
    /** 音量广播：主通道。每按一下音量键系统都发一条，改没改值都发 */
    private var volumeBroadcast: BroadcastReceiver? = null
    /** Settings.System 里的媒体音量档位；-1 = 没读到过。这条是备用通道 */
    private var lastVolumeValue = -1
    /** AudioManager 报的媒体音量档位。实测 Settings 那个键在部分机器上根本不更新 */
    private var lastMusicIndex = -1
    /**
     * 待执行的那一次开/合。音量变化不立刻动作，先等 SettleMs ——
     * 这一小段就是留给"压低又弹回"的反悔窗口。
     */
    private var volumeJob: Job? = null
    private var pendingDir = 0
    /** 这一次变化之前的档位，回弹判定要用它；-1 = 边界按压，本来就没有"之前" */
    private var pendingBefore = -1
    /** pendingBefore 是在哪条通道上读到的：0=Settings 1=AudioManager，两边量纲不同不能互比 */
    private var pendingSpace = -1
    private var lastActionAt = 0L
    private var lastActionDir = 0
    /** 上一次送来的信号（方向 + 时刻），两条通道各报一次同一下按键时用它去重 */
    private var lastSignalAt = 0L
    private var lastSignalDir = 0
    /** 引导动画一次服务生命周期只弹一次：音量减键是日常动作，不该每次都看教学 */
    private var guideShownThisSession = false

    override val form: OverlayForm get() = store.overlayForm.value
    override val isExpanded: Boolean get() = expanded.value

    override fun onCreate() {
        super.onCreate()
        savedStateController.performAttach()
        // 服务这一侧没有"配置变更重建"这回事，注册表里永远是空的；
        // performRestore 仍要调一次，Owner 的契约是 attach 与 restore 都发生过，
        // 只 attach 的话 Compose 取到的 registry 是未恢复状态
        savedStateController.performRestore(null)
        posX = (12 * resources.displayMetrics.density).toInt()
        posY = (resources.displayMetrics.heightPixels * 0.28f).toInt()
        startForeground(NOTIF_ID, buildNotification())
        watchVolumeKeys()
        // 形态/球径/展开态/防录屏任一变化都重排窗口。这是切换异常的正解：
        // 不再依赖"恰好有人发了一次 start 命令"来对齐两份状态。
        // 防录屏也在里面 —— FLAG_SECURE 是窗口属性，不重排 flags 它不会自己变。
        scope.launch {
            snapshotFlow {
                FormGeometry(
                    store.overlayForm.value, store.ballScale.value,
                    expanded.value, store.preventRecord.value,
                )
            }.collect { applyLayout() }
        }
        // 注意这里不 attachOverlay：窗口的建与不建由 onStartCommand 的 action 决定。
        // 否则"只想同步一下偏好"的启动也会顺手把控制台显示出来，用户没要求过这件事。
    }

    /** snapshotFlow 要比较的是一个值对象：用 Pair 之外的自定义类型，避免每次都是新实例 */
    private data class FormGeometry(
        val form: OverlayForm,
        val ballScale: Float,
        val expanded: Boolean,
        val secure: Boolean,
    )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            // 退场动画还在放的时候又收到唤出（媒体键、应用内开关），意思是"我要它回来"，
            // 不是"再关一次"：所以 exiting 为真时走 attach（它会取消待移除的任务），而不是 hide
            ACTION_TOGGLE -> if (view == null || exiting.value) attachOverlay() else hide()
            ACTION_HIDE -> hide()
            ACTION_DISMISS -> stopSelf()
            else -> if (view == null) attachOverlay()
        }
        // 服务被系统杀掉后要能自己回来，否则"常驻控制台"名不副实；
        // 但绝不能是 START_STICKY + 无条件重建：用户主动隐藏就不该再冒出来。
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 转屏后旧的绝对坐标多半落在屏幕外，把球拉回可视区内
        val side = ballSidePx()
        posX = posX.coerceIn(0, (screenW() - side).toInt().coerceAtLeast(0))
        posY = posY.coerceIn(0, (screenH() - side).toInt().coerceAtLeast(0))
        applyLayout()
    }

    override fun onDestroy() {
        volumeObserver?.let { contentResolver.unregisterContentObserver(it) }
        volumeObserver = null
        volumeBroadcast?.let { runCatching { unregisterReceiver(it) } }
        volumeBroadcast = null
        volumeJob?.cancel()
        volumeJob = null
        dismissGuide()
        detachOverlay()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null

    // ---------------------------------------------------------------- //
    // 窗口                                                             //
    // ---------------------------------------------------------------- //

    private fun attachOverlay() {
        // 唤回优先于退场：动画还没走完就被要求再出现，就取消那次延迟移除
        hideJob?.cancel()
        hideJob = null
        exiting.value = false
        if (view != null) return
        dismissGuide()
        // 走到"挂窗口"这一步，控制台就是被用户要回来的：偏好跟着翻回来，
        // 通知文案也从"已收起"退回正常态。三个入口（应用内开启、媒体键切换、
        // 音量加键）都从这里汇合，不会出现"窗口在、开关却是关的"。
        store.prefs.overlayEnabled = true
        updateNotification(dormant = false)
        val root = OverlayRootView(
            context = this,
            onInsideDown = { store.overlayDownTime.longValue = it },
            onOutsideTap = { onOutsideTap(it) },
            // 三种形态都由 View 层数双击（见 OverlayRootView）：小球和紧凑控制台的第一下
            // 会被 Compose 的 onTap 用掉（展开卡片），第二下只有在这一层才还配得上对。
            // Compose 那边不再写 onDoubleTap，否则一次双击触发两回 toggleForm，等于没换。
            onInsideDoubleTap = { if (form != OverlayForm.PANEL) toggleForm() },
        )
        val cv = root.content
        // tag 打在 root 上而不是 ComposeView 上：Compose 要 Recomposer 时先取 contentChild，
        // 也就是真正挂进 WindowManager 的那一层，再从它身上找 ViewTreeLifecycleOwner。
        // 现在那一层是 OverlayRootView，tag 只打在里面的 ComposeView 会够不着，
        // addView 后第一次布局当场 FATAL（ViewTreeLifecycleOwner not found）。
        root.setViewTreeLifecycleOwner(this)
        root.setViewTreeSavedStateRegistryOwner(this)
        cv.setContent {
            // 这对 provides 与上面的 tag 不冲突：tag 供 View 树查询（Recomposer 用），
            // Local 供 Composable 查询（rememberSaveable 用），两条取值链各自独立。
            CompositionLocalProvider(
                LocalLifecycleOwner provides this@OverlayService,
                LocalSavedStateRegistryOwner provides this@OverlayService,
            ) {
                // 偏好变化的读依赖由 PanelConsole 自己建立（它才是 prefs 的消费者）。
                // 在这里读一遍只会让整棵树跟着重组合，小球也不例外地跟着重建 —— 多余。
                val st by store.state.collectAsState()
                OverlayRoot(
                    win = this@OverlayService,
                    store = store,
                    state = st,
                    themeMode = store.themeMode.value,
                    form = store.overlayForm.value,
                    expanded = expanded.value,
                    ballScale = store.ballScale.value,
                    chrome = panelChrome(),
                    exiting = exiting.value,
                )
            }
        }
        applyParamsLocked()
        runCatching { wm.addView(root, params) }
            .onFailure {
                // 权限被撤销时会抛异常：如实停服务，让 UI 回到"需要重新授权"
                store.note("悬浮窗创建失败，请重新授予显示在其他应用上层")
                stopSelf()
                return
            }
        view = root
        attachSide()
    }

    private fun detachOverlay() {
        detachSide()
        view?.let { runCatching { wm.removeView(it) }; it.content.disposeComposition() }
        view = null
        store.panelInsetPx.value = 0
    }

    /**
     * 右侧小面板。宽和高都是大面板的三分之二，贴在右缘的正中间，装的是我的 / 信息 / 开关 / 排行四页。
     *
     * 单独一块窗口而不是画在左面板里：右缘那条位置本来就在左面板窗口之外，
     * 画进去只会让左面板横穿整屏，把要测的游戏画面全盖住。分成两块，
     * 中间留出的空档还能让灵动岛站在谁都不挡的位置上。
     */
    private fun attachSide() {
        if (store.overlayForm.value != OverlayForm.PANEL) return
        if (sideView != null) return
        val root = OverlayRootView(
            context = this,
            onInsideDown = { store.overlayDownTime.longValue = it },
            onOutsideTap = { onOutsideTap(it) },
            onInsideDoubleTap = { if (form != OverlayForm.PANEL) toggleForm() },
        )
        root.setViewTreeLifecycleOwner(this)
        root.setViewTreeSavedStateRegistryOwner(this)
        root.content.setContent {
            CompositionLocalProvider(
                LocalLifecycleOwner provides this@OverlayService,
                LocalSavedStateRegistryOwner provides this@OverlayService,
            ) {
                val st by store.state.collectAsState()
                // 主题必须在这一层再给一遍：CompositionLocal 不跨窗口，
                // 左面板那层的 EntropyTheme 覆盖不到这块画布
                SideConsoleRoot(
                    win = this@OverlayService,
                    store = store,
                    state = st,
                    themeMode = store.themeMode.value,
                    // 这块垂直居中在右缘，上下都不落在状态栏或操作条底下，那份内边距一分也不需要
                    chrome = OverlayChrome(0f, 0f),
                )
            }
        }
        applySideParams()
        // 加窗口失败（权限刚被撤销）时不把 root 记成"已经挂着"：
        // 记了以后 syncSide 会对一块不在窗口管理器里的视图 updateViewLayout，拆不掉也补不上
        runCatching { wm.addView(root, sideParams) }.onSuccess { sideView = root }
            .onFailure { store.sideInsetPx.value = 0 }
    }

    private fun detachSide() {
        sideView?.let { runCatching { wm.removeView(it) }; it.content.disposeComposition() }
        sideView = null
        store.sideInsetPx.value = 0
    }

    /**
     * 右侧小面板的尺寸与落点。
     *
     * 宽度是大面板的三分之二（见 panelWidths），高度取大面板高度的三分之二、
     * 垂直居中贴在右缘：整块面板悬浮在屏幕中间，上下都不碰边，
     * 灵动岛要站的顶部空档也就不会被这块压住。
     *
     * 单独一块窗口而不是画在左面板里：右缘那条位置本来就在左面板窗口之外，
     * 画进去只会让左面板横穿整屏，把要测的游戏画面全盖住。分成两块，
     * 中间留出的空档还能让灵动岛站在谁都不挡的位置上。
     *
     * 下限是那条图标栏本身：栏高不够就只会把四个图标裁掉，那是比"矮一点"严重得多的事。
     */
    private fun applySideParams() {
        val sw = panelWidths().second.toInt()
        val n = SideTab.entries.size
        val railPx = dp((SideItemH * n + SideGap * (n - 1) + 12).toFloat()) + dp(12f)
        val h = maxOf(railPx, panelHeightPx() * SIDE_H_OF_PANEL)
            .coerceAtMost(screenH())
        sideParams.width = sw
        sideParams.height = h.toInt()
        store.sideInsetPx.value = sw
        sideParams.format = PixelFormat.TRANSLUCENT
        sideParams.type = overlayType()
        // 这块也要听窗口外那一下：点空白收起看的是"这一下有没有落在控制台上"，
        // 按在左面板里对这块就是 outside，靠 overlayDownTime 那一层挡掉（见 onOutsideTap）
        sideParams.flags = panelFlags(watchOutside = true)
        // 同样用 LEFT 而不是 START：这块的契约是"贴屏幕右缘"，用 START 在 RTL 下会翻到左边
        sideParams.gravity = Gravity.LEFT or Gravity.TOP
        sideParams.x = (screenW() - sw).toInt().coerceAtLeast(0)
        sideParams.y = ((screenH() - h) / 2).toInt().coerceAtLeast(0)
    }

    /** 右面板跟着形态走：回到小球就拆掉，PANEL 就补上或重排 */
    private fun syncSide() {
        if (store.overlayForm.value != OverlayForm.PANEL) { detachSide(); return }
        val sv = sideView ?: run { attachSide(); return }
        applySideParams()
        runCatching { wm.updateViewLayout(sv, sideParams) }.onFailure {
            detachSide()
            attachSide()
        }
    }

    /** 两块面板共用的窗口类型 */
    private fun overlayType(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    else
        @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    /** 两块面板共用的 flag 组合 */
    private fun panelFlags(watchOutside: Boolean): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            // 防录屏：secure 层不参与录屏/截屏的合成，面板与岛会一起从成片里消失
            (if (store.preventRecord.value) WindowManager.LayoutParams.FLAG_SECURE else 0) or
            // 只有"成块"的形态才听窗口外那一下：小球天天被拖着走，给它挂上这个标志
            // 等于屏幕上每一下都要多派一次事件，而小球本来就没有"点外面收起"这一说
            (if (watchOutside) WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH else 0)

    /**
     * 尺寸按形态给确定值，不用 WRAP_CONTENT。
     * ComposeView 在 WindowManager 里拿到的是 UNSPECIFIED 测量规格，
     * WRAP_CONTENT 会被测成 0x0 —— 窗口在，内容看不见。
     */
    private fun applyParamsLocked() {
        val f = store.overlayForm.value
        val (w, h) = when {
            f == OverlayForm.PANEL -> panelWidthPx() to panelHeightPx()
            expanded.value -> expandedBallSizePx()
            else -> ballSidePx().let { it to it }
        }
        params.width = w
        params.height = h
        // 灵动岛据此把自己挪到"面板之外"的正中，见 EntropyStore.panelInsetPx
        store.panelInsetPx.value = if (f == OverlayForm.PANEL) w else 0
        params.format = PixelFormat.TRANSLUCENT
        // LayoutParams 的字段名是 type，不是 windowType（那是 SurfaceControl 的叫法）
        params.type = overlayType()
        params.flags = panelFlags(
            // 大面板和小球那张展开卡片都要听窗口外那一下（点空白处收起，见 onOutsideTap）。
            // 贴在屏幕上的小球本身不听：它天天被拖着走，挂这个标志等于屏幕上每一下多派一次事件
            watchOutside = f == OverlayForm.PANEL || expanded.value)
        // 用 LEFT 而不是 START：控制台的契约是"贴屏幕左侧"，
        // 在阿拉伯语这类 RTL 布局下 START 会把它贴到右边去。
        params.gravity = Gravity.LEFT or Gravity.TOP
        if (f == OverlayForm.PANEL) {
            // 左上角钉在 (0, 边距)：拿小球坐标去放它会随用户上次拖到哪儿就偏哪儿，
            // 底部那条操作栏会被 FLAG_LAYOUT_NO_LIMITS 推到屏幕外（点不到也看不见）。
            // 上下各让出 PANEL_EDGE_DP —— 这块不再贴屏幕边，窗口高度也是按同一道边距算的，
            // 两处必须一起动。posX/posY 本身不动 —— 切回小球时还回到用户拖到的位置。
            params.x = 0
            params.y = dp(PANEL_EDGE_DP).toInt()
        } else {
            // 展开态的窗口比小球大得多：小球贴着边时还按小球坐标摆，
            // 控制台就有一半在屏幕外。把左上角钳回可视区，posX/posY 本身不动。
            params.x = posX.coerceIn(0, ((screenW() - w).toInt()).coerceAtLeast(0))
            params.y = posY.coerceIn(0, ((screenH() - h).toInt()).coerceAtLeast(0))
        }
    }

    /**
     * 左右两块面板的宽度（px）。
     *
     * 不是"半个屏幕"那么写死的：左侧标签栏要 52dp，卡片里的滑杆和大数字还要一整列，
     * 竖屏手机上拿半屏（约 180dp）排版会挤成一团；横屏平板上半屏又宽到一行能塞三个
     * 读数，读起来反而散。左块按 0.44 倍屏宽走，右块是大面板的三分之二，
     * 两端各钉一个下限。
     *
     * 两块加起来不许超出屏幕：窄屏上 300dp + 210dp 已经比屏宽还大，那种时候
     * 按比例一起缩，而不是只砍右块 —— 只砍一边会让右块掉到没法排版。
     */
    private fun panelWidths(): Pair<Float, Float> {
        val gap = dp(6f)
        val left = (screenW() * 0.44f).coerceAtLeast(dp(300f)).coerceAtMost(screenW())
        val right = (left * SIDE_W_OF_LEFT).coerceAtLeast(dp(210f)).coerceAtMost(screenW())
        if (left + right + gap <= screenW()) return left to right
        val k = ((screenW() - gap) / (left + right)).coerceAtLeast(0.42f)
        return (left * k) to (right * k)
    }

    private fun panelWidthPx(): Int = panelWidths().first.toInt()

    /**
     * 大面板的高度（px）：整屏高减去上下两道边距。
     * 同一个边距还决定了窗口落点（applyParamsLocked）和内部还剩多少内边距（panelChrome），
     * 三处共用这一个数，改一处就得改三处。
     */
    private fun panelHeightPx(): Int =
        (screenH() - 2 * dp(PANEL_EDGE_DP)).coerceIn(dp(240f), screenH()).toInt()

    /**
     * 大面板的 chrome。窗口自己已经往下让了 PANEL_EDGE_DP，状态栏和底部操作条那份内边距
     * 就跟着少同样的量，不然顶部会空出两道。缺口（cutout）不减：刘海是从屏幕顶往下垂的，
     * 让开的那 12dp 躲不开它。
     */
    private fun panelChrome() = OverlayChrome(
        (dpOf(statusBarPx()) - PANEL_EDGE_DP).coerceAtLeast(0f),
        (dpOf(navBarPx()) - PANEL_EDGE_DP).coerceAtLeast(0f),
        dpOf(cutoutInsetPx()),
    )

    /** 小球展开成紧凑控制台时的窗口尺寸：宽度同样不能超出屏幕 */
    private fun expandedBallSizePx(): Pair<Int, Int> {
        val w = dp(BALL_CARD_W_DP).coerceAtMost(screenW() - dp(8f))
        val h = dp(BALL_CARD_H_DP).coerceAtMost(screenH() - topInsetPx() - dp(8f))
        return w.toInt().coerceAtLeast(dp(150f).toInt()) to
            h.toInt().coerceAtLeast(dp(150f).toInt())
    }

    private fun applyLayout() {
        val v = view ?: return
        // 只有真的换了形态才让之前那一下作废：同一块视图在小球 / 面板之间复用时，
        // "卡片上的第二下"会和稍后面板上的任意一下拼成一次双击。
        // 但小球展开成紧凑控制台不算换形态 —— 那一次展开正是双击的第一下，
        // 在这里把它抹掉，双击切形态就永远配不成对。
        if (appliedForm != form) v.forgetTap()
        appliedForm = form
        applyParamsLocked()
        // 换形态时宽高和坐标一起变。个别 ROM 在尺寸跨越很大时会拒绝 update
        // （IllegalArgumentException: View not attached），重建一次窗口即可，
        // 不能只把异常吞掉 —— 那会变成"窗口还在，但永远停在旧尺寸"。
        runCatching { wm.updateViewLayout(v, params) }.onFailure {
            detachOverlay()
            attachOverlay()
        }
        syncSide()
    }

    /**
     * 窗口外面那一下：大面板收回小球，展开的紧凑卡片收回去。
     *
     * 中间那一版按用户要求改成"大面板点外面不动"，这一版又还原回收起 ——
     * 两种诉求都是他们提的，以最后一次为准，所以这里保留的是最早那条契约：
     * 空白处一下，控制台就走。
     *
     * 两块面板都挂了 WATCH_OUTSIDE_TOUCH，一次落在左面板里的按下会给右面板送来一次
     * outside，看着就像"点了外面"。于是先等一会儿，再问"这一下有没有真的按在控制台上"：
     * 两个面板的 OverlayRootView 都往 overlayDownTime 里记自己的 DOWN（attachOverlay /
     * attachSide 各一处），记下的不小于这次 outside 的 downTime，就是同一次手势，不算点外面。
     * 灵动岛同理，问 hudDownTime。ACTION_OUTSIDE 本身问不出落点 —— 坐标实测恒为 (0,0)。
     */
    private fun onOutsideTap(downTime: Long) {
        if (form != OverlayForm.PANEL && !expanded.value) return
        outsideJob?.cancel()
        outsideJob = scope.launch {
            delay(OutsideSettleMs)
            if (store.hudDownTime.longValue >= downTime) return@launch
            if (store.overlayDownTime.longValue >= downTime) return@launch
            // 等完这 260ms 里形态可能已经被别的路径改过，所以这里重新问一次再决定收哪一层
            if (form == OverlayForm.PANEL) setForm(OverlayForm.BALL) else setExpanded(false)
        }
    }

    /** 收起态小球的窗口边长：球本身 + 一圈留给捏合手势与阴影的余量 */
    private fun ballSidePx(): Int =
        dp(BALL_BASE_DP * store.ballScale.value + BALL_PAD_DP).toInt()

    private fun screenW(): Float = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        wm.currentWindowMetrics.bounds.width().toFloat()
    else resources.displayMetrics.widthPixels.toFloat()

    private fun screenH(): Float = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        wm.currentWindowMetrics.bounds.height().toFloat()
    else resources.displayMetrics.heightPixels.toFloat()

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun dpOf(px: Int): Float = px / resources.displayMetrics.density

    /**
     * 系统栏高度。窗口开了 FLAG_LAYOUT_NO_LIMITS，内容会顶到状态栏底下，
     * 大面板又是整屏高的，不自己让位的话第一行卡片和底部操作栏都会被压住。
     */
    private fun statusBarPx(): Int = systemDimen("status_bar_height", 24f)

    private fun navBarPx(): Int = systemDimen("navigation_bar_height", 0f)

    /** 刘海占掉的、状态栏还没让开的那一段。没有刘海就是 0 */
    private fun cutoutInsetPx(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        val cutout = runCatching {
            wm.currentWindowMetrics.windowInsets.getDisplayCutout()?.safeInsetTop ?: 0
        }.getOrDefault(0)
        return (cutout - statusBarPx()).coerceAtLeast(0)
    }

    /** 内容顶部要避让的总高度：状态栏 + 刘海多出来的那截 */
    private fun topInsetPx(): Int = statusBarPx() + cutoutInsetPx()

    private fun systemDimen(name: String, fallbackDp: Float): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) runCatching { resources.getDimensionPixelSize(id) }
            .getOrDefault(dp(fallbackDp).toInt())
        else dp(fallbackDp).toInt()
    }

    // ---------------------------------------------------------------- //
    // OverlayWindow                                                    //
    // ---------------------------------------------------------------- //

    override fun setForm(which: OverlayForm) {
        if (form == which) return
        store.setOverlayForm(which)
        // 展开态属于小球那一套：切形态时收掉，否则回到小球会顶着一个
        // 半开不开的控制台，窗口尺寸和内容对不上
        expanded.value = false
        applyLayout()
    }

    override fun toggleForm() = setForm(if (form == OverlayForm.PANEL) OverlayForm.BALL else OverlayForm.PANEL)

    override fun setExpanded(value: Boolean) {
        if (form == OverlayForm.PANEL) {
            // 大面板没有"展开"这一档，整块屏左半边本来就是它的展开态
            setForm(OverlayForm.BALL)
            return
        }
        expanded.value = value
        applyLayout()
    }

    override fun launchUfo() {
        // 悬浮窗不能自己变成全屏测试画面：那样它会盖住要测的东西，
        // 而且测完没法回到 App。交给 Activity 画，窗口只负责唤出。
        startActivity(Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_UFO, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    override fun openApp() {
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    override fun hide() {
        store.prefs.overlayEnabled = false
        if (view == null) { finishHide(); return }
        if (hideJob != null) return
        exiting.value = true
        hideJob = scope.launch {
            // 多给 60ms：动画跑完那一帧才算真的"看不见"，卡在刚好等长时会截掉尾帧。
            // 用 delay 而不是等 Compose 回调 —— 组合被提前 dispose 时回调根本不会来，
            // 那条路会让这块窗永远留在屏幕上。
            delay(ExitAnimMs + 60L)
            hideJob = null
            finishHide()
        }
    }

    /** 隐藏的后半段：移除窗口、通知退回"已收起"、按需弹一次按键引导 */
    private fun finishHide() {
        exiting.value = false
        detachOverlay()
        // 窗口没了，服务留着。这是"完全隐藏"与"退出"的分界：
        // 音量键的监听挂在 ContentObserver 上，跟着服务生死 —— stopSelf 之后
        // "音量加键唤回"就成了空头承诺，引导动画也不要弹了。
        // 真要彻底退出，通知栏上的动作（ACTION_DISMISS）就是那条路。
        updateNotification(dormant = true)
        // 音量减键现在是日常动作，不是"最后一次关掉"。每次收起都弹八秒教学，
        // 熟练用户会被自己的开关动作反复打断 —— 一次服务生命周期只教一次。
        if (!guideShownThisSession && store.showVolumeGuide.value) {
            guideShownThisSession = true
            showVolumeGuide()
        }
    }

    // ---------------------------------------------------------------- //
    // 收起后的引导动画                                                  //
    // ---------------------------------------------------------------- //

    private val guideTimeout = Runnable { dismissGuide() }

    /**
     * 一块 8 秒后自己消失的提示窗：手机 + 两颗音量键，先演加键把控制台推开，再演减键把它收回去。
     *
     * 窗口只占 236x196dp，且是 NOT_TOUCH_MODAL：窗口外的触摸全部落给游戏，
     * 窗口内那一下留给"点掉这块提示"。系统对穿透型浮窗强制 80% 不透明度，
     * 要让底板真正挡住游戏画面，就只能接收触摸 —— 这笔账记在这儿。
     * "以后别再提示"的开关在应用内（我的 · 悬浮控制台）。
     */
    private fun showVolumeGuide() {
        if (guideView != null) return
        val cv = ComposeView(this)
        cv.setViewTreeLifecycleOwner(this)
        cv.setViewTreeSavedStateRegistryOwner(this)
        cv.setContent {
            CompositionLocalProvider(
                LocalLifecycleOwner provides this@OverlayService,
                LocalSavedStateRegistryOwner provides this@OverlayService,
            ) {
                EntropyTheme(
                    themeMode = store.themeMode.value,
                    accentArgb = store.prefs.accentColor,
                    highlightArgb = store.highlightColor.intValue,
                    palette = store.palette.value,
                ) { VolumeKeyGuide(onDismiss = { mainHandler.post { dismissGuide() } }) }
            }
        }
        val p = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            // 不能加 FLAG_NOT_TOUCHABLE：系统认得出"穿透型"浮窗，会强制把它压到
            // 80% 不透明（dumpsys 里 mAttrs alpha=0.8），底板于是透出游戏画面。
            // 接收触摸的窗口不会被压。NOT_TOUCH_MODAL 保证窗口外的触摸照常落到游戏。
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                // 引导动画也是熵自己的浮层，防录屏开着时它同样不该进成片
                (if (store.preventRecord.value) WindowManager.LayoutParams.FLAG_SECURE else 0)
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            width = dp(GUIDE_W_DP).toInt()
            height = dp(GUIDE_H_DP).toInt()
            // 压在底部导航条上方，不盖住游戏里的拇指区
            y = dp(GUIDE_BOTTOM_DP).toInt()
        }
        runCatching { wm.addView(cv, p) }.onSuccess {
            guideView = cv
            mainHandler.postDelayed(guideTimeout, GUIDE_MS)
        }
    }

    private fun dismissGuide() {
        mainHandler.removeCallbacks(guideTimeout)
        guideView?.let { runCatching { wm.removeView(it) }; it.disposeComposition() }
        guideView = null
    }

    override fun onDrag(dx: Float, dy: Float) {
        // 钳在屏幕内：拖出边界后 updateViewLayout 不报错，但球就再也点不回来了
        val side = ballSidePx()
        posX = (posX + dx).toInt().coerceIn(0, (screenW() - side).toInt().coerceAtLeast(0))
        posY = (posY + dy).toInt().coerceIn(0, (screenH() - side).toInt().coerceAtLeast(0))
        applyLayout()
    }

    override fun onScale(delta: Float) {
        // 逐事件相乘并限幅：Compose 给的是相邻两次事件的比值，不是相对初始值。
        // 钳制在 Prefs 里做，所以这里只表达意图，不自己算区间。
        store.setBallScale(store.ballScale.value * delta)
    }

    override fun toggleHud() {
        // 灵动岛是另一块窗、另一个服务：控制台收起不该把它一起带走，
        // 所以这里只做"开关翻转"，不跟随本服务的生命周期
        SafeHudService.toggle(this)
    }

    // ---------------------------------------------------------------- //
    // 音量键开合                                                        //
    // ---------------------------------------------------------------- //

    private fun watchVolumeKeys() {
        // 主通道：音量广播。每按一下音量键系统都发一条 android.media.VOLUME_CHANGED_ACTION，
        // 改没改值都发 —— 所以顶到格、触到底那一下它照样响，这是 Settings 监听做不到的。
        // 方向不在广播里，只能自己记上一档：发广播时系统已经把新值写进去了，读一次就知道涨跌。
        //
        // 常量用字面量而不是 AudioManager 上的名字：ACTION_VOLUME_CHANGED /
        // EXTRA_VOLUME_STREAM_TYPE 在公开 SDK 里是 @hide，写了编不过。抄的是 AOSP 的字面值，
        // 拼错的后果是"注册成功但永远收不到"，和音量键坏了长得一模一样，只能靠注释钉住。
        val br = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.getIntExtra(VolumeStreamTypeExtra, -1) !=
                    AudioManager.STREAM_MUSIC) return
                val am = audio() ?: return
                val music = AudioManager.STREAM_MUSIC
                val cur = runCatching { am.getStreamVolume(music) }.getOrDefault(-1)
                if (cur < 0) return
                val prev = lastMusicIndex
                lastMusicIndex = cur
                lastVolumeValue = readVolume()
                val dir = when {
                    prev < 0 -> return
                    cur > prev -> 1
                    cur < prev -> -1
                    // 值一动没动：只能是贴着边上按。判不出方向就算了 ——
                    // 只有真的在最大/最小格时才认，中间某一格被别的原因重发不会误判。
                    cur >= am.getStreamMaxVolume(music) -> 1
                    cur <= 0 -> -1
                    else -> return
                }
                onVolumeStep(dir, prev, cur, SpaceAudio)
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                registerReceiver(br, IntentFilter(VolumeChangedAction),
                    Context.RECEIVER_NOT_EXPORTED)
            else
                registerReceiver(br, IntentFilter(VolumeChangedAction))
        }.onSuccess { volumeBroadcast = br }
        // 先记一次当前档位，否则第一下按键只有"新值"没有"上一档"，方向判不出来
        audio()?.let { am ->
            lastMusicIndex = runCatching {
                am.getStreamVolume(AudioManager.STREAM_MUSIC)
            }.getOrDefault(-1)
        }

        // 备用通道：监听 Settings.System 里的 "volume_music"。
        // 留着它是因为部分厂商 ROM 上音量广播被裁剪，而这一项照常更新；
        // 反过来实测（MuMu / API 35）这个键常年停在 5 一动不动，所以它只能当备份。
        // SDK 里没有 VOLUME_MUSIC 常量，只能字面量 + 注释钉住键名。
        val uri = Settings.System.getUriFor(VOLUME_MUSIC_KEY) ?: return
        lastVolumeValue = readVolume()
        val obs = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                // 只认"音量值真的动了"。settings provider 把整张表的变更都发到
                // content://settings/system 及其子路径上，转屏、亮度这类写入同样会敲到这里 ——
                // 实测一次转屏就连着敲两下，控制台自己收成小球。通知不可信，值才可信。
                val value = readVolume()
                if (value == lastVolumeValue) return
                val dir = if (value > lastVolumeValue) 1 else -1
                val before = lastVolumeValue
                lastVolumeValue = value
                onVolumeStep(dir, before, value, SpaceSettings)
            }
        }
        contentResolver.registerContentObserver(uri, false, obs)
        volumeObserver = obs
    }

    /** 读不到就当没变：-1 是 Settings.System.getInt 的默认值，不会和任何真实档位撞上 */
    private fun readVolume(): Int =
        runCatching { Settings.System.getInt(contentResolver, VOLUME_MUSIC_KEY, -1) }.getOrDefault(-1)

    private fun audio(): AudioManager? = getSystemService(AUDIO_SERVICE) as? AudioManager

    /**
     * 一次音量变化到这里，决定它算不算"按了一颗键"。
     *
     * 四层过滤，按能不能修排序：
     *  0) 同一下按键被广播和 Settings 各报一次 → 150ms 内同方向只认第一条；
     *  1) 同方向连跳（长按音量键，系统每几十毫秒进一格）只认第一下落地的那次动作，
     *     不然一次长按就是一串开合，看着像面板在抽风；
     *  2) 反方向、并且正好走回这一轮开始前的档位 → 判成 ducking 的回弹，
     *     把还没执行的动作撤掉，这一压一弹两边都不作数。跨通道不比对：
     *     Settings 的 0~15 和 AudioManager 的 0~100 不是同一把尺，只有同一条通道上的
     *     before/current 才可比（space 就是干这个的）；
     *  3) 剩下的排一次 SettleMs 之后的动作 —— 这点延迟就是换第 2 条的代价，
     *     实测按键到面板出现约 0.3 秒，读起来仍然像"按键叫出来的"。
     */
    private fun onVolumeStep(dir: Int, before: Int, current: Int, space: Int) {
        val now = System.currentTimeMillis()
        if (dir == lastSignalDir && now - lastSignalAt < SignalDedupeMs) return
        lastSignalDir = dir
        lastSignalAt = now
        if (dir == lastActionDir && now - lastActionAt < RepeatGuardMs) return
        val pending = volumeJob
        if (pending != null && dir == -pendingDir && space == pendingSpace &&
            pendingBefore >= 0 && current == pendingBefore) {
            pending.cancel()
            volumeJob = null
            pendingDir = 0
            pendingBefore = -1
            pendingSpace = -1
            return
        }
        // 同方向续期时保留最早那个起点，长按抬到顶再一路按回来仍能识别成回弹
        val keptBefore = if (pending != null && pendingDir == dir) pendingBefore else before
        val keptSpace = if (pending != null && pendingDir == dir) pendingSpace else space
        pending?.cancel()
        pendingDir = dir
        pendingBefore = keptBefore
        pendingSpace = keptSpace
        volumeJob = scope.launch {
            delay(SettleMs)
            volumeJob = null
            pendingDir = 0
            pendingBefore = -1
            pendingSpace = -1
            lastActionAt = System.currentTimeMillis()
            lastActionDir = dir
            if (dir > 0) openConsole() else closeConsole()
        }
    }

    /** 音量加键：没显示就显示；小球就点开成紧凑控制台；已经开着就保持原样 */
    private fun openConsole() {
        if (view == null) { attachOverlay(); return }
        if (form == OverlayForm.BALL && !expanded.value) setExpanded(true)
    }

    /** 音量减键：整块控制台收起。窗口移除、服务留着继续听键，退场动画走完才算真没了 */
    private fun closeConsole() {
        if (view == null && !exiting.value) return
        hide()
    }

    // ---------------------------------------------------------------- //
    // 通知                                                             //
    // ---------------------------------------------------------------- //

    private fun buildNotification(dormant: Boolean = false): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // 显示中 → "隐藏"只管收起窗口（服务继续听音量键）；
        // 已收起 → "退出"才是真的停服务。两个按钮的字和事必须对上，
        // 否则用户点了"隐藏"却发现音量键也不灵了，只会以为功能坏了。
        val action = if (dormant) ACTION_DISMISS else ACTION_HIDE
        val actionLabel = getString(
            if (dormant) R.string.overlay_notif_quit else R.string.overlay_notif_close)
        val close = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, EntropyApp.OVERLAY_CHANNEL)
            .setSmallIcon(R.drawable.ic_entropy_ball)
            .setContentTitle(getString(R.string.overlay_channel))
            .setContentText(
                getString(if (dormant) R.string.overlay_notif_hidden else R.string.overlay_notif_hint))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, actionLabel, close)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return builder.build()
    }

    /** 收起/唤回时只换通知内容，不停服务：NotificationManager 的 id 与 startForeground 同一个 */
    private fun updateNotification(dormant: Boolean) {
        runCatching {
            (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .notify(NOTIF_ID, buildNotification(dormant))
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.entropy.overlay.TOGGLE"
        const val ACTION_DISMISS = "com.entropy.overlay.DISMISS"

        /** 收起窗口但保留服务：音量键监听不能跟着窗口一起死 */
        const val ACTION_HIDE = "com.entropy.overlay.HIDE"
        private const val NOTIF_ID = 41001
        /** 一次音量变化等多久才算数：给 ducking 的回弹留出反悔窗口，见 onVolumeStep */
        private const val SettleMs = 260L
        /** 动作落地后同方向再跳这么多毫秒内的都算同一次长按的连跳，不再重复开合 */
        private const val RepeatGuardMs = 900L
        /** 同一下按键在两条通道上各报一次的间隔极小，这么近的同方向信号只认第一条 */
        private const val SignalDedupeMs = 150L
        /** onVolumeStep 的档位量纲：Settings 和 AudioManager 各有各的刻度，不能互比 */
        private const val SpaceSettings = 0
        private const val SpaceAudio = 1
        private const val VOLUME_MUSIC_KEY = "volume_music"
        /** AudioManager 里这两个常量是 @hide，只能照抄它们的字面值 */
        private const val VolumeChangedAction = "android.media.VOLUME_CHANGED_ACTION"
        private const val VolumeStreamTypeExtra = "android.media.EXTRA_VOLUME_STREAM_TYPE"

        /** 引导窗：尺寸给死（WRAP_CONTENT 在 WindowManager 里会被测成 0），8 秒后自己走 */
        private const val GUIDE_W_DP = 236f
        private const val GUIDE_H_DP = 210f
        private const val GUIDE_BOTTOM_DP = 92f
        private const val GUIDE_MS = 8200L

        /** 原子小球基准直径与窗口余量（dp）。44dp 是"游戏里不挡视线"的上限，再大就该用控制台 */
        private const val BALL_BASE_DP = 44f
        private const val BALL_PAD_DP = 14f

        /** 大面板上下各让出的边距（dp）：窗口高度、落点、内部内边距三处共用，见 panelHeightPx */
        private const val PANEL_EDGE_DP = 12f

        /** 右侧小面板的高和宽都取大面板的三分之二 —— 两块是一个体系里的两件东西，不是一个方一个扁 */
        private const val SIDE_H_OF_PANEL = 2f / 3f
        private const val SIDE_W_OF_LEFT = 2f / 3f

        /** 小球点开后的紧凑控制台尺寸（dp），实际会用屏幕尺寸再钳一次 */
        /** 小球点开后的紧凑控制台尺寸（dp），实际会用屏幕尺寸再钳一次。
         *  高度按内容实测重算过：去掉 Root 时"档位需要 …"那两行不会出现，
         *  但解锁后它会出现，232dp 会把底部「控制台/隐藏」两颗按钮裁掉一半。 */
        private const val BALL_CARD_W_DP = 196f
        private const val BALL_CARD_H_DP = 268f

        /** 悬浮窗权限。Android 6 以下装机即有，6+ 必须由用户授予 */
        fun canDraw(ctx: android.content.Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)

        /** 开启（或唤回）悬浮控制台。形态由 store 里的单一来源决定，这里不再传参 */
        fun start(ctx: android.content.Context) {
            EntropyStore.get(ctx).prefs.overlayEnabled = true
            ctx.startForegroundService(Intent(ctx, OverlayService::class.java))
        }

        fun toggle(ctx: android.content.Context) =
            ctx.startForegroundService(Intent(ctx, OverlayService::class.java).setAction(ACTION_TOGGLE))

        /**
         * 只改形态（小球 / 左半屏大面板）。
         * 这里不发 start 命令：形态是 store 的快照状态，正在显示的窗口订阅它会自己重排，
         * 没在显示时这次改动就留到下次开启生效 —— 不该为了同步一个偏好就凭空冒出一块画布。
         */
        fun setForm(ctx: android.content.Context, form: OverlayForm) {
            EntropyStore.get(ctx).setOverlayForm(form)
        }
    }
}
