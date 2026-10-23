package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 鑑定リーダー。
 * 1) renkin/skills の見本テンプレ照合（追加スキルPNG対応）
 * 2) 失敗時のみ公式 WASM にフォールバック
 */
object HybridCharmReader {
    private const val TAG = "HybridCharm"

    data class Result(val charm: OcrCharm, val note: String, val confidence: Float)

    suspend fun read(context: Context, bitmap: Bitmap): kotlin.Result<Result> =
        withContext(Dispatchers.Default) {
            runCatching {
                // ① 見本画像テンプレ（assets/renkin/skills）
                val local = RenkinSwitchReader.read(context, bitmap)
                if (local != null &&
                    local.charm.skills.isNotEmpty() &&
                    local.confidence >= 0.45f
                ) {
                    Log.i(TAG, "local ${local.note} conf=${local.confidence}")
                    return@runCatching Result(local.charm, "tmpl ${local.note}", local.confidence)
                }

                // ② WASM フォールバック
                val wasm = RenkinWasmBridge.read(context, bitmap)
                if (wasm != null && wasm.charm.skills.isNotEmpty()) {
                    Log.i(TAG, "wasm ${wasm.note}")
                    return@runCatching Result(wasm.charm, "wasm ${wasm.note}", wasm.confidence)
                }

                // ③ どちらも弱い場合はスコアが高い方
                val best = listOfNotNull(local, wasm)
                    .filter { it.charm.skills.isNotEmpty() }
                    .maxByOrNull { it.confidence }
                if (best != null) {
                    return@runCatching Result(best.charm, best.note, best.confidence)
                }

                error(
                    "スキル未認識\n" +
                        "local=${local?.note}\n" +
                        "wasm=${wasm?.note}"
                )
            }
        }
}
