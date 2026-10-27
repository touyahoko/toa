package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 鑑定はテンプレート照合のみ（OCRなし）。
 * 1) 公式 WASM（サイトと同じ見本エンジン）
 * 2) 失敗時は Kotlin テンプレ（assets/renkin、追加スキル見本含む）
 */
object HybridCharmReader {
    private const val TAG = "HybridCharm"

    data class Result(val charm: OcrCharm, val note: String, val confidence: Float)

    suspend fun read(context: Context, bitmap: Bitmap): kotlin.Result<Result> =
        withContext(Dispatchers.Default) {
            runCatching {
                // ① 公式 WASM（テンプレ照合）
                val wasm = RenkinWasmBridge.read(context, bitmap)
                if (wasm != null &&
                    wasm.charm.skills.isNotEmpty() &&
                    wasm.confidence >= 0.5f
                ) {
                    Log.i(TAG, "wasm ${wasm.note}")
                    return@runCatching Result(wasm.charm, "wasm ${wasm.note}", wasm.confidence)
                }

                // ② ローカル見本テンプレ（OCRなし）
                val local = RenkinSwitchReader.read(context, bitmap)
                if (local != null && local.charm.skills.isNotEmpty()) {
                    Log.i(TAG, "tmpl ${local.note}")
                    return@runCatching Result(local.charm, local.note, local.confidence)
                }

                // ③ 弱い結果でもスキルがあれば返す
                val best = listOfNotNull(wasm, local)
                    .filter { it.charm.skills.isNotEmpty() }
                    .maxByOrNull { it.confidence }
                if (best != null) {
                    return@runCatching Result(best.charm, best.note, best.confidence)
                }

                error("テンプレ未認識\nwasm=${wasm?.note}\nlocal=${local?.note}")
            }
        }
}
