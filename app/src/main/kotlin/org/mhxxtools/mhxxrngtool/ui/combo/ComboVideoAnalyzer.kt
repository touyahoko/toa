package org.mhxxtools.mhxxrngtool.ui.combo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.math.max

/**
 * Switch 録画は共通で 1280×720。
 * サイト ROI (742,209,102,72 @1920×1080) を 720p にスケールすると
 * 約 (495,139,68,48) と小さくなり OCR が失敗しやすいため、
 * 720p 向けに広げた ROI を優先する。
 */
object ComboVideoAnalyzer {

    /**
     * 1280×720 向け ROI（ピクセル指定を比率化）。
     * サイト相当を中心に、数字が確実に入るよう余白を足している。
     */
    private val ROI_720P = listOf(
        // サイト相当を 1.5〜2 倍に拡大した領域
        floatArrayOf(450f / 1280f, 120f / 720f, 160f / 1280f, 100f / 720f),
        floatArrayOf(480f / 1280f, 130f / 720f, 140f / 1280f, 90f / 720f),
        // ダイアログ内の所持数付近（広め）
        floatArrayOf(0.30f, 0.15f, 0.35f, 0.35f),
        floatArrayOf(0.35f, 0.18f, 0.28f, 0.28f),
        floatArrayOf(0.28f, 0.12f, 0.42f, 0.40f),
        // さらに広め（最終手段）
        floatArrayOf(0.22f, 0.10f, 0.50f, 0.48f)
    )

