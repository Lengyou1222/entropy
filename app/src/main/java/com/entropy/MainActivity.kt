// File: app/src/main/java/com/entropy/MainActivity.kt
//
// 主界面骨架：Scaffold + 底部 NavigationBar + NavHost，七页路由
// （首页 / 游戏 / 记录 / 录制 / 终端 / 权限 / 我的）。
//
// 为什么用 NavHost 而不是 when(selected)：
//  底部导航的往返要保持各页的滚动位置与正在读取的状态。
//  saveState/restoreState 让 popUpTo 记住每页的 SaveableState，
//  从"我的"切回"首页"时不会把已经展开的页签和列表位置重置掉。
//
// Activity 只做四件事：挂主题、挂导航、承接悬浮窗传进来的 UFO 可见性状态、挂更新提示。
// 硬件与权限数据的读取全部在 SystemViewModel + SystemRepository 里，UI 层没有 IO；
// 更新那条也不在这里做网络，Activity 只负责在冷启动时踢一次、并把结果对话框画出来。
package com.entropy

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.entropy.data.EntropyStore
import com.entropy.data.ThemeMode
import com.entropy.data.UiState
import com.entropy.media.ScreenRecord
import com.entropy.overlay.OverlayService
import com.entropy.overlay.SafeHudService
import com.entropy.ui.GameFrameScreen
import com.entropy.ui.HomeScreen
import com.entropy.ui.PermissionScreen
import com.entropy.ui.ProfileScreen
import com.entropy.ui.RecordingScreen
import com.entropy.ui.RecordScreen
import com.entropy.ui.SettingsScreen
import com.entropy.ui.SystemViewModel
import com.entropy.ui.TerminalScreen
import com.entropy.ui.UfoTestDialog
import com.entropy.ui.UpdatePromptDialog
import com.entropy.ui.components.EntropyMotion
import com.entropy.ui.theme.EntropyTheme
import com.entropy.ui.theme.isDarkFor
import com.entropy.update.UpdateFlow

class MainActivity : ComponentActivity() {

    private val store by lazy { EntropyStore.get(this) }

    /** 悬浮窗的 UFO 按钮把 Activity 拉到前台时用的可见性状态 */
    private val showUfo = mutableStateOf(false)

    /**
     * 悬浮控制台上的「录屏」按钮递过来的请求。
     * 用状态位而不是在 onCreate 里直接 launch：注册好的 launcher 要在 onCreate 走完才能发，
     * 而这里读 intent 的时机就在 onCreate 中间。状态位交给下面 setContent 里的 effect 消费，
     * 那条 effect 跑的时候组合已经挂上，不会撞上"还没通过 CREATE 事件"。
     */
    private val recordRequest = mutableStateOf(false)

