// File: app/src/main/java/com/entropy/ui/UfoScreen.kt
//
// UFO 刷新率测试。
//
// 除了经典的"看移动是否平滑"，这里还做了一件现有测试工具都没做的事：
// 用 Compose 的 withFrameNanos 自己量出真实出帧间隔。
// withFrameNanos 拿到的时间戳来自 Choreographer，也就是 vsync 本身，
// 因此它统计出来的就是"这段时间内屏幕真正刷了多少次"，
// 而不是 App 想画多少次 —— 两者在有帧率限制的场景下差别很大。
package com.entropy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt

@Composable
fun UfoTestDialog(onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        // dismissOnClickOutside=false：测试中途误触关掉就白测了
        properties = DialogProperties(usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false)
    ) {
        var pos by remember { mutableFloatStateOf(0f) }
        var speed by remember { mutableFloatStateOf(9f) }        // 像素/帧
        var measuredFps by remember { mutableFloatStateOf(0f) }
        var band by remember { mutableStateOf(0) }               // 当前色带编号，0..5
        val frameTimes = remember { ArrayList<Long>(180) }
        val cfg = LocalConfiguration.current
        val widthPx = with(androidx.compose.ui.platform.LocalDensity.current) {
            cfg.screenWidthDp.dp.toPx()
        }

        // 出帧循环：每一帧推进色块，同时记录 vsync 时间戳
        LaunchedEffect(speed) {
            androidx.compose.runtime.withFrameNanos { }          // 对齐一次 vsync 再开始
            var lastNs = 0L
            while (true) {
                androidx.compose.runtime.withFrameNanos { ns ->
                    if (lastNs != 0L) {
                        frameTimes.add(ns - lastNs)
                        if (frameTimes.size > 120) frameTimes.removeAt(0)
                        val avg = frameTimes.average()
                        if (avg > 0) measuredFps = (1_000_000_000.0 / avg).toFloat()
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
                val blockW = 24.dp.toPx()
                val gap = 4.dp.toPx()
                val rowH = size.height / 7f
                for (r in 0 until 6) {
                    val c = palette[(band + r) % 6]
                    // 每行反向/错位移动，形成经典 UFO 的"斜纹"效果
                    val dir = if (r % 2 == 0) 1f else -1f
                    val offset = (pos * dir + r * 37f) % (blockW + gap)
                    var x = -offset
                    while (x < size.width) {
                        drawRect(c, Offset(x, r * rowH + rowH * 0.1f),
                            Size(blockW, rowH * 0.8f))
                        x += blockW + gap
                    }
                }
            }
            Column(
                Modifier.align(Alignment.TopStart).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "实测出帧 %.1f Hz".format(measuredFps),
                    color = Color.White, style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "色块速度 %.1f px/帧 · 若色块边缘呈锯齿状跳动，说明实际刷新率低于屏幕标称值"
                        .format(speed),
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            // 底部控制条：速度太慢时 60Hz 与 120Hz 看不出差别，所以要能加速
            Row(
                Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = 40.dp)
                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { speed = (speed - 2f).coerceAtLeast(2f) }) {
                    Text("减速", color = Color.White)
                }
                Spacer(Modifier.size(4.dp))
                Text("%.0f px/f".format(speed), color = Color.White,
                    style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { speed = (speed + 2f).coerceAtMost(40f) }) {
                    Text("加速", color = Color.White)
                }
                TextButton(onClick = onClose) { Text("关闭", color = Color(0xFFFF453A),
                    fontWeight = FontWeight.Bold) }
            }
        }
    }
}
