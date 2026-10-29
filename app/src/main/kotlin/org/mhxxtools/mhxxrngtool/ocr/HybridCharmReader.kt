package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mhxxtools.mhxxrngtool.rng.SKILL_NAMES

/**
 * 錬金リーダー方式。ローカルテンプレ → 失敗時 WASM。
 * 最終結果に 50(重撃弾強化) ↔ 59(強撃瓶追加) の取り違え補正を必ず適用。
 */
object HybridCharmReader {
    private const val TAG = "HybridCharm"

    data class Result(val charm: OcrCharm, val note: String, val confidence: Float)

    suspend fun read(context: Context, bitmap: Bitmap): kotlin.Result<Result> =
        withContext(Dispatchers.Default) {
            runCatching {
                val local = RenkinSwitchReader.read(context, bitmap)
                if (local != null && local.charm.skills.isNotEmpty()) {
                    val fixed = applyConfusionFix(local.charm)
                    val note = "renkin ${local.note}" + if (fixed != local.charm) " [50→59]" else ""
                    Log.i(TAG, note)
                    return@runCatching Result(fixed, note, local.confidence)
                }
                val wasm = RenkinWasmBridge.read(context, bitmap)
                if (wasm != null && wasm.charm.skills.isNotEmpty()) {
                    val fixed = applyConfusionFix(wasm.charm)
                    val note = "wasm ${wasm.note}" + if (fixed != wasm.charm) " [50→59]" else ""
                    Log.i(TAG, note)
                    return@runCatching Result(fixed, note, wasm.confidence)
                }
                error("テンプレ未認識\nlocal=${local?.note}\nwasm=${wasm?.note}")
            }
        }

    /**
     * 強撃瓶追加(59) と 重撃弾強化(50) は字形が酷似するため、
     * 50 と出たスキルは 59 に置き換える。
     * （天運・光るで出る瓶追加スキルを優先。真の重撃弾強化は手動修正）
     */
    private fun applyConfusionFix(charm: OcrCharm): OcrCharm {
        val name59 = SKILL_NAMES.getOrElse(59) { "強撃瓶追加" }
        val skills = charm.skills.map { s ->
            if (s.globalIdx == 50) {
                Log.i(TAG, "confusion fix: 重撃弾強化(50) → 強撃瓶追加(59) pts=${s.pts}")
                OcrSkill(59, name59, s.pts)
            } else {
                s
            }
        }
        return if (skills == charm.skills) charm else OcrCharm(charm.kind, charm.slots, skills)
    }
}
