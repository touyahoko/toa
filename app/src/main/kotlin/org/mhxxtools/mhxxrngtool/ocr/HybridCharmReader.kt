package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.mhxxtools.mhxxrngtool.rng.SKILL_NAMES

/**
 * AI護石フレーム検索（オンデバイス版）
 *
 * 外部の Google AI Studio 課金ツールと同等の流れを端末内で完結:
 *   写真 → 護石内容の認識 →（ViewModel側で）フレーム全件検索
 *
 * 認識パス（複数を並列→統合）:
 *  1) 全体 OCR (ML Kit 日本語)
 *  2) 相対座標クロップ OCR（スキル1/2・スロット行）
 *  3) テンプレート照合（ポイント・スロット・検証）
 *  4) 学習モデルで最終決定
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
                    // ── 1) 全体 OCR ──────────────────────────────────────
                    val fullOcrJob = async {
                        runCatching { AndroidOcr.ocrBitmap(bitmap) }.getOrDefault("")
                    }
                    // ── 2) 行クロップ OCR ────────────────────────────────
                    val cropJob = async { cropPass(bitmap) }
                    // ── 3) テンプレ ──────────────────────────────────────
                    val tmplJob = async {
                        CharmTemplateReader.read(context, bitmap).getOrNull()
                    }

                    val fullText = fullOcrJob.await()
                    val cropCharm = cropJob.await()
                    val tmpl = tmplJob.await()

                    val fullCharm = if (fullText.isNotBlank()) parseOcrCharm(fullText) else null

                    // クロップ結果と全体OCRをマージ（クロップを優先してスキル補充）
                    val ocrMerged = mergeOcr(fullCharm, cropCharm, fullText)

                    val scored = CharmLearningModel.decide(
                        context = context,
                        ocr = ocrMerged,
                        tmpl = tmpl?.charm,
                        ocrRaw = fullText
                    )

                    // ポイントが 0 のままでテンプレに値があれば補完
                    val charm = enrichPoints(scored.charm, tmpl?.charm, ocrMerged)

                    val note = buildString {
                        append("ai-frame conf=").append("%.2f".format(scored.confidence))
                        append(" ").append(scored.detail)
                        append(" full=").append(if (fullText.isBlank()) "empty" else "ok")
                        append(" crop=").append(
                            cropCharm?.skills?.joinToString("+") { "${it.name}${it.pts}" } ?: "none"
                        )
                        if (tmpl != null) append(" | tmpl=").append(tmpl.note.take(80))
                    }

                    if (charm.skills.isEmpty()) {
                        error(
                            "護石情報を認識できませんでした。\n" +
                                "鑑定画面のパネル全体が写るように撮影してください。\n($note)"
                        )
                    }
                    Result(charm, note, scored.confidence)
                }
            }
        }

    /** 相対座標でスキル1/2・スロット行を切って OCR */
    private suspend fun cropPass(bitmap: Bitmap): OcrCharm? {
        val t1 = AndroidOcr.recognizeCropped(
            bitmap,
            CropRegion.SKILL1[0], CropRegion.SKILL1[1],
            CropRegion.SKILL1[2], CropRegion.SKILL1[3]
        ).getOrDefault("")
        val t2 = AndroidOcr.recognizeCropped(
            bitmap,
            CropRegion.SKILL2[0], CropRegion.SKILL2[1],
            CropRegion.SKILL2[2], CropRegion.SKILL2[3]
        ).getOrDefault("")
        val ts = AndroidOcr.recognizeCropped(
            bitmap,
            CropRegion.SLOT[0], CropRegion.SLOT[1],
            CropRegion.SLOT[2], CropRegion.SLOT[3]
        ).getOrDefault("")

        val combined = listOf(t1, t2, ts).filter { it.isNotBlank() }.joinToString("\n")
        if (combined.isBlank()) return null

        // 行ごとにも parse してスキルを拾う
        val fromFull = parseOcrCharm(combined)
        val s1 = extractSkillFromLine(t1)
        val s2 = extractSkillFromLine(t2)
        val skills = mutableListOf<OcrSkill>()
        s1?.let { skills += it }
        s2?.let { skills += it }
        if (skills.isEmpty()) return fromFull

        val slots = when {
            fromFull.slots in 0..3 -> fromFull.slots
            else -> parseSlotCrop(ts) ?: -1
        }
        return OcrCharm(fromFull.kind, slots, skills)
    }

    private fun extractSkillFromLine(line: String): OcrSkill? {
        if (line.isBlank()) return null
        val c = parseOcrCharm(line)
        if (c.skills.isNotEmpty()) return c.skills.first()
        // エイリアス直接
        val norm = line.replace(Regex("[　\\s]+"), "")
        for ((alias, idx) in SKILL_ALIASES.entries.sortedByDescending { it.key.length }) {
            if (alias.length < 2) continue
            if (norm.contains(alias)) {
                val pts = Regex("\\+?(\\d{1,2})").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val name = SKILL_NAMES.getOrNull(idx) ?: alias
                return OcrSkill(idx, name, pts)
            }
        }
        return null
    }

    private fun mergeOcr(full: OcrCharm?, crop: OcrCharm?, raw: String): OcrCharm? {
        if (full == null && crop == null) return null
        if (full == null) return crop
        if (crop == null) return full

        val skills = mutableListOf<OcrSkill>()
        val c1 = crop.skills.getOrNull(0)
        val c2 = crop.skills.getOrNull(1)
        val f1 = full.skills.getOrNull(0)
        val f2 = full.skills.getOrNull(1)

        // クロップで取れたスキル名を優先、ポイントは大きい方
        fun pick(a: OcrSkill?, b: OcrSkill?): OcrSkill? {
            if (a == null) return b
            if (b == null) return a
            if (a.globalIdx == b.globalIdx) {
                return OcrSkill(a.globalIdx, a.name, maxOf(a.pts, b.pts))
            }
            // クロップ優先
            return a
        }
        pick(c1, f1)?.let { skills += it }
        pick(c2, f2)?.let { skills += it }
        if (skills.isEmpty()) skills += full.skills.ifEmpty { crop.skills }

        val slots = when {
            crop.slots in 0..3 -> crop.slots
            full.slots in 0..3 -> full.slots
            else -> -1
        }
        val kind = when {
            full.kind >= 0 -> full.kind
            crop.kind >= 0 -> crop.kind
            else -> -1
        }
        return OcrCharm(kind, slots, skills)
    }

    private fun enrichPoints(base: OcrCharm, tmpl: OcrCharm?, ocr: OcrCharm?): OcrCharm {
        if (tmpl == null && ocr == null) return base
        val skills = base.skills.mapIndexed { i, s ->
            if (s.pts > 0) return@mapIndexed s
            val tp = tmpl?.skills?.getOrNull(i)?.takeIf { it.globalIdx == s.globalIdx }?.pts
                ?: tmpl?.skills?.firstOrNull { it.globalIdx == s.globalIdx }?.pts
            val op = ocr?.skills?.firstOrNull { it.globalIdx == s.globalIdx }?.pts
            val pts = when {
                (tp ?: 0) > 0 -> tp!!
                (op ?: 0) > 0 -> op!!
                else -> 0
            }
            OcrSkill(s.globalIdx, s.name, pts)
        }
        val slots = if (base.slots in 0..3) base.slots
        else tmpl?.slots?.takeIf { it in 0..3 } ?: ocr?.slots ?: -1
        return OcrCharm(base.kind, slots, skills)
    }
}
