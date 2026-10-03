package org.mhxxtools.mhxxrngtool.ui.combo

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * mhxx-combo-scan の read.rs を Kotlin 移植。
 * 1280×720 前提でテンプレート照合により素材/完成品の個数を読む。
 */
object ComboFrameReader {

    const val CAP = 99
    const val SOURCE_W = 1280
    const val SOURCE_H = 720

    // ROI that covers header + material slots + product (from read.rs)
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
     * Full-frame Bitmap (ideally 1280×720) → FrameReading.
     * Scales coordinates if resolution differs.
     */
    fun readFrame(t: Double, frame: Bitmap): FrameReading {
        val sx = frame.width.toFloat() / SOURCE_W
        val sy = frame.height.toFloat() / SOURCE_H
        if (!isCrafting(frame, sx, sy)) {
            return FrameReading.notCrafting(t)
        }
        val product = readNumber(frame, PROD_SLOTS, sx, sy)
        return FrameReading(
            t = t,
            crafting = true,
            material1 = readNumber(frame, MAT_SLOTS[0], sx, sy),
            material2 = readNumber(frame, MAT_SLOTS[1], sx, sy),
            product = product,
            done = false
        )
    }

    fun reachedCap(product: Int?, seenBelow: BooleanArray): Boolean {
        // seenBelow is a 1-element holder
        return when {
            product == null -> false
            product < CAP -> {
                seenBelow[0] = true
                false
            }
            else -> seenBelow[0]
        }
    }

    private fun isCrafting(frame: Bitmap, sx: Float, sy: Float): Boolean {
        val pairs = listOf(
            HEADER_RECT to ComboTemplates.HEADER,
            SLASH_RECT to ComboTemplates.SLASH
        )
        for ((rect, tpl) in pairs) {
            val patch = extractPatch(frame, rect, sx, sy)
            val (_, dist) = bestMatch(patch, rect.width, rect.height, listOf(tpl))
            if (dist > MAX_DIST_MARK) return false
        }
        return true
    }

    private fun readNumber(
        frame: Bitmap,
        slots: Array<Rect>,
        sx: Float,
        sy: Float
    ): Int? {
        if (slots.size < 2) return null
        fun isBlank(patch: IntArray): Boolean {
            val lo = patch.minOrNull() ?: 0
            val hi = patch.maxOrNull() ?: 0
            return hi - lo < MIN_CONTRAST
        }
        val tensPatch = extractPatch(frame, slots[0], sx, sy)
        val onesPatch = extractPatch(frame, slots[1], sx, sy)
        if (isBlank(onesPatch)) return null
        val (onesIdx, onesDist) = bestMatch(onesPatch, slots[1].width, slots[1].height, ComboTemplates.DIGITS.toList())
        if (onesDist > MAX_DIST || onesIdx < 0) return null
        if (isBlank(tensPatch)) return onesIdx
        val (tensIdx, tensDist) = bestMatch(tensPatch, slots[0].width, slots[0].height, ComboTemplates.DIGITS.toList())
        if (tensDist > MAX_DIST || tensIdx < 0) return null
        return tensIdx * 10 + onesIdx
    }

    /**
     * Extract brightness (max of R,G,B) for rect ±1px padding, then binarize relative to frame.
     * Returns flat array of size (w+2)*(h+2) as 0..255 brightness.
     */
    private fun extractPatch(frame: Bitmap, rect: Rect, sx: Float, sy: Float): IntArray {
        val pad = 1
        val w = rect.width + 2 * pad
        val h = rect.height + 2 * pad
        val out = IntArray(w * h)
        val x0 = ((rect.x - pad) * sx).toInt().coerceIn(0, frame.width - 1)
        val y0 = ((rect.y - pad) * sy).toInt().coerceIn(0, frame.height - 1)
        val x1 = ((rect.x + rect.width + pad) * sx).toInt().coerceIn(1, frame.width)
        val y1 = ((rect.y + rect.height + pad) * sy).toInt().coerceIn(1, frame.height)
        // sample into w*h grid
        for (row in 0 until h) {
            val fy = y0 + ((y1 - y0) * row / h.coerceAtLeast(1)).coerceIn(0, frame.height - 1)
            for (col in 0 until w) {
                val fx = x0 + ((x1 - x0) * col / w.coerceAtLeast(1)).coerceIn(0, frame.width - 1)
                val c = frame.getPixel(fx, fy)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                out[row * w + col] = max(r, max(g, b))
            }
        }
        return out
    }

    /**
     * Match patch against templates with ±1px shifts. Returns (bestIndex, bestDist).
     */
    private fun bestMatch(
        patch: IntArray,
        tw: Int,
        th: Int,
        templates: List<ComboTemplates.Template>
    ): Pair<Int, Double> {
        val pw = tw + 2
        val ph = th + 2
        if (patch.size != pw * ph) return -1 to 1.0

        // binarize patch using its own contrast
        val lo = patch.minOrNull()?.toFloat() ?: 0f
        val hi = patch.maxOrNull()?.toFloat() ?: 0f
        if (hi - lo < MIN_CONTRAST) return -1 to 1.0
        val thr = (lo + hi) / 2f
        val bits = BooleanArray(patch.size) { patch[it] > thr }

        var bestIdx = -1
        var bestDist = Double.MAX_VALUE
        for ((ti, tpl) in templates.withIndex()) {
            if (tpl.w != tw || tpl.h != th) continue
            for (dy in 0..2) {
                for (dx in 0..2) {
                    var mismatch = 0
                    val total = tw * th
                    for (row in 0 until th) {
                        for (col in 0 until tw) {
                            val pi = (row + dy) * pw + (col + dx)
                            val ti2 = row * tw + col
                            if (bits[pi] != tpl.bits[ti2]) mismatch++
                        }
                    }
                    val dist = mismatch.toDouble() / total
                    if (dist < bestDist) {
                        bestDist = dist
                        bestIdx = ti
                    }
                }
            }
        }
        return bestIdx to bestDist
    }
}
