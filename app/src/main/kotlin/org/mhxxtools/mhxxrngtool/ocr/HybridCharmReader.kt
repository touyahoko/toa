package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * 鑑定ハイブリッド読取:
 *  3) ML Kit OCR → スキル名（辞書制約）
 *  4) テンプレ照合 → ポイント・スロット + OCR結果の検証スコア
 * OCRとテンプレが一致した項目を優先し、片方だけの値は信頼度付きで採用する。
 */
object HybridCharmReader {

    data class Result(
        val charm: OcrCharm,
        val note: String
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
                    val fused = fuse(ocrCharm, tmpl?.charm)

                    val note = buildString {
                        append("hybrid")
                        append(" ocr=").append(if (raw.isBlank()) "empty" else "ok")
                        if (ocrCharm != null) {
                            append(" oS=")
                            append(ocrCharm.skills.joinToString("+") { "${it.name}${it.pts}" })
                            append(" oSlot=").append(ocrCharm.slots)
                        }
                        if (tmpl != null) {
                            append(" | tmpl=").append(tmpl.note)
                        }
                        if (raw.isNotBlank() && raw.length < 200) {
                            append(" | text=").append(raw.replace("\n", " / ").take(120))
                        }
                    }

                    if (fused.skills.isEmpty()) {
                        error(
                            "スキルを認識できませんでした。\n" +
                                "明るい画面で鑑定パネル全体が入るように撮影してください。\n($note)"
                        )
                    }
                    Result(fused, note)
                }
            }
        }

    private fun fuse(ocr: OcrCharm?, tmpl: OcrCharm?): OcrCharm {
        if (ocr == null && tmpl == null) {
            return OcrCharm(-1, -1, emptyList())
        }
        if (ocr == null) return tmpl!!
        if (tmpl == null) return ocr

        // スキル名は OCR 優先。ポイントは OCR が 0/欠落ならテンプレを採用。
        val skills = mutableListOf<OcrSkill>()
        val o1 = ocr.skills.getOrNull(0)
        val o2 = ocr.skills.getOrNull(1)
        val t1 = tmpl.skills.getOrNull(0)
        val t2 = tmpl.skills.getOrNull(1)

        fun mergeSkill(o: OcrSkill?, t: OcrSkill?): OcrSkill? {
            if (o == null && t == null) return null
            if (o == null) return t
            if (t == null) return o
            // 同じスキルならポイントはテンプレ優先（数字は OCR が弱い）
            if (o.globalIdx == t.globalIdx) {
                val pts = when {
                    t.pts > 0 -> t.pts
                    o.pts > 0 -> o.pts
                    else -> 0
                }
                return OcrSkill(o.globalIdx, o.name, pts)
            }
            // 不一致: OCR のスキル名を優先、ポイントは OCR>0 なら OCR、否则テンプレ
            val pts = when {
                o.pts > 0 -> o.pts
                t.pts > 0 -> t.pts
                else -> 0
            }
            return OcrSkill(o.globalIdx, o.name, pts)
        }

        mergeSkill(o1, t1)?.let { skills += it }
        mergeSkill(o2, t2)?.let { skills += it }

        // OCR がスキルを1つしか取れていないがテンプレが2つ → 2つ目を補完
        if (skills.size < 2 && t2 != null && skills.none { it.globalIdx == t2.globalIdx }) {
            skills += t2
        }
        if (skills.isEmpty() && tmpl.skills.isNotEmpty()) {
            skills += tmpl.skills
        }

        val slots = when {
            ocr.slots in 0..3 && tmpl.slots in 0..3 && ocr.slots == tmpl.slots -> ocr.slots
            tmpl.slots in 0..3 && ocr.slots !in 0..3 -> tmpl.slots
            ocr.slots in 0..3 -> ocr.slots
            tmpl.slots in 0..3 -> tmpl.slots
            else -> -1
        }

        val kind = when {
            ocr.kind >= 0 -> ocr.kind
            tmpl.kind >= 0 -> tmpl.kind
            else -> -1
        }

        return OcrCharm(kind, slots, skills)
    }
}
