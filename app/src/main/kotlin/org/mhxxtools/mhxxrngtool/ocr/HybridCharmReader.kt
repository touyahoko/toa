package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 鑑定読み取り入口。
 *
 * 旧来の ML Kit / ハイブリッド OCR は使わず、
 * mhxx-renkin-reader と同じ固定座標 + 見本テンプレート照合のみ。
 * (Switch 16:9 キャプチャ専用)
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
                if (!RenkinSwitchReader.isSwitchCapture(bitmap)) {
                    error(
                        "Switchのキャプチャ画像（16:9）を選んでください。\n" +
                            "例: 1280×720 の錬金結果・鑑定画面のスクリーンショット"
                    )
                }
                val r = RenkinSwitchReader.read(context, bitmap)
                    ?: error("読み取りに失敗しました（画像形式を確認）")
                if (r.charm.skills.isEmpty()) {
                    error(
                        "スキルを認識できませんでした。\n" +
                            "・鑑定パネル全体が写った Switch キャプチャを使用\n" +
                            "・${r.note}"
                    )
                }
                Result(r.charm, r.note, r.confidence)
            }
        }
}
