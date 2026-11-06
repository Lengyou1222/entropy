// File: app/src/main/java/com/entropy/ui/UfoScreen.kt
//
// UFO 刷新率测试。
//
// 除了经典的"看移动是否平滑"，这里还做了一件现有测试工具都没做的事：
// 用 Compose 的 withFrameNanos 自己量出真实出帧间隔。
// withFrameNanos 拿到的时间戳来自 Choreographer，也就是 vsync 本身，
// 因此它统计出来的就是"这段时间内屏幕真正刷了多少次"，
// 而不是 App 想画多少次 —— 两者在有帧率限制的场景下差别很大。
//
// 版面分两层：条纹区永远纯黑满屏，读数浮在上面自成一套暗色玻璃。
// 不把整页换成 Material 的 surface 底色，是因为测试的前提就是背景足够暗 ——
// 浅色主题那层近白底板一铺，色块边缘那点抖动正好被反光吃掉。
package com.entropy.ui

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.entropy.data.EntropyStore
import com.entropy.data.UiState
import com.entropy.hardware.HardwareBridge
import com.entropy.ui.theme.LocalEntropyColors
import kotlinx.coroutines.delay
import kotlin.math.sqrt

// HUD 底板固定用暗色，理由见文件头。透明度给到 0.96/0.95：
// 再透一点，底下移动的色块就会从文字背后穿过去，小字号直接读不出来
private val InkTop = Color(0xF6141721)
private val InkBottom = Color(0xF2090B10)
private val Hi = Color(0xFFFAF9FE)
private val Mid = Color(0xB3F0EFF6)
private val Lo = Color(0x80EFEFF6)
private val Hairline = Color(0x22FFFFFF)

