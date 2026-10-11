package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * 鑑定ハイブリッド
 *
 * 1) Switch固定スクショ → 座標OCR + テンプレ
 * 2) スマホ直撮り → TL;DR方式（射影＋トップハット＋テンプレ）※note.com/fwe 準拠
 * 3) フォールバック → テンプレ / ML Kit OCR
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
                // ── 1. Switch 固定画角 ──────────────────────────────
                if (SwitchFixedReader.isSwitchCapture(bitmap)) {
                    val fixed = SwitchFixedReader.read(bitmap)
                    val tmpl = CharmTemplateReader.read(context, bitmap).getOrNull()
                    if (fixed != null) {
                        val merged = mergePreferTemplate(fixed.charm, tmpl?.charm)
                        if (isComplete(merged)) {
                            return@runCatching Result(
                                merged,
                                "switch-fixed ${fixed.note}",
                                0.97f
                            )
                        }
                    }
                    if (tmpl != null && isComplete(tmpl.charm)) {
                        return@runCatching Result(
                            tmpl.charm,
                            "switch-tmpl ${tmpl.note}",
                            0.95f
                        )
                    }
                }

                // ── 2. TL;DR スマホ直撮りパイプライン ────────────────
                val tldr = TldrCharmReader.read(context, bitmap).getOrNull()
                if (tldr != null && isComplete(tldr.charm)) {
                    return@runCatching Result(
                        tldr.charm,
                        tldr.note,
                        tldr.confidence.coerceAtLeast(0.90f)
                    )
                }
                // 部分成功でもスキルがあれば採用候補
                if (tldr != null && tldr.charm.skills.isNotEmpty()) {
                    return@runCatching Result(
                        tldr.charm,
                        tldr.note + " (partial)",
                        tldr.confidence
                    )
                }

                // ── 3. 汎用フォールバック ────────────────────────────
                coroutineScope {
                    val tmplJob = async {
                        CharmTemplateReader.read(context, bitmap).getOrNull()
                    }
                    val ocrJob = async {
                        runCatching { AndroidOcr.ocrBitmap(bitmap) }.getOrDefault("")
                    }
                    val tmpl = tmplJob.await()
                    val raw = ocrJob.await()
                    val ocrCharm = if (raw.isNotBlank()) parseOcrCharm(raw) else null

                    if (tmpl != null && isComplete(tmpl.charm)) {
                        return@coroutineScope Result(
                            mergePreferTemplate(ocrCharm ?: tmpl.charm, tmpl.charm),
                            "tmpl-fallback ${tmpl.note}",
                            0.88f
                        )
                    }
                    val scored = CharmLearningModel.decide(context, ocrCharm, tmpl?.charm, raw)
                    if (scored.charm.skills.isEmpty()) {
                        error(
                            "護石情報を認識できませんでした。\n" +
                                "・鑑定パネル全体が写るように\n" +
                                "・反射・ブレを避けて撮影\n" +
                                "または Switch のキャプチャ画像を使ってください。\n" +
                                "(${scored.detail})"
                        )
                    }
                    Result(
                        scored.charm,
                        "hybrid ${scored.detail}",
                        scored.confidence
                    )
                }
            }
        }

    private fun isComplete(c: OcrCharm): Boolean =
        c.skills.isNotEmpty() && c.slots in 0..3

    private fun mergePreferTemplate(base: OcrCharm, tmpl: OcrCharm?): OcrCharm {
        if (tmpl == null) return base
        if (base.skills.isEmpty()) return tmpl
        val skills = mutableListOf<OcrSkill>()
        val n = maxOf(base.skills.size, tmpl.skills.size)
        for (i in 0 until n) {
            val b = base.skills.getOrNull(i)
            val t = tmpl.skills.getOrNull(i)
            when {
                t != null && b != null -> {
                    val pts = when {
                        t.pts > 0 -> t.pts
                        b.pts > 0 -> b.pts
                        else -> 0
                    }
                    skills += OcrSkill(t.globalIdx, t.name, pts)
                }
                t != null -> skills += t
                b != null -> skills += b
            }
        }
        val slots = when {
            tmpl.slots in 0..3 -> tmpl.slots
            base.slots in 0..3 -> base.slots
            else -> -1
        }
        return OcrCharm(base.kind.takeIf { it >= 0 } ?: tmpl.kind, slots, skills)
    }
}
