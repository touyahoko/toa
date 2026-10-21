package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object HybridCharmReader {
    data class Result(val charm: OcrCharm, val note: String, val confidence: Float)

    suspend fun read(context: Context, bitmap: Bitmap): kotlin.Result<Result> =
        withContext(Dispatchers.Default) {
            runCatching {
                val r = RenkinSwitchReader.read(context, bitmap)
                    ?: error("読み取り失敗。Switch 16:9 キャプチャの鑑定画面を選んでください。")
                if (r.charm.skills.isEmpty()) {
                    error("スキル未認識\n${r.note}")
                }
                Result(r.charm, r.note, r.confidence)
            }
        }
}
