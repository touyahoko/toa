package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mhxxtools.mhxxrngtool.rng.SKILL_NAMES
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * mhxx-renkin-reader の読み取りロジックを Android 向けに移植。
 *
 * Switch キャプチャ (16:9) を 1280×720 に正規化し、
 * renkin-reader と同じ固定座標 + 見本テンプレート照合を行う。
 *
 * 座標 (read.rs):
 *   NAME1 (655,165)-(800,191)  PTS1 (795,165)-(850,191)
 *   NAME2 (655,194)-(800,218)  PTS2 (795,194)-(850,218)
 *   SLOT  cells x=580,604,628  y=224
 *
 * 見本: assets/renkin/{skills,digits,slots}/
 * ※ スマホ直撮りの SIFT 射影変換は含まない（Switchスクショ専用）。
 */
object RenkinSwitchReader {

    data class Result(
        val charm: OcrCharm,
        val note: String,
        val confidence: Float
    )

    private const val SW = 1280
    private const val SH = 720

    private val NAME1 = intArrayOf(655, 165, 800, 191)
    private val PTS1  = intArrayOf(795, 165, 850, 191)
    private val NAME2 = intArrayOf(655, 194, 800, 218)
    private val PTS2  = intArrayOf(795, 194, 850, 218)
    private val SLOT_CELLS = intArrayOf(580, 604, 628)
    private const val SLOT_Y = 224
    private val SLOT_CROP = intArrayOf(-1, -2, 17, 18)

    private const val SKILL_MIN = 0.50f
    private const val SKILL_MARGIN = 0.03f
    private const val DIGIT_MIN = 0.45f
    private const val SLOT_MIN = 0.42f

    private data class Gray(val w: Int, val h: Int, val px: IntArray)

    @Volatile private var loaded = false
    private var skillTmpls: List<Pair<Int, Gray>> = emptyList()
    private var digitTmpls: List<Pair<Int, Gray>> = emptyList()
    private var circleTmpl: Gray? = null
    private var dashTmpl: Gray? = null

    fun isSwitchCapture(bmp: Bitmap): Boolean {
        if (bmp.width < 640 || bmp.height < 360) return false
        return abs(bmp.width.toFloat() / bmp.height - 16f / 9f) < 0.06f
    }

