package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import org.mhxxtools.mhxxrngtool.rng.SKILL_NAMES
import kotlin.math.max
import kotlin.math.min

/**
 * オンデバイス学習モデル（軽量）。
 *
 * - OCR候補とテンプレ候補を特徴量でスコアリングして最終決定
 * - 完全一致フレームが見つかった組み合わせを SharedPreferences に蓄積し、
 *   次回以降のスコアにボーナスを加える（オンライン学習）
 *
 * 真の深層学習（TFLite）ではなく、辞書＋統計の学習層。
 * ハイブリッド読取の最終ジャッジとして連携する。
 */
object CharmLearningModel {

    private const val PREF = "charm_learning_v1"
    private const val KEY_SKILL = "skill_w_"
    private const val KEY_SLOT = "slot_w_"
    private const val KEY_PAIR = "pair_w_"

    data class ScoredCharm(
        val charm: OcrCharm,
        val confidence: Float,
        val detail: String
    )

    /** OCR×テンプレを学習スコアで統合 */
    fun decide(
        context: Context,
        ocr: OcrCharm?,
        tmpl: OcrCharm?,
        ocrRaw: String = ""
    ): ScoredCharm {
        if (ocr == null && tmpl == null) {
            return ScoredCharm(OcrCharm(-1, -1, emptyList()), 0f, "empty")
        }
        if (ocr == null) {
            return ScoredCharm(tmpl!!, 0.55f, "tmpl-only")
        }
        if (tmpl == null) {
            return ScoredCharm(ocr, 0.60f, "ocr-only")
        }

        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

        fun skillWeight(idx: Int): Float =
            1f + prefs.getFloat(KEY_SKILL + idx, 0f).coerceIn(0f, 2f)

        fun pairWeight(a: Int, b: Int, pts1: Int, pts2: Int, slot: Int): Float {
            val key = "$KEY_PAIR${a}_${pts1}_${b}_${pts2}_$slot"
            return 1f + prefs.getFloat(key, 0f).coerceIn(0f, 3f)
        }

        val skills = mutableListOf<OcrSkill>()
        val details = mutableListOf<String>()

        fun pickSkill(pos: Int, o: OcrSkill?, t: OcrSkill?): OcrSkill? {
            if (o == null && t == null) return null
            if (o == null) {
                details += "s$pos=tmpl(${t!!.name})"
                return t
            }
            if (t == null) {
                details += "s$pos=ocr(${o.name})"
                return o
            }
            if (o.globalIdx == t.globalIdx) {
                val pts = when {
                    t.pts > 0 -> t.pts
                    o.pts > 0 -> o.pts
                    else -> 0
                }
                details += "s$pos=agree(${o.name}+$pts)"
                return OcrSkill(o.globalIdx, o.name, pts)
            }

            // 不一致: 学習重み + 文字列類似 + ポイント有無で判定
            val oScore =
                0.55f * skillWeight(o.globalIdx) +
                    0.25f * nameSimilarity(ocrRaw, o.name) +
                    if (o.pts > 0) 0.15f else 0f
            val tScore =
                0.50f * skillWeight(t.globalIdx) +
                    0.20f * nameSimilarity(ocrRaw, t.name) +
                    if (t.pts > 0) 0.20f else 0f

            return if (oScore >= tScore) {
                val pts = if (o.pts > 0) o.pts else t.pts
                details += "s$pos=ocr(${o.name} o=${"%.2f".format(oScore)}>t=${"%.2f".format(tScore)})"
                OcrSkill(o.globalIdx, o.name, pts)
            } else {
                val pts = if (t.pts > 0) t.pts else o.pts
                details += "s$pos=tmpl(${t.name} t=${"%.2f".format(tScore)}>o=${"%.2f".format(oScore)})"
                OcrSkill(t.globalIdx, t.name, pts)
            }
        }

        pickSkill(1, ocr.skills.getOrNull(0), tmpl.skills.getOrNull(0))?.let { skills += it }
        pickSkill(2, ocr.skills.getOrNull(1), tmpl.skills.getOrNull(1))?.let { skills += it }

        if (skills.size < 2) {
            tmpl.skills.getOrNull(1)?.let { t2 ->
                if (skills.none { it.globalIdx == t2.globalIdx }) skills += t2
            }
        }
        if (skills.isEmpty()) skills += tmpl.skills.ifEmpty { ocr.skills }

        val slot = decideSlot(ocr.slots, tmpl.slots, prefs)
        details += "slot=$slot"

        val kind = when {
            ocr.kind >= 0 -> ocr.kind
            tmpl.kind >= 0 -> tmpl.kind
            else -> -1
        }

        val s1 = skills.getOrNull(0)
        val s2 = skills.getOrNull(1)
        var conf = 0.5f
        if (s1 != null && ocr.skills.any { it.globalIdx == s1.globalIdx }) conf += 0.15f
        if (s1 != null && tmpl.skills.any { it.globalIdx == s1.globalIdx }) conf += 0.15f
        if (s2 != null && ocr.skills.any { it.globalIdx == s2.globalIdx }) conf += 0.08f
        if (s2 != null && tmpl.skills.any { it.globalIdx == s2.globalIdx }) conf += 0.08f
        if (slot >= 0 && ocr.slots == tmpl.slots) conf += 0.1f
        if (s1 != null && s2 != null) {
            conf *= pairWeight(s1.globalIdx, s2.globalIdx, s1.pts, s2.pts, slot)
        }
        conf = conf.coerceIn(0f, 1f)

        return ScoredCharm(
            OcrCharm(kind, slot, skills),
            conf,
            details.joinToString(" ")
        )
    }

