package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mhxxtools.mhxxrngtool.rng.KIND_TABLES
import org.mhxxtools.mhxxrngtool.rng.SKILL_NAMES

/**
 * テンプレ照合で「どこを読むか」を決め → その座標を ML Kit で文字化 → 辞書マッチ。
 *
 * 流れ:
 *  1. RenkinSwitchReader が reference 位置合わせ + スキル／スロット推定
 *  2. 同じ正規化座標の NAME/PTS ROI を切り出し ML Kit
 *  3. 辞書で正式名に解決。ポイントは数字OCR優先、ダメならテンプレ側
 *  4. 行の順序（第1／第2）は ROI 固定なので入れ替わらない
 */
object HybridCharmReader {
    private const val TAG = "HybridCharm"

    data class Result(val charm: OcrCharm, val note: String, val confidence: Float)

    // RenkinSwitchReader と同じ 1170x612 系座標に合わせる
    private const val SW = 1170
    private const val SH = 612
    private val NAME1 = intArrayOf(599, 140, 731, 162)
    private val PTS1 = intArrayOf(727, 140, 777, 162)
    private val NAME2 = intArrayOf(599, 165, 731, 187)
    private val PTS2 = intArrayOf(727, 165, 777, 187)
    private val SLOT = intArrayOf(530, 185, 640, 210)

    suspend fun read(
        context: Context,
        bitmap: Bitmap,
        preferredKind: Int = -1
    ): kotlin.Result<Result> =
        withContext(Dispatchers.Default) {
            try {
                // 1) テンプレ（位置合わせ込み）
                val local = RenkinSwitchReader.read(context, bitmap, preferredKind = -1)

                // 2) 同じ画面正規化で ROI → ML Kit
                val aligned = alignLikeRenkin(context, bitmap)
                val mlSkills = mutableListOf<OcrSkill>()
                val notes = mutableListOf<String>()

                if (aligned != null) {
                    val rows = listOf(
                        NAME1 to PTS1,
                        NAME2 to PTS2
                    )
                    for (ni in rows.indices) {
                        val nb = rows[ni].first
                        val pb = rows[ni].second
                        val nameTxt = ocrRoi(aligned, nb)
                        val ptsTxt = ocrRoi(aligned, pb)
                        val skill = dictSkill(nameTxt, ptsTxt, local?.charm?.skills?.getOrNull(ni))
                        Log.i(TAG, "row$ni name='$nameTxt' pts='$ptsTxt' -> ${skill?.name}+${skill?.pts}")
                        if (skill != null) {
                            mlSkills += skill
                            notes += "r$ni:${skill.name}"
                        }
                    }
                }

                val slotMl = aligned?.let { parseSlots(ocrRoi(it, SLOT)) } ?: -1
                if (aligned != null && aligned != bitmap) {
                    // recycle only if we created a new bitmap - scale may return new
                }

                // 3) 行ごとに「名前は ML 優先、ポイントはテンプレ優先」で合成
                //    （+6→+9 の数字誤読、窮地→闘魂 の似字誤読を抑える）
                val tmplSkills = local?.charm?.skills.orEmpty()
                val finalSkills = mergeRows(mlSkills, tmplSkills)

                if (finalSkills.isEmpty()) {
                    // WASM 最後の手段
                    val wasm = try {
                        RenkinWasmBridge.read(context, bitmap)
                    } catch (_: Exception) {
                        null
                    }
                    if (wasm != null && wasm.charm.skills.isNotEmpty()) {
                        return@withContext kotlin.Result.success(finalize(wasm.charm, "wasm ${wasm.note}", wasm.confidence, preferredKind))
                    }
                    error("認識失敗 template=${local?.note}")
                }

                val slots = when {
                    local?.charm?.slots in 0..3 -> local!!.charm.slots
                    slotMl in 0..3 -> slotMl
                    else -> -1
                }

                val conf = (0.55f + finalSkills.size * 0.15f + if (slots in 0..3) 0.1f else 0f)
                    .coerceAtMost(0.98f)
                val note = "tmpl→mlkit ${notes.joinToString(",")} slots=$slots"
                kotlin.Result.success(finalize(OcrCharm(-1, slots, finalSkills), note, conf, preferredKind))
            } catch (e: Exception) {
                kotlin.Result.failure(e)
            }
        }

    private fun finalize(charm: OcrCharm, note: String, conf: Float, preferredKind: Int): Result {
        val autoKind = detectKind(charm)
        val kind = when {
            preferredKind in 0..3 -> preferredKind
            autoKind in 0..3 -> autoKind
            else -> -1
        }
        val kindLabel = listOf("風化", "古び", "光る", "なぞ").getOrNull(kind) ?: "不明"
        val out = if (kind in 0..3) OcrCharm(kind, charm.slots, charm.skills) else charm
        return Result(out, "$note kind=$kindLabel", conf)
    }

    private fun pickOther(list: List<OcrSkill>, one: OcrSkill): OcrSkill =
        list.firstOrNull { it.globalIdx != one.globalIdx } ?: list.last()