    suspend fun read(context: Context, bitmap: Bitmap): Result? = withContext(Dispatchers.Default) {
        if (!isSwitchCapture(bitmap)) return@withContext null
        ensureLoaded(context)
        if (skillTmpls.isEmpty()) {
            return@withContext Result(
                OcrCharm(-1, -1, emptyList()),
                "renkin: assets/renkin/skills が空です",
                0f
            )
        }

        val screen = scaleToScreen(bitmap)
        try {
            val name1 = cropAbs(screen, NAME1, 2) ?: return@withContext null
            val pts1  = cropAbs(screen, PTS1, 2) ?: return@withContext null
            val name2 = cropAbs(screen, NAME2, 2)
            val pts2  = cropAbs(screen, PTS2, 2)

            val (s1Id, s1Score) = matchSkill(name1)
            val (p1, _) = matchPoints(pts1)
            val (s2Id, s2Score) = if (name2 != null) matchSkill(name2) else (null to 0f)
            val (p2, _) = if (pts2 != null) matchPoints(pts2) else (null to 0f)

            val skill2Valid = s2Id != null && s2Score >= SKILL_MIN && !looksLikeEmpty(name2)

            val skills = mutableListOf<OcrSkill>()
            if (s1Id != null && s1Score >= SKILL_MIN) {
                val pts = (p1 ?: 0).coerceIn(0, 20)
                skills += OcrSkill(s1Id, SKILL_NAMES.getOrElse(s1Id) { "skill$s1Id" }, pts)
            }
            if (skill2Valid && s2Id != null) {
                val pts = (p2 ?: 0).coerceIn(0, 20)
                skills += OcrSkill(s2Id, SKILL_NAMES.getOrElse(s2Id) { "skill$s2Id" }, pts)
            }

            val slots = matchSlots(screen)

            if (skills.isEmpty()) {
                return@withContext Result(
                    OcrCharm(-1, slots, emptyList()),
                    "renkin-fail s1=%.2f s2=%.2f n=%d".format(s1Score, s2Score, skillTmpls.size),
                    0.2f
                )
            }

            val conf = when {
                skills.size >= 2 && slots in 0..3 && s1Score >= 0.7f -> 0.97f
                skills.size >= 1 && slots in 0..3 -> 0.90f
                skills.size >= 1 -> 0.80f
                else -> 0.5f
            }
            val note = "renkin ${screen.width}x${screen.height} " +
                skills.joinToString("+") { "${it.name}+${it.pts}" } +
                " sl=$slots (s1=%.2f".format(s1Score) +
                (if (skill2Valid) " s2=%.2f".format(s2Score) else "") + ")"

            Result(OcrCharm(-1, slots, skills), note, conf)
        } finally {
            if (screen !== bitmap) screen.recycle()
        }
    }

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val am = context.assets
            fun loadPngs(dir: String): List<Pair<String, Gray>> {
                val names = runCatching { am.list(dir)?.toList().orEmpty() }.getOrDefault(emptyList())
                return names.filter { it.endsWith(".png", true) }.mapNotNull { n ->
                    runCatching {
                        am.open("$dir/$n").use { ins ->
                            val bmp = BitmapFactory.decodeStream(ins) ?: return@runCatching null
                            val g = toMaxChannel(bmp)
                            bmp.recycle()
                            n.substringBeforeLast('.') to g
                        }
                    }.getOrNull()
                }
            }
            skillTmpls = loadPngs("renkin/skills").mapNotNull { (n, g) ->
                n.toIntOrNull()?.let { it to g }
            }.sortedBy { it.first }
            digitTmpls = loadPngs("renkin/digits").mapNotNull { (n, g) ->
                n.toIntOrNull()?.let { it to g }
            }
            val slots = loadPngs("renkin/slots")
            circleTmpl = slots.find { it.first.equals("circle", true) }?.second
            dashTmpl = slots.find { it.first.equals("dash", true) }?.second
            loaded = true
        }
    }

    private fun matchSkill(roi: Gray): Pair<Int?, Float> {
        var bestId: Int? = null
        var best = -1f
        var second = -1f
        for ((id, tmpl) in skillTmpls) {
            for (sc in floatArrayOf(0.95f, 0.97f, 1.0f, 1.03f, 1.05f)) {
                val scaled = scaleGray(tmpl, sc) ?: continue
                if (scaled.w > roi.w || scaled.h > roi.h) continue
                val s = nccBest(roi, scaled)
                when {
                    s > best -> { second = best; best = s; bestId = id }
                    s > second -> second = s
                }
            }
        }
        if (best < SKILL_MIN) return null to best
        if (best - second < SKILL_MARGIN && best < 0.72f) return null to best
        return bestId to best
    }

    private fun matchPoints(roi: Gray): Pair<Int?, Float> {
        if (digitTmpls.isEmpty()) return null to 0f
        var bestN: Int? = null
        var best = -1f
        for ((n, tmpl) in digitTmpls) {
            for (sc in floatArrayOf(0.9f, 1.0f, 1.1f, 1.25f, 1.4f)) {
                val scaled = scaleGray(tmpl, sc) ?: continue
                if (scaled.w > roi.w || scaled.h > roi.h) continue
                val s = nccBest(roi, scaled)
                if (s > best) {
                    best = s
                    bestN = n
                }
            }
        }
        return if (best >= DIGIT_MIN) bestN to best else null to best
    }

    private fun matchSlots(screen: Bitmap): Int {
        val circle = circleTmpl ?: return -1
        val dash = dashTmpl ?: return -1
        var count = 0
        for (cx in SLOT_CELLS) {
            val l = (cx + SLOT_CROP[0] - 3).coerceIn(0, SW - 2)
            val t = (SLOT_Y + SLOT_CROP[1] - 3).coerceIn(0, SH - 2)
            val r = (cx + SLOT_CROP[2] + 3).coerceIn(l + 1, SW)
            val b = (SLOT_Y + SLOT_CROP[3] + 3).coerceIn(t + 1, SH)
            val roi = cropAbs(screen, intArrayOf(l, t, r, b), 0) ?: continue
            var bestC = -1f
            var bestD = -1f
            for (sc in floatArrayOf(0.85f, 1.0f, 1.15f)) {
                scaleGray(circle, sc)?.let {
                    if (it.w <= roi.w && it.h <= roi.h) bestC = max(bestC, nccBest(roi, it))
                }
                scaleGray(dash, sc)?.let {
                    if (it.w <= roi.w && it.h <= roi.h) bestD = max(bestD, nccBest(roi, it))
                }
            }
            if (bestC >= SLOT_MIN && bestC > bestD + 0.04f) count++
        }
        return count.coerceIn(0, 3)
    }

    private fun looksLikeEmpty(roi: Gray?): Boolean {
        if (roi == null) return true
        var bright = 0
        for (v in roi.px) if (v > 140) bright++
        return bright.toFloat() / roi.px.size < 0.08f
    }

    private fun scaleToScreen(src: Bitmap): Bitmap {
        if (src.width == SW && src.height == SH) return src
        val out = Bitmap.createBitmap(SW, SH, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, null, Rect(0, 0, SW, SH), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun toMaxChannel(bmp: Bitmap): Gray {
        val w = bmp.width
        val h = bmp.height
        val argb = IntArray(w * h)
        bmp.getPixels(argb, 0, w, 0, 0, w, h)
        val px = IntArray(w * h)
        for (i in argb.indices) {
            val c = argb[i]
            px[i] = max((c shr 16) and 0xFF, max((c shr 8) and 0xFF, c and 0xFF))
        }
        return stretch(Gray(w, h, px))
    }

    private fun stretch(g: Gray): Gray {
        var lo = 255
        var hi = 0
        for (v in g.px) {
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        val range = (hi - lo).coerceAtLeast(1)
        val out = IntArray(g.px.size)
        for (i in g.px.indices) out[i] = ((g.px[i] - lo) * 255 / range).coerceIn(0, 255)
        return Gray(g.w, g.h, out)
    }

    private fun cropAbs(bmp: Bitmap, r: IntArray, pad: Int): Gray? {
        val l = (r[0] - pad).coerceIn(0, bmp.width - 2)
        val t = (r[1] - pad).coerceIn(0, bmp.height - 2)
        val rr = (r[2] + pad).coerceIn(l + 1, bmp.width)
        val b = (r[3] + pad).coerceIn(t + 1, bmp.height)
        val w = rr - l
        val h = b - t
        if (w < 2 || h < 2) return null
        val sub = Bitmap.createBitmap(bmp, l, t, w, h)
        val g = toMaxChannel(sub)
        sub.recycle()
        return g
    }

    private fun scaleGray(src: Gray, scale: Float): Gray? {
        if (scale < 0.5f || scale > 2.5f) return null
        val nw = (src.w * scale).toInt().coerceAtLeast(1)
        val nh = (src.h * scale).toInt().coerceAtLeast(1)
        if (nw == src.w && nh == src.h) return src
        val out = IntArray(nw * nh)
        for (y in 0 until nh) {
            val sy = min(src.h - 1, (y / scale).toInt())
            for (x in 0 until nw) {
                val sx = min(src.w - 1, (x / scale).toInt())
                out[y * nw + x] = src.px[sy * src.w + sx]
            }
        }
        return Gray(nw, nh, out)
    }

    /** OpenCV TM_CCOEFF_NORMED 相当 */
    private fun nccBest(image: Gray, tmpl: Gray): Float {
        if (tmpl.w > image.w || tmpl.h > image.h) return -1f
        val tw = tmpl.w
        val th = tmpl.h
        val tN = (tw * th).toDouble()
        var tSum = 0.0
        for (v in tmpl.px) tSum += v
        val tMean = tSum / tN
        var tVar = 0.0
        for (v in tmpl.px) {
            val d = v - tMean
            tVar += d * d
        }
        if (tVar < 1e-6) return -1f
        val tNorm = sqrt(tVar)

        var best = -1f
        val maxY = image.h - th
        val maxX = image.w - tw
        val stepY = max(1, th / 6)
        val stepX = max(1, tw / 8)

        fun scoreAt(x: Int, y: Int): Float {
            var iSum = 0.0
            for (ty in 0 until th) {
                val row = (y + ty) * image.w + x
                for (tx in 0 until tw) iSum += image.px[row + tx]
            }
            val iMean = iSum / tN
            var cross = 0.0
            var iVar = 0.0
            var ti = 0
            for (ty in 0 until th) {
                val row = (y + ty) * image.w + x
                for (tx in 0 until tw) {
                    val iv = image.px[row + tx] - iMean
                    val tv = tmpl.px[ti] - tMean
                    cross += iv * tv
                    iVar += iv * iv
                    ti++
                }
            }
            if (iVar < 1e-6) return -1f
            return (cross / (sqrt(iVar) * tNorm)).toFloat()
        }

        var y = 0
        while (y <= maxY) {
            var x = 0
            while (x <= maxX) {
                val s = scoreAt(x, y)
                if (s > best) best = s
                x += stepX
            }
            y += stepY
        }
        if (best > 0.35f) {
            y = 0
            while (y <= maxY) {
                var x = 0
                while (x <= maxX) {
                    val s = scoreAt(x, y)
                    if (s > best) best = s
                    x += max(1, stepX / 2)
                }
                y += max(1, stepY / 2)
            }
        }
        return best
    }
}