    /**
     * 完全一致フレームが見つかったときに呼ぶ。
     * 次回の decide() で同じスキル/組み合わせにボーナスが付く。
     */
    fun recordSuccess(context: Context, charm: OcrCharm) {
        if (charm.skills.isEmpty()) return
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val ed = prefs.edit()
        for (s in charm.skills) {
            val k = KEY_SKILL + s.globalIdx
            val w = (prefs.getFloat(k, 0f) + 0.15f).coerceAtMost(2f)
            ed.putFloat(k, w)
        }
        if (charm.slots in 0..3) {
            val k = KEY_SLOT + charm.slots
            ed.putFloat(k, (prefs.getFloat(k, 0f) + 0.1f).coerceAtMost(1.5f))
        }
        val s1 = charm.skills.getOrNull(0)
        val s2 = charm.skills.getOrNull(1)
        if (s1 != null) {
            val bIdx = s2?.globalIdx ?: -1
            val bPts = s2?.pts ?: 0
            val key = "$KEY_PAIR${s1.globalIdx}_${s1.pts}_${bIdx}_${bPts}_${charm.slots}"
            ed.putFloat(key, (prefs.getFloat(key, 0f) + 0.2f).coerceAtMost(3f))
        }
        ed.apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun decideSlot(o: Int, t: Int, prefs: android.content.SharedPreferences): Int {
        if (o in 0..3 && t in 0..3 && o == t) return o
        if (o !in 0..3 && t in 0..3) return t
        if (t !in 0..3 && o in 0..3) return o
        if (o in 0..3 && t in 0..3) {
            val ow = prefs.getFloat(KEY_SLOT + o, 0f)
            val tw = prefs.getFloat(KEY_SLOT + t, 0f)
            // スロット0は誤検出しやすいので非0をやや優先
            val oAdj = ow + if (o == 0) -0.05f else 0.05f
            val tAdj = tw + if (t == 0) -0.05f else 0.05f
            return if (tAdj >= oAdj) t else o
        }
        return -1
    }

    /** OCR全文にスキル名がどの程度含まれるか (0〜1) */
    private fun nameSimilarity(raw: String, name: String): Float {
        if (raw.isBlank() || name.isBlank()) return 0f
        val n = raw.replace(" ", "").replace("　", "")
        if (n.contains(name)) return 1f
        // 部分一致
        if (name.length >= 2 && n.contains(name.take(2))) return 0.6f
        // 編集距離ベース
        val best = SKILL_NAMES.minOfOrNull { levenshteinRatio(name, it) } ?: 0f
        return best
    }

    private fun levenshteinRatio(a: String, b: String): Float {
        if (a == b) return 1f
        val m = a.length
        val n = b.length
        if (m == 0 || n == 0) return 0f
        val dp = IntArray(n + 1) { it }
        for (i in 1..m) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..n) {
                val tmp = dp[j]
                dp[j] = if (a[i - 1] == b[j - 1]) prev
                else 1 + min(prev, min(dp[j], dp[j - 1]))
                prev = tmp
            }
        }
        val dist = dp[n]
        return 1f - dist.toFloat() / max(m, n)
    }
}
