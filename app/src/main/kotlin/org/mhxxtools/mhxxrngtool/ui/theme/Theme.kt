package org.mhxxtools.mhxxrngtool.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * mhxx-renkin-reader サイト .dark と同一の Material3 カラースキーム。
 * 全タブの Button / TextField / Card / Surface がこれに従う。
 */
private val RenkinDark = darkColorScheme(
    primary = Color(0xFF348FF9),
    onPrimary = Color(0xFF080D16),
    primaryContainer = Color(0xFF0E1A2E),
    onPrimaryContainer = Color(0xFFD6E3FC),
    secondary = Color(0xFF222832),
    onSecondary = Color(0xFFF5F7FA),
    secondaryContainer = Color(0xFF272F3D),
    onSecondaryContainer = Color(0xFFF5F7FA),
    tertiary = Color(0xFF61CB7C),
    onTertiary = Color(0xFF080D16),
    background = Color(0xFF090C13),
    onBackground = Color(0xFFF5F7FA),
    surface = Color(0xFF12171F),
    onSurface = Color(0xFFF5F7FA),
    surfaceVariant = Color(0xFF222832),
    onSurfaceVariant = Color(0xFF9DA5B1),
    surfaceBright = Color(0xFF1A2030),
    surfaceDim = Color(0xFF090C13),
    surfaceContainer = Color(0xFF12171F),
    surfaceContainerHigh = Color(0xFF1A2030),
    surfaceContainerHighest = Color(0xFF222832),
    surfaceContainerLow = Color(0xFF0D1118),
    surfaceContainerLowest = Color(0xFF05070B),
    outline = Color(0xFF2A303C),
    outlineVariant = Color(0xFF1E2430),
    error = Color(0xFFFF6467),
    onError = Color(0xFF080D16),
    errorContainer = Color(0xFF5C1A1A),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE8EAED),
    inverseOnSurface = Color(0xFF12171F),
    inversePrimary = Color(0xFF1A5BB8),
    scrim = Color(0xFF000000),
)

private val RenkinShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),  // site --radius 0.625rem ≈ 10px
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

@Composable
fun MhxxRngTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RenkinDark,
        typography = Typography(),
        shapes = RenkinShapes,
        content = content
    )
}
