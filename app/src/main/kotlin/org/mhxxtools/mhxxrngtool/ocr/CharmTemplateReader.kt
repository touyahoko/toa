package org.mhxxtools.mhxxrngtool.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mhxxtools.mhxxrngtool.rng.SKILL_NAMES
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 鑑定画面をテンプレート照合で読む（OCRなし）。
 *
 * - パネル: 紫系UI枠を探索（画角ズレ・拡大縮小に対応するため複数スケール）
 * - スキル名: SKILL_NAMES をフォント描画したテンプレと正規化相互相関
 * - ポイント: 0〜9 / + の描画テンプレ
 * - スロット: 明部の円形ブロブ数
 */
object CharmTemplateReader {

    data class ReadResult(
        val charm: OcrCharm,
        val note: String
    )

    suspend fun read(bitmap: Bitmap): Result<ReadResult> = withContext(Dispatchers.Default) {
        runCatching {
            val work = downscale(bitmap, 960)
            val panel = findPanel(work) ?: Rect(
                (work.width * 0.28f).toInt(),
                (work.height * 0.08f).toInt(),
                (work.width * 0.72f).toInt(),
                (work.height * 0.42f).toInt()
            )
            val crop = Bitmap.createBitmap(
                work,
                panel.left.coerceIn(0, work.width - 2),
                panel.top.coerceIn(0, work.height - 2),
                panel.width().coerceIn(1, work.width - panel.left),
                panel.height().coerceIn(1, work.height - panel.top)
            )
            val gray = toGray(crop)

            // 上から: 名前行 / スキル1 / スキル2 / スロット
            val h = gray.height
            val w = gray.width
            val row1 = band(gray, (h * 0.18f).toInt(), (h * 0.38f).toInt())
            val row2 = band(gray, (h * 0.38f).toInt(), (h * 0.58f).toInt())
            val rowSlot = band(gray, (h * 0.58f).toInt(), (h * 0.82f).toInt())

            // スキル名は行の左〜中央、ポイントは右端
            val nameW = (w * 0.62f).toInt()
            val ptsL = (w * 0.68f).toInt()
            val s1Name = matchSkill(sub(row1, 0, nameW))
            val s2Name = matchSkill(sub(row2, 0, nameW))
            val p1 = matchPoints(sub(row1, ptsL, w - ptsL))
            val p2 = matchPoints(sub(row2, ptsL, w - ptsL))
            val slots = countSlots(rowSlot)

            val skills = mutableListOf<OcrSkill>()
            if (s1Name != null && p1 != null) {
                skills += OcrSkill(s1Name.first, s1Name.second, p1)
            }
            if (s2Name != null && p2 != null && p2 != 0) {
                skills += OcrSkill(s2Name.first, s2Name.second, p2)
            }

            // 行全体からポイントが取れなかった場合、名前マッチだけでも候補に
            if (skills.isEmpty() && s1Name != null) {
                skills += OcrSkill(s1Name.first, s1Name.second, p1 ?: 0)
            }

            val kind = -1
            val note = buildString {
                append("template panel=${panel.width()}x${panel.height()}")
                append(" s1=${s1Name?.second ?: "?"}($p1)")
                append(" s2=${s2Name?.second ?: "?"}($p2)")
                append(" slot=$slots")
            }
            if (work !== bitmap) work.recycle()
            crop.recycle()
            ReadResult(OcrCharm(kind, slots, skills), note)
        }
    }

    private fun downscale(src: Bitmap, maxSide: Int): Bitmap {
        val m = max(src.width, src.height)
        if (m <= maxSide) return src
        val s = maxSide.toFloat() / m
        return Bitmap.createScaledBitmap(
            src,
            (src.width * s).roundToInt().coerceAtLeast(1),
            (src.height * s).roundToInt().coerceAtLeast(1),
            true
        )
    }

