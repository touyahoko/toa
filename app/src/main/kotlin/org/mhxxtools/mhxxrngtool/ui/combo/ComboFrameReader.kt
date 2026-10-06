package org.mhxxtools.mhxxrngtool.ui.combo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect as AndroidRect
import kotlin.math.max

/**
 * mhxx-combo-scan combo-core/src/read.rs の完全移植。
 *
 * 本家同様:
 * - 前提解像度 1280×720 (他解像度はここに正規化してから読む)
 * - ROI (513,49,321,221) だけを切り出してテンプレート照合
 * - ±1px シフトごとに窓を二値化して最良一致を取る
 */
object ComboFrameReader {

    const val CAP = 99
    const val SOURCE_W = 1280
    const val SOURCE_H = 720

    val ROI_X = 513
    val ROI_Y = 49
    val ROI_W = 321
    val ROI_H = 221

    private const val MIN_CONTRAST = 50f
    private const val MAX_DIST = 0.20
    private const val MAX_DIST_MARK = 0.15

    data class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {
        fun offset(dx: Int, dy: Int) = Rect(x + dx, y + dy, width, height)
    }

    data class FrameReading(
        val t: Double,
        val crafting: Boolean,
        val material1: Int?,
        val material2: Int?,
        val product: Int?,
        val done: Boolean = false
    ) {
        companion object {
            fun notCrafting(t: Double) = FrameReading(t, false, null, null, null, false)
        }
    }

    private val BOX = Rect(760, 242, 85, 32)
    private val HEADER_RECT = Rect(514, 50, 82, 27)
    private val PROD_SLOTS = arrayOf(
        Rect(12, 3, 15, 24).offset(BOX.x, BOX.y),
        Rect(26, 3, 15, 24).offset(BOX.x, BOX.y)
    )
    private val SLASH_RECT = Rect(40, 3, 12, 24).offset(BOX.x, BOX.y)
    private val MAT_SLOTS = arrayOf(
        arrayOf(Rect(802, 97, 15, 24), Rect(818, 97, 15, 24)),
        arrayOf(Rect(802, 165, 15, 24), Rect(818, 165, 15, 24))
    )

    /**
     * 任意解像度の Bitmap を受け取り、1280×720 に正規化してから読む。
     * 呼び出し側で frame を recycle すること (正規化コピーは内部で解放)。
     */
    fun readFrame(t: Double, frame: Bitmap): FrameReading {
        val normalized = normalizeToSource(frame)
        val owned = normalized !== frame
        return try {
            readNormalized(t, normalized)
        } finally {
            if (owned) normalized.recycle()
        }
    }

    private fun readNormalized(t: Double, frame: Bitmap): FrameReading {
        val roi = extractRoiBrightness(frame)
        if (!isCrafting(roi)) {
            return FrameReading.notCrafting(t)
        }
        val product = readNumber(roi, PROD_SLOTS)
        return FrameReading(
            t = t,
            crafting = true,
            material1 = readNumber(roi, MAT_SLOTS[0]),
            material2 = readNumber(roi, MAT_SLOTS[1]),
            product = product,
            done = false
        )
    }

    fun reachedCap(product: Int?, seenBelow: BooleanArray): Boolean {
        return when {
            product == null -> false
            product < CAP -> {
                seenBelow[0] = true
                false
            }
            else -> seenBelow[0]
        }
    }

    private fun normalizeToSource(frame: Bitmap): Bitmap {
        if (frame.width == SOURCE_W && frame.height == SOURCE_H) return frame
        val out = Bitmap.createBitmap(SOURCE_W, SOURCE_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(
            frame,
            AndroidRect(0, 0, frame.width, frame.height),
            AndroidRect(0, 0, SOURCE_W, SOURCE_H),
            null
        )
        return out
    }

    private fun extractRoiBrightness(frame: Bitmap): IntArray {
        val w = ROI_W
        val h = ROI_H
        val pixels = IntArray(w * h)
        val x = ROI_X.coerceIn(0, frame.width - w)
        val y = ROI_Y.coerceIn(0, frame.height - h)
        frame.getPixels(pixels, 0, w, x, y, w, h)
        val out = IntArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            out[i] = max(r, max(g, b))
        }
        return out
    }

