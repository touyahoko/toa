package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mhxxtools.mhxxrngtool.rng.KIND_TABLES
import org.mhxxtools.mhxxrngtool.rng.SKILL_NAMES

/**
 * 錬金リーダー方式。
 * 1) 全スキルで照合 → 2) スキル組み合わせから種類を自動判定 → 3) 種類で再照合
 * preferredKind が 0..3 のときは手動指定を優先。
 */
object HybridCharmReader {
    private const val TAG = "HybridCharm"

    data class Result(val charm: OcrCharm, val note: String, val confidence: Float)

    /**
     * @param preferredKind 0=風化..3=なぞ / -1=画像から自動判定
     */
    suspend fun read(
        context: Context,
        bitmap: Bitmap,
        preferredKind: Int = -1
    ): kotlin.Result<Result> =
        withContext(Dispatchers.Default) {
            runCatching {
                // --- Pass 1: 全スキル候補で読む ---
                val local1 = RenkinSwitchReader.read(context, bitmap, preferredKind = -1)
                val wasm1 = if (local1 == null || local1.charm.skills.isEmpty()) {
                    RenkinWasmBridge.read(context, bitmap)
                } else null

                var base = when {
                    local1 != null && local1.charm.skills.isNotEmpty() -> local1
                    wasm1 != null && wasm1.charm.skills.isNotEmpty() -> wasm1
                    else -> null
                } ?: error("テンプレ未認識\nlocal=${local1?.note}\nwasm=${wasm1?.note}")

                val source = if (local1 != null && local1.charm.skills.isNotEmpty()) "renkin" else "wasm"

                // --- 種類の決定 ---
                val autoKind = detectKind(base.charm)
                val kind = when {
                    preferredKind in 0..3 -> preferredKind
                    autoKind in 0..3 -> autoKind
                    else -> -1
                }
                val kindNames = listOf("風化", "古び", "光る", "なぞ")
                val kindLabel = kindNames.getOrNull(kind) ?: "不明"

                // --- Pass 2: 種類が分かったらその種類のスキルだけ再照合 ---
                if (kind in 0..3) {
                    val local2 = RenkinSwitchReader.read(context, bitmap, preferredKind = kind)
                    if (local2 != null && local2.charm.skills.isNotEmpty()) {
                        val fixed = local2.charm.let {
                            OcrCharm(kind, it.slots, it.skills)
                        }
                        val note = "$source→kind=$kindLabel ${local2.note} [自動種類]"
                        Log.i(TAG, note)
                        return@runCatching Result(fixed, note, local2.confidence)
                    }
                    // 再照合失敗時は pass1 結果を種類付きで補正
                    val fixed = applyKindAwareFix(base.charm, kind)
                    val note = "$source ${base.note} [自動種類=$kindLabel 補正]"
                    Log.i(TAG, note)
                    return@runCatching Result(
                        OcrCharm(kind, fixed.slots, fixed.skills),
                        note,
                        base.confidence
                    )
                }

                val note = "$source ${base.note} [種類自動判定できず]"
                Log.i(TAG, note)
                Result(base.charm, note, base.confidence)
            }
        }

    /**
     * スキルID・ポイントが KIND_TABLES のどの種類に妥当かで判定。
     * 一意スキル（例: 強撃瓶追加=59 は光る/なぞ）を優先。
     */
    fun detectKind(charm: OcrCharm): Int {
        if (charm.skills.isEmpty()) return -1
        val s1 = charm.skills[0]
        val s2 = charm.skills.getOrNull(1)

        data class Cand(val kind: Int, val score: Int)
        val cands = mutableListOf<Cand>()

        for (k in 0..3) {
            val tbl = KIND_TABLES[k] ?: continue
            var score = 0

            val i1 = tbl.skill1.indexOf(s1.globalIdx)
            if (i1 < 0) continue
            score += 10
            val sp1 = tbl.sp1[i1]
            val lo1 = minOf(sp1[0], sp1[1])
            val hi1 = maxOf(sp1[0], sp1[1])
            if (s1.pts in lo1..hi1) score += 5

            if (s2 != null) {
                val i2 = tbl.skill2.indexOf(s2.globalIdx)
                if (i2 < 0) {
                    // skill2 が skill1 側にもある種類は低スコアで許容
                    if (s2.globalIdx in tbl.skill1) score += 2
                    else continue
                } else {
                    score += 10
                    val sp2 = tbl.sp2[i2]
                    val lo2 = minOf(sp2[0], sp2[1])
                    val hi2 = maxOf(sp2[0], sp2[1])
                    if (s2.pts in lo2..hi2) score += 5
                }
            }

            // 種類特有スキルを加点
            val uniqueBoost = listOf(s1.globalIdx, s2?.globalIdx ?: -1).count { id ->
                id >= 0 && skillUniqueToKind(id, k)
            }
            score += uniqueBoost * 8

            cands += Cand(k, score)
        }

        if (cands.isEmpty()) {
            // 50/59 の取り違えを仮定して再評価
            return detectKindWith50_59Swap(charm)
        }
        val best = cands.maxBy { it.score }
        Log.i(TAG, "detectKind cands=${cands.map { "${it.kind}:${it.score}" }} best=${best.kind}")
        return best.kind
    }

