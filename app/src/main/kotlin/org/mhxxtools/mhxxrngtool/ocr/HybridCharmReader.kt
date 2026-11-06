package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.mhxxtools.mhxxrngtool.rng.KIND_TABLES
import org.mhxxtools.mhxxrngtool.rng.SKILL_NAMES
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

/**
 * 鑑定ハイブリッド（精度優先）
 *
 * 1) **テンプレート照合**（本家錬金リーダー方式）— Switchスクショで最優先
 * 2) **ML Kit 日本語 OCR** — 画角がずれている写真向け
 * 3) **PaddleOCR** — モデルがある場合のみ最後の補助
 *
 * 標準の Switch 鑑定スクショではテンプレが主体で高精度を狙う。
 * 「98%」は保証値ではなく、きれいなスクショを前提にした目標値。
 */
object HybridCharmReader {
    private const val TAG = "HybridCharm"

    data class Result(val charm: OcrCharm, val note: String, val confidence: Float)

    /** true=テンプレを使う（デフォルトON） */
    @Volatile
    var useTemplate: Boolean = true

    /** true=ML Kit フォールバック */
    @Volatile
    var useMlKit: Boolean = true

    /** true=Paddle 補助 */
    @Volatile
    var usePaddle: Boolean = true

    suspend fun read(
        context: Context,
        bitmap: Bitmap,
        preferredKind: Int = -1
    ): kotlin.Result<Result> =
        withContext(Dispatchers.Default) {
            try {
                val notes = mutableListOf<String>()

                // ── 1) テンプレート照合（本家方式）──────────────────
                if (useTemplate) {
                    val local = try {
                        RenkinSwitchReader.read(context, bitmap, preferredKind = preferredKind)
                    } catch (e: Exception) {
                        Log.w(TAG, "template: ${e.message}")
                        null
                    }
                    if (local != null && local.charm.skills.isNotEmpty()) {
                        notes += "template ${local.note}"
                        // 信頼度が高いテンプレ結果はそのまま採用
                        if (local.confidence >= 0.55f || local.charm.skills.size >= 1) {
                            return@withContext kotlin.Result.success(
                                finalize(
                                    local.charm,
                                    notes.joinToString(" | "),
                                    max(local.confidence, 0.9f),
                                    preferredKind
                                )
                            )
                        }
                    } else {
                        notes += "template=miss"
                    }
                }

                // ── 2) ML Kit 全画面 ───────────────────────────────
                var skills = emptyList<OcrSkill>()
                var slots = -1
                var kindFromText = -1
                if (useMlKit) {
                    val mlText = runMlKitJapanese(bitmap)
                    notes += "mlkit len=${mlText.length}"
                    Log.i(TAG, "mlkit: ${mlText.take(180)}")
                    if (mlText.isNotBlank()) {
                        val parsed = runCatching { parseOcrCharm(mlText) }.getOrNull()
                        skills = mergeSkills(parsed?.skills.orEmpty(), extractSkillsFromText(mlText))
                        slots = when {
                            parsed != null && parsed.slots in 0..3 -> parsed.slots
                            else -> extractSlots(mlText)
                        }
                        kindFromText = parsed?.kind ?: -1
                        if (skills.isNotEmpty()) {
                            val charm = OcrCharm(kindFromText, slots, skills.take(2))
                            return@withContext kotlin.Result.success(
                                finalize(charm, notes.joinToString(" | "), 0.85f, preferredKind)
                            )
                        }
                    }
                }

                // ── 3) Paddle 補助 ────────────────────────────────
                if (usePaddle) {
                    val pr = try {
                        if (PaddleOcrEngine.init(context)) {
                            PaddleCharmReader.read(context, bitmap, preferredKind)
                        } else null
                    } catch (e: Exception) {
                        Log.w(TAG, "paddle: ${e.message}")
                        null
                    }
                    if (pr != null && pr.charm.skills.isNotEmpty()) {
                        notes += "paddle ${pr.note}"
                        return@withContext kotlin.Result.success(
                            finalize(pr.charm, notes.joinToString(" | "), pr.confidence, preferredKind)
                        )
                    }
                    notes += "paddle=miss"
                }

                kotlin.Result.failure(
                    IllegalStateException(
                        "認識失敗 (${notes.joinToString(", ")})。Switchの鑑定スクショか、護石パネルが大きく写った写真を使用してください。"
                    )
                )
            } catch (e: Exception) {
                kotlin.Result.failure(e)
            }
        }