@Composable
fun UfoTestDialog(store: EntropyStore, onClose: () -> Unit) {
    val entropy = LocalEntropyColors.current
    val scheme = MaterialTheme.colorScheme
    val cfg = LocalConfiguration.current
    val widthPx = with(LocalDensity.current) { cfg.screenWidthDp.dp.toPx() }
    val view = LocalView.current
    // 强调色在纯黑上必须提亮：浅色主题那族 primary 是给白底设计的，
    // 原样搬过来在这页上就是几条看不清的灰线
    val accent = remember(scheme.primary) { lerp(scheme.primary, Color.White, 0.45f) }

    Dialog(
        onDismissRequest = onClose,
        // dismissOnClickOutside=false：测试中途误触关掉就白测了
        properties = DialogProperties(usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false)
    ) {
        var pos by remember { mutableFloatStateOf(0f) }
        var speed by remember { mutableFloatStateOf(9f) }        // 像素/帧
        var paused by remember { mutableStateOf(false) }
        var band by remember { mutableStateOf(0) }               // 当前色带编号，0..5
        var showParams by remember { mutableStateOf(true) }
        val frameTimes = remember { ArrayList<Long>(240) }
        var stats by remember { mutableStateOf(UfoStats.EMPTY) }
        var trace by remember { mutableStateOf<List<Float>>(emptyList()) }
        var totalFrames by remember { mutableIntStateOf(0) }
        var elapsedMs by remember { mutableLongStateOf(0L) }
        var nominalHz by remember { mutableFloatStateOf(0f) }
        var modesHz by remember { mutableStateOf<List<Int>>(emptyList()) }
        var nativeRes by remember { mutableStateOf(0 to 0) }
        var peakHz by remember { mutableStateOf<String?>(null) }
        var minHz by remember { mutableStateOf<String?>(null) }
        val state by store.state.collectAsState()

        // 帧率争抢上限/下限读的是系统 setting，一次就够：
        // 它恰好是"实测比标称低"最常见的原因，所以值得占两行。
        // 档位表与物理分辨率也从这块 display 现取 —— store.bridge.profile 是启动时用
        // display=null 探的，那两栏在它是空值和写死的 1080×2400，照搬过来就是假数据。
        LaunchedEffect(Unit) {
            peakHz = store.bridge.readPeakRefreshRate()
            minHz = store.bridge.readMinRefreshRate()
            val d = view.display
            modesHz = supportedRates(d)
            nativeRes = nativeResolution(d)
        }

        // 出帧循环：每一帧推进色块，同时记录 vsync 时间戳
        LaunchedEffect(speed, paused) {
            withFrameNanos { }                                   // 对齐一次 vsync 再开始
            var lastNs = 0L
            while (true) {
                withFrameNanos { ns ->
                    if (paused) {
                        // 暂停要连基准一起丢掉，否则继续的那一帧会把整个暂停时长
                        // 记成一个超长帧，长帧数当场翻红
                        lastNs = 0L
                        return@withFrameNanos
                    }
                    if (lastNs != 0L) {
                        val dt = ns - lastNs
                        frameTimes.add(dt)
                        if (frameTimes.size > 240) frameTimes.removeAt(0)
                        totalFrames++
                        elapsedMs += dt / 1_000_000
                    }
                    lastNs = ns
                    pos += speed
                    if (pos > widthPx) {
                        pos = 0f
                        band = (band + 1) % 6
                    }
                }
            }
        }

        // 读数每 120ms 汇总一次。每帧都重算中位数并重排整页文字，
        // 高刷下等于自己给自己造掉帧，测出来的数就不干净了
        LaunchedEffect(Unit) {
            while (true) {
                delay(120)
                stats = statsOf(frameTimes)
                trace = frameTimes.takeLast(64).map { it / 1_000_000f }
                nominalHz = runCatching {
                    @Suppress("DEPRECATION")
                    view.display?.mode?.refreshRate ?: 0f
                }.getOrDefault(0f)
            }
        }

        val palette = listOf(
            Color(0xFFFF5964), Color(0xFFFF9500), Color(0xFFFFCC00),
            Color(0xFF32D74B), Color(0xFF0A84FF), Color(0xFFBF5AF2)
        )

        Box(
            Modifier.fillMaxSize()
                // 纯黑背景：OLED 上只有背景足够暗，色块边缘的抖动才看得出来
                .background(Color.Black)
        ) {
            Canvas(Modifier.fillMaxSize()) {
                // 色块比旧版更窄、行与行之间留更多黑：整屏糊成六条色带时，
                // 边缘的锯齿反而看不出来，细一点既好读也不吵
                val blockW = 16.dp.toPx()
                val gap = 12.dp.toPx()
                val rowH = size.height / 7f
                val blockH = rowH * 0.46f
                val corner = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                for (row in 0 until 6) {
                    val c = palette[(band + row) % 6]
                    // 每行反向/错位移动，形成经典 UFO 的"斜纹"效果
                    val dir = if (row % 2 == 0) 1f else -1f
                    val offset = (pos * dir + row * 37f) % (blockW + gap)
                    var x = -offset
                    val top = row * rowH + rowH * 0.27f
                    while (x < size.width) {
                        drawRoundRect(c, Offset(x, top), Size(blockW, blockH), corner)
                        x += blockW + gap
                    }
                }
            }

            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                ReadoutBar(
                    Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp),
                    accent, entropy.tempCool, entropy.tempWarm, entropy.warn,
                    stats, trace, nominalHz, paused, showParams,
                    { showParams = !showParams },
                )

                Row(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)) {
                    Spacer(Modifier.weight(1f))
                    AnimatedVisibility(
                        visible = showParams,
                        enter = fadeIn(tween(180)) + slideInHorizontally(tween(230)) { it / 4 },
                        exit = fadeOut(tween(140)) + slideOutHorizontally(tween(200)) { it / 4 },
                    ) {
                        ParamPanel(
                            stats, totalFrames, elapsedMs, nominalHz, speed, widthPx,
                            modesHz, nativeRes, peakHz, minHz, state, accent,
                            entropy.tempCool, entropy.tempWarm, entropy.warn,
                        )
                    }
                }

                ControlBar(
                    accent, entropy.warn, speed, paused,
                    onSlower = { speed = (speed - 2f).coerceAtLeast(2f) },
                    onFaster = { speed = (speed + 2f).coerceAtMost(40f) },
                    onPause = { paused = !paused },
                    onReset = {
                        frameTimes.clear(); stats = UfoStats.EMPTY; trace = emptyList()
                        totalFrames = 0; elapsedMs = 0L; pos = 0f; band = 0
                    },
                    onClose = onClose,
                )
            }
        }
    }
}

