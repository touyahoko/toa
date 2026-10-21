package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mhxxtools.mhxxrngtool.rng.SKILL_NAMES
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max

/**
 * mhxx-renkin-reader と同じ手順:
 *  1280x720 正規化 → max(R,G,B) → トップハット(k=25)
 *  → 固定ROI → matchTemplate(TM_CCOEFF_NORMED) + leftover 減点
 */
object RenkinSwitchReader {

    private const val TAG = "RenkinSwitch"

    data class Result(
        val charm: OcrCharm,
        val note: String,
        val confidence: Float
    )

    private const val SW = 1280
    private const val SH = 720
    private const val TOPHAT_K = 25

    private val NAME1 = intArrayOf(655, 165, 800, 191)
    private val PTS1  = intArrayOf(795, 165, 850, 191)
    private val NAME2 = intArrayOf(655, 194, 800, 218)
    private val PTS2  = intArrayOf(795, 194, 850, 218)
    private val SLOT_X = intArrayOf(580, 604, 628)
    private const val SLOT_Y = 224
    private val SLOT_BOX = intArrayOf(-1, -2, 17, 18)

    private const val SKILL_MIN = 0.35
    private const val DIGIT_MIN = 0.40
    private const val SLOT_MIN = 0.40
    private const val LEFTOVER = 1.0

    private data class Tmpl(val id: Int, val mat: Mat)

    @Volatile private var loaded = false
    private val skillTmpls = mutableListOf<Tmpl>()
    private val digitTmpls = mutableListOf<Tmpl>()
    private var circleMat: Mat? = null
    private var dashMat: Mat? = null

    fun isSwitchCapture(bmp: Bitmap): Boolean {
        if (bmp.width < 640 || bmp.height < 360) return false
        return abs(bmp.width.toFloat() / bmp.height - 16f / 9f) < 0.06f
    }

    suspend fun read(context: Context, bitmap: Bitmap): Result? = withContext(Dispatchers.Default) {
        if (!OpenCvBridge.ensureInit()) {
            return@withContext Result(OcrCharm(-1, -1, emptyList()), "OpenCV未初期化", 0f)
        }
        ensureLoaded(context)
        if (skillTmpls.isEmpty()) {
            return@withContext Result(OcrCharm(-1, -1, emptyList()), "assets/renkin/skills が空", 0f)
        }

        val screenBmp: Bitmap = when {
            isSwitchCapture(bitmap) -> scaleToScreen(bitmap)
            else -> {
                val ref = loadReference(context)
                if (ref != null) {
                    try {
                        OpenCvBridge.alignWithReference(bitmap, ref, SW, SH)
                            ?: scaleToScreen(bitmap)
                    } finally {
                        ref.recycle()
                    }
                } else scaleToScreen(bitmap)
            }
        }

        try {
            val rgba = OpenCvBridge.bitmapToMat(screenBmp)
            val mx = maxChannel(rgba)
            val th = topHat(mx, TOPHAT_K)
            rgba.release()
            mx.release()

            val (s1Id, s1Sc) = matchSkill(th, NAME1)
            val (p1, p1Sc) = matchDigit(th, PTS1)
            val (s2Id, s2Sc) = matchSkill(th, NAME2)
            val (p2, _) = matchDigit(th, PTS2)
            val (slots, slotNote) = matchSlots(th)
            th.release()

            val skills = mutableListOf<OcrSkill>()
            if (s1Id != null && s1Sc >= SKILL_MIN) {
                skills += OcrSkill(
                    s1Id,
                    SKILL_NAMES.getOrElse(s1Id) { "skill$s1Id" },
                    (p1 ?: 0).coerceIn(0, 20)
                )
            }
            if (s2Id != null && s2Sc >= 0.42) {
                skills += OcrSkill(
                    s2Id,
                    SKILL_NAMES.getOrElse(s2Id) { "skill$s2Id" },
                    (p2 ?: 0).coerceIn(0, 20)
                )
            }

            if (skills.isEmpty()) {
                return@withContext Result(
                    OcrCharm(-1, slots, emptyList()),
                    "renkin-fail s1=%.2f s2=%.2f p1=%.2f n=%d".format(s1Sc, s2Sc, p1Sc, skillTmpls.size),
                    0.15f
                )
            }

            val conf = when {
                skills.size >= 2 && slots in 0..3 && s1Sc >= 0.55 -> 0.97f
                skills.size >= 1 && slots in 0..3 -> 0.90f
                else -> 0.75f
            }
            val note = "renkin ${SW}x${SH} " +
                skills.joinToString("+") { "${it.name}+${it.pts}" } +
                " sl=$slots s1=%.2f".format(s1Sc) +
                (if (skills.size > 1) " s2=%.2f".format(s2Sc) else "") +
                " $slotNote"
            Log.i(TAG, note)
            Result(OcrCharm(-1, slots, skills), note, conf)
        } finally {
            if (screenBmp !== bitmap) screenBmp.recycle()
        }
    }

