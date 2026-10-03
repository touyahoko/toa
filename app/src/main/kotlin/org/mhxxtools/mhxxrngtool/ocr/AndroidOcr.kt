package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

object AndroidOcr {
    private val recognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    /** 任意 Bitmap を OCR（スロット切り出し再認識用） */
    suspend fun ocrBitmap(bitmap: Bitmap): String = recognizeBitmap(bitmap)

    // ── 内部: Bitmap → ML Kit OCR ─────────────────────────────────────────
    private suspend fun recognizeBitmap(bitmap: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { cont.resume(it.text) }
                .addOnFailureListener { cont.resume("[エラー] ${it.message}") }
        }

    // ── URI からフル画像を Bitmap として読み込む ──────────────────────────
    suspend fun loadBitmap(context: Context, uri: Uri): Result<Bitmap> =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                } ?: throw IllegalStateException("画像を開けませんでした")
            }
        }

    // ── Bitmap の指定領域を切り抜いて OCR する ────────────────────────────
    // cropLeft/Top/Right/Bottom は 0.0〜1.0 の相対座標
    suspend fun recognizeCropped(
        bitmap: Bitmap,
        cropLeft: Float, cropTop: Float,
        cropRight: Float, cropBottom: Float
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val w = bitmap.width; val h = bitmap.height
            val x  = (cropLeft  * w).toInt().coerceIn(0, w - 2)
            val y  = (cropTop   * h).toInt().coerceIn(0, h - 2)
            val rw = ((cropRight  - cropLeft)  * w).toInt().coerceIn(1, w - x)
            val rh = ((cropBottom - cropTop)   * h).toInt().coerceIn(1, h - y)
            val crop = Bitmap.createBitmap(bitmap, x, y, rw, rh)
            recognizeBitmap(crop)
        }
    }

    // ── 従来互換: URI から直接 OCR (フル画像) ────────────────────────────
    suspend fun recognizeText(context: Context, uri: Uri): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                recognizeBitmap(
                    BitmapFactory.decodeStream(
                        context.contentResolver.openInputStream(uri)
                    ) ?: throw IllegalStateException("画像を開けませんでした")
                )
            }
        }


    data class SlotOcr(
        val fullText: String,
        val slotLineText: String,
        /** 切り出し画像を再OCRしたテキスト（○ / --- が取りやすい） */
        val cropText: String,
        val slotFromText: Int?,
        val slotCrop: Bitmap?
    )

    /**
     * 「スロット」ラベルの右側を切り出して判定する。
     * 全画面スクショでも護石枠の位置に依存しない。
     */
    suspend fun recognizeSlotLine(bitmap: Bitmap): SlotOcr =
        suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    val full = result.text ?: ""
                    var line = ""
                    var crop: Bitmap? = null
                    val w = bitmap.width
                    val h = bitmap.height
                    var slotBox: android.graphics.Rect? = null
                    for (block in result.textBlocks) {
                        for (lineEl in block.lines) {
                            val t = lineEl.text ?: continue
                            if (!t.contains("スロ")) continue
                            line = t
                            slotBox = lineEl.boundingBox
                            break
                        }
                        if (line.isNotEmpty()) break
                    }
                    // 同じ高さ帯で「スロット」より右の文字を行テキストに足す
                    if (slotBox != null) {
                        val midY = (slotBox.top + slotBox.bottom) / 2
                        val extras = mutableListOf<String>()
                        for (block in result.textBlocks) {
                            for (lineEl in block.lines) {
                                val b = lineEl.boundingBox ?: continue
                                val cy = (b.top + b.bottom) / 2
                                if (kotlin.math.abs(cy - midY) > slotBox.height()) continue
                                if (b.left < slotBox.left - 4) continue
                                val tx = lineEl.text ?: continue
                                if (tx !in extras && tx != line) extras.add(tx)
                            }
                        }
                        if (extras.isNotEmpty()) {
                            line = (listOf(line) + extras).joinToString(" ")
                        }
                        // 切り出し: ラベル右〜十分広く（○ / ---）
                        val left = (slotBox.left).coerceIn(0, w - 2)
                        val top = (slotBox.top - slotBox.height() / 2).coerceIn(0, h - 2)
                        val right = (slotBox.right + slotBox.width() * 3).coerceIn(left + 2, w)
                        val bottom = (slotBox.bottom + slotBox.height() / 2).coerceIn(top + 2, h)
                        runCatching {
                            crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
                        }
                    }
                    // 行テキストが空なら full は使わない（装飾○混入防止）→ 空のまま
                    val parsed = if (line.isNotBlank()) SlotDetector.parseFromText(line) else null
                    cont.resume(SlotOcr(full, line, "", parsed, crop))
                }
                .addOnFailureListener {
                    cont.resume(SlotOcr("", "", "", null, null))
                }
        }

    /** バイト列 (JPEG) からテキストを認識する (CharmDetector 用) */
    suspend fun recognizeFromBytes(context: Context, jpegBytes: ByteArray): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                    ?: throw IllegalArgumentException("JPEGバイト列をBitmapにデコードできませんでした")
                recognizeBitmap(bmp)
            }
        }
}