    private suspend fun runMlKitJapanese(bitmap: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            val recognizer = TextRecognition.getClient(
                JapaneseTextRecognizerOptions.Builder().build()
            )
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val sb = StringBuilder()
                    for (block in result.textBlocks) {
                        for (line in block.lines) sb.appendLine(line.text)
                    }
                    if (sb.isEmpty()) sb.append(result.text)
                    recognizer.close()
                    cont.resume(sb.toString())
                }
                .addOnFailureListener {
                    recognizer.close()
                    cont.resume("")
                }
        }

    private fun mergeSkills(a: List<OcrSkill>, b: List<OcrSkill>): List<OcrSkill> {
        val map = LinkedHashMap<Int, OcrSkill>()
        for (s in a + b) {
            val prev = map[s.globalIdx]
            if (prev == null || kotlin.math.abs(prev.pts) < kotlin.math.abs(s.pts)) {
                map[s.globalIdx] = s
            }
        }
        return map.values.toList()
    }

    private fun extractSkillsFromText(text: String): List<OcrSkill> {
        if (text.isBlank()) return emptyList()
        val lines = text.split(Regex("[\\n\\r]+")).map { it.trim() }.filter { it.isNotEmpty() }
        val found = ArrayList<OcrSkill>()
        val ptRegex = Regex("([+＋\\-−])\\s*(\\d{1,2})")
        for (line in lines) {
            if (line.contains("スロット") && !containsSkillName(line)) continue
            val pts = ptRegex.findAll(line).lastOrNull()?.let { m ->
                val sign = if (m.groupValues[1] == "-" || m.groupValues[1] == "−") -1 else 1
                (sign * (m.groupValues[2].toIntOrNull() ?: 0)).coerceIn(-20, 20)
            } ?: 0
            val namePart = line
                .replace(Regex("固有スキル\\s*[12１２]?"), "")
                .replace(Regex("国有スキル\\s*[12１２]?"), "")
                .replace(ptRegex, "")
                .replace(Regex("[\\s　+＋\\-−:：\\d]"), "")
                .trim()
            val idx = matchSkill(namePart) ?: continue
            if (found.none { it.globalIdx == idx }) {
                found += OcrSkill(idx, SKILL_NAMES[idx], pts)
            }
            if (found.size >= 2) break
        }
        if (found.size < 2) {
            val compact = text.replace(Regex("\\s"), "")
            val candidates = SKILL_NAMES.mapIndexed { i, n -> i to n.replace("　", "") }
                .filter { it.second.length >= 2 }
                .sortedByDescending { it.second.length }
            for ((i, n) in candidates) {
                if (compact.contains(n) && found.none { it.globalIdx == i }) {
                    found += OcrSkill(i, SKILL_NAMES[i], 0)
                }
                if (found.size >= 2) break
            }
        }
        return found.take(2)
    }

    private fun containsSkillName(line: String): Boolean {
        val c = line.replace("　", "")
        return SKILL_NAMES.any { it.length >= 2 && c.contains(it.replace("　", "")) }
    }

    private fun extractSlots(text: String): Int {
        val unified = text.replace(Regex("[〇○◯ＯOｏo◎●◉]"), "◯")
        val area = Regex("スロ(?:ット)?[^\\n]{0,50}").find(unified)?.value ?: unified
        val runs = Regex("◯{1,3}").findAll(area).map { it.value.length }.toList()
        if (runs.isNotEmpty()) return runs.maxOrNull()!!.coerceIn(1, 3)
        if (Regex("(?:[-－─—–―_]\\s*){2,}").containsMatchIn(area)) return 0
        Regex("スロット\\s*[:：]?\\s*([0-3])").find(area)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return -1
    }

    private fun matchSkill(raw: String): Int? {
        val s = raw.replace("　", "").trim()
        if (s.length < 2) return null
        val exact = SKILL_NAMES.indexOfFirst { it.replace("　", "") == s }
        if (exact >= 0) return exact
        val hit = SKILL_NAMES.mapIndexed { i, n -> i to n.replace("　", "") }
            .filter { (_, n) -> n.length >= 2 && (s.contains(n) || n.contains(s)) }
            .maxByOrNull { it.second.length }
        if (hit != null) return hit.first
        var bestI = -1
        var bestD = 99
        for (i in SKILL_NAMES.indices) {
            val n = SKILL_NAMES[i].replace("　", "")
            if (n.length < 2) continue
            val d = levenshtein(s, n)
            if (d < bestD && d <= max(1, n.length / 3)) {
                bestD = d
                bestI = i
            }
        }
        return if (bestI >= 0) bestI else null
    }

    private fun levenshtein(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        if (m == 0) return n
        if (n == 0) return m
        val dp = IntArray(n + 1) { it }
        for (i in 1..m) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..n) {
                val tmp = dp[j]
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[j] = min(min(dp[j] + 1, dp[j - 1] + 1), prev + cost)
                prev = tmp
            }
        }
        return dp[n]
    }

    private fun finalize(charm: OcrCharm, note: String, conf: Float, preferredKind: Int): Result {
        val autoKind = detectKind(charm)
        val kind = when {
            preferredKind in 0..3 -> preferredKind
            autoKind in 0..3 -> autoKind
            charm.kind in 0..3 -> charm.kind
            else -> -1
        }
        val kindLabel = listOf("風化", "古び", "光る", "なぞ").getOrNull(kind) ?: "不明"
        val out = if (kind in 0..3) OcrCharm(kind, charm.slots, charm.skills) else charm
        return Result(out, "$note kind=$kindLabel", conf)
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
        if (i1 in tbl.sp1.indices) {
            val sp1 = tbl.sp1[i1]
            if (s1.pts in minOf(sp1[0], sp1[1])..maxOf(sp1[0], sp1[1])) score += 5
        }
        if (skills.size > 1) {
            val s2 = skills[1]
            val i2 = tbl.skill2.indexOf(s2.globalIdx)
            if (i2 < 0) return score
            score += 10
            if (i2 in tbl.sp2.indices) {
                val sp2 = tbl.sp2[i2]
                if (s2.pts in minOf(sp2[0], sp2[1])..maxOf(sp2[0], sp2[1])) score += 5
            }
        }
        return score
    }
}
