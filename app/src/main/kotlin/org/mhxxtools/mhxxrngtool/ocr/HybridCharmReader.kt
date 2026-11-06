package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mhxxtools.mhxxrngtool.rng.KIND_TABLES

/**
 * 鑑定 = PaddleOCR 全画面スキャン → OcrCharm →（ViewModel側で RNG 検索）
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
                val paddle = PaddleCharmReader.read(context, bitmap, preferredKind)
                Log.i(TAG, "paddle: ${paddle?.note} skills=${paddle?.charm?.skills}")
                if (paddle != null && paddle.charm.skills.isNotEmpty()) {
                    return@withContext kotlin.Result.success(
                        finalize(paddle.charm, "paddle ${paddle.note}", paddle.confidence, preferredKind)
                    )
                }
                kotlin.Result.failure(
                    IllegalStateException(
                        "全画面OCRでスキルを読めませんでした。護石パネルが写るように撮影するか、Switchスクショを使用してください。 note=${paddle?.note}"
                    )
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
            else -> charm.kind
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
