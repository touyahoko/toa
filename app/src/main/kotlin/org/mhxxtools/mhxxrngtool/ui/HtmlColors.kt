package org.mhxxtools.mhxxrngtool.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * HTML ツールの配色を Compose Color に移植したカラーパレット。
 * MainScreen 等から import org.mhxxtools.mhxxrngtool.ui.theme.HtmlColors で参照。
 */
object HtmlColors {
    /** メイン背景 */
    val Bg      = Color(0xFF121212)
    /** ヘッダー・カード面 */
    val Surface = Color(0xFF1E1E1E)
    /** チップ・サブ面 */
    val Surface2= Color(0xFF2C2C2C)
    /** 主テキスト */
    val Text    = Color(0xFFE8E8E8)
    /** サブ・ミュートテキスト */
    val Muted   = Color(0xFF9E9E9E)
    /** アクセント (選択タブ・チップ) */
    val Accent  = Color(0xFF4A90D9)
    /** 区切り線・ボーダー */
    val Border  = Color(0xFF3A3A3A)
}
