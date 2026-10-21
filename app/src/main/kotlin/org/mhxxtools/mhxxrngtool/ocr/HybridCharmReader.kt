package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 鑑定読み取り入口 — mhxx-renkin-reader 方式のみ。
 *
 * 1. Switch 16:9 → 1280×720 に正規化
 * 2. それ以外 → assets/renkin/reference.png で射影位置合わせ
 * 3. 固定座標 + 見本テンプレート照合（スキル/Pt/スロット）
 *
 * 旧 ML Kit OCR / SwitchFixed / Tldr / CharmTemplate は使用しない。
 */
object HybridCharmReader {

    data class Result(
        val charm: OcrCharm,
        val note: String,
        val confidence: Float
    )

    suspend fun read(context: Context, bitmap: Bitmap): kotlin.Result<Result> =
        withContext(Dispatchers.Default) {
            runCatching {
                val r = RenkinSwitchReader.read(context, bitmap)
                    ?: error(
                        "読み取り失敗。\n" +
                            "Switchのキャプチャ（16:9）の鑑定画面を選んでください。"
                    )
                if (r.charm.skills.isEmpty()) {
                    error(
                        "スキルを認識できませんでした。\n" +
                            "・錬金結果の鑑定画面（天の護石など）全体が写った画像\n" +
                            "・${r.note}"
                    )
                }
                Result(r.charm, r.note, r.confidence)
            }
        }
}
