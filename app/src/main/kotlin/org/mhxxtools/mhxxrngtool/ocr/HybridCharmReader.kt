package org.mhxxtools.mhxxrngtool.ocr

import org.mhxxtools.mhxxrngtool.rng.SKILL_NAMES

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * 鑑定ハイブリッド（認識優先・失敗しにくい版）
 *
 * 1) 元画像をそのまま ML Kit（固定座標）+ テンプレ
 * 2) Switch固定スクショ判定時は SwitchFixedReader
 * 3) OpenCV/射影 TL;DR（補助）
 * 4) 全画面 ML Kit テキスト
 *
 * スロット不明でもスキルが取れれば成功扱い。
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
                coroutineScope {
                    // 並列で複数経路
                    val fixedJob = async {
                        if (SwitchFixedReader.isSwitchCapture(bitmap)) {
                            SwitchFixedReader.read(bitmap)
                        } else null
                    }
                    val tmplJob = async {
                        CharmTemplateReader.read(context, bitmap).getOrNull()
                    }
                    val panelJob = async {
                        // パネル相対座標を直接 ML Kit
                        mlKitPanel(bitmap)
                    }
                    val fullJob = async {
                        runCatching { AndroidOcr.ocrBitmap(bitmap) }.getOrDefault("")
                    }
                    val tldrJob = async {
                        TldrCharmReader.read(context, bitmap).getOrNull()
                    }

                    val fixed = fixedJob.await()
                    val tmpl = tmplJob.await()
                    val panel = panelJob.await()
                    val fullText = fullJob.await()
                    val tldr = tldrJob.await()
                    val fullCharm = if (fullText.isNotBlank()) parseOcrCharm(fullText) else null

                    // マージ優先: fixed → tldr → tmpl → panel → full
                    var merged = OcrCharm(-1, -1, emptyList())
                    val notes = mutableListOf<String>()

                    fun absorb(c: OcrCharm?, tag: String) {
                        if (c == null) return
                        val before = merged.skills.size
                        merged = mergePreferTemplate(merged, c)
                        // mergePreferTemplate は base 空だと tmpl を返すので逆向きも
                        if (merged.skills.isEmpty() && c.skills.isNotEmpty()) {
                            merged = c
                        } else if (c.skills.isNotEmpty()) {
                            merged = smartMerge(merged, c)
                        }
                        if (merged.slots !in 0..3 && c.slots in 0..3) {
                            merged = OcrCharm(merged.kind, c.slots, merged.skills)
                        }
                        if (merged.skills.size > before || (c.slots in 0..3)) {
                            notes += tag
                        }
                    }

                    absorb(fixed?.charm, "fixed")
                    absorb(tldr?.charm, "tldr")
                    absorb(tmpl?.charm, "tmpl")
                    absorb(panel, "panel-ml")
                    absorb(fullCharm, "full-ml")

                    if (merged.skills.isEmpty()) {
                        error(
                            "スキルを認識できませんでした。\n" +
                                "・鑑定パネル（スキル名・スロット）がはっきり写るように\n" +
                                "・反射・ブレを避けて撮影\n" +
                                "debug: fixed=${fixed != null} tmpl=${tmpl != null} " +
                                "tldr=${tldr != null} panel=${panel.skills.size} " +
                                "fullLen=${fullText.length}"
                        )
                    }

                    var conf = 0.55f
                    if (fixed != null && fixed.charm.skills.isNotEmpty()) conf += 0.20f
                    if (tmpl != null && tmpl.charm.skills.isNotEmpty()) conf += 0.12f
                    if (tldr != null && tldr.charm.skills.isNotEmpty()) conf += 0.10f
                    if (merged.slots in 0..3) conf += 0.08f
                    if (merged.skills.size >= 2) conf += 0.05f
                    conf = conf.coerceIn(0.5f, 0.99f)

                    Result(
                        merged,
                        "hybrid [${notes.joinToString("+")}] " +
                            merged.skills.joinToString("+") { "${it.name}+${it.pts}" } +
                            " sl=${merged.slots}",
                        conf
                    )
                }
            }
        }

    private suspend fun mlKitPanel(bitmap: Bitmap): OcrCharm {
        val j1n = AndroidOcr.recognizeCropped(
            bitmap, CropRegion.SKILL1_NAME[0], CropRegion.SKILL1_NAME[1],
            CropRegion.SKILL1_NAME[2], CropRegion.SKILL1_NAME[3]
        ).getOrDefault("")
        val j1p = AndroidOcr.recognizeCropped(
            bitmap, CropRegion.SKILL1_PTS[0], CropRegion.SKILL1_PTS[1],
            CropRegion.SKILL1_PTS[2], CropRegion.SKILL1_PTS[3]
        ).getOrDefault("")
        val j2n = AndroidOcr.recognizeCropped(
            bitmap, CropRegion.SKILL2_NAME[0], CropRegion.SKILL2_NAME[1],
            CropRegion.SKILL2_NAME[2], CropRegion.SKILL2_NAME[3]
        ).getOrDefault("")
        val j2p = AndroidOcr.recognizeCropped(
            bitmap, CropRegion.SKILL2_PTS[0], CropRegion.SKILL2_PTS[1],
            CropRegion.SKILL2_PTS[2], CropRegion.SKILL2_PTS[3]
        ).getOrDefault("")
        val js = AndroidOcr.recognizeCropped(
            bitmap, CropRegion.SLOT[0], CropRegion.SLOT[1],
            CropRegion.SLOT[2], CropRegion.SLOT[3]
        ).getOrDefault("")
        val jp = AndroidOcr.recognizeCropped(
            bitmap, CropRegion.PANEL[0], CropRegion.PANEL[1],
            CropRegion.PANEL[2], CropRegion.PANEL[3]
        ).getOrDefault("")

        val skills = mutableListOf<OcrSkill>()
        parseSkillLoose(j1n, j1p)?.let { skills += it }
        parseSkillLoose(j2n, j2p)?.let { skills += it }
        if (skills.isEmpty() && jp.isNotBlank()) {
            skills += parseOcrCharm(jp).skills
        }
        var slots = parseSlotLoose(js)
        if (slots < 0 && jp.isNotBlank()) slots = parseOcrCharm(jp).slots
        return OcrCharm(-1, slots, skills)
    }

    private fun parseSkillLoose(nameText: String, ptsText: String): OcrSkill? {
        val combined = "$nameText $ptsText"
        if (isEmptySkill(nameText) && isEmptySkill(ptsText)) return null
        val pts = Regex("\\+?\\s*(\\d{1,2})")
            .findAll(ptsText + " " + nameText)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .firstOrNull { it in 1..20 } ?: 0
        val fromParse = parseOcrCharm(combined).skills.firstOrNull()
        if (fromParse != null) {
            return if (pts > 0) OcrSkill(fromParse.globalIdx, fromParse.name, pts) else fromParse
        }
        val norm = nameText.replace(Regex("[　\\s]+"), "")
            .replace(Regex("[+＋]\\d+"), "")
            .replace(Regex("固有スキル[12]?"), "")
        if (isEmptySkill(norm)) return null
        for ((alias, idx) in SKILL_ALIASES.entries.sortedByDescending { it.key.length }) {
            if (alias.length < 2) continue
            if (norm.contains(alias)) {
                return OcrSkill(idx, SKILL_NAMES.getOrNull(idx) ?: alias, pts)
            }
        }
        return null
    }

    private fun isEmptySkill(t: String): Boolean {
        val s = t.replace(Regex("[\\s　\\-_ー−–—―=＝]+"), "").trim()
        return s.isEmpty() || s in setOf("なし", "無", "(無)", "（なし）")
    }

    private fun parseSlotLoose(t: String): Int {
        if (t.isBlank()) return -1
        parseSlotCrop(t)?.let { return it }
        val circles = Regex("[○〇◯●◎◉]").findAll(t).count()
        if (circles in 1..3) return circles
        val dashes = t.replace(Regex("[\\s　]"), "")
        if (Regex("^[-_ー−–—―]{2,}$").matches(dashes)) return 0
        return -1
    }

    /** 両方のスキルを可能な限り残すマージ */
    private fun smartMerge(a: OcrCharm, b: OcrCharm): OcrCharm {
        val skills = mutableListOf<OcrSkill>()
        fun add(s: OcrSkill) {
            val i = skills.indexOfFirst { it.globalIdx == s.globalIdx }
            if (i < 0) {
                if (skills.size < 2) skills += s
            } else if (skills[i].pts <= 0 && s.pts > 0) {
                skills[i] = s
            }
        }
        a.skills.forEach { add(it) }
        b.skills.forEach { add(it) }
        val slots = when {
            a.slots in 0..3 -> a.slots
            b.slots in 0..3 -> b.slots
            else -> -1
        }
        val kind = a.kind.takeIf { it >= 0 } ?: b.kind
        return OcrCharm(kind, slots, skills)
    }

    private fun mergePreferTemplate(base: OcrCharm, tmpl: OcrCharm?): OcrCharm {
        if (tmpl == null) return base
        if (base.skills.isEmpty()) return tmpl
        return smartMerge(base, tmpl)
    }
}
