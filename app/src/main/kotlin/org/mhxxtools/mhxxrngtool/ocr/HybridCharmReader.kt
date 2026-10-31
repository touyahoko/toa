package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mhxxtools.mhxxrngtool.rng.KIND_TABLES
import org.mhxxtools.mhxxrngtool.rng.SKILL_NAMES

/**
 * 錬金リーダー方式の自然照合。
 * 強制ID変換はしない。テンプレNCC + 余り減点 + 幅適合で選ぶ。
 * 種類はスキル組み合わせが KIND_TABLES に通るかで自動判定。
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
                // Pass1: 全スキル自然照合
                val local1 = RenkinSwitchReader.read(context, bitmap, preferredKind = -1)
                val wasm1 = if (local1 == null || local1.charm.skills.isEmpty()) {
                    RenkinWasmBridge.read(context, bitmap)
                } else null

                val base = when {
                    local1 != null && local1.charm.skills.isNotEmpty() -> local1
                    wasm1 != null && wasm1.charm.skills.isNotEmpty() -> wasm1
                    else -> null
                } ?: error("テンプレ未認識\nlocal=${local1?.note}\nwasm=${wasm1?.note}")

                val source = if (local1 != null && local1.charm.skills.isNotEmpty()) "renkin" else "wasm"
                val autoKind = detectKind(base.charm)
                val kind = when {
                    preferredKind in 0..3 -> preferredKind
                    autoKind in 0..3 -> autoKind
                    else -> -1
                }
                val kindNames = listOf("風化", "古び", "光る", "なぞ")
                val kindLabel = kindNames.getOrNull(kind) ?: "不明"

                // Pass2: 種類ヒントで再照合。スキル数やポイントが悪化する結果は捨てる
                if (kind in 0..3) {
                    val local2 = RenkinSwitchReader.read(context, bitmap, preferredKind = kind)
                    if (local2 != null && local2.charm.skills.isNotEmpty()) {
                        fun quality(c: OcrCharm): Int =
                            c.skills.size * 20 + c.skills.sumOf { maxOf(it.pts, 0) } + (if (c.slots in 0..3) 3 else 0)
                        if (quality(local2.charm) >= quality(base.charm)) {
                            val fixed = OcrCharm(kind, local2.charm.slots, local2.charm.skills)
                            val note = "$source→kind=$kindLabel ${local2.note}"
                            Log.i(TAG, note)
                            return@runCatching Result(fixed, note, local2.confidence)
                        }
                        Log.i(TAG, "pass2 worse, keep pass1")
                    }
                    return@runCatching Result(
                        OcrCharm(kind, base.charm.slots, base.charm.skills),
                        "$source ${base.note} kind=$kindLabel",
                        base.confidence
                    )
                }

                Result(base.charm, "$source ${base.note}", base.confidence)
            }
        }

    /** スキルが KIND_TABLES のどの種類に妥当か（ポイント範囲込み） */
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