    /** 系统那条"开始录屏"的授权页。整台机器只能由 Activity 发起，浮窗自己发不出去 */
    private val recordLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) ScreenRecord.start(this, r.resultCode, data)
        else ScreenRecord.note.value = "已取消授权，没有开始录制"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(EXTRA_UFO, false)) showUfo.value = true
        if (intent.getBooleanExtra(EXTRA_RECORD, false)) recordRequest.value = true
        // HUD 属于"安全显示"，上次开着而进程被系统回收的，这次要自己回来。
        // 只能在 Activity 已经在前台时拉起：Android 12+ 不允许后台进程随便 startForegroundService，
        // 放在 Application.onCreate 里会在个别 ROM 上直接抛 ForegroundServiceStartNotAllowedException。
        if (store.prefs.hudEnabled) SafeHudService.start(this)
        setContent {
            val state by store.state.collectAsState()
            val mode = store.themeMode.value
            // 每次冷启动只查一次更新。放在 setContent 而不是 onCreate：结果要驱动的那个
            // 对话框就在下面这个组合里，effect 和它的 UI 在同一组作用域，检查回来才不会落到空处。
            // key 用 Unit 而不是开关值：启动这一下查不查由启动那一刻的设置决定，
            // 用户在设置页里改开关不该让已经在跑的这次检查重发一遍请求。
            LaunchedEffect(Unit) {
                if (store.autoUpdateCheck.value) UpdateFlow.check(this@MainActivity, auto = true)
            }
            // 消费悬浮控制台递来的录屏请求。effect 跑在组合挂上之后，launcher 这时已经注册完事，
            // 不会撞上"Activity 还没通过 CREATE"。键用请求位而不是 Unit：浮窗第二次按录屏要能再发一次授权页。
            // 先把请求位落下、再把 intent 里那位抹掉 —— 授权页还挂在前面时转屏重建，
            // 不会照着旧 intent 凭空再弹一次。
            LaunchedEffect(recordRequest.value) {
                if (!recordRequest.value) return@LaunchedEffect
                recordRequest.value = false
                intent.removeExtra(EXTRA_RECORD)
                recordLauncher.launch(ScreenRecord.captureIntent(this@MainActivity))
            }
            EntropyTheme(
                themeMode = mode,
                accentArgb = store.prefs.accentColor,
                highlightArgb = store.highlightColor.intValue,
                palette = store.palette.value,
            ) {
                ApplySystemBarAppearance(mode, this@MainActivity.window)
                EntropyRoot(store, state, initialTab = intent.getStringExtra(EXTRA_TAB))
                if (showUfo.value) UfoTestDialog(onClose = { showUfo.value = false })
                // 更新对话框挂在 Activity 而不是某一页上：用户可能在任何一页时被它拦住，
                // 切页、进设置、返回都不该把已经下载好的包丢掉。
                if (UpdateFlow.shouldDialog()) UpdatePromptDialog()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask 下悬浮窗第二次唤出不会走 onCreate，必须在这里补
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_UFO, false)) showUfo.value = true
        if (intent.getBooleanExtra(EXTRA_RECORD, false)) recordRequest.value = true
    }

    override fun onDestroy() {
        super.onDestroy()
        // Store 是进程级单例长驻，但 Activity 销毁时要主动断开 socket：
        // Daemon 侧每个连接起一个线程，不释放就会一直占着线程与 fd。
        store.bridge.daemon.disconnect()
    }

    companion object {
        const val EXTRA_UFO = "com.entropy.extra.UFO"
        const val EXTRA_TAB = "tab"

        /** 悬浮控制台的录屏按钮：把这一位带上，Activity 起来后代发系统授权页 */
        const val EXTRA_RECORD = "com.entropy.extra.RECORD"
    }
}

private enum class Dest(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "首页", Icons.Filled.Home),
    GAME("game", "游戏", Icons.Filled.SportsEsports),
    RECORD("record", "记录", Icons.Filled.ShowChart),
    // 录制排在记录之后：两页都在"留下东西"，但记录出的是 CSV、录制出的是 mp4，
    // 顺序跟着用户的心智走 —— 先看仪表，再取画面。
    RECORDING("recording", "录制", Icons.Filled.Videocam),
    TERMINAL("terminal", "终端", Icons.Filled.Terminal),
    PERMISSION("permission", "权限", Icons.Filled.Shield),
    PROFILE("profile", "我的", Icons.Filled.Person),
}

/** "tab" 这个 extra 的兼容映射：老入口传的 dev/sys 仍然落到首页对应页签 */
private fun sectionOf(tab: String?) = when (tab) { "dev" -> 1; "sys" -> 2; else -> 0 }

/** 设置页的路由：不在 Dest 里，所以底部导航不会多出一格 */
private const val SETTINGS = "settings"

/**
 * 状态栏/导航栏图标的明暗跟着应用内的主题选择走。
 * 窗口底色由 Compose 画，XML 主题只在启动那一瞬起作用；
 * 用户在应用里切到深色而系统还是浅色时，不补这一步就会得到一套白图标压在黑底上。
 *
 * window 由 Activity 传进来：这个 composable 不在 Activity 成员作用域里，
 * 自己取 LocalContext 强转成 Activity 在 Dialog/悬浮窗场景下会直接 ClassCastException。
 */
@Composable
private fun ApplySystemBarAppearance(mode: ThemeMode, window: Window) {
    val dark = isDarkFor(mode)
    val view = LocalView.current
    DisposableEffect(window, view, dark) {
        val c = WindowCompat.getInsetsController(window, view)
        c.isAppearanceLightStatusBars = !dark
        c.isAppearanceLightNavigationBars = !dark
        onDispose { }
    }
}

