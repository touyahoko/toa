package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mhxxtools.mhxxrngtool.rng.KIND_TABLES

/**
 * 公式 WASM 優先。ローカルは skills.rs + 追加スキル見本で照合。
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
                val wasm = try {
                    RenkinWasmBridge.read(context, bitmap)
                } catch (e: Exception) {
                    Log.w(TAG, "wasm: ${e.message}")
                    null
                }
                val local = RenkinSwitchReader.read(context, bitmap, preferredKind = -1)

                fun score(r: RenkinSwitchReader.Result?): Int {
                    if (r == null) return -1000
                    val c = r.charm
                    if (c.skills.isEmpty()) return -1000
                    val pts = c.skills.sumOf { it.pts }
                    if (pts <= 0) return -500
                    var s = c.skills.size * 30 + pts
                    // skills.rs / 追加リスト内なら加点
                    // 公式スキルへの加点はしない（追加スキルが公式に負ける原因になるため）
                    // ポイントが付いている結果を優先
                    if (c.slots in 0..3) s += 5
                    return s
                }

                val ws = score(wasm)
                val ls = score(local)
                Log.i(TAG, "score wasm=$ws (${wasm?.note}) local=$ls (${local?.note})")

                val base = when {
                    ws >= ls && ws > -500 -> wasm!!
                    ls > -500 -> local!!
                    wasm != null && wasm.charm.skills.isNotEmpty() -> wasm
                    local != null && local.charm.skills.isNotEmpty() -> local
                    else -> error("認識失敗 wasm=${wasm?.note} local=${local?.note}")
                }

                val source = if (base === wasm) "wasm" else "renkin"
                val autoKind = detectKind(base.charm)
                val kind = when {
                    preferredKind in 0..3 -> preferredKind
                    autoKind in 0..3 -> autoKind
                    else -> -1
                }
                val kindLabel = listOf("風化", "古び", "光る", "なぞ").getOrNull(kind) ?: "不明"
                val charm = if (kind in 0..3) OcrCharm(kind, base.charm.slots, base.charm.skills) else base.charm
                Result(charm, "$source kind=$kindLabel ${base.note}", base.confidence)
            }
        }

    fun detectKind(charm: OcrCharm): Int {
        if (charm.skills.isEmpty()) return -1
        var bestK = -1
        var bestS = 0
        for (k in 0..3) {
            val sc = scoreKind(charm.skills, k)
            if (sc > bestS) { bestS = sc; bestK = k }
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
