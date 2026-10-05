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
     * 本家 Patch::new 準拠: rect ±1px の明るさ (max R,G,B)。
     * 1280×720 座標系の各ピクセルを sx/sy で実フレームに最近傍マッピング。
     */
    private fun extractPatch(frame: Bitmap, rect: Rect, sx: Float, sy: Float): IntArray {
        val pad = 1
        val w = rect.width + 2 * pad
        val h = rect.height + 2 * pad
        val out = IntArray(w * h)
        val baseX = rect.x - pad
        val baseY = rect.y - pad
        for (row in 0 until h) {
            val srcY = ((baseY + row) * sy).toInt().coerceIn(0, frame.height - 1)
            for (col in 0 until w) {
                val srcX = ((baseX + col) * sx).toInt().coerceIn(0, frame.width - 1)
                val c = frame.getPixel(srcX, srcY)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                out[row * w + col] = max(r, max(g, b))
            }
        }
        return out
    }

    /**
     * 本家 best_match 準拠: ±1px の各シフトごとにその窓で二値化し、
     * 全テンプレートとの不一致率を計算して最小を返す。
     */
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
        val total = (tw * th).toDouble()
        val buf = BooleanArray(tw * th)

        for (dy in 0..2) {
            for (dx in 0..2) {
                // このシフト窓の明暗で二値化 (本家 Patch::binarize)
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
                        bestDist = dist
                        bestIdx = ti
                    }
                }
            }
        }
        return bestIdx to bestDist
    }
}