    private fun loadReference(context: Context): Bitmap? =
        runCatching {
            context.assets.open("renkin/reference.png").use { BitmapFactory.decodeStream(it) }
        }.getOrNull()

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            if (!OpenCvBridge.ensureInit()) return
            skillTmpls.clear()
            digitTmpls.clear()
            val am = context.assets
            fun listPng(dir: String) =
                runCatching { am.list(dir)?.filter { it.endsWith(".png", true) }.orEmpty() }
                    .getOrDefault(emptyList())

            for (n in listPng("renkin/skills")) {
                val id = n.substringBeforeLast('.').toIntOrNull() ?: continue
                am.open("renkin/skills/$n").use { ins ->
                    val bmp = BitmapFactory.decodeStream(ins) ?: return@use
                    val mat = OpenCvBridge.bitmapToMat(bmp)
                    bmp.recycle()
                    val gray = maxChannel(mat)
                    mat.release()
                    val th = topHat(gray, TOPHAT_K)
                    gray.release()
                    skillTmpls += Tmpl(id, th)
                }
            }
            skillTmpls.sortBy { it.id }

            for (n in listPng("renkin/digits")) {
                val id = n.substringBeforeLast('.').toIntOrNull() ?: continue
                am.open("renkin/digits/$n").use { ins ->
                    val bmp = BitmapFactory.decodeStream(ins) ?: return@use
                    val mat = OpenCvBridge.bitmapToMat(bmp)
                    bmp.recycle()
                    val gray = maxChannel(mat)
                    mat.release()
                    val th = topHat(gray, TOPHAT_K)
                    gray.release()
                    digitTmpls += Tmpl(id, th)
                }
            }