@Composable
private fun EntropyRoot(store: EntropyStore, state: UiState, initialTab: String?) {
    val ctx = LocalContext.current
    val nav = rememberNavController()
    // viewModel() 挂在 Activity 的 ViewModelStore 上：首页与"我的"共享同一次读取结果
    val vm: SystemViewModel = viewModel()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    var overlayGranted by remember { mutableStateOf(OverlayService.canDraw(ctx)) }
    val overlayLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { overlayGranted = OverlayService.canDraw(ctx) }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 拒绝也要继续：悬浮窗本身可用，只是没有常驻通知 */ }

    val startOverlay: () -> Unit = {
        if (!overlayGranted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                overlayLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${ctx.packageName}")))
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            OverlayService.start(ctx)
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Dest.values().forEach { d ->
                    NavigationBarItem(
                        selected = currentRoute == d.route,
                        onClick = { nav.switchTab(d.route) },
                        icon = { Icon(d.icon, contentDescription = d.label) },
                        label = { Text(d.label) }
                    )
                }
            }
        }
    ) { inner ->
        // 平板与横屏上给内容一臂宽的上限：整页拉到 1200dp 后，KvRow 的键与值
        // 隔着一臂远，眼睛回扫要转头。"占满"在这种页面上不是优点。
        Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.TopCenter) {
            NavHost(
                navController = nav,
                startDestination = when (initialTab) {
                    "game" -> Dest.GAME.route
                    // "rec" 是老入口传下来的，当年那一页里既有硬件记录也有录屏，
                    // 语义上它指"记录"；录制是新拆出去的页，另给一个不歧义的 "video"，
                    // 免得把已有快捷方式的目的页改掉。
                    "rec" -> Dest.RECORD.route
                    "video" -> Dest.RECORDING.route
                    "term" -> Dest.TERMINAL.route
                    "perm" -> Dest.PERMISSION.route
                    "me" -> Dest.PROFILE.route
                    else -> Dest.HOME.route
                },
                modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth().fillMaxHeight(),
                // 底部导航的切页用"淡入 + 极小横移"：位移量只有页宽的 1/24，
                // 目的只是给出方向感，页面本身不能看起来像在飞。曲线与首页页签、
                // 悬浮控制台共用 EntropyMotion，全站才是同一个节奏。
                enterTransition = {
                    EntropyMotion.fadeEnter() +
                        slideInHorizontally(tween(EntropyMotion.NORMAL, easing = EntropyMotion.Ease)) { it / 24 }
                },
                exitTransition = { EntropyMotion.fadeExit() },
                popEnterTransition = { EntropyMotion.fadeEnter() },
                popExitTransition = {
                    EntropyMotion.fadeExit() +
                        slideOutHorizontally(tween(EntropyMotion.FAST)) { it / 24 }
                }
            ) {
                composable(Dest.HOME.route) {
                    HomeScreen(store, state, initialSection = sectionOf(initialTab), onStartOverlay = startOverlay)
                }
                composable(Dest.GAME.route) { GameFrameScreen(store, state) }
                // 记录、录制与终端页自己管滚动：终端要贴底的输出列表 + 输入框，
                // 外面再套一层首页那种 verticalScroll 就变成"滚动嵌滚动"，子项拿到无限高度
                composable(Dest.RECORD.route) { RecordScreen(store, state) }
                composable(Dest.RECORDING.route) { RecordingScreen(store, state) }
                composable(Dest.TERMINAL.route) { TerminalScreen(store) }
                composable(Dest.PERMISSION.route) { PermissionScreen(vm, store, state) }
                composable(Dest.PROFILE.route) { ProfileScreen(store, vm) { nav.navigate(SETTINGS) } }
                // 设置不进 Dest：底部导航已经有七格，第八格放"设置"会让它看起来
                // 和业务页同级，而它实际是"我的"页里齿轮打开的子页
                composable(SETTINGS) { SettingsScreen(store) { nav.popBackStack() } }
            }
        }
    }
}

/**
 * 底部导航的标准切换：回到起始图的栈顶，同时保存/恢复每页状态。
 * 少了 saveState/restoreState，从"我的"切回首页会把首页的页签和滚动位置抹平。
 */
private fun NavHostController.switchTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
