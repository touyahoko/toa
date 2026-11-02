package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mhxxtools.mhxxrngtool.rng.KIND_TABLES
import org.mhxxtools.mhxxrngtool.rng.SKILL_NAMES

/**
 * 鑑定パイプライン（錬金リーダー主軸 + ML Kit 補正）
 *
 * 1. RenkinSwitchReader（テンプレ照合＝錬金リーダー方式）を主結果とする
 * 2. 同じ位置合わせ後の ROI に ML Kit をかけ、辞書で正式名を得る（補正用）
 * 3. WASM は最後の手段
 *
 * マージ方針:
 * - スキル名・スロット・ポイントの土台はテンプレ
 * - ML Kit が「明らかに別の正式名」を出したときだけ名前を差し替え
 *   （強撃瓶追加 vs 重撃弾強化、窮地 vs 闘魂 など）
 * - ポイントは常にテンプレ優先（数字テンプレの方が安定）
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

    /** テンプレが誤りやすい → ML 側を採用してよいペア (wrongTmpl to rightMl) */
    private val ML_OVERRIDE = listOf(
        50 to 59,  // 重撃弾強化 → 強撃瓶追加
        93 to 100, // 闘魂 → 窮地
        32 to 46,  // 特殊攻撃誤認時の精密射撃など（必要なら）
    )

    suspend fun read(
        context: Context,
        bitmap: Bitmap,
        preferredKind: Int = -1
    ): kotlin.Result<Result> =
        withContext(Dispatchers.Default) {
            try {
                // ── 1) 錬金リーダー方式（主）──
                val local = RenkinSwitchReader.read(context, bitmap, preferredKind = -1)
                Log.i(TAG, "renkin: ${local?.note} skills=${local?.charm?.skills}")

                // ── 2) ROI ML Kit（補正）──
                val aligned = alignLikeRenkin(context, bitmap)
                val mlSkills = mutableListOf<OcrSkill>()
                if (aligned != null) {
                    val rows = listOf(NAME1 to PTS1, NAME2 to PTS2)
                    for (ni in rows.indices) {
                        val nameTxt = ocrRoi(aligned, rows[ni].first)
                        val ptsTxt = ocrRoi(aligned, rows[ni].second)
                        val skill = dictSkill(nameTxt, ptsTxt, local?.charm?.skills?.getOrNull(ni))
                        Log.i(TAG, "ml-roi row$ni '$nameTxt' '$ptsTxt' -> ${skill?.name}+${skill?.pts}")
                        if (skill != null) mlSkills += skill
                    }
                }
                val slotMl = aligned?.let { parseSlots(ocrRoi(it, SLOT)) } ?: -1

                // ── 3) WASM（保険）──
                val wasm = try {
                    RenkinWasmBridge.read(context, bitmap)
                } catch (e: Exception) {
                    Log.w(TAG, "wasm: ${e.message}")
                    null
                }

                // ── 合成: テンプレ土台 + ML 名前補正 ──
                val baseCharm: OcrCharm? = when {
                    local != null && local.charm.skills.isNotEmpty() -> local.charm
                    wasm != null && wasm.charm.skills.isNotEmpty() -> wasm.charm
                    mlSkills.isNotEmpty() -> OcrCharm(-1, slotMl, mlSkills)
                    else -> null
                }
                if (baseCharm == null) {
                    return@withContext kotlin.Result.failure(IllegalStateException("認識失敗"))
                }

                val fromRenkin = local != null && local.charm.skills.isNotEmpty()
                val merged = if (fromRenkin) {
                    applyMlNameOverride(baseCharm, mlSkills)
                } else {
                    baseCharm
                }

                val slots = when {
                    merged.slots in 0..3 -> merged.slots
                    local?.charm?.slots in 0..3 -> local!!.charm.slots
                    slotMl in 0..3 -> slotMl
                    else -> -1
                }
                val charmSlots = OcrCharm(merged.kind, slots, merged.skills)

                val conf = when {
                    fromRenkin -> (local!!.confidence).coerceAtLeast(0.6f)
                    else -> 0.5f
                }
                val note = buildString {
                    append(if (fromRenkin) "renkin" else if (wasm != null) "wasm" else "mlkit")
                    if (mlSkills.isNotEmpty()) append("+mlfix")
                    append(" ${local?.note ?: ""}")
                }

                kotlin.Result.success(finalize(charmSlots, note.trim(), conf, preferredKind))
            } catch (e: Exception) {
                kotlin.Result.failure(e)
            }
        }

    /** テンプレ結果のポイントを保ったまま、ML が混同ペアの正解側なら名前だけ差し替え */
    private fun applyMlNameOverride(base: OcrCharm, ml: List<OcrSkill>): OcrCharm {
        if (ml.isEmpty()) return base
        val out = base.skills.toMutableList()
        for (i in out.indices) {
            val b = out[i]
            val m = ml.getOrNull(i) ?: continue
            if (b.globalIdx == m.globalIdx) continue
            val shouldOverride = ML_OVERRIDE.any { (wrong, right) ->
                b.globalIdx == wrong && m.globalIdx == right
            } || (
                // ML の方が長く正式な名前で、ポイントが近い
                m.name.length >= b.name.length + 2 &&
                    kotlin.math.abs(b.pts - m.pts) <= 3
            )
            if (shouldOverride) {
                // ポイントはテンプレ維持
                out[i] = OcrSkill(m.globalIdx, m.name, b.pts)
                Log.i(TAG, "ml override ${b.name}->${m.name} pts=${b.pts}")
            }
        }
        return OcrCharm(base.kind, base.slots, out)
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
        if (pts == null || pts !in 1..20) {
            return if (cleaned.isEmpty()) fallback else null
        }
        if (cleaned.isEmpty()) return fallback?.copy(pts = pts)
        val gIdx = SkillMatcher.findSkillGlobalIdx(cleaned)
        if (gIdx < 0) return fallback?.copy(pts = pts)
        val disp = SKILL_NAMES.getOrElse(gIdx) { cleaned }
        return OcrSkill(gIdx, disp, pts)
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