            fun loadSlot(name: String): Mat? = runCatching {
                am.open("renkin/slots/$name.png").use { ins ->
                    val bmp = BitmapFactory.decodeStream(ins) ?: return@use null
                    val mat = OpenCvBridge.bitmapToMat(bmp)
                    bmp.recycle()
                    val gray = maxChannel(mat)
                    mat.release()
                    val th = topHat(gray, 9)
                    gray.release()
                    th
                }
            }.getOrNull()
            circleMat = loadSlot("circle")
            dashMat = loadSlot("dash")
            loaded = true
            Log.i(TAG, "loaded skills=${skillTmpls.size} digits=${digitTmpls.size}")
        }
    }

    private fun matchSkill(screenTh: Mat, rect: IntArray): Pair<Int?, Double> {
        val roi = crop(screenTh, rect, 1) ?: return null to -1.0
        var bestId: Int? = null
        var best = -1.0
        var second = -1.0
        for (t in skillTmpls) {
            if (t.mat.cols() > roi.cols() || t.mat.rows() > roi.rows()) continue
            val pair = matchNcc(roi, t.mat) ?: continue
            val adj = pair.first - LEFTOVER * leftoverRatio(roi, pair.second, t.mat.cols())
            when {
                adj > best -> { second = best; best = adj; bestId = t.id }
                adj > second -> second = adj
            }
        }
        roi.release()
        if (best < SKILL_MIN) return null to best
        if (best - second < 0.03 && best < 0.55) return null to best
        return bestId to best
    }

    private fun matchDigit(screenTh: Mat, rect: IntArray): Pair<Int?, Double> {
        val roi = crop(screenTh, rect, 2) ?: return null to -1.0
        var bestN: Int? = null
        var best = -1.0
        for (t in digitTmpls) {
            for (scale in doubleArrayOf(0.85, 1.0, 1.15, 1.3)) {
                val scaled = Mat()
                Imgproc.resize(t.mat, scaled, Size(t.mat.cols() * scale, t.mat.rows() * scale))
                if (scaled.cols() > roi.cols() || scaled.rows() > roi.rows()) {
                    scaled.release(); continue
                }
                val pair = matchNcc(roi, scaled)
                scaled.release()
                if (pair != null && pair.first > best) {
                    best = pair.first
                    bestN = t.id
                }
            }
        }
        roi.release()
        return if (best >= DIGIT_MIN) bestN to best else null to best
    }

    private fun matchSlots(screenTh: Mat): Pair<Int, String> {
        val circle = circleMat ?: return -1 to "no-slot-tmpl"
        val dash = dashMat ?: return -1 to "no-slot-tmpl"
        var count = 0
        val scores = mutableListOf<String>()
        for (cx in SLOT_X) {
            val l = (cx + SLOT_BOX[0] - 2).coerceIn(0, SW - 2)
            val t = (SLOT_Y + SLOT_BOX[1] - 2).coerceIn(0, SH - 2)
            val r = (cx + SLOT_BOX[2] + 2).coerceIn(l + 1, SW)
            val b = (SLOT_Y + SLOT_BOX[3] + 2).coerceIn(t + 1, SH)
            val roi = crop(screenTh, intArrayOf(l, t, r, b), 0) ?: continue
            var bestC = -1.0
            var bestD = -1.0
            for (scale in doubleArrayOf(0.9, 1.0, 1.15)) {
                fun tryMatch(tmpl: Mat): Double {
                    val sc = Mat()
                    Imgproc.resize(tmpl, sc, Size(tmpl.cols() * scale, tmpl.rows() * scale))
                    if (sc.cols() > roi.cols() || sc.rows() > roi.rows()) {
                        sc.release(); return -1.0
                    }
                    val pair = matchNcc(roi, sc)
                    sc.release()
                    return pair?.first ?: -1.0
                }
                bestC = max(bestC, tryMatch(circle))
                bestD = max(bestD, tryMatch(dash))
            }
            roi.release()
            scores += "c=%.2f/d=%.2f".format(bestC, bestD)
            if (bestC >= SLOT_MIN && bestC > bestD + 0.05) count++
        }
        return count.coerceIn(0, 3) to scores.joinToString(",")
    }

    private fun matchNcc(image: Mat, tmpl: Mat): Pair<Double, Int>? {
        if (tmpl.cols() > image.cols() || tmpl.rows() > image.rows()) return null
        val result = Mat()
        return try {
            Imgproc.matchTemplate(image, tmpl, result, Imgproc.TM_CCOEFF_NORMED)
            val mm = Core.minMaxLoc(result)
            mm.maxVal to mm.maxLoc.x.toInt()
        } catch (e: Throwable) {
            Log.e(TAG, "matchTemplate failed", e)
            null
        } finally {
            result.release()
        }
    }

    private fun leftoverRatio(roi: Mat, matchX: Int, tmplW: Int): Double {
        val cols = roi.cols()
        val rows = roi.rows()
        val data = ByteArray(cols * rows)
        roi.get(0, 0, data)
        var total = 0
        var outside = 0
        val left = (matchX - 2).coerceAtLeast(0)
        val right = (matchX + tmplW + 2).coerceAtMost(cols)
        for (x in 0 until cols) {
            var colInk = 0
            for (y in 0 until rows) {
                if ((data[y * cols + x].toInt() and 0xFF) > 30) colInk++
            }
            total += colInk
            if (x < left || x >= right) outside += colInk
        }
        if (total == 0) return 0.0
        return outside.toDouble() / total
    }

    private fun scaleToScreen(src: Bitmap): Bitmap {
        if (src.width == SW && src.height == SH) return src
        val out = Bitmap.createBitmap(SW, SH, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, null, Rect(0, 0, SW, SH), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun maxChannel(rgba: Mat): Mat {
        val ch = ArrayList<Mat>(4)
        Core.split(rgba, ch)
        val out = Mat()
        Core.max(ch[0], ch[1], out)
        Core.max(out, ch[2], out)
        ch.forEach { it.release() }
        return out
    }

    private fun topHat(src: Mat, k: Int): Mat {
        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size(k.toDouble(), k.toDouble())
        )
        val out = Mat()
        Imgproc.morphologyEx(src, out, Imgproc.MORPH_TOPHAT, kernel)
        kernel.release()
        return out
    }

    private fun crop(src: Mat, r: IntArray, pad: Int): Mat? {
        val l = (r[0] - pad).coerceIn(0, src.cols() - 2)
        val t = (r[1] - pad).coerceIn(0, src.rows() - 2)
        val rr = (r[2] + pad).coerceIn(l + 1, src.cols())
        val b = (r[3] + pad).coerceIn(t + 1, src.rows())
        val w = rr - l
        val h = b - t
        if (w < 4 || h < 4) return null
        return Mat(src, org.opencv.core.Rect(l, t, w, h)).clone()
    }
}
