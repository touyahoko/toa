package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mhxxtools.mhxxrngtool.rng.SKILL_NAMES
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * assets/charm_templates の切り出しPNGだけで鑑定画面を読む（OCRなし）。
 * Switch 1200×675 は固定座標を優先。
 */
object CharmTemplateReader {

    data class ReadResult(val charm: OcrCharm, val note: String)

    private data class Tmpl(val name: String, val gray: Array<IntArray>)

    @Volatile private var loaded = false
    private var skillTmpls: List<Tmpl> = emptyList()
    private var digitTmpls: List<Pair<Int, Array<IntArray>>> = emptyList()
    private var slotTmpls: List<Pair<Int, Array<IntArray>>> = emptyList()

    private val FIXED_NAME1 = Rect(618, 150, 740, 180)
    private val FIXED_PTS1 = Rect(738, 150, 800, 180)
    private val FIXED_NAME2 = Rect(618, 178, 740, 208)
    private val FIXED_PTS2 = Rect(738, 178, 800, 208)
    private val FIXED_SLOT = Rect(500, 204, 680, 232)

    suspend fun read(context: Context, bitmap: Bitmap): Result<ReadResult> = withContext(Dispatchers.Default) {
        runCatching {
            ensureLoaded(context)
            if (skillTmpls.isEmpty()) {
                error("テンプレが assets にありません (charm_templates/skills)")
            }
            if (bitmap.width in 1180..1220 && bitmap.height in 660..690) {
                val r = matchFixed(bitmap)
                if (r.charm.skills.isNotEmpty()) return@runCatching r
            }
            val work = downscale(bitmap, 960)
            val panel = findPanel(work) ?: Rect(
                (work.width * 0.30f).toInt(),
                (work.height * 0.10f).toInt(),
                (work.width * 0.70f).toInt(),
                (work.height * 0.45f).toInt()
            )
            val crop = safeCrop(work, panel)
            val gray = toGray(crop)
            val h = gray.size
            val w = gray[0].size
            val row1 = band(gray, (h * 0.18f).toInt(), (h * 0.42f).toInt())
            val row2 = band(gray, (h * 0.38f).toInt(), (h * 0.62f).toInt())
            val rowSlot = band(gray, (h * 0.55f).toInt(), (h * 0.80f).toInt())
            val nameW = (w * 0.72f).toInt()
            val ptsL = (w * 0.58f).toInt()
            val s1 = matchSkill(sub(row1, 0, nameW))
            val s2 = matchSkill(sub(row2, 0, nameW))
            val p1 = matchPoints(sub(row1, ptsL, w - ptsL))
            val p2 = matchPoints(sub(row2, ptsL, w - ptsL))
            val slots = matchSlots(rowSlot)
            val skills = buildSkills(s1, p1, s2, p2)
            val note = "panel ${panel.width()}x${panel.height()} assets s=${skillTmpls.size} d=${digitTmpls.size} sl=${slotTmpls.size} s1=${s1?.second ?: "?"}($p1) s2=${s2?.second ?: "?"}($p2) slot=$slots"
            if (work !== bitmap) work.recycle()
            crop.recycle()
            ReadResult(OcrCharm(-1, slots, skills), note)
        }
    }

    private fun matchFixed(bitmap: Bitmap): ReadResult {
        val s1 = matchSkill(toGray(safeCrop(bitmap, FIXED_NAME1)))
        val s2 = matchSkill(toGray(safeCrop(bitmap, FIXED_NAME2)))
        val p1 = matchPoints(toGray(safeCrop(bitmap, FIXED_PTS1)))
        val p2 = matchPoints(toGray(safeCrop(bitmap, FIXED_PTS2)))
        val slots = matchSlots(toGray(safeCrop(bitmap, FIXED_SLOT)))
        val skills = buildSkills(s1, p1, s2, p2)
        val note = "fixed1200 s1=${s1?.second ?: "?"}($p1) s2=${s2?.second ?: "?"}($p2) slot=$slots"
        return ReadResult(OcrCharm(-1, slots, skills), note)
    }

