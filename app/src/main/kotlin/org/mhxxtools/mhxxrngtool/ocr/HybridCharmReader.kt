package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 錬金リーダー (mhxx-renkin-reader) と同じ認識。
 * 見本は assets/renkin/skills の全PNG（公式134 + 天運/古び/光る向け追加スキル）。
 * OCR なし。ローカルテンプレ照合 → 失敗時のみ WASM。
 */
object HybridCharmReader {
    private const val TAG = "HybridCharm"

    data class Result(val charm: OcrCharm, val note: String, val confidence: Float)

    suspend fun read(context: Context, bitmap: Bitmap): kotlin.Result<Result> =
        withContext(Dispatchers.Default) {
            runCatching {
                val local = RenkinSwitchReader.read(context, bitmap)
                if (local != null && local.charm.skills.isNotEmpty()) {
                    Log.i(TAG, "renkin ${local.note}")
                    return@runCatching Result(local.charm, "renkin ${local.note}", local.confidence)
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
