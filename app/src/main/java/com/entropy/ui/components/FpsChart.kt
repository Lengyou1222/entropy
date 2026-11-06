// File: app/src/main/java/com/entropy/ui/components/FpsChart.kt
//
// 帧率曲线。自己用 Canvas 画、不引第三方图表库，理由和 Gauge 一样：
// 这套控件悬浮窗也要复用，多一个依赖就多一份包体和一条混淆规则，
// 而这里全部的几何只是"折线 + 面积 + 两个标点"。
//
// 横轴按"第几拍"等距排布，而不是按真实时间：记录中途会停会续、间隔也能改，
// 用时间轴会把空档拉成一条长直线，反而看不出掉帧发生在哪一段。
// 两端的时刻照样标在轴上，读"这段曲线是几点到几点"够用。
package com.entropy.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.entropy.ui.theme.LocalEntropyColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 曲线上的一个点。fps 为空的采样拍不该进这个列表（那是"没有可信来源"，不是 0 帧） */
data class FpsPoint(val ts: Long, val fps: Int)

/** 画之前先算好的一轮统计：轴顶、极值点下标。和绘制分开，是为了让 remember 只认数据 */
private class ChartPlan(
    val yMax: Int,
    val minIdx: Int,
    val maxIdx: Int,
    val minV: Int,
    val maxV: Int,
)

private fun planOf(points: List<FpsPoint>): ChartPlan? {
    if (points.size < 2) return null
    var minIdx = 0
    var maxIdx = 0
    points.forEachIndexed { i, p ->
        if (p.fps < points[minIdx].fps) minIdx = i
        if (p.fps > points[maxIdx].fps) maxIdx = i
    }
    // 轴顶凑到 30 的整档：读帧率的人眼睛认的是 30/60/90/120 这几条线
    val yMax = ((maxOf(points[maxIdx].fps, 60) + 29) / 30) * 30
    return ChartPlan(yMax, minIdx, maxIdx, points[minIdx].fps, points[maxIdx].fps)
}

@OptIn(ExperimentalTextApi::class)
@Composable
fun FpsChart(
    points: List<FpsPoint>,
    modifier: Modifier = Modifier,
    lineColor: Color = LocalEntropyColors.current.tempCool,
) {
    val scheme = MaterialTheme.colorScheme
    val entropy = LocalEntropyColors.current
    val measurer = rememberTextMeasurer()
    val baseStyle = MaterialTheme.typography.labelSmall
    val gridColor = scheme.outlineVariant.copy(alpha = 0.55f)
    val axisColor = scheme.onSurfaceVariant
    val plan = remember(points) { planOf(points) }

    Box(modifier.fillMaxWidth().height(158.dp)) {
        if (plan == null || points.isEmpty()) {
            Text(
                if (points.isEmpty()) "还没有帧率读数。开始记录并让目标应用真的出帧之后，这里才会出现曲线。"
                else "只有 1 拍读数，连不成线；再等几拍。",
                style = MaterialTheme.typography.bodySmall,
                color = axisColor,
            )
            return@Box
        }
        Canvas(Modifier.fillMaxSize()) {
            val gutterL = 28.dp.toPx()
            val gutterB = 17.dp.toPx()
            val top = 12.dp.toPx()
            val left = gutterL
            val right = size.width - 2.dp.toPx()
            val bottom = size.height - gutterB
            val plotW = right - left
            val plotH = bottom - top
            val last = points.lastIndex

            fun xAt(i: Int) = left + plotW * i / last
            fun yAt(v: Int) = bottom - plotH * (v.toFloat() / plan.yMax)

            fun label(text: String, color: Color) = measurer.measure(text, baseStyle.copy(color = color))

            // 轴与网格：0 / 半程 / 顶 三条，够定位又不糊
            for (k in 0..2) {
                val v = plan.yMax * k / 2
                val y = yAt(v)
                drawLine(gridColor, Offset(left, y), Offset(right, y), 1.dp.toPx())
                val lay = label("$v", axisColor)
                drawText(lay, axisColor,
                    Offset(left - lay.size.width - 4.dp.toPx(), y - lay.size.height / 2f))
            }

            val line = Path()
            val area = Path()
            points.forEachIndexed { i, p ->
                val x = xAt(i)
                val y = yAt(p.fps.coerceIn(0, plan.yMax))
                if (i == 0) {
                    line.moveTo(x, y)
                    area.moveTo(x, bottom)
                    area.lineTo(x, y)
                } else {
                    line.lineTo(x, y)
                    area.lineTo(x, y)
                }
            }
            area.lineTo(xAt(last), bottom)
            area.close()
            drawPath(area, Brush.verticalGradient(
                listOf(lineColor.copy(alpha = 0.26f), lineColor.copy(alpha = 0.02f))))
            drawPath(line, lineColor, style = Stroke(
                width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

            val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
            drawLine(entropy.tempHot.copy(alpha = 0.35f), Offset(left, yAt(plan.minV)),
                Offset(right, yAt(plan.minV)), 1.dp.toPx(), pathEffect = dash)
            if (plan.maxV != plan.minV) {
                drawLine(entropy.tempCool.copy(alpha = 0.35f), Offset(left, yAt(plan.maxV)),
                    Offset(right, yAt(plan.maxV)), 1.dp.toPx(), pathEffect = dash)
            }

            // 标点：极值只写在数字上不够，得让人一眼看到它发生在曲线的哪一段
            fun marker(idx: Int, v: Int, color: Color, text: String, above: Boolean) {
                val cx = xAt(idx)
                val cy = yAt(v)
                drawCircle(color.copy(alpha = 0.25f), 6.5.dp.toPx(), Offset(cx, cy))
                drawCircle(color, 3.2.dp.toPx(), Offset(cx, cy))
                val lay = label(text, color)
                val tw = lay.size.width.toFloat()
                val th = lay.size.height.toFloat()
                val tx = (cx - tw / 2).coerceIn(0f, (size.width - tw).coerceAtLeast(0f))
                // 最低点常常贴着底边，字放下去会压住时间轴，这时自动翻到上方
                val flipUp = cy + th + 8.dp.toPx() > size.height
                val ty = (if (above || flipUp) cy - th - 8.dp.toPx() else cy + 8.dp.toPx())
                    .coerceIn(0f, (size.height - th).coerceAtLeast(0f))
                drawText(lay, color, Offset(tx, ty))
            }
            marker(plan.maxIdx, plan.maxV, entropy.tempCool, "最高 ${plan.maxV}", above = true)
            marker(plan.minIdx, plan.minV, entropy.tempHot, "最低 ${plan.minV}", above = false)

            val t0 = label(TIME.format(Date(points.first().ts)), axisColor)
            val t1 = label(TIME.format(Date(points.last().ts)), axisColor)
            drawText(t0, axisColor, Offset(left, bottom + 3.dp.toPx()))
            drawText(t1, axisColor, Offset(right - t1.size.width, bottom + 3.dp.toPx()))
        }
    }
}

private val TIME = SimpleDateFormat("HH:mm:ss", Locale.US)
