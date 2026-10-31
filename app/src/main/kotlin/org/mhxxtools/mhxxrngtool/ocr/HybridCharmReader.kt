package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mhxxtools.mhxxrngtool.rng.KIND_TABLES

/**
 * 公式 WASM（mhxx-renkin-reader）を最優先。
 * ローカル見本照合は WASM 失敗時のフォールバックのみ。
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
                // 1) 公式 WASM を先に実行
                val wasm = try {
                    RenkinWasmBridge.read(context, bitmap)
                } catch (e: Exception) {
                    Log.w(TAG, "wasm error: ${e.message}")
                    null
                }
                val wasmOk = wasm != null && isStrong(wasm.charm)

                // 2) WASM が弱い/失敗のときだけローカル
                val local = if (!wasmOk) {
                    RenkinSwitchReader.read(context, bitmap, preferredKind = -1)
                } else null
                val localOk = local != null && isStrong(local.charm)

                Log.i(TAG, "wasmOk=$wasmOk (${wasm?.note}) localOk=$localOk (${local?.note})")

                val base = when {
                    wasmOk -> wasm!!
                    localOk -> local!!
                    wasm != null && wasm.charm.skills.isNotEmpty() -> wasm
                    local != null && local.charm.skills.isNotEmpty() -> local
                    else -> error("認識失敗\nwasm=${wasm?.note}\nlocal=${local?.note}")
                }

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
                } else base.charm

                Result(charm, "$source kind=$kindLabel ${base.note}", base.confidence)
            }
        }

    /** 笛+0 のような弱い結果を除外 */
    private fun isStrong(c: OcrCharm): Boolean {
        if (c.skills.isEmpty()) return false
        val pts = c.skills.sumOf { it.pts }
        // 全スキルポイント0はほぼ誤認
        if (pts <= 0) return false
        // スキル1は必須
        if (c.skills[0].pts <= 0 && c.skills.size == 1) return false
        return true
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
        return cands.maxBy { it.score }.kind
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
