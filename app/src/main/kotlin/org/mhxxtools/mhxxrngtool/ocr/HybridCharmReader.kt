package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mhxxtools.mhxxrngtool.rng.KIND_TABLES
import org.mhxxtools.mhxxrngtool.rng.SKILL_NAMES

/**
 * 1) 全スキル照合 → 2) 種類自動判定（類似スキル入替も試行） → 3) 種類で再照合
 */
object HybridCharmReader {
    private const val TAG = "HybridCharm"

    data class Result(val charm: OcrCharm, val note: String, val confidence: Float)

    /** 字形が似て取り違えやすいペア (短↔長 / 公式↔追加) */
    private val CONFUSION_PAIRS: List<Pair<Int, Int>> = listOf(
        50 to 59,   // 重撃弾強化 ↔ 強撃瓶追加
        32 to 46,   // 特殊攻撃 ↔ 精密射撃
        70 to 130,  // 痛撃 ↔ 採取
        47 to 51,   // 通常弾強化 ↔ 通常弾追加
        48 to 52,   // 貫通弾強化 ↔ 貫通弾追加
        49 to 53,   // 散弾強化 ↔ 散弾追加
    )

    suspend fun read(
        context: Context,
        bitmap: Bitmap,
        preferredKind: Int = -1
    ): kotlin.Result<Result> =
        withContext(Dispatchers.Default) {
            runCatching {
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

                if (kind in 0..3) {
                    val local2 = RenkinSwitchReader.read(context, bitmap, preferredKind = kind)
                    if (local2 != null && local2.charm.skills.isNotEmpty()) {
                        val fixedSkills = resolveConfusionsForKind(local2.charm.skills, kind)
                        val fixed = OcrCharm(kind, local2.charm.slots, fixedSkills)
                        val note = "$source→kind=$kindLabel ${local2.note} [自動種類]"
                        Log.i(TAG, note)
                        return@runCatching Result(fixed, note, local2.confidence)
                    }
                    val fixedSkills = resolveConfusionsForKind(base.charm.skills, kind)
                    val note = "$source ${base.note} [自動種類=$kindLabel 補正]"
                    return@runCatching Result(
                        OcrCharm(kind, base.charm.slots, fixedSkills),
                        note,
                        base.confidence
                    )
                }

                Result(base.charm, "$source ${base.note} [種類自動判定できず]", base.confidence)
            }
        }

    fun detectKind(charm: OcrCharm): Int {
        if (charm.skills.isEmpty()) return -1

        // 本体 + 類似スキル入替バリエーションを評価
        val variants = mutableListOf(charm.skills)
        for (i in charm.skills.indices) {
            val id = charm.skills[i].globalIdx
            for ((a, b) in CONFUSION_PAIRS) {
                val alt = when (id) {
                    a -> b
                    b -> a
                    else -> continue
                }
                val v = charm.skills.toMutableList()
                v[i] = OcrSkill(alt, SKILL_NAMES.getOrElse(alt) { "s$alt" }, charm.skills[i].pts)
                variants += v
            }
        }

        data class Cand(val kind: Int, val score: Int, val skills: List<OcrSkill>)
        val cands = mutableListOf<Cand>()

        for (skills in variants) {
            for (k in 0..3) {
                val sc = scoreKind(skills, k)
                if (sc > 0) cands += Cand(k, sc, skills)
            }
        }
        if (cands.isEmpty()) return -1
        val best = cands.maxBy { it.score }
        Log.i(TAG, "detectKind best=${best.kind} score=${best.score} skills=${best.skills.map { it.globalIdx }}")
        return best.kind
    }

    private fun scoreKind(skills: List<OcrSkill>, kind: Int): Int {
        val tbl = KIND_TABLES[kind] ?: return 0
        if (skills.isEmpty()) return 0
        val s1 = skills[0]
        val i1 = tbl.skill1.indexOf(s1.globalIdx)
        if (i1 < 0) return 0
        var score = 10
        val sp1 = tbl.sp1[i1]
        if (s1.pts in minOf(sp1[0], sp1[1])..maxOf(sp1[0], sp1[1])) score += 5
        if (skillUniqueToKind(s1.globalIdx, kind)) score += 8

        if (skills.size > 1) {
            val s2 = skills[1]
            val i2 = tbl.skill2.indexOf(s2.globalIdx)
            if (i2 < 0) {
                if (s2.globalIdx in tbl.skill1) score += 2 else return 0
            } else {
                score += 10
                val sp2 = tbl.sp2[i2]
                if (s2.pts in minOf(sp2[0], sp2[1])..maxOf(sp2[0], sp2[1])) score += 5
                if (skillUniqueToKind(s2.globalIdx, kind)) score += 8
            }
        }
        return score
    }

    private fun skillUniqueToKind(skillId: Int, kind: Int): Boolean {
        if (!skillInKind(skillId, kind)) return false
        for (k in 0..3) {
            if (k != kind && skillInKind(skillId, k)) return false
        }
        return true
    }

    private fun skillInKind(skillId: Int, kind: Int): Boolean {
        val tbl = KIND_TABLES[kind] ?: return false
        return skillId in tbl.skill1 || skillId in tbl.skill2
    }

    /**
     * 種類テーブルに合うよう類似スキルを入れ替え。
     * 両方とも種類に出る場合は「出現種類が少ない＝より特有」な方を優先
     * （例: 光るで特殊攻撃(32)と精密射撃(46)が両方有効 → 46を優先）
     */
    private fun resolveConfusionsForKind(skills: List<OcrSkill>, kind: Int): List<OcrSkill> {
        return skills.map { s ->
            var bestId = s.globalIdx
            var bestSpec = kindSpecificity(s.globalIdx) // 小さいほど特有

            for ((a, b) in CONFUSION_PAIRS) {
                val alt = when (s.globalIdx) {
                    a -> b
                    b -> a
                    else -> continue
                }
                if (!skillInKind(alt, kind)) continue
                val spec = kindSpecificity(alt)
                // 現在のIDが種類外なら必ず入替
                if (!skillInKind(s.globalIdx, kind)) {
                    bestId = alt
                    bestSpec = spec
                    continue
                }
                // 両方有効: より特有（出現種類が少ない）方を採用
                if (spec < bestSpec) {
                    bestId = alt
                    bestSpec = spec
                }
                // 同特有度なら長いスキル名（文字数）を優先
                else if (spec == bestSpec) {
                    val nameAlt = SKILL_NAMES.getOrElse(alt) { "" }
                    val nameCur = SKILL_NAMES.getOrElse(bestId) { "" }
                    if (nameAlt.length > nameCur.length) {
                        bestId = alt
                    }
                }
            }

            if (bestId == s.globalIdx) s
            else {
                Log.i(TAG, "kind=$kind confusion ${s.globalIdx}→$bestId")
                OcrSkill(bestId, SKILL_NAMES.getOrElse(bestId) { s.name }, s.pts)
            }
        }
    }

    /** そのスキルが何種類に出現するか（1=その種類のみ特有, 4=全部） */
    private fun kindSpecificity(skillId: Int): Int {
        var n = 0
        for (k in 0..3) if (skillInKind(skillId, k)) n++
        return if (n == 0) 99 else n
    }
}