    /** 紫〜ピンク系の枠が多い矩形をパネル候補にする */
    private fun findPanel(bmp: Bitmap): Rect? {
        val w = bmp.width
        val h = bmp.height
        val step = max(4, min(w, h) / 80)
        var bestScore = 0
        var best: Rect? = null
        // 画面中央寄りの探索窓
        val x0 = w / 8
        val x1 = w * 7 / 8
        val y0 = h / 20
        val y1 = h * 55 / 100
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val ww = min(w * 45 / 100, x1 - x)
                val hh = min(h * 35 / 100, y1 - y)
                if (ww > 80 && hh > 50) {
                    var score = 0
                    var n = 0
                    var yy = y
                    while (yy < y + hh) {
                        var xx = x
                        while (xx < x + ww) {
                            val c = bmp.getPixel(xx, yy)
                            val r = Color.red(c)
                            val g = Color.green(c)
                            val b = Color.blue(c)
                            // 鑑定パネルの紫枠・紫背景
                            if (b > 80 && r > 60 && b > g && r > g - 20) score++
                            n++
                            xx += step
                        }
                        yy += step
                    }
                    val ratio = if (n == 0) 0 else score * 1000 / n
                    if (ratio > bestScore) {
                        bestScore = ratio
                        best = Rect(x, y, x + ww, y + hh)
                    }
                }
                x += step * 3
            }
            y += step * 3
        }
        return if (bestScore > 40) best else null
    }

    private fun toGray(src: Bitmap): Array<IntArray> {
        val w = src.width
        val h = src.height
        val out = Array(h) { IntArray(w) }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = src.getPixel(x, y)
                out[y][x] = (Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100
            }
        }
        return out
    }

    private fun band(gray: Array<IntArray>, top: Int, bottom: Int): Array<IntArray> {
        val t = top.coerceIn(0, gray.size - 1)
        val b = bottom.coerceIn(t + 1, gray.size)
        return Array(b - t) { y -> gray[t + y].copyOf() }
    }

    private fun sub(gray: Array<IntArray>, left: Int, width: Int): Array<IntArray> {
        if (gray.isEmpty()) return gray
        val w = gray[0].size
        val l = left.coerceIn(0, w - 1)
        val rw = width.coerceIn(1, w - l)
        return Array(gray.size) { y -> gray[y].copyOfRange(l, l + rw) }
    }

    private fun matchSkill(region: Array<IntArray>): Pair<Int, String>? {
        if (region.isEmpty() || region[0].isEmpty()) return null
        val rh = region.size
        val rw = region[0].size
        // テンプレ高さは領域の 55〜90%
        val th = (rh * 0.72f).roundToInt().coerceIn(12, 48)
        var bestIdx = -1
        var bestName = ""
        var bestScore = 0.42f
        for (i in SKILL_NAMES.indices) {
            val name = SKILL_NAMES[i].replace("　", "").trim()
            if (name.isEmpty() || name.length > 10) continue
            val tmpl = renderTextTemplate(name, th)
            val score = ncc(region, tmpl)
            if (score > bestScore) {
                bestScore = score
                bestIdx = i
                bestName = name
            }
        }
        return if (bestIdx >= 0) bestIdx to bestName else null
    }

    private fun matchPoints(region: Array<IntArray>): Int? {
        if (region.isEmpty() || region[0].isEmpty()) return null
        val rh = region.size
        val th = (rh * 0.75f).roundToInt().coerceIn(12, 40)
        // +N or N
        var bestPts: Int? = null
        var best = 0.40f
        for (n in 1..20) {
            for (label in listOf("+$n", n.toString(), "＋$n")) {
                val tmpl = renderTextTemplate(label, th)
                val s = ncc(region, tmpl)
                if (s > best) {
                    best = s
                    bestPts = n
                }
            }
        }
        return bestPts
    }

    private fun countSlots(region: Array<IntArray>): Int {
        if (region.isEmpty() || region[0].isEmpty()) return -1
        val h = region.size
        val w = region[0].size
        // 明度の高い画素の連結（簡易）
        val bin = Array(h) { y -> BooleanArray(w) { x -> region[y][x] > 175 } }
        var blobs = 0
        val vis = Array(h) { BooleanArray(w) }
        val qx = IntArray(w * h)
        val qy = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!bin[y][x] || vis[y][x]) continue
                var qs = 0
                var qe = 0
                qx[qe] = x; qy[qe] = y; qe++
                vis[y][x] = true
                var count = 0
                var minX = x; var maxX = x; var minY = y; var maxY = y
                while (qs < qe) {
                    val cx = qx[qs]; val cy = qy[qs]; qs++
                    count++
                    minX = min(minX, cx); maxX = max(maxX, cx)
                    minY = min(minY, cy); maxY = max(maxY, cy)
                    for (dy in -1..1) for (dx in -1..1) {
                        val nx = cx + dx; val ny = cy + dy
                        if (nx !in 0 until w || ny !in 0 until h) continue
                        if (vis[ny][nx] || !bin[ny][nx]) continue
                        vis[ny][nx] = true
                        qx[qe] = nx; qy[qe] = ny; qe++
                    }
                }
                val bw = maxX - minX + 1
                val bh = maxY - minY + 1
                // スロット○相当のサイズ・丸さ
                if (count in 12..900 && bw in 4..40 && bh in 4..40 && abs(bw - bh) <= 12) {
                    blobs++
                }
            }
        }
        return when {
            blobs <= 0 -> 0
            blobs >= 3 -> 3
            else -> blobs
        }
    }

    private fun renderTextTemplate(text: String, height: Int): Array<IntArray> {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = height * 0.85f
            typeface = Typeface.DEFAULT_BOLD
            isFakeBoldText = true
        }
        val width = max(8, (paint.measureText(text) + 4).roundToInt())
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)
        val fm = paint.fontMetrics
        val ty = height / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, 2f, ty, paint)
        val g = toGray(bmp)
        bmp.recycle()
        return g
    }

    /** 正規化相互相関（高いほど一致） */
    private fun ncc(image: Array<IntArray>, tmpl: Array<IntArray>): Float {
        if (image.isEmpty() || tmpl.isEmpty()) return -1f
        val ih = image.size
        val iw = image[0].size
        val th = tmpl.size
        val tw = tmpl[0].size
        if (th > ih || tw > iw) {
            // テンプレが大きいときは縮小
            val scale = min(ih.toFloat() / th, iw.toFloat() / tw)
            if (scale < 0.35f) return -1f
            val sth = max(6, (th * scale).roundToInt())
            val stw = max(6, (tw * scale).roundToInt())
            val st = Array(sth) { y ->
                IntArray(stw) { x ->
                    tmpl[(y * th / sth).coerceIn(0, th - 1)][(x * tw / stw).coerceIn(0, tw - 1)]
                }
            }
            return nccSame(image, st)
        }
        return nccSame(image, tmpl)
    }

    private fun nccSame(image: Array<IntArray>, tmpl: Array<IntArray>): Float {
        val ih = image.size
        val iw = image[0].size
        val th = tmpl.size
        val tw = tmpl[0].size
        val maxY = ih - th
        val maxX = iw - tw
        if (maxY < 0 || maxX < 0) return -1f
        // テンプレ平均
        var tSum = 0L
        val n = th * tw
        for (y in 0 until th) for (x in 0 until tw) tSum += tmpl[y][x]
        val tMean = tSum.toDouble() / n
        var tVar = 0.0
        for (y in 0 until th) for (x in 0 until tw) {
            val d = tmpl[y][x] - tMean
            tVar += d * d
        }
        if (tVar < 1e-3) return -1f
        var best = -1f
        val step = max(1, min(maxX, maxY) / 12)
        var oy = 0
        while (oy <= maxY) {
            var ox = 0
            while (ox <= maxX) {
                var iSum = 0L
                for (y in 0 until th) for (x in 0 until tw) iSum += image[oy + y][ox + x]
                val iMean = iSum.toDouble() / n
                var num = 0.0
                var iVar = 0.0
                for (y in 0 until th) for (x in 0 until tw) {
                    val id = image[oy + y][ox + x] - iMean
                    val td = tmpl[y][x] - tMean
                    num += id * td
                    iVar += id * id
                }
                if (iVar > 1e-3) {
                    val s = (num / kotlin.math.sqrt(iVar * tVar)).toFloat()
                    if (s > best) best = s
                }
                ox += step
            }
            oy += step
        }
        return best
    }
}
