// File: app/src/main/java/com/entropy/ui/theme/Theme.kt
//
// Material 3 主题。三件事：
//  1. Android 12+ 用动态取色（壁纸派生），12 以下退回内置的"熵"色板；
//  2. 明暗由 ThemeMode 决定：跟随系统 / 强制浅色 / 强制深色，
//     用户能在应用内直接改（见"我的"页），不必去动系统设置；
//  3. 把"高亮警示色"从用户设置里读出来注入到 ColorScheme 之外的自定义 token 里 ——
//     M3 的 Error 色语义是"操作失败"，不是"硬件过热"，混用会让用户误读，
//     所以警示色单独走 EntropyColors。
package com.entropy.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import com.entropy.data.Palette
import com.entropy.data.ThemeMode

// ---- 内置色板：以紫灰为主，避免大面积纯黑纯白造成的"廉价 OLED 感" ----
private val LightScheme = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71),
    secondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFF7D5260),
    background = Color(0xFFFBF8FF),
    surface = Color(0xFFFBF8FF),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurface = Color(0xFF1C1B1F),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF79747E),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC),
    secondaryContainer = Color(0xFF4A4458),
    tertiary = Color(0xFFEFB8C8),
    background = Color(0xFF14121A),
    surface = Color(0xFF14121A),
    surfaceVariant = Color(0xFF49454F),
    onSurface = Color(0xFFE6E1E5),
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF938F99),
)

