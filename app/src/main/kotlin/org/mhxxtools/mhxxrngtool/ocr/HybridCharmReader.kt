package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mhxxtools.mhxxrngtool.rng.KIND_TABLES

/**
 * 鑑定 = 錬金リーダー本体と同じ認識法のみ。
 *
 * - RenkinSwitchReader: reference 位置合わせ + トップハット + スキル見本 NCC
 *   （公式 mhxx-renkin-reader と同じパイプライン）
 * - ML Kit / 全画面OCR / 辞書上書きは使わない
 * - WASM はテンプレが空のときだけの保険
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
            try {
                // 錬金リーダー方式のみ
                val local = RenkinSwitchReader.read(context, bitmap, preferredKind = preferredKind)
                Log.i(TAG, "renkin: ${local?.note} skills=${local?.charm?.skills}")

                if (local != null && local.charm.skills.isNotEmpty()) {
                    return@withContext kotlin.Result.success(
                        finalize(local.charm, "renkin ${local.note}", local.confidence, preferredKind)
                    )
                }

                // 保険: WASM（公式エンジン）。テンプレ失敗時のみ
                val wasm = try {
                    RenkinWasmBridge.read(context, bitmap)
                } catch (e: Exception) {
                    Log.w(TAG, "wasm: ${e.message}")
                    null
                }
                if (wasm != null && wasm.charm.skills.isNotEmpty()) {
                    return@withContext kotlin.Result.success(
                        finalize(wasm.charm, "wasm ${wasm.note}", wasm.confidence, preferredKind)
                    )
                }

                kotlin.Result.failure(
                    IllegalStateException("認識失敗 renkin=${local?.note} wasm=${wasm?.note}")
                )
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
