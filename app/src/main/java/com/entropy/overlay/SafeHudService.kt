// File: app/src/main/java/com/entropy/overlay/SafeHudService.kt
//
// 灵动岛的宿主：1Hz 采样 + 一块顶部居中的常驻窗口 + 过热提醒。
//
// 几个必须写清楚的决定：
//
// 1) 采样只用免 Root 的那条链（HwSampler），再叠加 Daemon 已经推上来的 telemetry。
//    温度优先取 Daemon：它以 root 身份遍历全部热区，能看见普通应用读不到的 gpu/skin；
//    Daemon 不在时退回 sysfs 热区，最后才是电池温度。三条来源都会在展开态里标注，
//    用户看到的每个数字都答得出"你从哪来的"。
//
// 2) 帧率没有近似值。只信两个来源：Daemon 的 LD_PRELOAD 逐帧探针，
//    和 Root 通道下 dumpsys gfxinfo 的累计帧数差分。两个都拿不到就显示"—"，
//    并把原因写在展开态里。用 Choreographer 或 vsync 凑一个数是错的：
//    那样抓到的是本进程的帧，目标游戏的卡顿永远看不见，显示的是一条永不波动的假曲线。
//
// 3) 过热提醒走"上升沿 + 迟滞 + 冷却"。温度一越过提醒线就发一条 HIGH 渠道通知，
//    之后即使持续高温也不再重复轰炸；要回落 2℃ 以下才解除，
//    解除后 2 分钟内再冲高也不重发。没有这套机制的话，
//    阈值附近的正常抖动会被 1Hz 采样变成每分钟几十条通知。
//
// 4) 窗口不可拖动，也没有位置落库这回事：横向永远居中（gravity = TOP|CENTER_HORIZONTAL），
//    纵向让开状态栏与刘海中更大的那个。
//    宽高不给确定值而给 WRAP_CONTENT —— 岛该多大由内容说了算：勾几项就多宽、
//    几行格子就多高。服务只递两条上限（可用宽度要扣掉控制台占掉的那一段、屏高的一截），
//    由 SafeHud 在自己的排版里收。以前按屏幕算死宽高，勾一项和勾四项一样大，
//    多出来的宽度是块点不动的死边，字长了还要被裁掉。
package com.entropy.overlay

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
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
import com.entropy.data.HudItem
import com.entropy.hardware.HwSampler
import com.entropy.shared.Proto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SafeHudService : LifecycleService(), SavedStateRegistryOwner, HudWindow {

    private val store by lazy { EntropyStore.get(this) }
    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val nm by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val expandedState = mutableStateOf(false)
    private val reading = mutableStateOf(HudReading())
    override val expanded: Boolean get() = expandedState.value

    /**
     * 岛的内容最多能长到多宽/多高（px）。
     * 窗口本身是 WRAP_CONTENT，尺寸由内容要多少给多少；这个值只划上界，
     * 所以它必须是个可读的 State —— 转屏、控制台开合都要让排版重收一次。
     */
    private val contentCapPx = mutableStateOf(0 to 0)

    /** 岛那层 FrameLayout，内容在它的 content 里 */
    private var view: OverlayRootView? = null
    private val params = WindowManager.LayoutParams()
    /** 正在等"这一下到底落在谁身上"的那次判定，见 onOutsideTap */
    private var outsideJob: Job? = null

    /**
     * 岛的窗口当前是否排在控制台之上。同类型浮窗没有 z 序 API，只有"重新 add"能改先后，
     * 而这个判据问不出来（WindowManager 不告诉你谁压着谁），所以只能自己记：
     * 面板每被添加一次就作废一次，见 onCreate 里那个 collect。
     */
    private var raisedAbovePanels = false

    private var alertActive = false
    private var lastAlertAt = 0L
    private var tick = 0

    override fun onCreate() {
        super.onCreate()
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        startForeground(NOTIF_ID, buildOngoing(reading.value))
        attach()
        scope.launch { sampleLoop() }
        // 控制台开合会改变岛该居中到哪一片区域，跟着重算一次；面板每重新出现一回，
        // 同类型浮窗的先后顺序就跟着变一回，于是顺手把岛抬回它们之上（见 raiseToTop）
        scope.launch {
            snapshotFlow { store.panelInsetPx.value to store.sideInsetPx.value }
                .distinctUntilChanged().drop(1)
                .collect { (left, right) ->
                    applyGeometry()
                    if (left + right > 0) raiseToTop() else raisedAbovePanels = false
                }
        }
        // 防录屏翻起来要重排 flags —— 那是窗口属性，不重建窗口不会自己变
        scope.launch {
            snapshotFlow { store.preventRecord.value }.drop(1).collect { applyGeometry() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_DISMISS -> {
                store.setHudEnabled(false)
                stopSelf()
            }
            // 过热通知上的"切到省电"：服务本来就活着，动作直接回到这里最省事，
            // 不必为一条按钮广播再注册一个 Receiver
            ACTION_POWERSAVE -> {
                alertActive = false
                nm.cancel(ALERT_ID)
                store.setProfile(Proto.PROFILE_POWERSAVE)
            }
        }
        // 用户主动关掉就不该被系统复活；进程被杀后下次进 App 会按偏好重新拉起
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 转屏不改"顶部居中"这个契约，只需要按新屏幕重算尺寸与避让高度
        applyGeometry()
    }

    override fun onDestroy() {
        detach()
        nm.cancel(ALERT_ID)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null

    // ---------------------------------------------------------------- //
    // 采样                                                             //
    // ---------------------------------------------------------------- //

    private suspend fun sampleLoop() {
        var lastPkg: String? = null
        // 挂起函数里没有 CoroutineScope 接收者，isActive 要从现在的上下文取
        while (currentCoroutineContext().isActive) {
            val pkg = store.prefs.hudTargetPkg
            // 换目标包必须清差分基线：否则第一次差值算的是"上一个应用累计的帧"
            if (pkg != lastPkg) { store.sampler.resetFps(); lastPkg = pkg }
            val sample = withContext(Dispatchers.IO) {
                runCatching { store.sampler.sample(pkg) }.getOrNull()
            }
            val st = store.state.value
            val r = merge(sample, st.telemetry, st.heartbeat, st.safety.fused)
            reading.value = r
            maybeAlert(r)
            if (++tick % NOTIFY_EVERY_N == 0) nm.notify(NOTIF_ID, buildOngoing(r))
            delay(SAMPLE_INTERVAL_MS)
        }
    }

    /** 把两条来源链合成一帧显示数据；任何一路缺了都要说清楚缺在哪 */
    private fun merge(
        s: HwSampler.Sample?,
        t: Proto.Telemetry?,
        h: Proto.Heartbeat?,
        fused: Boolean,
    ): HudReading {
        val alertAtC = store.hudAlertMc.intValue / 1000f

        val daemonTemp = t?.maxTempC
        val tempC: Float?
        val tempSource: String
        when {
            daemonTemp != null -> { tempC = daemonTemp; tempSource = "Daemon 全热区最高值" }
            s?.zoneMaxC != null -> { tempC = s.zoneMaxC; tempSource = "sysfs 热区（免 Root 直读）" }
            s?.batteryC != null -> { tempC = s.batteryC; tempSource = "电池温度（兜底来源）" }
            else -> { tempC = null; tempSource = "thermal 节点不可读，电池温度也没有" }
        }

        val probeFps = t?.fps ?: 0
        val fps: Int?
        val fpsNote: String
        when {
            probeFps > 0 -> {
                fps = probeFps
                fpsNote = if (h?.hookOk == 1) "帧率探针逐帧统计（LD_PRELOAD）"
                          else "帧率探针上报，但心跳未确认在线，值可能滞后"
            }
            s?.fps != null -> { fps = s.fps; fpsNote = s.fpsSource }
            else -> { fps = null; fpsNote = s?.fpsSource ?: "采样中断，无帧率来源" }
        }

        val maxKhz = (t?.cpuKhz?.filter { it > 0 }?.maxOrNull() ?: s?.cpuKhz?.maxOrNull())
        return HudReading(
            tempC = tempC,
            tempSource = tempSource,
            zones = s?.zones ?: emptyList(),
            batteryC = s?.batteryC,
            thermalStatus = s?.thermalStatus ?: -1,
            headroomS = s?.headroomS,
            fps = fps,
            fpsNote = fpsNote,
            cpuMaxMhz = maxKhz?.let { it / 1000 },
            fused = fused,
            alerting = tempC != null && tempC >= alertAtC,
            alertAtC = alertAtC,
        )
    }

    // ---------------------------------------------------------------- //
    // 过热提醒                                                          //
    // ---------------------------------------------------------------- //

    private fun maybeAlert(r: HudReading) {
        val tempC = r.tempC ?: return
        if (!alertActive && r.alerting) {
            val now = System.currentTimeMillis()
            // 冷却期内不重发，即使中间已经回落解除过一次
            if (now - lastAlertAt < ALERT_COOLDOWN_MS) return
            alertActive = true
            lastAlertAt = now
            runCatching { nm.notify(ALERT_ID, buildAlert(tempC, r.alertAtC)) }
            store.recorder.event("hud", "过热提醒 %.1f℃ ≥ 提醒线 %.1f℃".format(tempC, r.alertAtC))
        } else if (alertActive && tempC <= r.alertAtC - ALERT_HYSTERESIS_C) {
            alertActive = false
            nm.cancel(ALERT_ID)
        }
    }

    private fun buildAlert(tempC: Float, alertAtC: Float): Notification {
        val open = PendingIntent.getActivity(
            this, 20, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val save = PendingIntent.getService(
            this, 21, Intent(this, SafeHudService::class.java).setAction(ACTION_POWERSAVE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val body = getString(R.string.hud_alert_text).format(tempC, alertAtC)
        return NotificationCompat.Builder(this, EntropyApp.HUD_ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_entropy_ball)
            .setContentTitle(getString(R.string.hud_alert_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$body ${getString(R.string.hud_alert_desc)}"))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            // 打断性由渠道重要性（HIGH）决定；PRIORITY_MAX 只是给 7.x 之前的老系统看的
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(open)
            .addAction(0, getString(R.string.hud_alert_powersave), save)
            .setAutoCancel(true)
            .build()
    }

    private fun buildOngoing(r: HudReading): Notification {
        val open = PendingIntent.getActivity(
            this, 22, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val dismiss = PendingIntent.getService(
            this, 23, Intent(this, SafeHudService::class.java).setAction(ACTION_DISMISS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = "温度 ${r.tempC?.let { "%.1f℃".format(it) } ?: "—"} · " +
            "帧率 ${r.fps?.toString() ?: "—"} · 提醒线 %.0f℃".format(r.alertAtC)
        val builder = NotificationCompat.Builder(this, EntropyApp.HUD_CHANNEL)
            .setSmallIcon(R.drawable.ic_entropy_ball)
            .setContentTitle(getString(R.string.hud_channel))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.hud_notif_close), dismiss)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return builder.build()
    }

    // ---------------------------------------------------------------- //
    // 窗口                                                             //
    // ---------------------------------------------------------------- //

    private fun attach() {
        if (view != null) return
        if (!OverlayService.canDraw(this)) {
            store.note("安全显示（灵动岛）需要「显示在其他应用上层」权限")
            store.setHudEnabled(false)
            stopSelf()
            return
        }
        val root = OverlayRootView(
            context = this,
            onInsideDown = { store.hudDownTime.longValue = it },
            onOutsideTap = { onOutsideTap(it) },
        )
        val cv = root.content
        // tag 打在 root 上而不是 ComposeView 上：Compose 要 Recomposer 时先取 contentChild，
        // 也就是真正挂进 WindowManager 的那一层，再从它身上找 ViewTreeLifecycleOwner。
        // 现在那一层是 OverlayRootView，tag 只打在里面的 ComposeView 会够不着，
        // addView 后第一次布局当场 FATAL。
        root.setViewTreeLifecycleOwner(this)
        root.setViewTreeSavedStateRegistryOwner(this)
        // 先把上界算好再 setContent：排版第一次量尺寸就要读它，
        // 反过来会让首帧按 0 宽量一次，窗口跟着闪一下 0x0
        applyGeometry()
        cv.setContent {
            CompositionLocalProvider(
                LocalLifecycleOwner provides this@SafeHudService,
                LocalSavedStateRegistryOwner provides this@SafeHudService,
            ) {
                SafeHud(
                    win = this@SafeHudService,
                    reading = reading.value,
                    items = HudItem.of(store.hudItems.intValue),
                    themeMode = store.themeMode.value,
                    accentArgb = store.prefs.accentColor,
                    highlightArgb = store.highlightColor.intValue,
                    palette = store.palette.value,
                    capWidthPx = contentCapPx.value.first,
                    capHeightPx = contentCapPx.value.second,
                )
            }
        }
        runCatching { wm.addView(root, params) }.onFailure {
            store.note("安全显示（灵动岛）创建失败，请重新授予显示在其他应用上层")
            store.setHudEnabled(false)
            stopSelf()
            return
        }
        view = root
    }

    private fun detach() {
        view?.let { runCatching { wm.removeView(it) }; it.content.disposeComposition() }
        view = null
    }

    /**
     * 把岛的窗口重新 add 一次，让它排到同类型浮窗的最上面。
     *
     * TYPE_APPLICATION_OVERLAY 之间没有 z 序 API，系统只按 addView 的先后排队，而岛和控制台
     * 分属两个服务 —— 谁先 add 取决于用户先开哪一个。于是"展开卡片被面板压住"只在一部分
     * 操作顺序上出现，实测就是 dumpsys 里岛排在 panel/side 下面那一档。公开 API 里没有
     * bringToFront，能重排的只有重新 add；代价是 Composition 要重建一次。
     * 调用点选在状态翻转之前：那时候新旧两扇窗画的都是同一条胶囊，位置尺寸内容全同，
     * 旧窗被异步摘掉的那一两帧也只是 95% 的黑底上又叠了一层 95% 的黑底，看不出来。
     * 不用 removeViewImmediate：那一下是在窗口自己的触摸回调里发生的，同步拆自己的窗
     * 等于在事件还没走完的时候把 View 树端走。
     */
    private fun raiseToTop() {
        if (view == null) return
        detach()
        // attach 里 addView 失败会自行收摊（权限没了就停服务），旧窗此刻已经摘了，不会留下半块岛
        attach()
        raisedAbovePanels = view != null
    }

    /**
     * 岛按这一档宽度铺开会不会盖到面板：中间那条空档减去边距还不够这一档就会。
     * 够装就不去付换窗那笔钱 —— 面板出现时已经统一抬过一次（onCreate 里那个 collect），
     * 这里补的是"抬过之后又被别的顺序压回去"那一档。
     */
    private fun willOverlap(floorDp: Float): Boolean {
        val inset = store.panelInsetPx.value + store.sideInsetPx.value
        if (inset <= 0) return false
        return screenW() - inset - dp(16f) < dp(floorDp)
    }

    /**
     * 窗口外面那一下：展开态收回去。收起态本来就没什么可收的，不去动它 ——
     * 岛是常驻读数，不该因为屏幕上随便点了一下就关掉。
     *
     * 判据和控制台那边一样：ACTION_OUTSIDE 问不出这一下落在哪儿，只能改问
     * "同一下有没有真的按在控制台上"。点控制台同样会给岛送来一次 outside，不判就会被误伤。
     */
    private fun onOutsideTap(downTime: Long) {
        if (!expandedState.value) return
        outsideJob?.cancel()
        outsideJob = scope.launch {
            delay(OutsideSettleMs)
            if (store.overlayDownTime.longValue >= downTime) return@launch
            setExpanded(false)
        }
    }

    /**
     * 尺寸与位置。
     *
     * 宽高给 WRAP_CONTENT，让岛按自己装了多少内容收；服务这边只负责三件事：
     * 递上界（[contentCapPx]）、把窗口摆在顶部居中、以及展开态才挂"点外面收起"。
     * 横向靠 gravity 居中，所以"跟着内容缩放"不需要算坐标；
     * 纵向让开状态栏与刘海中更大的那个，越线的顶部会被摄像头挡住。
     */
    private fun applyGeometry() {
        contentCapPx.value = maxContentW() to maxContentH()
        params.width = WindowManager.LayoutParams.WRAP_CONTENT
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.format = PixelFormat.TRANSLUCENT
        params.type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        // NOT_FOCUSABLE + NOT_TOUCH_MODAL：岛只吃掉自己那一小块矩形，其余区域照常点穿
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            // 防录屏。secure 层不参与录屏与截屏的合成，岛和面板会一起从成片里消失
            (if (store.preventRecord.value) WindowManager.LayoutParams.FLAG_SECURE else 0) or
            // 只有展开态才需要"点外面收起"。收起态那条胶囊是常驻读数，
            // 给它挂这个标志只会让屏幕上每一下都多派一次事件，换不来任何行为
            (if (expandedState.value)
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH else 0)
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        // 面板占掉左侧、右面板占掉右侧时，岛要在中间那条空档里居中：
        // 相对屏幕中心的偏移就是 (左宽 - 右宽) / 2，所以这里允许算出负数
        params.x = (store.panelInsetPx.value - store.sideInsetPx.value) / 2
        params.y = topInsetPx()

        val v = view ?: return
        // 展开/收起让宽高跨档变化，个别 ROM 会抛 not attached；重建一次窗口比吞掉异常好
        runCatching { wm.updateViewLayout(v, params) }.onFailure { raiseToTop() }
    }

    /**
     * 内容可用的最大宽度（px）。
     *
     * 先扣掉控制台占掉的那一段，再留 16dp 边距。
     * 下限按状态给，而且给得比"看着舒服"更大：展开态两列格子最少要 200dp
     * （两格 84dp 下限 + 6dp 间距 + 26dp 内边距），上一版下限只到 120dp，
     * 于是窄屏上开着控制台时内容比窗口宽，右边一整列被 clip 削掉 —— 这就是
     * 展开式渲染异常。宽度实在不够时宁可让岛压在控制台上，也不把用户勾出来的
     * 读数截成一个字；真装不下的那一档由 SafeHud 自己降成一列。
     */
    private fun maxContentW(): Int {
        val free = screenW() - store.panelInsetPx.value - store.sideInsetPx.value
        val floorDp = if (expandedState.value) EXPANDED_FLOOR_DP else 140f
        return (free - dp(16f)).coerceAtLeast(dp(floorDp)).toInt()
    }

    /**
     * 内容可用的最大高度（px）。收起态那条胶囊是 32dp，给 40dp 足够；
     * 展开态按屏高走并夹在 120~300dp 之间：再高就吃掉小半个屏幕，
     * 那时候宁可让最后几行挤一挤，也不让岛盖住准星。
     */
    private fun maxContentH(): Int =
        (if (expandedState.value)
            (screenH() * 0.45f).coerceIn(dp(120f), dp(300f))
        else dp(40f)).toInt()

    /** 状态栏与刘海谁更高听谁。刘海尺寸只有 API 30+ 能从 WindowManager 直接问 */
    private fun topInsetPx(): Int {
        val cutout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            runCatching {
                wm.currentWindowMetrics.windowInsets.getDisplayCutout()?.safeInsetTop ?: 0
            }.getOrDefault(0) else 0
        return maxOf(statusBarPx(), cutout)
    }

    override fun setExpanded(value: Boolean) {
        if (expandedState.value == value) return
        // 先换窗、后翻状态：展开动画于是由新 Composition 自己从"收起"走到"展开"，照样播
        if (value && !raisedAbovePanels && willOverlap(EXPANDED_FLOOR_DP)) raiseToTop()
        expandedState.value = value
        // 展开态更高更宽：窗口跟着换尺寸，gravity 保证它仍然顶部居中
        applyGeometry()
    }

    override fun hide() {
        // 开关是唯一事实来源：只停服务不改偏好的话，应用里的开关还亮着，
        // 用户看到的是"开着却没有窗"，下一次进 App 也说不清到底该不该出现
        store.setHudEnabled(false)
        detach()
        stopSelf()
    }

    private fun screenW(): Float = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        wm.currentWindowMetrics.bounds.width().toFloat()
    else resources.displayMetrics.widthPixels.toFloat()

    private fun screenH(): Float = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        wm.currentWindowMetrics.bounds.height().toFloat()
    else resources.displayMetrics.heightPixels.toFloat()

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun statusBarPx(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) runCatching { resources.getDimensionPixelSize(id) }
            .getOrDefault(dp(24f).toInt()) else dp(24f).toInt()
    }

    companion object {
        const val ACTION_DISMISS = "com.entropy.hud.DISMISS"
        const val ACTION_POWERSAVE = "com.entropy.hud.POWERSAVE"

        private const val NOTIF_ID = 41002
        private const val ALERT_ID = 41003
        private const val SAMPLE_INTERVAL_MS = 1000L

        /** 常驻通知 5 秒才更新一次：1Hz 重绘一条 LOW 通知除了费电没有任何意义 */
        private const val NOTIFY_EVERY_N = 5

        private const val ALERT_COOLDOWN_MS = 120_000L
        private const val ALERT_HYSTERESIS_C = 2f

        /**
         * 展开卡片最窄的一档（dp）：两列格子 84+6+84 加左右 13 内边距，再加一点点余量。
         * maxContentW 拿它当宽度下限，willOverlapPanels 拿它判断会不会压到面板，两处必须是同一个数。
         */
        private const val EXPANDED_FLOOR_DP = 216f

        fun start(ctx: Context) {
            if (!OverlayService.canDraw(ctx)) {
                EntropyStore.get(ctx).note("请先授予「显示在其他应用上层」，安全显示（灵动岛）才能出现")
                return
            }
            EntropyStore.get(ctx).setHudEnabled(true)
            ctx.startForegroundService(Intent(ctx, SafeHudService::class.java))
        }

        fun stop(ctx: Context) {
            EntropyStore.get(ctx).setHudEnabled(false)
            ctx.stopService(Intent(ctx, SafeHudService::class.java))
        }

        /** 应用内开关用这个：状态只认 store 里的快照，服务起没起都由它说了算 */
        fun toggle(ctx: Context) {
            if (EntropyStore.get(ctx).hudEnabled.value) stop(ctx) else start(ctx)
        }
    }
}
