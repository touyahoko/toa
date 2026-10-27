package org.mhxxtools.mhxxrngtool.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * https://skst328.github.io/mhxx-renkin-reader/ と同じライト配色。
 * oklch 値を近似した Compose Color。
 */
object HtmlColors {
    /** ページ背景 (ほぼ白) */
    val Bg = Color(0xFFF8F9FB)
    /** カード・ヘッダー面 */
    val Surface = Color(0xFFF3F4F7)
    /** チップ・入力・サブ面 */
    val Surface2 = Color(0xFFECEEF2)
    /** 主テキスト */
    val Text = Color(0xFF1A1B1E)
    /** ミュートテキスト */
    val Muted = Color(0xFF6B7280)
    /** プライマリ青 (サイト primary) */
    val Accent = Color(0xFF3B6FDB)
    /** プライマリ上の文字 */
    val OnAccent = Color(0xFFF8FAFC)
    /** ボーダー */
    val Border = Color(0xFFD8DAE0)
    /** 薄い青の情報バー背景 */
    val InfoBg = Color(0xFFE8F0FE)
    /** 成功 */
    val Success = Color(0xFF2E9B57)
    /** 警告 */
    val Warning = Color(0xFFC4891A)
    /** エラー */
    val Error = Color(0xFFD14343)
}