// ---- 手调色板：熵青 / 熵金。熵紫就是上面那两套默认方案 ----
private val TealLight = lightColorScheme(
    primary = Color(0xFF0F766E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = Color(0xFF042F2E),
    secondary = Color(0xFF4A6360),
    secondaryContainer = Color(0xFFCDE8E5),
    tertiary = Color(0xFF7A5C3E),
    background = Color(0xFFFAFDFA),
    surface = Color(0xFFFAFDFA),
    surfaceVariant = Color(0xFFDBE5E3),
    onSurface = Color(0xFF191C1B),
    onSurfaceVariant = Color(0xFF414847),
    outline = Color(0xFF717877),
)

private val TealDark = darkColorScheme(
    primary = Color(0xFF5EEAD4),
    onPrimary = Color(0xFF003733),
    primaryContainer = Color(0xFF0F766E),
    onPrimaryContainer = Color(0xFFBDF3EA),
    secondary = Color(0xFFB4CBC8),
    secondaryContainer = Color(0xFF33403E),
    tertiary = Color(0xFFE7C9A4),
    background = Color(0xFF101413),
    surface = Color(0xFF101413),
    surfaceVariant = Color(0xFF33403E),
    onSurface = Color(0xFFE1E8E6),
    onSurfaceVariant = Color(0xFFBCC8C6),
    outline = Color(0xFF869391),
)

private val GoldLight = lightColorScheme(
    primary = Color(0xFF8A5A00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDFB8),
    onPrimaryContainer = Color(0xFF2C1800),
    secondary = Color(0xFF675C40),
    secondaryContainer = Color(0xFFEEE0BF),
    tertiary = Color(0xFF4A5C63),
    background = Color(0xFFFFFBF5),
    surface = Color(0xFFFFFBF5),
    surfaceVariant = Color(0xFFEAE0CF),
    onSurface = Color(0xFF1D1B16),
    onSurfaceVariant = Color(0xFF4A463D),
    outline = Color(0xFF7B766B),
)

private val GoldDark = darkColorScheme(
    primary = Color(0xFFF5C462),
    onPrimary = Color(0xFF3D2900),
    primaryContainer = Color(0xFF6B4400),
    onPrimaryContainer = Color(0xFFFFDFB8),
    secondary = Color(0xFFD4C49C),
    secondaryContainer = Color(0xFF4A422C),
    tertiary = Color(0xFFA7B7BE),
    background = Color(0xFF15130E),
    surface = Color(0xFF15130E),
    surfaceVariant = Color(0xFF4A422C),
    onSurface = Color(0xFFE9E2D5),
    onSurfaceVariant = Color(0xFFCCC5B7),
    outline = Color(0xFF979184),
)

// ---- 樱吹雪：默认方案。底是极浅的粉白，主色压到玫瑰红 ----
// 浅粉做底时 primary 不能也跟着变浅，否则滑杆、选中态、数字全部掉进背景里，
// 悬浮窗压在花哨游戏画面上更是直接消失，所以这里走"浅底 + 深字"的反差路线。
private val SakuraLight = lightColorScheme(
    primary = Color(0xFFB02A5C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDAE3),
    onPrimaryContainer = Color(0xFF3F001D),
    secondary = Color(0xFF74565F),
    secondaryContainer = Color(0xFFFFD9E2),
    tertiary = Color(0xFF8A6A3C),
    background = Color(0xFFFFF8F8),
    surface = Color(0xFFFFF8F8),
    surfaceVariant = Color(0xFFF3DDE1),
    onSurface = Color(0xFF231A1C),
    onSurfaceVariant = Color(0xFF524345),
    outline = Color(0xFF857375),
)

private val SakuraDark = darkColorScheme(
    primary = Color(0xFFFFB2C7),
    onPrimary = Color(0xFF5F1638),
    primaryContainer = Color(0xFF7C2B4E),
    onPrimaryContainer = Color(0xFFFFDAE3),
    secondary = Color(0xFFE6BCC6),
    secondaryContainer = Color(0xFF5B4348),
    tertiary = Color(0xFFE7C9A0),
    background = Color(0xFF1E1216),
    surface = Color(0xFF1E1216),
    surfaceVariant = Color(0xFF524345),
    onSurface = Color(0xFFECE0E1),
    onSurfaceVariant = Color(0xFFD4C2C5),
    outline = Color(0xFF9E8A8C),
)

// ---- 霜蓝 ----
private val FrostLight = lightColorScheme(
    primary = Color(0xFF1565A8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCFE6FA),
    onPrimaryContainer = Color(0xFF001D33),
    secondary = Color(0xFF51626F),
    secondaryContainer = Color(0xFFD4E4F1),
    tertiary = Color(0xFF5C6088),
    background = Color(0xFFFAFCFF),
    surface = Color(0xFFFAFCFF),
    surfaceVariant = Color(0xFFDCE3EC),
    onSurface = Color(0xFF191C1F),
    onSurfaceVariant = Color(0xFF41484E),
    outline = Color(0xFF71787E),
)

private val FrostDark = darkColorScheme(
    primary = Color(0xFF8FCAFF),
    onPrimary = Color(0xFF003251),
    primaryContainer = Color(0xFF0A4A78),
    onPrimaryContainer = Color(0xFFCFE6FA),
    secondary = Color(0xFFBCC8D4),
    secondaryContainer = Color(0xFF39454E),
    tertiary = Color(0xFFC3C6F0),
    background = Color(0xFF0F1418),
    surface = Color(0xFF0F1418),
    surfaceVariant = Color(0xFF41484E),
    onSurface = Color(0xFFE1E7EC),
    onSurfaceVariant = Color(0xFFC2CCD3),
    outline = Color(0xFF8A939B),
)

// ---- 抹茶 ----
private val MatchaLight = lightColorScheme(
    primary = Color(0xFF4E6B22),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCFEFA0),
    onPrimaryContainer = Color(0xFF132000),
    secondary = Color(0xFF5A6340),
    secondaryContainer = Color(0xFFDFE9BC),
    tertiary = Color(0xFF3A6565),
    background = Color(0xFFFBFCF2),
    surface = Color(0xFFFBFCF2),
    surfaceVariant = Color(0xFFDFE4CE),
    onSurface = Color(0xFF1A1C14),
    onSurfaceVariant = Color(0xFF44473E),
    outline = Color(0xFF75776D),
)