/** 顶部读数：大字实测值 + 逐帧间隔柱 + 结论 */
@Composable
private fun ReadoutBar(
    modifier: Modifier,
    accent: Color,
    cool: Color,
    warm: Color,
    hot: Color,
    stats: UfoStats,
    trace: List<Float>,
    nominalHz: Float,
    paused: Boolean,
    showParams: Boolean,
    onToggleParams: () -> Unit,
) {
    val verdict = verdictOf(stats, nominalHz, cool, warm, hot)
    Row(
        modifier.glass(accent).padding(start = 18.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column {
            Text("实测出帧", style = MaterialTheme.typography.labelSmall, color = Lo)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (stats.samples == 0) "--" else "%.1f".format(stats.hzMean),
                    style = MaterialTheme.typography.displaySmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, color = Hi,
                )
                Spacer(Modifier.width(4.dp))
                Text("Hz", style = MaterialTheme.typography.titleMedium, color = Mid,
                    modifier = Modifier.padding(bottom = 5.dp))
            }
        }
        IntervalBars(trace, nominalHz, Modifier.width(126.dp).height(44.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Badge(verdict.text, verdict.color)
            Text(
                if (nominalHz <= 0f) "系统当前档位未取到"
                else "当前档位 %.0f Hz · 中位 %.1f Hz（%+.1f%%）".format(
                    nominalHz, stats.hzMedian, (stats.hzMedian / nominalHz - 1f) * 100f),
                style = MaterialTheme.typography.bodySmall, color = Mid,
            )
        }
        Spacer(Modifier.weight(1f))
        if (paused) Badge("已暂停", warm)
        Text(
            if (showParams) "收起参数" else "展开参数",
            style = MaterialTheme.typography.labelLarge, color = accent,
            modifier = Modifier.clickable(onClick = onToggleParams)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** 逐帧间隔柱：整体降档和偶发长帧是两种病，柱子形状一眼能分开 */
@Composable
private fun IntervalBars(trace: List<Float>, nominalHz: Float, modifier: Modifier) {
    val good = Color(0xFF4FD1C5)
    val warnC = Color(0xFFF6AD55)
    val bad = Color(0xFFFF5964)
    Canvas(modifier) {
        if (trace.isEmpty()) return@Canvas
        val nominalMs = if (nominalHz > 0f) 1000f / nominalHz else trace.average().toFloat()
        val scale = (nominalMs * 2.4f).coerceAtLeast(1f)
        val slot = size.width / trace.size
        val barW = (slot * 0.62f).coerceAtLeast(1f)
        // 标称 vsync 那条基准线：柱子全压在线上，还是零星冒尖，读法完全不同
        val baseY = size.height * (1f - (nominalMs / scale).coerceIn(0f, 1f))
        drawLine(Hairline, Offset(0f, baseY), Offset(size.width, baseY),
            strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round)
        trace.forEachIndexed { i, ms ->
            val h = size.height * (ms / scale).coerceIn(0.02f, 1f)
            val c = when {
                ms > nominalMs * 1.5f -> bad
                ms > nominalMs * 1.15f -> warnC
                else -> good
            }
            val cx = i * slot + barW / 2f
            drawLine(c, Offset(cx, size.height), Offset(cx, size.height - h),
                strokeWidth = barW, cap = StrokeCap.Round)
        }
    }
}

/** 右侧实况参数 */
@Composable
private fun ParamPanel(
    stats: UfoStats,
    totalFrames: Int,
    elapsedMs: Long,
    nominalHz: Float,
    speed: Float,
    widthPx: Float,
    modesHz: List<Int>,
    nativeRes: Pair<Int, Int>,
    peakHz: String?,
    minHz: String?,
    state: UiState,
    accent: Color,
    cool: Color,
    warm: Color,
    hot: Color,
) {
    val cfg = LocalConfiguration.current
    val dpi = cfg.densityDpi
    Column(
        Modifier
            .fillMaxHeight()
            .padding(end = 18.dp, bottom = 10.dp)
            .width(278.dp)
            .glass(accent)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("实况参数", style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold, color = Hi)
        Text("档位切换后逐帧重算", style = MaterialTheme.typography.labelSmall, color = Lo)

        GroupTitle("出帧统计", accent)
        Kv("实测均值", "%.2f Hz".format(stats.hzMean))
        Kv("实测中位", "%.2f Hz".format(stats.hzMedian))
        Kv("平均帧间隔", if (stats.hzMean > 0f) "%.2f ms".format(1000f / stats.hzMean) else "--")
        Kv("最短 / 最长", "%.1f / %.1f ms".format(stats.minMs, stats.maxMs))
        Kv("抖动 σ", "%.2f ms".format(stats.sigmaMs))
        Kv("稳定度", "%.1f%%".format(stats.stability * 100f),
            color = when {
                stats.stability > 0.92f -> cool
                stats.stability > 0.8f -> warm
                else -> hot
            })
        Kv("长帧 >1.5×中位", "%d 帧 · %.2f%%".format(stats.longFrames, stats.longPct),
            color = if (stats.longPct > 3f) hot else Hi)
        Kv("采样窗口", "%d / 240 帧".format(stats.samples))
        Kv("累计出帧", "%d 帧 · %.1f s".format(totalFrames, elapsedMs / 1000f))

        GroupTitle("屏幕", accent)
        Kv("当前档位", if (nominalHz > 0f) "%.0f Hz".format(nominalHz) else "未取到")
        Kv("标称 vsync", if (nominalHz > 0f) "%.2f ms".format(1000f / nominalHz) else "--")
        Kv("面板支持档位", if (modesHz.isEmpty()) "未取到" else modesHz.joinToString("/"))
        Kv("原生分辨率", if (nativeRes.first > 0) "%d×%d px".format(
            nativeRes.first, nativeRes.second) else "未取到")
        Kv("界面尺寸", "%d×%d dp · %d dpi".format(cfg.screenWidthDp, cfg.screenHeightDp, dpi))
        Kv("帧率争抢上限", peakHz?.takeIf { it.isNotBlank() } ?: "未改")
        Kv("帧率下限", minHz?.takeIf { it.isNotBlank() } ?: "未改")

        GroupTitle("测试图案", accent)
        Kv("色块速度", "%.0f px/帧".format(speed))
        Kv("线速度", "%.0f px/s".format(if (stats.hzMean > 0f) speed * stats.hzMean else 0f))
        Kv("横扫一屏", if (stats.hzMean > 0f && speed > 0f)
            "%.2f s".format(widthPx / (speed * stats.hzMean)) else "--")

        GroupTitle("熵的读数", accent)
        Kv("写入通道", when (state.path) {
            HardwareBridge.Path.DAEMON -> "entropyd"
            HardwareBridge.Path.ROOT_SHELL -> "Root Shell"
            HardwareBridge.Path.READONLY -> "只读"
        }, color = if (state.path == HardwareBridge.Path.DAEMON) cool else warm)
        Kv("Daemon 帧率", if (state.connected) "${state.telemetry?.fps ?: 0} fps" else "未连接")
        Kv("渲染耗时", state.telemetry?.let { "${it.renderMs} ms" } ?: "--")
        Kv("最高温度", state.telemetry?.maxTempC?.let { "%.0f ℃".format(it) }
            ?: state.safety.lastThermalTempC?.let { "%.0f ℃".format(it) } ?: "--")
        Kv("熔断", if (state.safety.fused) "已触发" else "未触发",
            color = if (state.safety.fused) hot else cool)

        Spacer(Modifier.height(10.dp))
        Text(
            "怎么看：柱子整体压在基准线上但比标称矮，是系统降了档；" +
                "柱子零星冒尖，是渲染没跟上（掉帧），和刷新率无关。",
            style = MaterialTheme.typography.bodySmall, color = Mid, lineHeight = 16.sp,
        )
    }
}

/** 底部控制条：速度太慢时 60Hz 与 120Hz 看不出差别，所以要能加速 */
@Composable
private fun ControlBar(
    accent: Color,
    hot: Color,
    speed: Float,
    paused: Boolean,
    onSlower: () -> Unit,
    onFaster: () -> Unit,
    onPause: () -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Pill("减速", accent, onSlower)
        Box(
            Modifier.glass(accent).padding(horizontal = 14.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("%.0f px/f".format(speed), color = Hi,
                style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
        }
        Pill("加速", accent, onFaster)
        Pill(if (paused) "继续" else "暂停", accent, onPause)
        Pill("重置", accent, onReset)
        Pill("关闭", hot, onClose)
    }
}

@Composable
private fun Pill(text: String, accent: Color, onClick: () -> Unit) {
    Text(
        text, color = accent, style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.glass(accent).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun GroupTitle(text: String, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 9.dp)) {
        Box(Modifier.size(width = 3.dp, height = 12.dp)
            .background(accent, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(7.dp))
        Text(text, style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold, color = Mid)
    }
}

@Composable
private fun Kv(key: String, value: String, color: Color = Hi) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(key, style = MaterialTheme.typography.bodySmall, color = Lo,
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(value, style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace, color = color,
            modifier = Modifier.weight(1.05f, fill = false),
            textAlign = TextAlign.End,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text, color = color, style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(999.dp))
            .border(BorderStroke(1.dp, color.copy(alpha = 0.45f)), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** 玻璃底板：HUD 各处共用同一套明度与描边，免得每块各调一遍 */
private fun Modifier.glass(accent: Color): Modifier = this
    .clip(RoundedCornerShape(20.dp))
    .background(Brush.verticalGradient(listOf(InkTop, InkBottom)), RoundedCornerShape(20.dp))
    .border(BorderStroke(1.dp, accent.copy(alpha = 0.26f)), RoundedCornerShape(20.dp))

private class Verdict(val text: String, val color: Color)

private fun verdictOf(stats: UfoStats, nominalHz: Float, cool: Color, warm: Color,
    hot: Color): Verdict = when {
    // 判定看中位数而不是均值：中位数才是"这段时间真正在跑的那一拍"。
    // 均值会被零星长帧拖下去，而掉帧那件事已经由"长帧占比"那一栏单独说了，
    // 用均值判降档会把"屏幕 120Hz 但渲染偶尔慢一帧"错报成"屏幕降到了 100Hz"
    stats.samples < 12 -> Verdict("采样中", Mid)
    nominalHz <= 0f -> Verdict("档位未知", warm)
    stats.hzMedian >= nominalHz * 0.95f ->
        if (stats.longPct > 3f) Verdict("达档但有掉帧", warm) else Verdict("达到标称", cool)
    stats.hzMedian >= nominalHz * 0.6f -> Verdict("被降档", warm)
    else -> Verdict("严重降档", hot)
}

/** 面板支持的刷新率档位。算法与 DeviceProber 那套一致，区别是这里拿得到真的 Display */
@Suppress("DEPRECATION")
private fun supportedRates(d: android.view.Display?): List<Int> {
    if (d == null) return emptyList()
    val modes = runCatching { d.supportedModes }.getOrNull() ?: emptyArray()
    val rates = modes.map { (it.refreshRate + 0.5f).toInt() }
        .filter { it > 0 }.distinct().sorted()
    if (rates.isNotEmpty()) return rates
    val cur = runCatching { d.refreshRate }.getOrDefault(0f)
    return if (cur > 0f) listOf((cur + 0.5f).toInt()) else emptyList()
}

/** 物理像素：API 31+ 取 Mode 的自然方向尺寸，更早的机器退回 getRealMetrics */
@Suppress("DEPRECATION")
private fun nativeResolution(d: android.view.Display?): Pair<Int, Int> {
    if (d == null) return 0 to 0
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val m = runCatching { d.mode }.getOrNull()
        if (m != null && m.physicalWidth > 0) return m.physicalWidth to m.physicalHeight
    }
    val dm = android.util.DisplayMetrics()
    runCatching { d.getRealMetrics(dm) }
    return dm.widthPixels to dm.heightPixels
}

private data class UfoStats(
    val hzMean: Float,
    val hzMedian: Float,
    val minMs: Float,
    val maxMs: Float,
    val sigmaMs: Float,
    val longFrames: Int,
    val samples: Int,
) {
    /** σ 相对一个 vsync 周期归一，这样 60Hz 与 144Hz 的稳定度可以横向比 */
    val stability: Float
        get() = if (hzMean <= 0f) 0f else (1f - sigmaMs * hzMean / 1000f).coerceIn(0f, 1f)
    val longPct: Float get() = if (samples == 0) 0f else longFrames * 100f / samples

    companion object {
        val EMPTY = UfoStats(0f, 0f, 0f, 0f, 0f, 0, 0)
    }
}

private fun statsOf(ts: List<Long>): UfoStats {
    if (ts.isEmpty()) return UfoStats.EMPTY
    val mean = ts.average()
    val sorted = ts.sorted()
    val median = sorted[sorted.size / 2]
    val variance = ts.map { (it - mean) * (it - mean) }.average()
    val cutoff = median * 1.5
    return UfoStats(
        hzMean = (1e9 / mean).toFloat(),
        hzMedian = (1e9 / median).toFloat(),
        minMs = (sorted.first() / 1e6).toFloat(),
        maxMs = (sorted.last() / 1e6).toFloat(),
        sigmaMs = sqrt(variance).toFloat() / 1e6f,
        longFrames = ts.count { it > cutoff },
        samples = ts.size,
    )
}
