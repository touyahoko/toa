package org.mhxxtools.mhxxrngtool.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * mhxx-renkin-reader サイトのダークテーマと同じ配色。
 * https://skst328.github.io/mhxx-renkin-reader/
 */
private val RenkinDarkScheme = darkColorScheme(
    primary = Color(0xFF5B8DEF),
    onPrimary = Color(0xFF0E1520),
    primaryContainer = Color(0xFF1C2F55),
    onPrimaryContainer = Color(0xFFD6E3FC),
    secondary = Color(0xFF32363F),
    onSecondary = Color(0xFFF5F6F8),
    secondaryContainer = Color(0xFF3C404C),
    onSecondaryContainer = Color(0xFFF5F6F8),
    tertiary = Color(0xFF5DC97A),
    onTertiary = Color(0xFF0E1520),
    background = Color(0xFF16181E),
    onBackground = Color(0xFFF5F6F8),
    surface = Color(0xFF21242C),
    onSurface = Color(0xFFF5F6F8),
    surfaceVariant = Color(0xFF32363F),
    onSurfaceVariant = Color(0xFFA0A5B2),
    outline = Color(0xFF3A3F4A),
    outlineVariant = Color(0xFF2E323C),
    error = Color(0xFFE57373),
    onError = Color(0xFF1A0A0A),
    errorContainer = Color(0xFF5C1A1A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun MhxxRngTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RenkinDarkScheme,
        typography = Typography(),
        content = content
    )
}
