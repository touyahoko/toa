package org.mhxxtools.mhxxrngtool.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary          = Color(0xFF8AB4F8),
    onPrimary        = Color(0xFF0D2137),
    primaryContainer = Color(0xFF1E3A5F),
    secondary        = Color(0xFF81C784),
    background       = Color(0xFF121212),
    surface          = Color(0xFF1E1E1E),
    surfaceVariant   = Color(0xFF2A2A2A),
    onSurface        = Color(0xFFE0E0E0),
    onSurfaceVariant = Color(0xFFB0B0B0),
    outline          = Color(0xFF444444),
    error            = Color(0xFFCF6679),
)

@Composable
fun MhxxRngTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography  = Typography(),
        content     = content
    )
}
