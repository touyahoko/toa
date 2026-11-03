package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 画像読み込みのみ（ML Kit は使わない）。
 * 鑑定の文字認識は RenkinSwitchReader のテンプレ照合のみ。
 */
object AndroidOcr {

    suspend fun loadBitmap(context: Context, uri: Uri): Result<Bitmap> =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                } ?: throw IllegalStateException("画像を開けませんでした")
            }
        }
}
