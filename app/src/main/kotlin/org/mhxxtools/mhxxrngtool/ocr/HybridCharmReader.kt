package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 鑑定リーダー（錬金134以外のスキル組み合わせにも対応）。
 *
 * 1) RenkinSwitchReader: 見本テンプレ + ML Kit OCR で全 SKILL_NAMES を解決
 * 2) スキルが全く取れないときのみ公式 WASM（134スキル限定）にフォールバック
 */
object HybridCharmReader {
    private const val TAG = "HybridCharm"

    data class Result(val charm: OcrCharm, val note: String, val confidence: Float)

    suspend fun read(context: Context, bitmap: Bitmap): kotlin.Result<Result> =
        withContext(Dispatchers.Default) {
            runCatching {
                val local = RenkinSwitchReader.read(context, bitmap)
                if (local != null && local.charm.skills.isNotEmpty()) {
                    Log.i(TAG, "local ${local.note} conf=${local.confidence}")
                    return@runCatching Result(
                        local.charm,
                        "full ${local.note}",
                        local.confidence.coerceAtLeast(0.5f)
                    )
                }

                // WASM は錬金134のみ。非公式スキル護石には使わないが、完全失敗時の最後の手段
                val wasm = RenkinWasmBridge.read(context, bitmap)
                if (wasm != null && wasm.charm.skills.isNotEmpty()) {
                    Log.i(TAG, "wasm ${wasm.note}")
                    return@runCatching Result(wasm.charm, "wasm ${wasm.note}", wasm.confidence)
                }

                error(
                    "スキル未認識（全スキル対応エンジン）\n" +
                        "local=${local?.note}\n" +
                        "wasm=${wasm?.note}"
                )
            }
        }
}
