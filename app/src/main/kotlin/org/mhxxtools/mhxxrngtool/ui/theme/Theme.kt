package org.mhxxtools.mhxxrngtool.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * mhxx-renkin-reader サイトと同じライトテーマ。
 * https://skst328.github.io/mhxx-renkin-reader/
 */
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF3B6FDB),
    onPrimary = Color(0xFFF8FAFC),
    primaryContainer = Color(0xFFD6E3FC),
    onPrimaryContainer = Color(0xFF0D2137),
    secondary = Color(0xFF5B6578),
    onSecondary = Color(0xFFF8FAFC),
    secondaryContainer = Color(0xFFECEEF2),
    onSecondaryContainer = Color(0xFF1A1B1E),
    tertiary = Color(0xFF2E9B57),
    background = Color(0xFFF8F9FB),
    onBackground = Color(0xFF1A1B1E),
    surface = Color(0xFFF3F4F7),
    onSurface = Color(0xFF1A1B1E),
    surfaceVariant = Color(0xFFECEEF2),
    onSurfaceVariant = Color(0xFF6B7280),
    outline = Color(0xFFD8DAE0),
    outlineVariant = Color(0xFFE5E7EB),
    error = Color(0xFFD14343),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFDE8E8),
    onErrorContainer = Color(0xFF7F1D1D),
)

@Composable
fun MhxxRngTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography(),
        content = content
    )
}
