package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 鑑定は公式 mhxx-renkin-reader の WASM のみ（サイトと同一バイナリ）。
 * Kotlin 再実装は使わない。
 */
object HybridCharmReader {
    private const val TAG = "HybridCharm"

    data class Result(val charm: OcrCharm, val note: String, val confidence: Float)

    suspend fun read(context: Context, bitmap: Bitmap): kotlin.Result<Result> =
        withContext(Dispatchers.Default) {
            runCatching {
                val r = RenkinWasmBridge.read(context, bitmap)
                    ?: error("WASM読み取り失敗")
                Log.i(TAG, r.note)
                if (r.charm.skills.isEmpty()) {
                    error("スキル未認識（サイトと同じエンジン）\n${r.note}")
                }
                Result(r.charm, r.note, r.confidence)
            }
        }
}
