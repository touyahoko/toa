package org.mhxxtools.mhxxrngtool.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

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
