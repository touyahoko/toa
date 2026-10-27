package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 錬金リーダー (mhxx-renkin-reader) と同じテンプレート照合。
 *
 * - 全スキル（公式134 + assets/renkin/skills の追加見本）を同一の
 *   tophat + NCC + 余りインク減点で照合する。
 * - OCR は使わない。
 * - WASM はローカルが完全に失敗したときだけの保険（公式134範囲）。
 */
object HybridCharmReader {
    private const val TAG = "HybridCharm"

    data class Result(val charm: OcrCharm, val note: String, val confidence: Float)

    suspend fun read(context: Context, bitmap: Bitmap): kotlin.Result<Result> =
        withContext(Dispatchers.Default) {
            runCatching {
                // 1) 錬金リーダー方式（公式 + 追加スキルを同じ照合）
                val local = RenkinSwitchReader.read(context, bitmap)
                if (local != null && local.charm.skills.isNotEmpty()) {
                    Log.i(TAG, "renkin-tmpl ${local.note}")
                    return@runCatching Result(
                        local.charm,
                        "renkin ${local.note}",
                        local.confidence
                    )
                }

                // 2) 保険: 公式 WASM（追加スキルは出ない）
                val wasm = RenkinWasmBridge.read(context, bitmap)
                if (wasm != null && wasm.charm.skills.isNotEmpty()) {
                    Log.i(TAG, "wasm-fallback ${wasm.note}")
                    return@runCatching Result(
                        wasm.charm,
                        "wasm ${wasm.note}",
                        wasm.confidence * 0.9f
                    )
                }

                error(
                    "テンプレ未認識\n" +
                        "local=${local?.note}\n" +
                        "wasm=${wasm?.note}"
                )
            }
        }
}
