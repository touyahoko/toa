package org.mhxxtools.mhxxrngtool.ocr

import android.graphics.Bitmap
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.mhxxtools.mhxxrngtool.rng.SKILL_NAMES
import kotlin.math.abs

/**
 * Switch キャプチャボタン固定画角専用リーダー。
 * 16:9 スクショ (1280×720 等) の相対座標で切り出して OCR。
 * 画角が固定のため認識率を最大化する。
 */
object SwitchFixedReader {

    data class Result(val charm: OcrCharm, val note: String)

    /** 16:9 かつある程度大きい解像度 → Switch スクショとみなす */
    fun isSwitchCapture(bmp: Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        if (w < 900 || h < 500) return false
        val ratio = w.toFloat() / h
        return abs(ratio - 16f / 9f) < 0.04f
    }

    suspend fun read(bitmap: Bitmap): Result? {
        if (!isSwitchCapture(bitmap)) return null

        return coroutineScope {
            val j1n = async { ocrCrop(bitmap, CropRegion.SKILL1_NAME) }
            val j1p = async { ocrCrop(bitmap, CropRegion.SKILL1_PTS) }
            val j2n = async { ocrCrop(bitmap, CropRegion.SKILL2_NAME) }
            val j2p = async { ocrCrop(bitmap, CropRegion.SKILL2_PTS) }
            val js  = async { ocrCrop(bitmap, CropRegion.SLOT) }
            val jp  = async { ocrCrop(bitmap, CropRegion.PANEL) }

            val t1n = j1n.await()
            val t1p = j1p.await()
            val t2n = j2n.await()
            val t2p = j2p.await()
            val ts  = js.await()
            val tp  = jp.await()

            val s1 = parseSkillLine(t1n, t1p)
            val s2 = parseSkillLine(t2n, t2p)
            // パネル全体からも補完
            val panelCharm = if (tp.isNotBlank()) parseOcrCharm(tp) else null

            val skills = mutableListOf<OcrSkill>()
            when {
                s1 != null -> skills += s1
                panelCharm?.skills?.isNotEmpty() == true -> skills += panelCharm.skills[0]
            }
            when {
                s2 != null -> skills += s2
                panelCharm != null && panelCharm.skills.size >= 2 -> skills += panelCharm.skills[1]
            }

            var slots = parseSlotText(ts)
            if (slots < 0) {
                slots = panelCharm?.slots ?: -1
            }

            val kind = panelCharm?.kind ?: -1
            val note = "switch-fixed ${bitmap.width}x${bitmap.height} " +
                "s1='${t1n.trim()}'+${t1p.trim()} s2='${t2n.trim()}' slot='${ts.trim()}' " +
                "→ ${skills.joinToString("+") { "${it.name}${it.pts}" }} sl=$slots"

            Result(OcrCharm(kind, slots, skills), note)
        }
    }

    private suspend fun ocrCrop(bmp: Bitmap, r: FloatArray): String =
        AndroidOcr.recognizeCropped(bmp, r[0], r[1], r[2], r[3]).getOrDefault("")

    /**
     * スキル行テキスト + ポイントテキストから OcrSkill を作る。
     * 「-----」「ーーー」等はスキルなし。
     */
    private fun parseSkillLine(nameText: String, ptsText: String): OcrSkill? {
        val combined = "$nameText $ptsText"
        if (isEmptySkill(nameText) && isEmptySkill(ptsText)) return null

        // ポイント
        val pts = Regex("\\+?\\s*(\\d{1,2})")
            .findAll(ptsText + " " + nameText)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .firstOrNull { it in 1..20 }
            ?: 0

        // 名前: まず parseOcrCharm
        val fromParse = parseOcrCharm(combined).skills.firstOrNull()
        if (fromParse != null) {
            return if (pts > 0) OcrSkill(fromParse.globalIdx, fromParse.name, pts) else fromParse
        }

        // エイリアス直接
        val norm = nameText
            .replace(Regex("[　\\s]+"), "")
            .replace(Regex("[+＋]\\d+"), "")
            .replace(Regex("固有スキル[12]?"), "")
        if (isEmptySkill(norm)) return null

        for ((alias, idx) in SKILL_ALIASES.entries.sortedByDescending { it.key.length }) {
            if (alias.length < 2) continue
            if (norm.contains(alias)) {
                val name = SKILL_NAMES.getOrNull(idx) ?: alias
                return OcrSkill(idx, name, pts)
            }
        }
        return null
    }

    private fun isEmptySkill(t: String): Boolean {
        val s = t.replace(Regex("[\\s　\\-_ー−–—―=＝]+"), "").trim()
        return s.isEmpty() || s in setOf("なし", "無", "(無)", "（なし）")
    }

    private fun parseSlotText(t: String): Int {
        if (t.isBlank()) return -1
        val fromCrop = parseSlotCrop(t)
        if (fromCrop != null) return fromCrop
        // ○ / 〇 の個数
        val circles = Regex("[○〇◯●◎◉]").findAll(t).count()
        if (circles in 1..3) return circles
        // --- のみ → 0
        val dashes = t.replace(Regex("[\\s　]"), "")
        if (Regex("^[-_ー−–—―]{2,}$").matches(dashes)) return 0
        return -1
    }
}