private val MatchaDark = darkColorScheme(
    primary = Color(0xFFB4D97A),
    onPrimary = Color(0xFF243300),
    primaryContainer = Color(0xFF3A4E14),
    onPrimaryContainer = Color(0xFFCFEFA0),
    secondary = Color(0xFFC4CCAB),
    secondaryContainer = Color(0xFF424738),
    tertiary = Color(0xFFA2D2D2),
    background = Color(0xFF12140D),
    surface = Color(0xFF12140D),
    surfaceVariant = Color(0xFF44473E),
    onSurface = Color(0xFFE4E5D8),
    onSurfaceVariant = Color(0xFFC7C9B9),
    outline = Color(0xFF919385),
)

// ---- 落霞：赭橙。刻意避开 #FF453A 那条报警红，否则界面底色会和"过热"撞语义 ----
private val EmberLight = lightColorScheme(
    primary = Color(0xFF9A4717),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBBE),
    onPrimaryContainer = Color(0xFF340C00),
    secondary = Color(0xFF725A48),
    secondaryContainer = Color(0xFFFFDFC8),
    tertiary = Color(0xFF4E626B),
    background = Color(0xFFFFF8F4),
    surface = Color(0xFFFFF8F4),
    surfaceVariant = Color(0xFFF2DFD2),
    onSurface = Color(0xFF211A15),
    onSurfaceVariant = Color(0xFF50443C),
    outline = Color(0xFF83746B),
)

private val EmberDark = darkColorScheme(
    primary = Color(0xFFFFB684),
    onPrimary = Color(0xFF491B00),
    primaryContainer = Color(0xFF6B2E0A),
    onPrimaryContainer = Color(0xFFFFDBBE),
    secondary = Color(0xFFE2BFA6),
    secondaryContainer = Color(0xFF54402F),
    tertiary = Color(0xFFB3CBD5),
    background = Color(0xFF191210),
    surface = Color(0xFF191210),
    surfaceVariant = Color(0xFF50443C),
    onSurface = Color(0xFFEDE0D7),
    onSurfaceVariant = Color(0xFFD1C3B8),
    outline = Color(0xFF9B8C82),
)

// ---- 石墨：中性灰，给"别用颜色抢注意力"的用户 ----
private val InkLight = lightColorScheme(
    primary = Color(0xFF454A52),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDE1E8),
    onPrimaryContainer = Color(0xFF1C2128),
    secondary = Color(0xFF595E66),
    secondaryContainer = Color(0xFFDDE1E9),
    tertiary = Color(0xFF6B5E4B),
    background = Color(0xFFFAFAFB),
    surface = Color(0xFFFAFAFB),
    surfaceVariant = Color(0xFFDFE2E8),
    onSurface = Color(0xFF1B1C1E),
    onSurfaceVariant = Color(0xFF43474E),
    outline = Color(0xFF74777F),
)

private val InkDark = darkColorScheme(
    primary = Color(0xFFB7BEC8),
    onPrimary = Color(0xFF2B3037),
    primaryContainer = Color(0xFF42474E),
    onPrimaryContainer = Color(0xFFDDE1E8),
    secondary = Color(0xFFC1C6CE),
    secondaryContainer = Color(0xFF43474E),
    tertiary = Color(0xFFD3C6AE),
    background = Color(0xFF121316),
    surface = Color(0xFF121316),
    surfaceVariant = Color(0xFF43474E),
    onSurface = Color(0xFFE3E2E6),
    onSurfaceVariant = Color(0xFFC4C7CE),
    outline = Color(0xFF8D9198),
)

