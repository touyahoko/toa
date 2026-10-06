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
    /**
     * すでに切り出した ROI 輝度 (公式 321×221) から読む。連続デコード用。
     */
    fun readRoi(t: Double, roi: IntArray): FrameReading {
        if (roi.size != ROI_W * ROI_H) return FrameReading.notCrafting(t)
        if (!isCrafting(roi)) return FrameReading.notCrafting(t)
        return FrameReading(
            t = t,
            crafting = true,
            material1 = readNumber(roi, MAT_SLOTS[0]),
            material2 = readNumber(roi, MAT_SLOTS[1]),
            product = readNumber(roi, PROD_SLOTS),
            done = false
        )
    }

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
        // 録画の余白・再エンコードで公式座標から数pxずれる。見出しを探してオフセットする。
        val (dx, dy) = findHeaderOffset(frame)
        val roi = extractRoiBrightness(frame, dx, dy)
        if (!isCrafting(roi, dx, dy)) {
            return FrameReading.notCrafting(t)
        }
        val product = readNumber(roi, PROD_SLOTS, dx, dy)
        return FrameReading(
            t = t,
            crafting = true,
            material1 = readNumber(roi, MAT_SLOTS[0], dx, dy),
            material2 = readNumber(roi, MAT_SLOTS[1], dx, dy),
            product = product,
            done = false
        )
    }

    /** 「調合素材」見出しが公式座標からどれだけずれているか。±12px */
    private fun findHeaderOffset(frame: Bitmap): Pair<Int, Int> {
        val tpl = ComboTemplates.HEADER
        var best = 0 to 0
        var bestDist = 1.0
        val pad = 1
        for (dy in -12..12 step 2) {
            for (dx in -12..12 step 2) {
                val rect = HEADER_RECT
                val patch = extractPatchAbs(frame, rect.x + dx, rect.y + dy, rect.width, rect.height)
                val (_, dist) = bestMatch(patch, rect.width, rect.height, listOf(tpl))
                if (dist < bestDist) {
                    bestDist = dist
                    best = dx to dy
                }
            }
        }
        if (bestDist > MAX_DIST_MARK) return 0 to 0
        // 近傍を1px精度で詰め直す
        val (bx, by) = best
        var fine = best
        var fineDist = bestDist
        for (dy in (by - 2)..(by + 2)) {
            for (dx in (bx - 2)..(bx + 2)) {
                val rect = HEADER_RECT
                val patch = extractPatchAbs(frame, rect.x + dx, rect.y + dy, rect.width, rect.height)
                val (_, dist) = bestMatch(patch, rect.width, rect.height, listOf(tpl))
                if (dist < fineDist) {
                    fineDist = dist
                    fine = dx to dy
                }
            }
        }
        return fine
    }

    private fun extractPatchAbs(frame: Bitmap, x: Int, y: Int, w: Int, h: Int): IntArray {
        val pad = 1
        val pw = w + 2 * pad
        val ph = h + 2 * pad
        val out = IntArray(pw * ph)
        for (row in 0 until ph) {
            val sy = (y - pad + row).coerceIn(0, frame.height - 1)
            for (col in 0 until pw) {
                val sx = (x - pad + col).coerceIn(0, frame.width - 1)
                val c = frame.getPixel(sx, sy)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                out[row * pw + col] = max(r, max(g, b))
            }
        }
        return out
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

    private fun extractRoiBrightness(frame: Bitmap, dx: Int = 0, dy: Int = 0): IntArray {
        val w = ROI_W
        val h = ROI_H
        val pixels = IntArray(w * h)
        val x = (ROI_X + dx).coerceIn(0, frame.width - w)
        val y = (ROI_Y + dy).coerceIn(0, frame.height - h)
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

    private fun extractPatchFromRoi(roi: IntArray, rect: Rect, dx: Int = 0, dy: Int = 0): IntArray {
        val pad = 1
        val w = rect.width + 2 * pad
        val h = rect.height + 2 * pad
        val out = IntArray(w * h)
        val ox = rect.x + dx - (ROI_X + dx) - pad
        val oy = rect.y + dy - (ROI_Y + dy) - pad
        for (row in 0 until h) {
            val sy = (oy + row).coerceIn(0, ROI_H - 1)
            for (col in 0 until w) {
                val sx = (ox + col).coerceIn(0, ROI_W - 1)
                out[row * w + col] = roi[sy * ROI_W + sx]
            }
        }
        return out
    }

    private fun isCrafting(roi: IntArray, dx: Int = 0, dy: Int = 0): Boolean {
        val pairs = listOf(
            HEADER_RECT to ComboTemplates.HEADER,
            SLASH_RECT to ComboTemplates.SLASH
        )
        for ((rect, tpl) in pairs) {
            val patch = extractPatchFromRoi(roi, rect, dx, dy)
            val (_, dist) = bestMatch(patch, rect.width, rect.height, listOf(tpl))
            if (dist > MAX_DIST_MARK) return false
        }
        return true
    }

    /**
     * 本家 read_number。
     * 空白判定はパッチ全体ではなく、ずらさない窓 (dx=1,dy=1) の明暗差だけ。
     * ここをパッチ全体にすると 0 や一桁の 8 が空白扱いになり、公式テスト 721 枚と不一致になる。
     */
    private fun readNumber(roi: IntArray, slots: Array<Rect>, dx: Int = 0, dy: Int = 0): Int? {
        if (slots.size < 2) return null
        fun isBlankSlot(patch: IntArray, w: Int, h: Int): Boolean {
            val pw = w + 2
            var lo = 255
            var hi = 0
            for (row in 0 until h) {
                val base = (1 + row) * pw + 1
                for (col in 0 until w) {
                    val v = patch[base + col]
                    if (v < lo) lo = v
                    if (v > hi) hi = v
                }
            }
            return hi - lo < MIN_CONTRAST
        }
        val tensPatch = extractPatchFromRoi(roi, slots[0], dx, dy)
        val onesPatch = extractPatchFromRoi(roi, slots[1], dx, dy)
        val digits = ComboTemplates.DIGITS.toList()
        val ones = if (isBlankSlot(onesPatch, slots[1].width, slots[1].height)) {
            null
        } else {
            val (idx, dist) = bestMatch(onesPatch, slots[1].width, slots[1].height, digits)
            if (dist > MAX_DIST || idx < 0) return null
            idx
        }
        if (ones == null) return null
        if (isBlankSlot(tensPatch, slots[0].width, slots[0].height)) return ones
        val (tensIdx, tensDist) = bestMatch(tensPatch, slots[0].width, slots[0].height, digits)
        if (tensDist > MAX_DIST || tensIdx < 0) return null
        return tensIdx * 10 + ones
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