    /** 1080p など他解像度用（比率） */
    private val ROI_GENERIC = listOf(
        floatArrayOf(742f / 1920f, 209f / 1080f, 140f / 1920f, 100f / 1080f),
        floatArrayOf(0.30f, 0.15f, 0.40f, 0.40f),
        floatArrayOf(0.35f, 0.18f, 0.30f, 0.30f)
    )

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    fun getDisplayName(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && col >= 0) c.getString(col) else null
        } ?: uri.lastPathSegment
    }.getOrNull() ?: "動画ファイル"

    fun getTotalFrames(context: Context, uri: Uri, fps: Int): Int {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: return 0
            (ms * fps.coerceAtLeast(1) / 1000L).toInt()
        } catch (_: Exception) {
            0
        } finally {
            runCatching { r.release() }
        }
    }

    suspend fun analyze(
        context: Context,
        uri: Uri,
        beginFrame: Int,
        endFrame: Int,
        fps: Int = 30,
        frameStep: Int = 3,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Result<List<Int>> {
        val retriever = MediaMetadataRetriever()
        return try {
            val list = withContext(Dispatchers.IO) {
                retriever.setDataSource(context, uri)
                val vw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 1280
                val vh = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 720

                val is720 = vh in 700..800 || (vw in 1200..1360 && vh in 680..780)
                val rois = if (is720) ROI_720P else ROI_GENERIC
                val upscale = if (is720) 4 else 3

                // 間隔: サイト準拠 3。列が短い再走査時のみ 2
                val step1 = 3

                // ROI 選定
                val probeFrames = (beginFrame..endFrame step 9).take(8).toList()
                    .ifEmpty { listOf(beginFrame) }
                var bestRoi = 0
                var bestScore = -1
                for ((ri, ratio) in rois.withIndex()) {
                    val r = roiRect(vw, vh, ratio)
                    var score = 0
                    val seen = mutableSetOf<Int>()
                    for (fi in probeFrames) {
                        val fr = frameAt(retriever, fi, fps) ?: continue
                        try {
                            if (r[0] + r[2] > fr.width || r[1] + r[3] > fr.height) continue
                            val n = readCount(fr, r[0], r[1], r[2], r[3], upscale)
                            if (n != null && n in 0..99) {
                                score++
                                seen.add(n)
                            }
                        } finally {
                            fr.recycle()
                        }
                    }
                    val total = score + seen.size * 3
                    if (total > bestScore) {
                        bestScore = total
                        bestRoi = ri
                    }
                }

                // 本解析
                var seq = scanPass(
                    retriever, vw, vh, rois[bestRoi], upscale,
                    beginFrame, endFrame, fps, step1, onProgress
                )

                // 短い場合: 広い ROI + 間隔2
                if (seq.size < 10) {
                    val wide = rois.maxByOrNull { it[2] * it[3] } ?: rois.last()
                    val seq2 = scanPass(
                        retriever, vw, vh, wide, upscale,
                        beginFrame, endFrame, fps, 2, onProgress
                    )
                    if (seq2.size > seq.size) seq = seq2
                }

                // まだ短い: 全 ROI
                if (seq.size < 8) {
                    for (ratio in rois) {
                        coroutineContext.ensureActive()
                        val s = scanPass(
                            retriever, vw, vh, ratio, upscale,
                            beginFrame, endFrame, fps, 3, onProgress
                        )
                        if (s.size > seq.size) seq = s
                    }
                }

                seq
            }
            Result.success(list)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private suspend fun scanPass(
        retriever: MediaMetadataRetriever,
        vw: Int, vh: Int,
        ratio: FloatArray,
        upscale: Int,
        beginFrame: Int, endFrame: Int,
        fps: Int, step: Int,
        onProgress: (Int, Int) -> Unit
    ): List<Int> {
        val (cx, cy, cw, ch) = roiRect(vw, vh, ratio)
        val frameList = (beginFrame..endFrame step step.coerceAtLeast(1)).toList()
        val total = frameList.size.coerceAtLeast(1)
        val raw = mutableListOf<Int>()

        for ((idx, fi) in frameList.withIndex()) {
            coroutineContext.ensureActive()
            val frame = frameAt(retriever, fi, fps)
            if (frame != null) {
                try {
                    if (cx + cw <= frame.width && cy + ch <= frame.height) {
                        val num = readCount(frame, cx, cy, cw, ch, upscale)
                        if (num != null && num in 0..99) raw.add(num)
                    }
                } finally {
                    frame.recycle()
                }
            }
            if (idx % 5 == 0 || idx == frameList.lastIndex) onProgress(idx + 1, total)
            if (raw.count { it >= 97 } >= 2) break
        }
        return buildSequence(raw)
    }

    private fun frameAt(retriever: MediaMetadataRetriever, frameIndex: Int, fps: Int): Bitmap? {
        val timeUs = frameIndex.toLong() * 1_000_000L / fps.coerceAtLeast(1)
        return runCatching {
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
        }.getOrNull()
    }

    private fun buildSequence(raw: List<Int>): List<Int> {
        if (raw.isEmpty()) return emptyList()
        val seq = mutableListOf<Int>()
        var last: Int? = null
        for (v in raw) {
            if (v == 99) break
            if (last == null) {
                seq.add(v); last = v; continue
            }
            if (v == last || v < last) continue
            val d = v - last
            when {
                d in 2..4 -> { seq.add(v); last = v }
                d == 1 -> { }
                d in 5..28 -> {
                    fillGaps(last, v)?.let {
                        seq.addAll(it.drop(1))
                        last = v
                    }
                }
            }
            if (last != null && last >= 97) break
        }
        return seq
    }

    private fun fillGaps(from: Int, to: Int): List<Int>? {
        val need = to - from
        if (need <= 0) return null
        val parent = IntArray(need + 1) { -1 }
        val used = IntArray(need + 1)
        parent[0] = 0
        for (i in 0..need) {
            if (i != 0 && parent[i] < 0) continue
            for (d in intArrayOf(3, 2, 4)) {
                val j = i + d
                if (j <= need && parent[j] < 0) {
                    parent[j] = i; used[j] = d
                }
            }
        }
        if (parent[need] < 0) return null
        val steps = mutableListOf<Int>()
        var cur = need
        while (cur > 0) { steps.add(used[cur]); cur = parent[cur] }
        steps.reverse()
        val path = mutableListOf(from)
        var v = from
        for (d in steps) { v += d; path.add(v) }
        return if (path.last() == to) path else null
    }

    private fun roiRect(vw: Int, vh: Int, ratio: FloatArray): IntArray {
        val cx = (vw * ratio[0]).toInt().coerceIn(0, vw - 2)
        val cy = (vh * ratio[1]).toInt().coerceIn(0, vh - 2)
        // 720p では最低サイズを確保（小さすぎると OCR 不能）
        val minW = max(80, (vw * 0.08f).toInt())
        val minH = max(50, (vh * 0.08f).toInt())
        val cw = (vw * ratio[2]).toInt().coerceAtLeast(minW).coerceAtMost(vw - cx)
        val ch = (vh * ratio[3]).toInt().coerceAtLeast(minH).coerceAtMost(vh - cy)
        return intArrayOf(cx, cy, cw, ch)
    }

    private suspend fun readCount(
        frame: Bitmap, cx: Int, cy: Int, cw: Int, ch: Int, upscale: Int
    ): Int? {
        val cropped = Bitmap.createBitmap(frame, cx, cy, cw, ch)
        return try {
            val processed = preprocess(cropped, upscale)
            val text = recognizeText(processed)
            processed.recycle()
            parseCount(text)
        } finally {
            cropped.recycle()
        }
    }

    private fun parseCount(text: String): Int? {
        if (text.isBlank()) return null
        val norm = text
            .replace('／', '/')
            .replace('｜', '/')
            .replace('|', '/')
            .replace('l', '1')
            .replace('I', '1')
            .replace('O', '0')
            .replace('o', '0')
            .replace(Regex("[^0-9/\\s]"), " ")
            .trim()

        Regex("""(\d{1,2})\s*/\s*99""").find(norm)?.groupValues?.get(1)
            ?.toIntOrNull()?.let { if (it in 0..99) return it }
        Regex("""(\d{1,2})\s*/\s*9\d""").find(norm)?.groupValues?.get(1)
            ?.toIntOrNull()?.let { if (it in 0..99) return it }
        Regex("""(\d{1,2})\s*/\s*\d{1,2}""").find(norm)?.groupValues?.get(1)
            ?.toIntOrNull()?.let { if (it in 0..99) return it }

        val nums = Regex("""\b(\d{1,2})\b""").findAll(norm)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in 0..99 }
            .toList()
        if (nums.size == 1) return nums[0]
        val without99 = nums.filter { it != 99 }
        return without99.maxOrNull() ?: nums.firstOrNull()
    }

    private fun preprocess(src: Bitmap, upscale: Int): Bitmap {
        val u = upscale.coerceIn(2, 5)
        val sw = (src.width * u).coerceAtLeast(1)
        val sh = (src.height * u).coerceAtLeast(1)
        val scaled = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        1.9f, 0f, 0f, 0f, -55f,
                        0f, 1.9f, 0f, 0f, -55f,
                        0f, 0f, 1.9f, 0f, -55f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
        Canvas(scaled).drawBitmap(src, null, RectF(0f, 0f, sw.toFloat(), sh.toFloat()), paint)
        return scaled
    }

    private suspend fun recognizeText(bitmap: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { cont.resume(it.text ?: "") }
                .addOnFailureListener { cont.resume("") }
        }
}