    private fun buildSkills(
        s1: Pair<Int, String>?,
        p1: Int?,
        s2: Pair<Int, String>?,
        p2: Int?
    ): List<OcrSkill> {
        val skills = mutableListOf<OcrSkill>()
        if (s1 != null && (p1 ?: 0) > 0) skills += OcrSkill(s1.first, s1.second, p1!!)
        else if (s1 != null) skills += OcrSkill(s1.first, s1.second, p1 ?: 0)
        if (s2 != null && (p2 ?: 0) > 0) skills += OcrSkill(s2.first, s2.second, p2!!)
        return skills
    }

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            skillTmpls = loadDir(context, "charm_templates/skills").map { (name, g) ->
                Tmpl(name.removeSuffix(".png"), g)
            }
            digitTmpls = loadDir(context, "charm_templates/digits").mapNotNull { (name, g) ->
                val n = name.removePrefix("plus").removeSuffix(".png").toIntOrNull() ?: return@mapNotNull null
                n to g
            }
            slotTmpls = loadDir(context, "charm_templates/slots").mapNotNull { (name, g) ->
                val n = name.removePrefix("slot").removeSuffix(".png").toIntOrNull() ?: return@mapNotNull null
                n to g
            }
            loaded = true
        }
    }

    private fun loadDir(context: Context, dir: String): List<Pair<String, Array<IntArray>>> {
        val names = context.assets.list(dir).orEmpty()
        return names.mapNotNull { name ->
            if (!name.endsWith(".png", ignoreCase = true)) return@mapNotNull null
            val bmp = context.assets.open("$dir/$name").use { BitmapFactory.decodeStream(it) }
                ?: return@mapNotNull null
            val g = toGray(bmp)
            bmp.recycle()
            name to g
        }
    }

    private fun matchSkill(region: Array<IntArray>): Pair<Int, String>? {
        if (region.isEmpty() || skillTmpls.isEmpty()) return null
        var bestName = ""
        var best = 0.28f
        for (t in skillTmpls) {
            val s = ncc(region, t.gray)
            if (s > best) {
                best = s
                bestName = t.name
            }
        }
        if (bestName.isEmpty()) return null
        val idx = SKILL_NAMES.indexOfFirst { it.replace("　", "").trim() == bestName }
        if (idx < 0) return null
        return idx to bestName
    }

    private fun matchPoints(region: Array<IntArray>): Int? {
        if (region.isEmpty() || digitTmpls.isEmpty()) return null
        var bestN: Int? = null
        var best = 0.26f
        for ((n, g) in digitTmpls) {
            val s = ncc(region, g)
            if (s > best) {
                best = s
                bestN = n
            }
        }
        return bestN
    }

    private fun matchSlots(region: Array<IntArray>): Int {
        if (region.isEmpty() || slotTmpls.isEmpty()) return -1
        var bestN = -1
        var best = 0.22f
        for ((n, g) in slotTmpls) {
            val s = ncc(region, g)
            if (s > best) {
                best = s
                bestN = n
            }
        }
        return bestN
    }

    private fun safeCrop(src: Bitmap, r: Rect): Bitmap {
        val left = r.left.coerceIn(0, src.width - 1)
        val top = r.top.coerceIn(0, src.height - 1)
        val right = r.right.coerceIn(left + 1, src.width)
        val bottom = r.bottom.coerceIn(top + 1, src.height)
        return Bitmap.createBitmap(src, left, top, right - left, bottom - top)
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

    private fun findPanel(bmp: Bitmap): Rect? {
        val w = bmp.width
        val h = bmp.height
        val step = max(4, min(w, h) / 80)
        var bestScore = 0
        var best: Rect? = null
        var y = h / 20
        val y1 = h * 55 / 100
        while (y < y1) {
            var x = w / 8
            val x1 = w * 7 / 8
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
        for (y in 0 until h) for (x in 0 until w) {
            val c = src.getPixel(x, y)
            out[y][x] = (Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100
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

    private fun ncc(image: Array<IntArray>, tmpl: Array<IntArray>): Float {
        if (image.isEmpty() || tmpl.isEmpty()) return -1f
        val ih = image.size
        val iw = image[0].size
        var th = tmpl.size
        var tw = tmpl[0].size
        var use = tmpl
        if (th > ih || tw > iw) {
            val scale = min(ih.toFloat() / th, iw.toFloat() / tw)
            if (scale < 0.25f) return -1f
            val sth = max(6, (th * scale).roundToInt())
            val stw = max(6, (tw * scale).roundToInt())
            use = Array(sth) { y ->
                IntArray(stw) { x ->
                    tmpl[(y * th / sth).coerceIn(0, th - 1)][(x * tw / stw).coerceIn(0, tw - 1)]
                }
            }
            th = sth
            tw = stw
        }
        val maxY = ih - th
        val maxX = iw - tw
        if (maxY < 0 || maxX < 0) return -1f
        val n = th * tw
        var tSum = 0L
        for (y in 0 until th) for (x in 0 until tw) tSum += use[y][x]
        val tMean = tSum.toDouble() / n
        var tVar = 0.0
        for (y in 0 until th) for (x in 0 until tw) {
            val d = use[y][x] - tMean
            tVar += d * d
        }
        if (tVar < 1e-3) return -1f
        var best = -1f
        val step = max(1, min(maxX, maxY).coerceAtLeast(1) / 8)
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
                    num += id * (use[y][x] - tMean)
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
