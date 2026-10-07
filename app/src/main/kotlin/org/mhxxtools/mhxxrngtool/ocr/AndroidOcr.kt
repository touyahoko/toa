package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
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
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 鑑定 OCR — APK 内蔵オンデバイス。
 *
 * Google ML Kit Text Recognition (Japanese + Latin) を併用。
 * スマホ撮影の構図ズレに対応するため、バウンディングボックスで
 * 「スキル」「スロット」「お守り」等をアンカーにしてパネル位置を検出し、
 * その領域だけを切り出して高精度 OCR する。
 */
object AndroidOcr {

    private val jaRecognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    private val latinRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /** Switch スクショ基準の固定相対クロップ（フォールバック用） */
    private const val FIXED_L = 445f / 1200f
    private const val FIXED_T = 88f / 675f
    private const val FIXED_R = 792f / 1200f
    private const val FIXED_B = 235f / 675f

    /** パネル検出のアンカーキーワード（部分一致） */
    private val ANCHOR_KEYS = listOf(
        "スキル", "スロット", "スロッ", "スロ",
        "お守り", "風化", "古び", "光る", "なぞ",
        "固有", "攻撃", "防御", "体力", "達人", "痛撃",
        "回避", "ガード", "耐", "属性", "会心", "装填",
        "研ぎ", "斬れ味", "剣術", "砲術", "底力", "根性"
    )

    data class PanelDetectResult(
        val crop: Rect,
        val anchors: List<String>,
        val method: String
    )

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

    private fun enhanceForOcr(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val cm = ColorMatrix(
            floatArrayOf(
                1.4f, 0f, 0f, 0f, -30f,
                0f, 1.4f, 0f, 0f, -30f,
                0f, 0f, 1.4f, 0f, -30f,
                0f, 0f, 0f, 1f, 0f
            )
        )
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

        mergeOcrTexts(texts)
    }

    private fun mergeOcrTexts(texts: List<String>): String {
        if (texts.isEmpty()) return ""
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
     * ML Kit の行バウンディングボックスからお守りパネル領域を推定する。
     * スマホ撮影のズレ・傾き・余白に対応。
     */
    suspend fun detectPanel(bitmap: Bitmap): PanelDetectResult? = withContext(Dispatchers.IO) {
        val w = bitmap.width
        val h = bitmap.height
        // 大きい写真は縮小して検出（速度・安定性）
        val maxSide = max(w, h)
        val scale = if (maxSide > 1280) 1280f / maxSide else 1f
        val work = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (w * scale).roundToInt(), (h * scale).roundToInt(), true)
        } else bitmap

        val text = processTextSafe(jaRecognizer, work) ?: run {
            if (work !== bitmap) work.recycle()
            return@withContext null
        }

        val inv = if (scale < 1f) 1f / scale else 1f
        data class Hit(val box: Rect, val label: String)
        val hits = mutableListOf<Hit>()

        for (block in text.textBlocks) {
            for (line in block.lines) {
                val t = (line.text ?: "").replace(" ", "").replace("　", "")
                if (t.isBlank()) continue
                val box = line.boundingBox ?: continue
                val mapped = Rect(
                    (box.left * inv).roundToInt(),
                    (box.top * inv).roundToInt(),
                    (box.right * inv).roundToInt(),
                    (box.bottom * inv).roundToInt()
                )
                val key = ANCHOR_KEYS.firstOrNull { t.contains(it) } ?: continue
                hits.add(Hit(mapped, "$key:$t"))
            }
        }

        if (work !== bitmap) work.recycle()
        if (hits.isEmpty()) return@withContext null

        // スキル/スロット系を優先。なければ全アンカー
        val preferred = hits.filter {
            it.label.startsWith("スキル") || it.label.startsWith("スロ") ||
                it.label.startsWith("お守り") || it.label.startsWith("風化") ||
                it.label.startsWith("古び") || it.label.startsWith("光る") ||
                it.label.startsWith("なぞ")
        }
        val used = if (preferred.size >= 2) preferred else hits

        var left = used.minOf { it.box.left }
        var top = used.minOf { it.box.top }
        var right = used.maxOf { it.box.right }
        var bottom = used.maxOf { it.box.bottom }

        // パネル余白（相対）。スキル行の上に種類名、下にスロットがある想定で拡張
        val pw = right - left
        val ph = bottom - top
        val padX = max((pw * 0.35f).roundToInt(), (w * 0.04f).roundToInt())
        val padTop = max((ph * 0.55f).roundToInt(), (h * 0.03f).roundToInt())
        val padBottom = max((ph * 0.45f).roundToInt(), (h * 0.03f).roundToInt())

        left = max(0, left - padX)
        top = max(0, top - padTop)
        right = min(w, right + padX)
        bottom = min(h, bottom + padBottom)

        // 最低サイズ（小さすぎる検出は捨てる）
        if (right - left < w * 0.12f || bottom - top < h * 0.06f) return@withContext null

        PanelDetectResult(
            crop = Rect(left, top, right, bottom),
            anchors = used.map { it.label }.distinct().take(12),
            method = "bbox"
        )
    }

    /** Switch 固定座標のフォールバック領域 */
    fun fixedPanelRect(bitmap: Bitmap): Rect {
        val w = bitmap.width
        val h = bitmap.height
        return Rect(
            (FIXED_L * w).toInt().coerceIn(0, w - 2),
            (FIXED_T * h).toInt().coerceIn(0, h - 2),
            (FIXED_R * w).toInt().coerceIn(1, w),
            (FIXED_B * h).toInt().coerceIn(1, h)
        )
    }

    /**
     * 鑑定画像向け: パネル検出 → 切り出し → 強化 OCR。
     * 検出失敗時は固定座標にフォールバック。
     */
    suspend fun recognizeAppraisal(bitmap: Bitmap): Result<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val detected = detectPanel(bitmap)
                val rect = detected?.crop ?: fixedPanelRect(bitmap)
                val method = detected?.method ?: "fixed"
                val x = rect.left.coerceIn(0, bitmap.width - 2)
                val y = rect.top.coerceIn(0, bitmap.height - 2)
                val rw = (rect.right - rect.left).coerceIn(1, bitmap.width - x)
                val rh = (rect.bottom - rect.top).coerceIn(1, bitmap.height - y)
                val crop = Bitmap.createBitmap(bitmap, x, y, rw, rh)
                val text = try {
                    recognizeBest(crop)
                } finally {
                    crop.recycle()
                }
                val note = if (detected != null) {
                    "パネル検出($method) anchors=${detected.anchors.joinToString(",")}"
                } else {
                    "固定座標クロップ"
                }
                text to note
            }
        }

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

    suspend fun recognizeSlotLine(bitmap: Bitmap): SlotOcr {
        val result = processTextSafe(jaRecognizer, bitmap)
            ?: return SlotOcr("", "", "", null, null)
        val full = result.text ?: ""
        var line = ""
        var crop: Bitmap? = null
        val w = bitmap.width
        val h = bitmap.height
        var slotBox: Rect? = null

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
