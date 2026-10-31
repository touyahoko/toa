package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mhxxtools.mhxxrngtool.rng.KIND_TABLES

/**
 * 公式 mhxx-renkin-reader（WASM）を優先し、ローカル見本照合は補助。
 * ローカルが「笛+0」のような弱い誤認でも WASM が正しければそちらを採用する。
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
                // 両方試す（ローカルが弱くても WASM を捨てない）
                val local = RenkinSwitchReader.read(context, bitmap, preferredKind = -1)
                val wasm = try {
                    RenkinWasmBridge.read(context, bitmap)
                } catch (e: Exception) {
                    Log.w(TAG, "wasm failed: ${e.message}")
                    null
                }

                fun quality(c: OcrCharm?): Int {
                    if (c == null || c.skills.isEmpty()) return -1
                    // ポイント0だけの弱い結果は大幅減点
                    val pts = c.skills.sumOf { maxOf(it.pts, 0) }
                    val zeroPenalty = if (pts == 0) -50 else 0
                    return c.skills.size * 30 + pts + (if (c.slots in 0..3) 5 else 0) + zeroPenalty
                }

                val lq = quality(local?.charm)
                val wq = quality(wasm?.charm)
                Log.i(TAG, "quality local=$lq (${local?.note}) wasm=$wq (${wasm?.note})")

                val base = when {
                    wq > lq -> wasm
                    lq > wq -> local
                    wq >= 0 -> wasm ?: local
                    lq >= 0 -> local
                    else -> null
                } ?: error(
                    "テンプレ未認識\nlocal=${local?.note}\nwasm=${wasm?.note}"
                )

                val source = if (base === wasm) "wasm" else "renkin"
                val autoKind = detectKind(base.charm)
                val kind = when {
                    preferredKind in 0..3 -> preferredKind
                    autoKind in 0..3 -> autoKind
                    else -> -1
                }
                val kindNames = listOf("風化", "古び", "光る", "なぞ")
                val kindLabel = kindNames.getOrNull(kind) ?: "不明"

                val charm = if (kind in 0..3) {
                    OcrCharm(kind, base.charm.slots, base.charm.skills)
                } else {
                    base.charm
                }
                Result(charm, "$source kind=$kindLabel ${base.note}", base.confidence)
            }
        }

    fun detectKind(charm: OcrCharm): Int {
        if (charm.skills.isEmpty()) return -1
        data class Cand(val kind: Int, val score: Int)
        val cands = mutableListOf<Cand>()
        for (k in 0..3) {
            val sc = scoreKind(charm.skills, k)
            if (sc > 0) cands += Cand(k, sc)
        }
        if (cands.isEmpty()) return -1
        val best = cands.maxBy { it.score }
        Log.i(TAG, "detectKind ${cands.map { "${it.kind}:${it.score}" }} -> ${best.kind}")
        return best.kind
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
