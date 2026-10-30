package org.mhxxtools.mhxxrngtool.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** HTMLツールと同じカラーパレット */
object HtmlColors {
    val Bg = Color(0xFF12141A)
    val Surface = Color(0xFF1C1F28)
    val Surface2 = Color(0xFF252836)
    val Border = Color(0xFF33374A)
    val Text = Color(0xFFE8EAF0)
    val Muted = Color(0xFF8B90A5)
    val Accent = Color(0xFF5B8DEF)
    val Accent2 = Color(0xFF3D6BC7)
    val Success = Color(0xFF4CAF82)
    val Warn = Color(0xFFE6A817)
    val Danger = Color(0xFFE05C5C)
}

private val HtmlDarkScheme = darkColorScheme(
    primary = HtmlColors.Accent,
    onPrimary = Color.White,
    primaryContainer = HtmlColors.Accent2,
    onPrimaryContainer = Color.White,
    secondary = HtmlColors.Success,
    onSecondary = Color.White,
    background = HtmlColors.Bg,
    onBackground = HtmlColors.Text,
    surface = HtmlColors.Surface,
    onSurface = HtmlColors.Text,
    surfaceVariant = HtmlColors.Surface2,
    onSurfaceVariant = HtmlColors.Muted,
    outline = HtmlColors.Border,
    outlineVariant = HtmlColors.Border,
    error = HtmlColors.Danger,
    onError = Color.White,
    tertiary = HtmlColors.Warn,
)

private val HtmlTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = HtmlColors.Text),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, color = HtmlColors.Accent),
    bodyLarge = TextStyle(fontSize = 14.sp, color = HtmlColors.Text),
    bodyMedium = TextStyle(fontSize = 13.sp, color = HtmlColors.Text),
    bodySmall = TextStyle(fontSize = 12.sp, color = HtmlColors.Muted),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = HtmlColors.Text),
    labelMedium = TextStyle(fontSize = 12.sp, color = HtmlColors.Muted),
    labelSmall = TextStyle(fontSize = 11.sp, color = HtmlColors.Muted),
)

@Composable
fun MhxxRngTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = HtmlDarkScheme, typography = HtmlTypography, content = content)
}