// ---- 群青：第三种蓝。和熵紫、霜蓝各让开约 30°，底偏冷，tertiary 给一枚缃黄把蓝压住 ----
private val UltramarineLight = lightColorScheme(
    primary = Color(0xFF2F41A8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDEE1FF),
    onPrimaryContainer = Color(0xFF0A0C38),
    secondary = Color(0xFF5D5E7F),
    secondaryContainer = Color(0xFFE3E1F9),
    tertiary = Color(0xFF6F5A1B),
    background = Color(0xFFF8F8FF),
    surface = Color(0xFFF8F8FF),
    surfaceVariant = Color(0xFFE2E2F2),
    onSurface = Color(0xFF1A1B26),
    onSurfaceVariant = Color(0xFF44455C),
    outline = Color(0xFF75768E),
)

private val UltramarineDark = darkColorScheme(
    primary = Color(0xFFBAC4FF),
    onPrimary = Color(0xFF11175C),
    primaryContainer = Color(0xFF2E3B8C),
    onPrimaryContainer = Color(0xFFDEE1FF),
    secondary = Color(0xFFC5C5E2),
    secondaryContainer = Color(0xFF42435E),
    tertiary = Color(0xFFE2C68C),
    background = Color(0xFF0C0E1E),
    surface = Color(0xFF0C0E1E),
    surfaceVariant = Color(0xFF42435E),
    onSurface = Color(0xFFE2E3F3),
    onSurfaceVariant = Color(0xFFC3C4DC),
    outline = Color(0xFF8B8CA8),
)

// ---- 暮山：烟光凝而暮山紫。和熵紫的分别在于色相 —— 熵紫是蓝紫，这一档推到 287° 的紫罗，离得够远 ----
private val TwilightLight = lightColorScheme(
    primary = Color(0xFF85369A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEBDAFF),
    onPrimaryContainer = Color(0xFF26003D),
    secondary = Color(0xFF6C5A78),
    secondaryContainer = Color(0xFFECDCF8),
    tertiary = Color(0xFF7A5A14),
    background = Color(0xFFFCF8FF),
    surface = Color(0xFFFCF8FF),
    surfaceVariant = Color(0xFFE9DFEE),
    onSurface = Color(0xFF1D1A22),
    onSurfaceVariant = Color(0xFF4A4550),
    outline = Color(0xFF7C7684),
)

private val TwilightDark = darkColorScheme(
    primary = Color(0xFFE0B4F4),
    onPrimary = Color(0xFF3A1150),
    primaryContainer = Color(0xFF5C2A7C),
    onPrimaryContainer = Color(0xFFF0DCFF),
    secondary = Color(0xFFD1C0DE),
    secondaryContainer = Color(0xFF4A4054),
    tertiary = Color(0xFFE4CB8F),
    background = Color(0xFF14101C),
    surface = Color(0xFF14101C),
    surfaceVariant = Color(0xFF4A4054),
    onSurface = Color(0xFFE9E1EF),
    onSurfaceVariant = Color(0xFFCFC5D6),
    outline = Color(0xFF968CA0),
)

// ---- 石竹：品红。樱吹雪往红走（337°），这一档往紫走（313°），两档同时摆着才不会让人选错；tertiary 用青玉留一块冷色 ----
private val CarnationLight = lightColorScheme(
    primary = Color(0xFFA8288C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD5F0),
    onPrimaryContainer = Color(0xFF3A0030),
    secondary = Color(0xFF725869),
    secondaryContainer = Color(0xFFFCD8F0),
    tertiary = Color(0xFF1F6B5C),
    background = Color(0xFFFFF7FC),
    surface = Color(0xFFFFF7FC),
    surfaceVariant = Color(0xFFF2DCEB),
    onSurface = Color(0xFF241A21),
    onSurfaceVariant = Color(0xFF52434C),
    outline = Color(0xFF86737E),
)

private val CarnationDark = darkColorScheme(
    primary = Color(0xFFFFAFDE),
    onPrimary = Color(0xFF5C0048),
    primaryContainer = Color(0xFF7C2A66),
    onPrimaryContainer = Color(0xFFFFD5F0),
    secondary = Color(0xFFE7C1DD),
    secondaryContainer = Color(0xFF57434F),
    tertiary = Color(0xFF7FD6C6),
    background = Color(0xFF1C1218),
    surface = Color(0xFF1C1218),
    surfaceVariant = Color(0xFF57434F),
    onSurface = Color(0xFFEDE0E7),
    onSurfaceVariant = Color(0xFFD3C2CD),
    outline = Color(0xFF9E8A96),
)

