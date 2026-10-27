package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 鑑定はテンプレート照合のみ（OCRなし）。
 *
 * ローカル見本（公式134 + 追加スキル）を常に優先。
 * 公式 WASM は錬金134のみのため、強撃瓶追加などを重撃弾強化に誤マッチする。
 * WASM はローカルがスキルを1つも取れないときだけ使う。
 */
object HybridCharmReader {
    private const val TAG = "HybridCharm"

    data class Result(val charm: OcrCharm, val note: String, val confidence: Float)

    suspend fun read(context: Context, bitmap: Bitmap): kotlin.Result<Result> =
        withContext(Dispatchers.Default) {
            runCatching {
                val local = RenkinSwitchReader.read(context, bitmap)
                if (local != null && local.charm.skills.isNotEmpty()) {
                    Log.i(TAG, "local ${local.note}")
                    return@runCatching Result(local.charm, local.note, local.confidence)
                }

                val wasm = RenkinWasmBridge.read(context, bitmap)
                if (wasm != null && wasm.charm.skills.isNotEmpty()) {
                    Log.i(TAG, "wasm ${wasm.note}")
                    return@runCatching Result(wasm.charm, "wasm ${wasm.note}", wasm.confidence)
                }

                error("テンプレ未認識\nlocal=${local?.note}\nwasm=${wasm?.note}")
            }
        }
}
