package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mhxxtools.mhxxrngtool.rng.KIND_TABLES

/**
 * 鑑定優先順位:
 *  1) テンプレ照合（位置・スロットが安定）
 *  2) ROI限定 ML Kit + 辞書（スキル名の取り違え補正）
 *  3) WASM
 *
 * 全画面 ML Kit は廃止（別UIの文字を拾って大外れするため）。
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
                val local = RenkinSwitchReader.read(context, bitmap, preferredKind = -1)
                val roiMl = try {
                    MlKitRoiReader.read(context, bitmap)
                } catch (e: Exception) {
                    Log.w(TAG, "roi mlkit: ${e.message}")
                    null
                }
                val wasm = try {
                    RenkinWasmBridge.read(context, bitmap)
                } catch (e: Exception) {
                    Log.w(TAG, "wasm: ${e.message}")
                    null
                }

                fun score(c: OcrCharm?): Int {
                    if (c == null || c.skills.isEmpty()) return -1000
                    val pts = c.skills.sumOf { it.pts }
                    if (pts <= 0) return -400
                    var s = c.skills.size * 40 + pts
                    if (c.slots in 0..3) s += 15
                    // 2スキル揃いを強く優先
                    if (c.skills.size >= 2) s += 40
                    return s
                }

                val ls = score(local?.charm)
                val ms = score(roiMl?.charm)
                val ws = score(wasm?.charm)
                Log.i(TAG, "score local=$ls mlkit-roi=$ms wasm=$ws")

                // マージ: テンプレを土台に、ML Kit が明らかに違う正式名を出したら名前だけ差し替え
                var merged: OcrCharm? = null
                var note = ""
                var conf = 0.5f

                when {
                    ls >= ms && ls >= ws && ls > -400 -> {
                        merged = local!!.charm
                        note = "renkin ${local.note}"
                        conf = local.confidence
                        // skill2 がテンプレで弱い/無いとき ROI ML の skill2 を補完
                        if (merged.skills.size < 2 && roiMl != null && roiMl.charm.skills.size >= 2) {
                            merged = roiMl.charm
                            note = "mlkit-roi-fill ${roiMl.note}"
                            conf = roiMl.confidence
                        } else if (roiMl != null && roiMl.charm.skills.size >= 2 && merged.skills.size >= 2) {
                            // 同位置で辞書名が異なる → 長い正式名（瓶追加等）を優先
                            merged = mergePreferDictNames(merged, roiMl.charm)
                            note += "+dictMerge"
                        }
                    }
                    ms >= ws && ms > -400 -> {
                        merged = roiMl!!.charm
                        note = roiMl.note
                        conf = roiMl.confidence
                    }
                    ws > -400 -> {
                        merged = wasm!!.charm
                        note = "wasm ${wasm.note}"
                        conf = wasm.confidence
                    }
                    local != null && local.charm.skills.isNotEmpty() -> {
                        merged = local.charm
                        note = "renkin-fb ${local.note}"
                        conf = local.confidence
                    }
                    roiMl != null -> {
                        merged = roiMl.charm
                        note = "mlkit-fb ${roiMl.note}"
                        conf = roiMl.confidence
                    }
                    wasm != null && wasm.charm.skills.isNotEmpty() -> {
                        merged = wasm.charm
                        note = "wasm-fb ${wasm.note}"
                        conf = wasm.confidence
                    }
                    else -> error("認識失敗")
                }

                val autoKind = detectKind(merged)
                val kind = when {
                    preferredKind in 0..3 -> preferredKind
                    autoKind in 0..3 -> autoKind
                    else -> -1
                }
                val kindLabel = listOf("風化", "古び", "光る", "なぞ").getOrNull(kind) ?: "不明"
                val charm = if (kind in 0..3) OcrCharm(kind, merged.slots, merged.skills) else merged
                Result(charm, "$note kind=$kindLabel", conf)
            }
        }

    /** テンプレ結果のポイント・スロットを保ちつつ、辞書側の正式スキル名を採用 */
    private fun mergePreferDictNames(base: OcrCharm, dict: OcrCharm): OcrCharm {
        val out = base.skills.toMutableList()
        for (i in out.indices) {
            if (i >= dict.skills.size) break
            val b = out[i]
            val d = dict.skills[i]
            // ポイントが近く、名前が違う → 辞書名を採用（強撃瓶追加など）
            if (b.globalIdx != d.globalIdx && kotlin.math.abs(b.pts - d.pts) <= 2) {
                // 混同ペア: 50 vs 59 は辞書を優先
                if ((b.globalIdx == 50 && d.globalIdx == 59) ||
                    (b.globalIdx == 32 && d.globalIdx == 46) ||
                    d.name.length >= b.name.length
                ) {
                    out[i] = OcrSkill(d.globalIdx, d.name, b.pts)
                }
            }
        }
        val slots = if (base.slots in 0..3) base.slots else dict.slots
        return OcrCharm(base.kind, slots, out)
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