// ---- 新绿：抹茶是黄绿、熵青是蓝绿，中间这一段纯绿原本是空的 ----
private val FoliageLight = lightColorScheme(
    primary = Color(0xFF2C7A3B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC8F0C9),
    onPrimaryContainer = Color(0xFF00290B),
    secondary = Color(0xFF4F6650),
    secondaryContainer = Color(0xFFD6EAD5),
    tertiary = Color(0xFF7A5A20),
    background = Color(0xFFF5FAF0),
    surface = Color(0xFFF5FAF0),
    surfaceVariant = Color(0xFFDBE7D7),
    onSurface = Color(0xFF161D15),
    onSurfaceVariant = Color(0xFF414841),
    outline = Color(0xFF717970),
)

private val FoliageDark = darkColorScheme(
    primary = Color(0xFF97DA9C),
    onPrimary = Color(0xFF00350F),
    primaryContainer = Color(0xFF1A5B2B),
    onPrimaryContainer = Color(0xFFC8F0C9),
    secondary = Color(0xFFBACDB9),
    secondaryContainer = Color(0xFF394539),
    tertiary = Color(0xFFE1C490),
    background = Color(0xFF0F150E),
    surface = Color(0xFF0F150E),
    surfaceVariant = Color(0xFF394539),
    onSurface = Color(0xFFE0E8DB),
    onSurfaceVariant = Color(0xFFBEC9BD),
    outline = Color(0xFF879388),
)

// ---- 青瓷：汝窑的灰青绿。与熵青同族但把饱和降下来，主色不再顶到眼前，适合长时间挂浮窗 ----
private val CeladonLight = lightColorScheme(
    primary = Color(0xFF2F705B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCDE9DA),
    onPrimaryContainer = Color(0xFF04231A),
    secondary = Color(0xFF4C5F56),
    secondaryContainer = Color(0xFFCFE1D6),
    tertiary = Color(0xFF86603A),
    background = Color(0xFFF4F9F5),
    surface = Color(0xFFF4F9F5),
    surfaceVariant = Color(0xFFD8E6DD),
    onSurface = Color(0xFF161D19),
    onSurfaceVariant = Color(0xFF414845),
    outline = Color(0xFF707975),
)

private val CeladonDark = darkColorScheme(
    primary = Color(0xFF9DD0B7),
    onPrimary = Color(0xFF0D3828),
    primaryContainer = Color(0xFF2A5C49),
    onPrimaryContainer = Color(0xFFCDE9DA),
    secondary = Color(0xFFB8CABF),
    secondaryContainer = Color(0xFF3A4840),
    tertiary = Color(0xFFDBBA8C),
    background = Color(0xFF0E1512),
    surface = Color(0xFF0E1512),
    surfaceVariant = Color(0xFF3A4840),
    onSurface = Color(0xFFDEE7E0),
    onSurfaceVariant = Color(0xFFB8C6BE),
    outline = Color(0xFF84938B),
)

// ---- 天青：雨过天青云破处。刻意降到低饱和 —— 保持高彩度的话就和霜蓝是同一枚蓝，选了等于没选 ----
private val AzureLight = lightColorScheme(
    primary = Color(0xFF2F6B7C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC5E5EE),
    onPrimaryContainer = Color(0xFF04232B),
    secondary = Color(0xFF4F6368),
    secondaryContainer = Color(0xFFD2E4EA),
    tertiary = Color(0xFF7A5F2E),
    background = Color(0xFFF3FAFB),
    surface = Color(0xFFF3FAFB),
    surfaceVariant = Color(0xFFD7E5E9),
    onSurface = Color(0xFF141D1F),
    onSurfaceVariant = Color(0xFF3F484B),
    outline = Color(0xFF6E787B),
)