    private fun extractPatchFromRoi(roi: IntArray, rect: Rect): IntArray {
        val pad = 1
        val w = rect.width + 2 * pad
        val h = rect.height + 2 * pad
        val out = IntArray(w * h)
        val ox = rect.x - ROI_X - pad
        val oy = rect.y - ROI_Y - pad
        for (row in 0 until h) {
            val sy = (oy + row).coerceIn(0, ROI_H - 1)
            for (col in 0 until w) {
                val sx = (ox + col).coerceIn(0, ROI_W - 1)
                out[row * w + col] = roi[sy * ROI_W + sx]
            }
        }
        return out
    }

    private fun isCrafting(roi: IntArray): Boolean {
        val pairs = listOf(
            HEADER_RECT to ComboTemplates.HEADER,
            SLASH_RECT to ComboTemplates.SLASH
        )
        for ((rect, tpl) in pairs) {
            val patch = extractPatchFromRoi(roi, rect)
            val (_, dist) = bestMatch(patch, rect.width, rect.height, listOf(tpl))
            if (dist > MAX_DIST_MARK) return false
        }
        return true
    }

    private fun readNumber(roi: IntArray, slots: Array<Rect>): Int? {
        if (slots.size < 2) return null
        fun isBlank(patch: IntArray): Boolean {
            val lo = patch.minOrNull() ?: 0
            val hi = patch.maxOrNull() ?: 0
            return hi - lo < MIN_CONTRAST
        }
        val tensPatch = extractPatchFromRoi(roi, slots[0])
        val onesPatch = extractPatchFromRoi(roi, slots[1])
        if (isBlank(onesPatch)) return null
        val (onesIdx, onesDist) = bestMatch(
            onesPatch, slots[1].width, slots[1].height, ComboTemplates.DIGITS.toList()
        )
        if (onesDist > MAX_DIST || onesIdx < 0) return null
        if (isBlank(tensPatch)) return onesIdx
        val (tensIdx, tensDist) = bestMatch(
            tensPatch, slots[0].width, slots[0].height, ComboTemplates.DIGITS.toList()
        )
        if (tensDist > MAX_DIST || tensIdx < 0) return null
        return tensIdx * 10 + onesIdx
    }

    private fun bestMatch(
        patch: IntArray,
        tw: Int,
        th: Int,
        templates: List<ComboTemplates.Template>
    ): Pair<Int, Double> {
        val pw = tw + 2
        if (patch.size != pw * (th + 2)) return -1 to 1.0

        var bestIdx = -1
        var bestDist = 1.0
        var secondDist = 1.0
        val total = (tw * th).toDouble()
        val buf = BooleanArray(tw * th)

        for (dy in 0..2) {
            for (dx in 0..2) {
                var lo = 255
                var hi = 0
                for (row in 0 until th) {
                    val base = (dy + row) * pw + dx
                    for (col in 0 until tw) {
                        val v = patch[base + col]
                        if (v < lo) lo = v
                        if (v > hi) hi = v
                    }
                }
                if (hi - lo < MIN_CONTRAST) continue
                val thr = (lo + hi) / 2f
                for (row in 0 until th) {
                    val base = (dy + row) * pw + dx
                    for (col in 0 until tw) {
                        buf[row * tw + col] = patch[base + col] > thr
                    }
                }
                for ((ti, tpl) in templates.withIndex()) {
                    if (tpl.w != tw || tpl.h != th) continue
                    var mismatch = 0
                    for (i in 0 until tw * th) {
                        if (buf[i] != tpl.bits[i]) mismatch++
                    }
                    val dist = mismatch / total
                    if (dist < bestDist) {
                        secondDist = bestDist
                        bestDist = dist
                        bestIdx = ti
                    } else if (dist < secondDist) {
                        secondDist = dist
                    }
                }
            }
        }
        return bestIdx to bestDist
    }
}
