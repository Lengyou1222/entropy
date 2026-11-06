// File: app/src/main/java/com/entropy/ui/components/ColorPicker.kt
//
// 颜色选择卡。原先这份 HSV 调色板只长在"系统增强"页里，改警示色得先翻到那一页，
// 而"高亮色"其实是全站外观设置 —— 所以抽成控件，让"我的 · 外观"也能用同一个。
//
// 三根滑杆全部用 TickSlider：色相 13 档、饱和度/明度各 11 档。
// 连续滑杆在这里反而不合适 —— 调色时用户要的是"跳到一个干净的档位"，
// 而不是停在 137.4° 这种没人记得住的位置。
package com.entropy.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import kotlin.math.roundToInt

/** 色块本身有一圈发丝描边：纯白/纯黑的高亮色在浅/深背景下都会"没有边界" */
@Composable
fun AccentSwatch(c: Color, modifier: Modifier = Modifier, diameter: Int = 72) {
    Box(
        modifier.size(diameter.dp)
            .clip(RoundedCornerShape(18.dp))
            // 渐变收到"同色压深"，不是收到半透明：半透明的下半截会把卡面透出来，
            // 看着像色块没铺满，而不是一块有厚度的色片
            .background(androidx.compose.ui.graphics.Brush.linearGradient(
                listOf(c, androidx.compose.ui.graphics.lerp(c, Color.Black, 0.14f))))
    ) {
        Canvas(Modifier.fillMaxWidth().height(diameter.dp)) {
            // DrawScope 里 size 是画布自己的尺寸，所以这里换算成 IntSize 再转 Dp
            val edge = size.minDimension
            drawRoundRect(Color(0x33000000), topLeft = Offset(1f, 1f),
                size = androidx.compose.ui.geometry.Size(edge - 2f, edge - 2f),
                cornerRadius = CornerRadius(edge * 0.25f, edge * 0.25f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
        }
    }
}

/** Int ARGB -> HSV 三分量（h 0..360，s/v 0..1） */
private fun hsvOf(argb: Int): FloatArray = FloatArray(3).also {
    android.graphics.Color.colorToHSV(argb, it)
}

private fun argbOf(h: Float, s: Float, v: Float): Int =
    android.graphics.Color.HSVToColor(255, floatArrayOf(h, s, v))

/** 预设色：都是"压在游戏画面上仍然扎眼"的那几枚，不是随便挑的好看颜色 */
private val Presets = listOf(
    "熔岩红" to 0xFFFF453A.toInt(),
    "琥珀" to 0xFFF6AD55.toInt(),
    "荧光青" to 0xFF22D3EE.toInt(),
    "电紫" to 0xFFA78BFA.toInt(),
    "薄荷" to 0xFF34D399.toInt(),
    "樱粉" to 0xFFEC6A9C.toInt(),
    "霜蓝" to 0xFF3B9FE8.toInt(),
    "抹茶" to 0xFF84C24A.toInt(),
    "落霞" to 0xFFEF7A3C.toInt(),
    "石墨" to 0xFF9AA3AF.toInt(),
)

/**
 * 一张颜色卡：色块预览 + HSV 三滑杆 + 预设 + 应用/还原。
 *
 * 改滑杆只动预览，按「应用」才写偏好 —— 高亮色覆盖的是主题的 primary，
 * 一路拖过去全站颜色会连续重排，看着像在闪。预览和生效分开，用户才敢试。
 */
@Composable
fun HighlightColorCard(
    title: String,
    subtitle: String,
    /** 当前已生效的值；0 表示"跟随配色方案" */
    currentArgb: Int,
    /** 出厂默认值，「还原默认」写回它 */
    defaultArgb: Int,
    onApply: (Int) -> Unit,
    /** 是否允许"跟随配色方案"（0 哨兵）。警示色不该有这一项：它必须一直存在 */
    allowFollowTheme: Boolean = false,
) {
    val seeded = if (currentArgb == 0) defaultArgb else currentArgb
    var hue by remember { mutableFloatStateOf(hsvOf(seeded)[0]) }
    var sat by remember { mutableFloatStateOf(hsvOf(seeded)[1]) }
    var valB by remember { mutableFloatStateOf(hsvOf(seeded)[2]) }
    var preview by remember { mutableStateOf(Color(seeded)) }
    // 预览只在"改过但还没应用"时代表颜色。否则按「跟随方案」之后，
    // 色块还挂着上一轮编辑的残留色，和"当前生效"自相矛盾。
    var dirty by remember { mutableStateOf(false) }
    val following = currentArgb == 0
    val scheme = MaterialTheme.colorScheme
    val applied = if (following) scheme.primary else Color(currentArgb)
    val shown = if (dirty) preview else applied

    SectionCard(title, subtitle) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AccentSwatch(shown)
            Spacer(Modifier.size(width = 14.dp, height = 1.dp))
            Column {
                Text("ARGB = %08X".format(shown.toArgb()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (following) "当前生效：跟随配色方案" else "当前生效：自定义",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (following) FontWeight.Normal else FontWeight.SemiBold,
                    color = if (following) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(14.dp))
        TickSlider(value = hue.roundToInt().coerceIn(0, 360),
            onValueChange = { hue = it.toFloat(); preview = Color(argbOf(hue, sat, valB)); dirty = true },
            range = 0..360, detents = 13, label = "色相 H",
            valueText = "${hue.roundToInt()}°", color = shown)
        Spacer(Modifier.height(6.dp))
        TickSlider(value = (sat * 100).roundToInt().coerceIn(0, 100),
            onValueChange = { sat = it / 100f; preview = Color(argbOf(hue, sat, valB)); dirty = true },
            range = 0..100, detents = 11, label = "饱和度 S",
            valueText = "${(sat * 100).roundToInt()}%", color = shown)
        Spacer(Modifier.height(6.dp))
        TickSlider(value = (valB * 100).roundToInt().coerceIn(20, 100),
            onValueChange = { valB = it / 100f; preview = Color(argbOf(hue, sat, valB)); dirty = true },
            range = 20..100, detents = 9, label = "明度 V",
            valueText = "${(valB * 100).roundToInt()}%", color = shown)
        Spacer(Modifier.height(12.dp))
        // 五个预设按三列等宽铺：交给 FlowRow 的话第五个会独自掉到下一行左侧，
        // 补上一格空白又显得缺了一块。等宽格子一眼能把五个色放在一起比较。
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Presets.chunked(3).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowItems.forEach { (name, argb) ->
                        val c = Color(argb)
                        TextButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val hsv = hsvOf(argb)
                                hue = hsv[0]; sat = hsv[1]; valB = hsv[2]; preview = c
                                dirty = true
                            },
                        ) {
                            Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(c))
                            Spacer(Modifier.size(width = 5.dp, height = 1.dp))
                            Text(name, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        // 三个动作并成一行、按权重分宽：换行会让"跟随方案"孤零零占掉半行，
        // 卡片右下角空出一大块。主操作给更多宽度，读起来就是主次之分。
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                modifier = Modifier.weight(1.5f),
                onClick = { onApply(argbOf(hue, sat, valB)); dirty = false },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            ) { Text("应用这个颜色", maxLines = 1, fontSize = 13.sp) }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    val hsv = hsvOf(defaultArgb)
                    hue = hsv[0]; sat = hsv[1]; valB = hsv[2]
                    preview = Color(defaultArgb)
                    dirty = false
                    onApply(defaultArgb)
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            ) { Text("还原默认", maxLines = 1, fontSize = 13.sp) }
            if (allowFollowTheme) OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = { onApply(0); dirty = false },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            ) { Text("跟随方案", maxLines = 1, fontSize = 13.sp) }
        }
    }
}
