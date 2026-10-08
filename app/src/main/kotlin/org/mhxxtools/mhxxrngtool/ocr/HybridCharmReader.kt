package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * 鑑定ハイブリッド読取:
 *  3) ML Kit OCR → スキル名
 *  4) テンプレ照合 → ポイント/スロット
 *  学習モデル → OCR×テンプレの最終判定 + 成功例のオンライン学習
 */
object HybridCharmReader {

    data class Result(
        val charm: OcrCharm,
        val note: String,
        val confidence: Float = 0f
    )

    suspend fun read(context: Context, bitmap: Bitmap): kotlin.Result<Result> =
        withContext(Dispatchers.Default) {
            runCatching {
                coroutineScope {
                    val ocrJob = async {
                        runCatching { AndroidOcr.ocrBitmap(bitmap) }.getOrDefault("")
                    }
                    val tmplJob = async {
                        CharmTemplateReader.read(context, bitmap).getOrNull()
                    }
                    val raw = ocrJob.await()
                    val tmpl = tmplJob.await()

                    val ocrCharm = if (raw.isNotBlank()) parseOcrCharm(raw) else null

                    // 学習モデルで最終決定
                    val scored = CharmLearningModel.decide(
                        context = context,
                        ocr = ocrCharm,
                        tmpl = tmpl?.charm,
                        ocrRaw = raw
                    )
                    val fused = scored.charm

                    val note = buildString {
                        append("hybrid+learn conf=").append("%.2f".format(scored.confidence))
                        append(" ").append(scored.detail)
                        append(" ocr=").append(if (raw.isBlank()) "empty" else "ok")
                        if (ocrCharm != null) {
                            append(" oS=")
                            append(ocrCharm.skills.joinToString("+") { "${it.name}${it.pts}" })
                            append(" oSlot=").append(ocrCharm.slots)
                        }
                        if (tmpl != null) {
                            append(" | tmpl=").append(tmpl.note)
                        }
                    }

                    if (fused.skills.isEmpty()) {
                        error(
                            "スキルを認識できませんでした。\n" +
                                "明るい画面で鑑定パネル全体が入るように撮影してください。\n($note)"
                        )
                    }
                    Result(fused, note, scored.confidence)
                }
            }
        }
}
