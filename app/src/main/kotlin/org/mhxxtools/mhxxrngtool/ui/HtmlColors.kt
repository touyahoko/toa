package org.mhxxtools.mhxxrngtool.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * https://skst328.github.io/mhxx-renkin-reader/ の .dark を oklch→sRGB 変換した完全一致パレット。
 */
object HtmlColors {
    /** --background oklch(0.155 0.015 260) */
    val Bg = Color(0xFF090C13)
    /** --card oklch(0.205 0.018 260) */
    val Surface = Color(0xFF12171F)
    /** --secondary / --muted oklch(0.275 0.020 260) */
    val Surface2 = Color(0xFF222832)
    /** --accent oklch(0.305 0.028 260) */
    val Surface3 = Color(0xFF272F3D)
    /** --foreground */
    val Text = Color(0xFFF5F7FA)
    /** --muted-foreground */
    val Muted = Color(0xFF9DA5B1)
    /** --primary oklch(0.65 0.180 255) */
    val Accent = Color(0xFF348FF9)
    /** --primary-foreground */
    val OnAccent = Color(0xFF080D16)
    /** --border ~14% white */
    val Border = Color(0xFF2A303C)
    /** 情報バー（primary を薄く） */
    val InfoBg = Color(0xFF0E1A2E)
    val InfoBorder = Color(0xFF1E3A5F)
    /** --success */
    val Success = Color(0xFF61CB7C)
    /** --warning */
    val Warning = Color(0xFFF0B04F)
    /** --destructive */
    val Error = Color(0xFFFF6467)
    /** --ring */
    val Ring = Color(0xFF3970C2)
}