    /** その種類にしか出ないスキルか（他種類の skill1/2 に無い） */
    private fun skillUniqueToKind(skillId: Int, kind: Int): Boolean {
        if (!skillInKind(skillId, kind)) return false
        for (k in 0..3) {
            if (k == kind) continue
            if (skillInKind(skillId, k)) return false
        }
        return true
    }

    private fun skillInKind(skillId: Int, kind: Int): Boolean {
        val tbl = KIND_TABLES[kind] ?: return false
        return skillId in tbl.skill1 || skillId in tbl.skill2
    }

    /**
     * 50↔59 を入れ替えた場合に種類が確定するか試す
     * （pass1 で重撃弾強化と誤読した強撃瓶追加向け）
     */
    private fun detectKindWith50_59Swap(charm: OcrCharm): Int {
        val swapped = charm.skills.map { s ->
            when (s.globalIdx) {
                50 -> OcrSkill(59, SKILL_NAMES.getOrElse(59) { "強撃瓶追加" }, s.pts)
                59 -> OcrSkill(50, SKILL_NAMES.getOrElse(50) { "重撃弾強化" }, s.pts)
                else -> s
            }
        }
        if (swapped == charm.skills) return -1
        val alt = OcrCharm(charm.kind, charm.slots, swapped)
        // 再帰せず直接スコア
        var bestKind = -1
        var bestScore = 0
        for (k in 0..3) {
            val tbl = KIND_TABLES[k] ?: continue
            var score = 0
            val a = swapped[0]
            val i1 = tbl.skill1.indexOf(a.globalIdx)
            if (i1 < 0) continue
            score += 10
            if (swapped.size > 1) {
                val b = swapped[1]
                val i2 = tbl.skill2.indexOf(b.globalIdx)
                if (i2 < 0) continue
                score += 10
            }
            if (score > bestScore) {
                bestScore = score
                bestKind = k
            }
        }
        Log.i(TAG, "detectKind 50/59 swap -> kind=$bestKind score=$bestScore")
        return bestKind
    }

    private fun applyKindAwareFix(charm: OcrCharm, preferredKind: Int): OcrCharm {
        val skills = charm.skills.map { s ->
            if (s.globalIdx == 50 || s.globalIdx == 59) {
                resolve50or59(s, preferredKind)
            } else if (preferredKind in 0..3 && !skillInKind(s.globalIdx, preferredKind)) {
                // 種類に無いスキルは 50/59 以外はそのまま（再照合失敗時）
                s
            } else {
                s
            }
        }
        // 50/59 swap で種類が通るなら入れ替え
        val trial = OcrCharm(preferredKind, charm.slots, skills)
        if (detectKind(trial) == preferredKind) return trial
        val swapped = skills.map { s ->
            when (s.globalIdx) {
                50 -> OcrSkill(59, SKILL_NAMES.getOrElse(59) { "強撃瓶追加" }, s.pts)
                59 -> OcrSkill(50, SKILL_NAMES.getOrElse(50) { "重撃弾強化" }, s.pts)
                else -> s
            }
        }
        return OcrCharm(preferredKind, charm.slots, swapped)
    }

    private fun resolve50or59(s: OcrSkill, kind: Int): OcrSkill {
        val in50 = if (kind in 0..3) skillInKind(50, kind) else true
        val in59 = if (kind in 0..3) skillInKind(59, kind) else true
        val id = when {
            kind in 0..3 && in59 && !in50 -> 59
            kind in 0..3 && in50 && !in59 -> 50
            kind in 0..3 && in50 && in59 -> if (kind >= 2) 59 else 50
            else -> s.globalIdx
        }
        if (id == s.globalIdx) return s
        return OcrSkill(id, SKILL_NAMES.getOrElse(id) { s.name }, s.pts)
    }
}
