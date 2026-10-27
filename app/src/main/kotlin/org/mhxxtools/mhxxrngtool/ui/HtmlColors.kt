package org.mhxxtools.mhxxrngtool.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * https://skst328.github.io/mhxx-renkin-reader/ の .dark テーマと同じ配色。
 * （スマホ表示でサイトがダークモードのときの見た目）
 */
object HtmlColors {
    /** ページ背景 oklch(0.155 0.015 260) */
    val Bg = Color(0xFF16181E)
    /** カード面 oklch(0.205 0.018 260) */
    val Surface = Color(0xFF21242C)
    /** 入力・チップ・サブ面 oklch(0.275 0.020 260) */
    val Surface2 = Color(0xFF32363F)
    /** 主テキスト */
    val Text = Color(0xFFF5F6F8)
    /** ミュートテキスト oklch(0.720) */
    val Muted = Color(0xFFA0A5B2)
    /** プライマリ青 oklch(0.65 0.180 255) */
    val Accent = Color(0xFF5B8DEF)
    /** プライマリ上の文字（暗い） */
    val OnAccent = Color(0xFF0E1520)
    /** ボーダー（半透明白相当） */
    val Border = Color(0xFF2E323C)
    /** 情報バー背景 */
    val InfoBg = Color(0xFF1C2433)
    /** 成功 oklch(0.76 0.150 150) */
    val Success = Color(0xFF5DC97A)
    /** 警告 */
    val Warning = Color(0xFFE0B04A)
    /** エラー */
    val Error = Color(0xFFE57373)
}
