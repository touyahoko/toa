package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.max

/**
 * 鑑定 OCR — APK 内蔵オンデバイス。
 *
 * Google ML Kit Text Recognition (Japanese + Latin) を併用。
 * クラウド API なし・API キー不要。モデルは端末内で動作。
 *
 * 強化点:
 * - コントラスト強調 + グレースケール前処理
 * - 3 倍拡大（小さい UI 文字向け）
 * - 日本語エンジンとラテン数字エンジンを並列実行して結合
 * - 複数スケールの結果をマージ
 */
object AndroidOcr {

    /** 日本語（スキル名・ラベル） */
    private val jaRecognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    /** ラテン（数字・スロット ○ など） */
    private val latinRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun ocrBitmap(bitmap: Bitmap): String = recognizeBest(bitmap)

    private suspend fun processSafe(
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        bitmap: Bitmap
    ): String = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { cont.resume(it.text ?: "") }
            .addOnFailureListener { cont.resume("") }
    }

    private suspend fun processTextSafe(
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        bitmap: Bitmap
    ): Text? = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resume(null) }
    }

    /**
     * 前処理: コントラスト強調したグレースケール相当の Bitmap。
     * ゲーム UI の薄い文字を ML Kit が拾いやすくする。
     */
    private fun enhanceForOcr(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        // コントラスト + 輝度
        val cm = ColorMatrix(
            floatArrayOf(
                1.4f, 0f, 0f, 0f, -30f,
                0f, 1.4f, 0f, 0f, -30f,
                0f, 0f, 1.4f, 0f, -30f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        // グレースケール化
        val gray = ColorMatrix().apply { setSaturation(0f) }
        cm.postConcat(gray)
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private fun upscale(src: Bitmap, factor: Int): Bitmap {
        if (factor <= 1) return src
        return Bitmap.createScaledBitmap(src, src.width * factor, src.height * factor, true)
    }

    /** 日英並列 + 前処理 + 3x 拡大で最良テキストを返す */
    private suspend fun recognizeBest(bitmap: Bitmap): String = coroutineScope {
        val enhanced = enhanceForOcr(bitmap)
        val scaled3 = upscale(enhanced, 3)
        val scaled2 = upscale(enhanced, 2)

        val ja3 = async { processSafe(jaRecognizer, scaled3) }
        val la3 = async { processSafe(latinRecognizer, scaled3) }
        val ja2 = async { processSafe(jaRecognizer, scaled2) }

        val texts = listOf(ja3.await(), la3.await(), ja2.await())
            .filter { it.isNotBlank() && !it.startsWith("[エラー]") }

        if (scaled3 !== enhanced) scaled3.recycle()
        if (scaled2 !== enhanced) scaled2.recycle()
        if (enhanced !== bitmap) enhanced.recycle()

        // 最長かつ日本語スキルっぽい文字を含むものを優先して結合
        mergeOcrTexts(texts)
    }

    private fun mergeOcrTexts(texts: List<String>): String {
        if (texts.isEmpty()) return ""
        // 行単位でユニーク結合（長い方を優先）
        val lines = linkedSetOf<String>()
        texts.sortedByDescending { it.length }.forEach { t ->
            t.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { lines.add(it) }
        }
        return lines.joinToString("\n")
    }

    suspend fun loadBitmap(context: Context, uri: Uri): Result<Bitmap> =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                } ?: throw IllegalStateException("画像を開けませんでした")
            }
        }

    /**
     * 相対座標で切り出して OCR。
     * 小さい日本語 UI 向けに 3 倍拡大 + コントラスト強調 + 日英併用。
     */
    suspend fun recognizeCropped(
        bitmap: Bitmap,
        cropLeft: Float, cropTop: Float,
        cropRight: Float, cropBottom: Float
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val w = bitmap.width
            val h = bitmap.height
            val x = (cropLeft * w).toInt().coerceIn(0, w - 2)
            val y = (cropTop * h).toInt().coerceIn(0, h - 2)
            val rw = ((cropRight - cropLeft) * w).toInt().coerceIn(1, w - x)
            val rh = ((cropBottom - cropTop) * h).toInt().coerceIn(1, h - y)
            val crop = Bitmap.createBitmap(bitmap, x, y, rw, rh)
            val text = recognizeBest(crop)
            crop.recycle()
            text
        }
    }

    suspend fun recognizeText(context: Context, uri: Uri): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bmp = BitmapFactory.decodeStream(
                    context.contentResolver.openInputStream(uri)
                ) ?: throw IllegalStateException("画像を開けませんでした")
                try {
                    recognizeBest(bmp)
                } finally {
                    bmp.recycle()
                }
            }
        }

    data class SlotOcr(
        val fullText: String,
        val slotLineText: String,
        val cropText: String,
        val slotFromText: Int?,
        val slotCrop: Bitmap?
    )

    /**
     * 「スロット」ラベルの右側を切り出して判定。
     * 日本語エンジンでラベル位置を取り、切り出しを強化 OCR。
     */
    suspend fun recognizeSlotLine(bitmap: Bitmap): SlotOcr {
        val result = processTextSafe(jaRecognizer, bitmap)
            ?: return SlotOcr("", "", "", null, null)
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
            val left = slotBox.left.coerceIn(0, w - 2)
            val top = (slotBox.top - slotBox.height() / 2).coerceIn(0, h - 2)
            val right = (slotBox.right + slotBox.width() * 3).coerceIn(left + 2, w)
            val bottom = (slotBox.bottom + slotBox.height() / 2).coerceIn(top + 2, h)
            runCatching {
                crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
            }
        }

        // 切り出しを強化 OCR（ラテン含む）して ○ / --- を拾う
        val cropText = crop?.let { recognizeBest(it) } ?: ""
        val parsedLine = if (line.isNotBlank()) SlotDetector.parseFromText(line) else null
        val parsedCrop = if (cropText.isNotBlank()) SlotDetector.parseFromText(cropText) else null
        val slot = parsedCrop ?: parsedLine

        return SlotOcr(full, line, cropText, slot, crop)
    }

    suspend fun recognizeFromBytes(context: Context, jpegBytes: ByteArray): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                    ?: throw IllegalArgumentException("JPEGバイト列をBitmapにデコードできませんでした")
                try {
                    recognizeBest(bmp)
                } finally {
                    bmp.recycle()
                }
            }
        }
}