    private fun alignLikeRenkin(context: Context, bitmap: Bitmap): Bitmap? {
        if (!OpenCvBridge.ensureInit()) {
            return Bitmap.createScaledBitmap(bitmap, SW, SH, true)
        }
        val ref = try {
            context.assets.open("renkin/reference.png").use {
                android.graphics.BitmapFactory.decodeStream(it)
            }
        } catch (_: Exception) {
            null
        }
        return try {
            if (ref != null) {
                OpenCvBridge.alignWithReference(bitmap, ref, SW, SH)
                    ?: OpenCvBridge.alignToCanonical(bitmap, SW, SH)
                    ?: Bitmap.createScaledBitmap(bitmap, SW, SH, true)
            } else {
                OpenCvBridge.alignToCanonical(bitmap, SW, SH)
                    ?: Bitmap.createScaledBitmap(bitmap, SW, SH, true)
            }
        } finally {
            ref?.recycle()
        }
    }

    private suspend fun ocrRoi(screen: Bitmap, box: IntArray): String {
        val x1 = box[0].coerceIn(0, screen.width - 1)
        val y1 = box[1].coerceIn(0, screen.height - 1)
        val x2 = box[2].coerceIn(x1 + 1, screen.width)
        val y2 = box[3].coerceIn(y1 + 1, screen.height)
        val crop = Bitmap.createBitmap(screen, x1, y1, x2 - x1, y2 - y1)
        val big = Bitmap.createScaledBitmap(
            crop,
            (crop.width * 4).coerceAtLeast(8),
            (crop.height * 4).coerceAtLeast(8),
            true
        )
        return AndroidOcr.ocrBitmap(big).replace("\n", "").trim()
    }

    private fun dictSkill(nameRaw: String, ptsRaw: String, fallback: OcrSkill?): OcrSkill? {
        val cleaned = nameRaw
            .replace(Regex("固有スキル[12]?"), "")
            .replace(Regex("[0-9]"), "")
            .replace(Regex("[+＋\\s　]"), "")
            .trim()
        var pts = Regex("[+＋]?(\\d{1,2})").find(ptsRaw)?.groupValues?.get(1)?.toIntOrNull()
        if (pts == null) pts = Regex("[+＋](\\d{1,2})").find(nameRaw)?.groupValues?.get(1)?.toIntOrNull()
        if (pts == null) pts = fallback?.pts
        if (pts == null || pts !in 1..20) return fallback

        if (cleaned.isEmpty()) {
            return fallback?.copy(pts = pts) ?: return null
        }
        val gIdx = SkillMatcher.findSkillGlobalIdx(cleaned)
        if (gIdx < 0) {
            // テンプレのスキル名を残す
            return fallback?.copy(pts = pts)
        }
        val disp = SKILL_NAMES.getOrElse(gIdx) { cleaned }
        return OcrSkill(gIdx, disp, pts)
    }

    private fun parseSlots(raw: String): Int {
        var u = raw.replace(Regex("[〇○◯ＯOｏo◎●◉]"), "◯")
        val circles = Regex("◯+").findAll(u).map { it.value.length }.maxOrNull() ?: 0
        if (circles in 1..3) return circles
        if (Regex("(?:[-－─—–―_]\\s*){2,}").containsMatchIn(u)) return 0
        if (u.contains("---") || u.contains("なし")) return 0
        return -1
    }


    /**
     * 行単位マージ:
     * - スキル名: ML Kit 辞書が取れたら優先。ただし既知混同はテンプレを優先
     * - ポイント: テンプレの数字照合を優先（ML の 6↔9 誤読対策）
     */
    private fun mergeRows(ml: List<OcrSkill>, tmpl: List<OcrSkill>): List<OcrSkill> {
        val n = maxOf(ml.size, tmpl.size, 2)
        val out = mutableListOf<OcrSkill>()
        for (i in 0 until n) {
            val m = ml.getOrNull(i)
            val tp = tmpl.getOrNull(i)
            if (m == null && tp == null) continue
            var id = m?.globalIdx ?: tp!!.globalIdx
            var name = m?.name ?: tp!!.name
            // 混同: 闘魂(93) vs 窮地(100)
            if (id == 93 && tp?.globalIdx == 100) {
                id = 100
                name = tp.name
            }
            if (id == 50 && tp?.globalIdx == 59) {
                id = 59
                name = tp.name
            }
            // ポイントはテンプレ優先
            val pts = when {
                tp != null && tp.pts in 1..20 -> tp.pts
                m != null && m.pts in 1..20 -> m.pts
                else -> continue
            }
            out += OcrSkill(id, name, pts)
        }
        return out.distinctBy { it.globalIdx }.take(2)
    }

    fun detectKind(charm: OcrCharm): Int {
        if (charm.skills.isEmpty()) return -1
        var bestK = -1
        var bestS = 0
        for (k in 0..3) {
            val sc = scoreKind(charm.skills, k)
            if (sc > bestS) {
                bestS = sc
                bestK = k
            }
        }
        return bestK
    }

    private fun scoreKind(skills: List<OcrSkill>, kind: Int): Int {
        val tbl = KIND_TABLES[kind] ?: return 0
        val s1 = skills[0]
        val i1 = tbl.skill1.indexOf(s1.globalIdx)
        if (i1 < 0) return 0
        var score = 10
        val sp1 = tbl.sp1[i1]
        if (s1.pts in minOf(sp1[0], sp1[1])..maxOf(sp1[0], sp1[1])) score += 5
        if (skills.size > 1) {
            val s2 = skills[1]
            val i2 = tbl.skill2.indexOf(s2.globalIdx)
            if (i2 < 0) return 0
            score += 10
            val sp2 = tbl.sp2[i2]
            if (s2.pts in minOf(sp2[0], sp2[1])..maxOf(sp2[0], sp2[1])) score += 5
        }
        return score
    }
}