private val AzureDark = darkColorScheme(
    primary = Color(0xFF9DD5E3),
    onPrimary = Color(0xFF0A333F),
    primaryContainer = Color(0xFF17566A),
    onPrimaryContainer = Color(0xFFC5E5EE),
    secondary = Color(0xFFB2C6CB),
    secondaryContainer = Color(0xFF33444A),
    tertiary = Color(0xFFD8C08A),
    background = Color(0xFF0A1416),
    surface = Color(0xFF0A1416),
    surfaceVariant = Color(0xFF33444A),
    onSurface = Color(0xFFDCE7E9),
    onSurfaceVariant = Color(0xFFB5C4C9),
    outline = Color(0xFF829195),
)

// ---- 缃叶：陈年丝纸的黄绿。熵金是暖橙金，这一档往橄榄偏，tertiary 用石青把黄压住不漂 ----
private val StrawLight = lightColorScheme(
    primary = Color(0xFF6C6415),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF2EFAE),
    onPrimaryContainer = Color(0xFF221E00),
    secondary = Color(0xFF66623F),
    secondaryContainer = Color(0xFFECE8C6),
    tertiary = Color(0xFF4E6572),
    background = Color(0xFFFDFBEF),
    surface = Color(0xFFFDFBEF),
    surfaceVariant = Color(0xFFE9E6C9),
    onSurface = Color(0xFF1C1C0F),
    onSurfaceVariant = Color(0xFF484737),
    outline = Color(0xFF787761),
)

private val StrawDark = darkColorScheme(
    primary = Color(0xFFE4DF8B),
    onPrimary = Color(0xFF2E2A00),
    primaryContainer = Color(0xFF51490B),
    onPrimaryContainer = Color(0xFFF2EFAE),
    secondary = Color(0xFFCCC8A3),
    secondaryContainer = Color(0xFF49462D),
    tertiary = Color(0xFFA2C1CC),
    background = Color(0xFF13130B),
    surface = Color(0xFF13130B),
    surfaceVariant = Color(0xFF49462D),
    onSurface = Color(0xFFE6E4D1),
    onSurfaceVariant = Color(0xFFC8C6AB),
    outline = Color(0xFF91907A),
)

// ---- 素笺：未漂白的纸。石墨是冷灰、熵金是高饱和金，这里要的是几乎不带色相的暖灰 ----
private val PaperLight = lightColorScheme(
    primary = Color(0xFF6E5D46),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF2E4D0),
    onPrimaryContainer = Color(0xFF271C0B),
    secondary = Color(0xFF6A6255),
    secondaryContainer = Color(0xFFEDE4D5),
    tertiary = Color(0xFF4E6A72),
    background = Color(0xFFFBF8F2),
    surface = Color(0xFFFBF8F2),
    surfaceVariant = Color(0xFFEAE2D3),
    onSurface = Color(0xFF1B1A15),
    onSurfaceVariant = Color(0xFF48433B),
    outline = Color(0xFF797469),
)

private val PaperDark = darkColorScheme(
    primary = Color(0xFFDDCFB6),
    onPrimary = Color(0xFF322718),
    primaryContainer = Color(0xFF4E422E),
    onPrimaryContainer = Color(0xFFF2E4D0),
    secondary = Color(0xFFD2C7B2),
    secondaryContainer = Color(0xFF484134),
    tertiary = Color(0xFFA6C3CB),
    background = Color(0xFF12100D),
    surface = Color(0xFF12100D),
    surfaceVariant = Color(0xFF484134),
    onSurface = Color(0xFFE6E1D7),
    onSurfaceVariant = Color(0xFFC9C3B4),
    outline = Color(0xFF928B7C),
)

/**
 * 手调色板取表。DYNAMIC 不走这里，由 EntropyTheme 判断系统版本。
 *
 * 新增色板一律往枚举末尾追加：palette 存的是 ordinal，中间插一档会把老用户
 * 已经选好的方案整体错位一格。
 */
