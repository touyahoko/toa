package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * AI護石フレーム検索（オンデバイス）
 *
 * Switch キャプチャ固定画角 (16:9) は SwitchFixedReader を最優先。
 * それ以外は OCR + テンプレ + 学習モデルのハイブリッド。
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
                // ── Switch 固定画角優先（認識率最大化）────────────────
                if (SwitchFixedReader.isSwitchCapture(bitmap)) {
                    val fixed = SwitchFixedReader.read(bitmap)
                    if (fixed != null && fixed.charm.skills.isNotEmpty()) {
                        // テンプレでポイント/スロットを補強
                        val tmpl = CharmTemplateReader.read(context, bitmap).getOrNull()
                        val enriched = enrichWithTemplate(fixed.charm, tmpl?.charm)
                        val scored = CharmLearningModel.decide(
                            context, enriched, tmpl?.charm, fixed.note
                        )
                        return@runCatching Result(
                            scored.charm,
                            "switch-fixed+learn conf=${"%.2f".format(scored.confidence)} ${fixed.note}",
                            scored.confidence.coerceAtLeast(0.85f)
                        )
                    }
                }

                // ── フォールバック: 汎用ハイブリッド ────────────────
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
                    val scored = CharmLearningModel.decide(context, ocrCharm, tmpl?.charm, raw)
                    val charm = scored.charm
                    val note = "hybrid conf=${"%.2f".format(scored.confidence)} ${scored.detail}"
                    if (charm.skills.isEmpty()) {
                        error(
                            "護石情報を認識できませんでした。\n" +
                                "Switchのキャプチャボタンで撮影した画面を使ってください。\n($note)"
                        )
                    }
                    Result(charm, note, scored.confidence)
                }
            }
        }

    private fun enrichWithTemplate(base: OcrCharm, tmpl: OcrCharm?): OcrCharm {
        if (tmpl == null) return base
        val skills = base.skills.mapIndexed { i, s ->
            if (s.pts > 0) s
            else {
                val tp = tmpl.skills.firstOrNull { it.globalIdx == s.globalIdx }?.pts
                    ?: tmpl.skills.getOrNull(i)?.pts
                if ((tp ?: 0) > 0) OcrSkill(s.globalIdx, s.name, tp!!) else s
            }
        }
        val slots = if (base.slots in 0..3) base.slots
        else tmpl.slots.takeIf { it in 0..3 } ?: -1
        return OcrCharm(base.kind, slots, skills)
    }
}
