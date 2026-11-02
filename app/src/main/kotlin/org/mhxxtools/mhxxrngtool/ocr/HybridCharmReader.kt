package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mhxxtools.mhxxrngtool.rng.KIND_TABLES
import org.mhxxtools.mhxxrngtool.rng.SKILL_NAMES

/**
 * 錬金リーダー（テンプレ）＋ ML Kit ROI を併用（どちらも必ず実行）。
 *
 * - テンプレ: 位置・スロット・数字の土台
 * - ML Kit: スキル名の確定＋テンプレがポイント0のときの数字補完
 */
object HybridCharmReader {
    private const val TAG = "HybridCharm"

    data class Result(val charm: OcrCharm, val note: String, val confidence: Float)

    private const val SW = 1170
    private const val SH = 612
    private val NAME1 = intArrayOf(599, 140, 731, 162)
    private val PTS1 = intArrayOf(727, 140, 777, 162)
    private val NAME2 = intArrayOf(599, 165, 731, 187)
    private val PTS2 = intArrayOf(727, 165, 777, 187)
    private val SLOT = intArrayOf(530, 185, 640, 210)

    /** テンプレが誤りやすいペア: wrong → right（ML が right なら採用） */
    private val ML_NAME_FIX = mapOf(
        50 to 59,   // 重撃弾強化 → 強撃瓶追加
        93 to 100,  // 闘魂 → 窮地
        32 to 46,   // 特殊攻撃 → 精密射撃
        70 to 120,  // 痛撃 → 採取
        48 to 59,
    )

    suspend fun read(
        context: Context,
        bitmap: Bitmap,
        preferredKind: Int = -1
    ): kotlin.Result<Result> =
        withContext(Dispatchers.Default) {
            try {
                val local = RenkinSwitchReader.read(context, bitmap, preferredKind = -1)
                Log.i(TAG, "renkin: ${local?.note} ${local?.charm?.skills}")

                val aligned = alignLikeRenkin(context, bitmap)
                val mlSkills = mutableListOf<OcrSkill>()
                if (aligned != null) {
                    val rows = listOf(NAME1 to PTS1, NAME2 to PTS2)
                    for (ni in rows.indices) {
                        val nameTxt = ocrRoi(aligned, rows[ni].first)
                        val ptsTxt = ocrRoi(aligned, rows[ni].second)
                        val skill = dictSkill(nameTxt, ptsTxt, local?.charm?.skills?.getOrNull(ni))
                        Log.i(TAG, "ml row$ni '$nameTxt'/'$ptsTxt' -> ${skill?.name}+${skill?.pts}")
                        if (skill != null) mlSkills += skill
                    }
                }
                val slotMl = aligned?.let { parseSlots(ocrRoi(it, SLOT)) } ?: -1

                val wasm = try {
                    RenkinWasmBridge.read(context, bitmap)
                } catch (e: Exception) {
                    Log.w(TAG, "wasm: ${e.message}")
                    null
                }

                val tmpl = local?.charm
                val merged = mergeRenkinAndMl(tmpl, mlSkills, slotMl)
                    ?: wasm?.charm
                    ?: if (mlSkills.isNotEmpty()) OcrCharm(-1, slotMl, mlSkills) else null

                if (merged == null || merged.skills.isEmpty()) {
                    return@withContext kotlin.Result.failure(IllegalStateException("認識失敗"))
                }

                val note = buildString {
                    append("renkin+mlkit")
                    if (local != null) append(" ${local.note}")
                }
                val conf = (local?.confidence ?: 0.55f).coerceIn(0.4f, 0.98f)
                kotlin.Result.success(finalize(merged, note.trim(), conf, preferredKind))
            } catch (e: Exception) {
                kotlin.Result.failure(e)
            }
        }

    /**
     * 行ごとに合成。
     * - 名前: ML が混同ペアの正解、またはテンプレと違う正式名なら ML
     * - ポイント: テンプレが 1..20 ならテンプレ。0 / 無しなら ML
     * - スロット: テンプレ優先、無ければ ML
     */
    private fun mergeRenkinAndMl(
        tmpl: OcrCharm?,
        ml: List<OcrSkill>,
        slotMl: Int
    ): OcrCharm? {
        val tSkills = tmpl?.skills.orEmpty()
        if (tSkills.isEmpty() && ml.isEmpty()) return null

        val n = maxOf(tSkills.size, ml.size)
        val out = mutableListOf<OcrSkill>()
        for (i in 0 until n) {
            val t = tSkills.getOrNull(i)
            val m = ml.getOrNull(i)
            if (t == null && m == null) continue

            var id = t?.globalIdx ?: m!!.globalIdx
            var name = t?.name ?: m!!.name

            if (t != null && m != null && t.globalIdx != m.globalIdx) {
                val fix = ML_NAME_FIX[t.globalIdx]
                if (fix == m.globalIdx) {
                    id = m.globalIdx
                    name = m.name
                    Log.i(TAG, "name fix ${t.name} -> ${m.name}")
                } else if (m.name.length >= t.name.length + 1) {
                    // ML の方が長い正式名
                    id = m.globalIdx
                    name = m.name
                    Log.i(TAG, "name longer ${t.name} -> ${m.name}")
                }
            } else if (t == null && m != null) {
                id = m.globalIdx
                name = m.name
            }

            val pts = when {
                t != null && t.pts in 1..20 -> t.pts
                m != null && m.pts in 1..20 -> m.pts
                t != null && t.pts > 0 -> t.pts
                m != null && m.pts > 0 -> m.pts
                else -> 0
            }
            // +0 のスキルは検索に使えないので、可能なら捨てないが pts は補完済み
            if (pts in 1..20) {
                out += OcrSkill(id, name, pts)
            } else if (id >= 0 && name.isNotEmpty()) {
                // ポイントだけ取れなかった → ML/テンプレ再優先は上で済んでいる
                Log.w(TAG, "pts missing for $name")
            }
        }
        if (out.isEmpty()) return null
        val slots = when {
            tmpl?.slots in 0..3 -> tmpl!!.slots
            slotMl in 0..3 -> slotMl
            else -> -1
        }
        return OcrCharm(tmpl?.kind ?: -1, slots, out.distinctBy { it.globalIdx }.take(2))
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
        if (pts != null && pts !in 1..20) pts = null

        if (cleaned.isEmpty()) {
            return if (fallback != null && (pts ?: fallback.pts) in 1..20)
                fallback.copy(pts = pts ?: fallback.pts) else null
        }
        val gIdx = SkillMatcher.findSkillGlobalIdx(cleaned)
        if (gIdx < 0) {
            return if (fallback != null && (pts ?: fallback.pts) in 1..20)
                fallback.copy(pts = pts ?: fallback.pts) else null
        }
        val disp = SKILL_NAMES.getOrElse(gIdx) { cleaned }
        val p = pts ?: fallback?.pts
        if (p == null || p !in 1..20) {
            // 名前だけ分かった場合は pts を fallback に頼る。それでも無ければ 一旦 0 で返さず null
            return null
        }
        return OcrSkill(gIdx, disp, p)
    }

    private fun parseSlots(raw: String): Int {
        val u = raw.replace(Regex("[〇○◯ＯOｏo◎●◉]"), "◯")
        val circles = Regex("◯+").findAll(u).map { it.value.length }.maxOrNull() ?: 0
        if (circles in 1..3) return circles
        if (Regex("(?:[-－─—–―_]\\s*){2,}").containsMatchIn(u)) return 0
        if (u.contains("---") || u.contains("なし")) return 0
        return -1
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