private fun paletteScheme(p: Palette, dark: Boolean): ColorScheme = when (p) {
    Palette.TEAL -> if (dark) TealDark else TealLight
    Palette.GOLD -> if (dark) GoldDark else GoldLight
    Palette.SAKURA -> if (dark) SakuraDark else SakuraLight
    Palette.FROST -> if (dark) FrostDark else FrostLight
    Palette.MATCHA -> if (dark) MatchaDark else MatchaLight
    Palette.EMBER -> if (dark) EmberDark else EmberLight
    Palette.INK -> if (dark) InkDark else InkLight
    Palette.ULTRAMARINE -> if (dark) UltramarineDark else UltramarineLight
    Palette.TWILIGHT -> if (dark) TwilightDark else TwilightLight
    Palette.CARNATION -> if (dark) CarnationDark else CarnationLight
    Palette.FOLIAGE -> if (dark) FoliageDark else FoliageLight
    Palette.CELADON -> if (dark) CeladonDark else CeladonLight
    Palette.AZURE -> if (dark) AzureDark else AzureLight
    Palette.STRAW -> if (dark) StrawDark else StrawLight
    Palette.PAPER -> if (dark) PaperDark else PaperLight
    // 熵紫就是内置的那两套；跟随壁纸在这一层等价于内置紫，
    // 真正的动态取色在 EntropyTheme 里按 SDK 版本决定
    else -> if (dark) DarkScheme else LightScheme
}

/**
 * 把用户自定义的高亮色并进 ColorScheme。
 *
 * 只替换 primary 那一族：background / surface 由色板决定，动了它们等于
 * 用户的"配色方案"白选。onPrimary 按亮度现算而不是照抄色板里的白字 ——
 * 用户挑一枚浅黄做高亮时，白字压上去就糊成一片，这是必须自己算的原因。
 */
private fun tinted(s: ColorScheme, h: Color): ColorScheme {
    val bright = h.luminance() > 0.45f
    return s.copy(
        primary = h,
        onPrimary = if (bright) Color(0xFF1A1A20) else Color(0xFFF6F4FA),
        primaryContainer = lerp(h, s.surface, if (s.isSystemLight()) 0.62f else 0.78f),
        onPrimaryContainer = if (bright) lerp(h, Color.Black, 0.62f) else lerp(h, Color.White, 0.7f),
    )
}

/** 用 surface 的亮度判断这套方案是不是浅色，比再传一个布尔参数可靠 */
private fun ColorScheme.isSystemLight(): Boolean = surface.luminance() > 0.5f

/**
 * 熵自己的语义色。
 * 温度梯度用"冷 -> 暖 -> 危险"三段，色相跨度刻意做大，
 * 因为悬浮窗要在游戏画面上叠加，和用户自己的配色撞色就废了。
 *
 * overlayScrim / onScrim 是一对：前者是浮窗底板，后者是底板上的字。
 * 这两个色不能直接复用 MaterialTheme 的 surface/onSurface ——
 * 浮窗要压在半透明底板上，Material 的 surface 在浅色主题下是纯白，
 * 叠上游戏画面会刺眼，所以给浮窗单独一条更克制的明度曲线。
 */
@Immutable
data class EntropyColors(
    val warn: Color,
    val tempCool: Color,
    val tempWarm: Color,
    val tempHot: Color,
    val overlayScrim: Color,
    val onScrim: Color,
)

val LocalEntropyColors = staticCompositionLocalOf {
    EntropyColors(
        warn = Color(0xFFFF453A),
        tempCool = Color(0xFF4FD1C5),
        tempWarm = Color(0xFFF6AD55),
        tempHot = Color(0xFFFC8181),
        overlayScrim = Color(0x99000000),
        onScrim = Color(0xFFEDEAF2),
    )
}

