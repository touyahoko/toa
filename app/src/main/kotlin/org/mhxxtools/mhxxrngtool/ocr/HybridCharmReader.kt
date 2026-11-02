package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mhxxtools.mhxxrngtool.rng.KIND_TABLES
import org.mhxxtools.mhxxrngtool.rng.SKILL_NAMES

/**
 * 鑑定パイプライン:
 *  1) ML Kit 日本語 OCR + SKILL_NAMES 辞書マッチ（優先）
 *  2) テンプレ照合 RenkinSwitchReader
 *  3) WASM 錬金リーダー
 *
 * 似たスキル名（強撃瓶追加 vs 重撃弾強化）は文字列辞書の方が安定しやすい。
 */
object HybridCharmReader {
    private const val TAG = "HybridCharm"

    data class Result(val charm: OcrCharm, val note: String, val confidence: Float)

    suspend fun read(
        context: Context,
        bitmap: Bitmap,
        preferredKind: Int = -1
    ): kotlin.Result<Result> =
        withContext(Dispatchers.Default) {
            runCatching {
                // ── 1) ML Kit + 辞書 ──
                val mlkit = try {
                    val text = AndroidOcr.ocrBitmap(bitmap)
                    Log.i(TAG, "mlkit text len=${text.length}: ${text.take(120).replace("\n","|")}")
                    val charm = parseOcrCharm(text)
                    if (charm.skills.isNotEmpty() && charm.skills.any { it.pts > 0 }) {
                        val conf = (0.55f + charm.skills.size * 0.15f +
                            if (charm.slots in 0..3) 0.1f else 0f).coerceAtMost(0.95f)
                        Result(charm, "mlkit+dict skills=${charm.skills.size}", conf)
                    } else null
                } catch (e: Exception) {
                    Log.w(TAG, "mlkit: ${e.message}")
                    null
                }

                // ── 2) ローカルテンプレ ──
                val local = RenkinSwitchReader.read(context, bitmap, preferredKind = -1)

                // ── 3) WASM ──
                val wasm = try {
                    RenkinWasmBridge.read(context, bitmap)
                } catch (e: Exception) {
                    Log.w(TAG, "wasm: ${e.message}")
                    null
                }

                fun scoreTemplate(r: RenkinSwitchReader.Result?): Int {
                    if (r == null) return -1000
                    val c = r.charm
                    if (c.skills.isEmpty()) return -1000
                    val pts = c.skills.sumOf { it.pts }
                    if (pts <= 0) return -500
                    var s = c.skills.size * 30 + pts
                    if (c.slots in 0..3) s += 5
                    return s
                }

                val ls = scoreTemplate(local)
                val ws = scoreTemplate(wasm)
                Log.i(TAG, "score mlkit=${mlkit != null} local=$ls wasm=$ws")

                // ML Kit でスキル名+ポイントが取れたら最優先
                val base: RenkinSwitchReader.Result? = null // unused type bridge
                val chosen: Result = when {
                    mlkit != null && mlkit.charm.skills.size >= 1 -> {
                        // テンプレ側に同じポイントで別スキルがいる場合は、
                        // 辞書の正式名を優先（強撃瓶追加を守る）
                        mlkit
                    }
                    ls >= ws && ls > -500 -> {
                        val r = local!!
                        Result(r.charm, "renkin ${r.note}", r.confidence)
                    }
                    ws > -500 -> {
                        val r = wasm!!
                        Result(r.charm, "wasm ${r.note}", r.confidence)
                    }
                    local != null && local.charm.skills.isNotEmpty() ->
                        Result(local.charm, "renkin-fallback ${local.note}", local.confidence)
                    wasm != null && wasm.charm.skills.isNotEmpty() ->
                        Result(wasm.charm, "wasm-fallback ${wasm.note}", wasm.confidence)
                    mlkit != null -> mlkit
                    else -> error("認識失敗 mlkit/local/wasm いずれもスキルなし")
                }

                val autoKind = detectKind(chosen.charm)
                val kind = when {
                    preferredKind in 0..3 -> preferredKind
                    autoKind in 0..3 -> autoKind
                    else -> -1
                }
                val kindLabel = listOf("風化", "古び", "光る", "なぞ").getOrNull(kind) ?: "不明"
                val charm = if (kind in 0..3) {
                    OcrCharm(kind, chosen.charm.slots, chosen.charm.skills)
                } else chosen.charm
                Result(charm, "${chosen.note} kind=$kindLabel", chosen.confidence)
            }
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
