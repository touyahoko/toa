package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 鑑定は RenkinSwitchReader（mhxx-renkin-reader 方式）のみ */
object HybridCharmReader {
    data class Result(val charm: OcrCharm, val note: String, val confidence: Float)

    suspend fun read(context: Context, bitmap: Bitmap): kotlin.Result<Result> =
        withContext(Dispatchers.Default) {
            runCatching {
                val r = RenkinSwitchReader.read(context, bitmap)
                    ?: error("読み取り失敗。Switchキャプチャ（16:9）の鑑定画面を選んでください。")
                if (r.charm.skills.isEmpty()) {
                    error("スキルを認識できません。\n鑑定パネル全体が写ったSwitchキャプチャを使用。\n(${r.note})")
                }
                Result(r.charm, r.note, r.confidence)
            }
        }
}