/**
 * 温度 -> 颜色。
 *
 * 这里的读数口径是"所有热区里的最大值"，真实机型上占住这个位置的通常是 SoC / GPU 结温：
 * 负载起来 55~70℃ 是常态。旧版 48℃ 就见红，等于把常态天天涂成告警色，
 * 而一块天天红着的岛最后没人再信它报的温度。72℃ 以上才走警示红。
 * 结论词（安全/偏热/危险）另算，那条是按用户自己设的提醒线走的。
 */
fun tempColor(scheme: EntropyColors, celsius: Float): Color = when {
    celsius < 40f -> scheme.tempCool
    celsius < 55f -> lerp(scheme.tempCool, scheme.tempWarm, (celsius - 40f) / 15f)
    celsius < 72f -> lerp(scheme.tempWarm, scheme.tempHot, (celsius - 55f) / 17f)
    else -> scheme.warn
}

private fun lerp(a: Color, b: Color, t: Float): Color {
    val f = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * f,
        green = a.green + (b.green - a.green) * f,
        blue = a.blue + (b.blue - a.blue) * f,
        alpha = a.alpha + (b.alpha - a.alpha) * f,
    )
}

/**
 * 把 ThemeMode 拍平成布尔。
 * EntropyTheme 和状态栏明暗判定都走这里 —— 两处各写一遍 when 迟早会漂移。
 */
@Composable
fun isDarkFor(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun EntropyTheme(
    /** 应用内可改：跟随系统 / 浅色 / 深色。默认跟随系统，未授权场景也一定有合理配色 */
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    /** 用户在"系统增强"页自定义的高亮色；null 表示用默认警示色 */
    accentArgb: Int? = null,
    /** 用户自定义的界面高亮色（primary 那一族）；null 或 0 表示跟随配色方案 */
    highlightArgb: Int? = null,
    /** 配色方案。选"跟随壁纸"时只有 Android 12+ 真的会取壁纸色 */
    palette: Palette = Palette.SAKURA,
    content: @Composable () -> Unit,
) {
    val dark = isDarkFor(themeMode)
    val ctx = LocalContext.current
    val base = when {
        palette != Palette.DYNAMIC -> paletteScheme(palette, dark)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicScheme(ctx, dark)
        dark -> DarkScheme
        else -> LightScheme
    }
    val hl = highlightArgb?.takeIf { it != 0 }
    val scheme = hl?.let { tinted(base, Color(it)) } ?: base
    val entropy = EntropyColors(
        warn = accentArgb?.let { Color(it) } ?: Color(0xFFFF453A),
        // tempCool 不能用 base.tertiary：动态取色下 tertiary 完全由壁纸决定，
        // 本机实测就是一枚偏红的色 —— 于是 30℃ 的"正常"和 48℃ 的"过热"看起来一样，
        // 而安全显示悬浮窗整个语义就是靠这条颜色梯度说话的。温度梯度一律用固定色相。
        tempCool = if (dark) Color(0xFF4FD1C5) else Color(0xFF0F766E),
        tempWarm = if (dark) Color(0xFFF6AD55) else Color(0xFFB45309),
        tempHot = if (dark) Color(0xFFFC8181) else Color(0xFFDC2626),
        // 底板色直接跟着色板走：十六套手调方案 + 动态取色各有各的底色，
        // 再写死一枚 0xE6FBF8FF 就等于让樱吹雪的浮窗退回旧熵紫的白。
        // 透明度仍给 0.90 而不是 0.60：半透过头时底下的游戏画面会把字冲淡
        overlayScrim = base.surface.copy(alpha = 0.90f),
        onScrim = base.onSurface,
    )
    CompositionLocalProvider(LocalEntropyColors provides entropy) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Android 12+ 才有 dynamic*ColorScheme，包一层做 API 守卫，避免 minSdk 24 时崩溃 */
@Composable
private fun dynamicScheme(ctx: android.content.Context, dark: Boolean) =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        if (dark) DarkScheme else LightScheme
    }
