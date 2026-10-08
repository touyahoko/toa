package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mhxxtools.mhxxrngtool.rng.SKILL_NAMES
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * assets/charm_templates だけで鑑定を読む（OCRなし・固定座標なし）。
 * 1) パネルっぽい領域を探す 2) その中でスキル/ポイント/スロットをテンプレ照合
 */
object CharmTemplateReader {

    data class ReadResult(val charm: OcrCharm, val note: String)

    private data class Tmpl(val name: String, val gray: Array<IntArray>)
    private data class Hit(
        val name: String,
        val score: Float,
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int
    )

    @Volatile private var loaded = false
    private var skillTmpls: List<Tmpl> = emptyList()
    private var digitTmpls: List<Pair<Int, Array<IntArray>>> = emptyList()
    private var slotTmpls: List<Pair<Int, Array<IntArray>>> = emptyList()

    suspend fun read(context: Context, bitmap: Bitmap): Result<ReadResult> = withContext(Dispatchers.Default) {
        runCatching {
            ensureLoaded(context)
            if (skillTmpls.isEmpty()) {
                error("テンプレが assets にありません (charm_templates/skills)")
            }

            val work = downscale(bitmap, 720)
            val fullGray = toGray(work)

            // パネル領域（見つからなければ中央寄り）
            val panel = findPanel(work) ?: Rect(
                (work.width * 0.08f).toInt(),
                (work.height * 0.12f).toInt(),
                (work.width * 0.92f).toInt(),
                (work.height * 0.62f).toInt()
            )
            val crop = safeCrop(work, panel)
            val gray = toGray(crop)

            val skillHits = findSkillHits(gray)
            if (skillHits.isEmpty()) {
                if (work !== bitmap) work.recycle()
                crop.recycle()
                return@runCatching ReadResult(
                    OcrCharm(-1, -1, emptyList()),
                    "no-skill panel=${panel.width()}x${panel.height()} assets s=${skillTmpls.size}"
                )
            }

            val ordered = skillHits.sortedBy { it.y }
            val s1Hit = ordered.first()
            val s2Hit = ordered.drop(1).firstOrNull {
                it.y > s1Hit.y + s1Hit.h / 3 && abs(it.x - s1Hit.x) < s1Hit.w
            } ?: ordered.drop(1).firstOrNull { it.y > s1Hit.y + s1Hit.h / 3 }

            val s1 = toSkillPair(s1Hit.name)
            val s2 = s2Hit?.let { toSkillPair(it.name) }

            val p1 = matchPointsNear(gray, s1Hit)
            val p2 = s2Hit?.let { matchPointsNear(gray, it) }

            val slotY = (s2Hit?.y ?: s1Hit.y) + (s2Hit?.h ?: s1Hit.h)
            val slots = matchSlotsBelow(gray, slotY, min(s1Hit.x, s2Hit?.x ?: s1Hit.x))

            val skills = buildSkills(s1, p1, s2, p2)
            val note = "panel ${panel.width()}x${panel.height()} hits=${skillHits.size} " +
                "s1=${s1Hit.name}@${"%.2f".format(s1Hit.score)}($p1) " +
                "s2=${s2Hit?.name ?: "?"}@${"%.2f".format(s2Hit?.score ?: 0f)}($p2) slot=$slots"
            if (work !== bitmap) work.recycle()
            crop.recycle()
            ReadResult(OcrCharm(-1, slots, skills), note)
        }
    }

    private fun buildSkills(
        s1: Pair<Int, String>?,
        p1: Int?,
        s2: Pair<Int, String>?,
        p2: Int?
    ): List<OcrSkill> {
        val skills = mutableListOf<OcrSkill>()
        if (s1 != null) skills += OcrSkill(s1.first, s1.second, p1 ?: 0)
        if (s2 != null && (p2 ?: 0) > 0) skills += OcrSkill(s2.first, s2.second, p2!!)
        else if (s2 != null) skills += OcrSkill(s2.first, s2.second, p2 ?: 0)
        return skills
    }

    private fun toSkillPair(name: String): Pair<Int, String>? {
        val idx = SKILL_NAMES.indexOfFirst {
            val a = it.replace("　", "").replace("ＳＰ", "SP").trim()
            a == name
        }
        if (idx < 0) return null
        return idx to name
    }

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            skillTmpls = loadDir(context, "charm_templates/skills").map { (name, g) ->
                Tmpl(stripExt(name), g)
            }.filter { it.name.isNotEmpty() && it.name != "（なし）" && it.name != "(無)" }
            digitTmpls = loadDir(context, "charm_templates/digits").mapNotNull { (name, g) ->
                val n = stripExt(name).removePrefix("plus").toIntOrNull() ?: return@mapNotNull null
                n to g
            }
            slotTmpls = loadDir(context, "charm_templates/slots").mapNotNull { (name, g) ->
                val n = stripExt(name).removePrefix("slot").toIntOrNull() ?: return@mapNotNull null
                n to g
            }
            loaded = true
        }
    }

    private fun stripExt(name: String): String =
        name.substringBeforeLast('.').trim()

    private fun loadDir(context: Context, dir: String): List<Pair<String, Array<IntArray>>> {
        val names = context.assets.list(dir).orEmpty()
        return names.mapNotNull { name ->
            val lower = name.lowercase()
            if (!lower.endsWith(".png") && !lower.endsWith(".jpg") && !lower.endsWith(".jpeg")) {
                return@mapNotNull null
            }
            val bmp = context.assets.open("$dir/$name").use { BitmapFactory.decodeStream(it) }
                ?: return@mapNotNull null
            val g = toGray(bmp)
            bmp.recycle()
            name to g
        }
    }

    private fun findSkillHits(gray: Array<IntArray>): List<Hit> {
        val hits = mutableListOf<Hit>()
        val minScore = 0.48f
        // パネル内はテンプレ原寸に近いのでスケールを絞る
        val scales = floatArrayOf(1.0f, 0.85f, 1.15f, 0.7f, 1.3f)
        for (t in skillTmpls) {
            var best: Hit? = null
            for (sc in scales) {
                val scaled = scaleGray(t.gray, sc) ?: continue
                val pos = nccBestPos(gray, scaled, stepDiv = 8) ?: continue
                if (pos.score >= minScore && (best == null || pos.score > best.score)) {
                    best = Hit(t.name, pos.score, pos.x, pos.y, pos.w, pos.h)
                }
            }
            if (best != null) hits += best
        }
        hits.sortByDescending { it.score }
        val kept = mutableListOf<Hit>()
        for (h in hits) {
            val overlap = kept.any { k ->
                iou(h.x, h.y, h.w, h.h, k.x, k.y, k.w, k.h) > 0.35f
            }
            if (!overlap) kept += h
            if (kept.size >= 4) break
        }
        return kept
    }

    private fun matchPointsNear(gray: Array<IntArray>, skill: Hit): Int? {
        if (digitTmpls.isEmpty()) return null
        val ih = gray.size
        val iw = gray[0].size
        val left = (skill.x + skill.w * 0.65f).toInt().coerceIn(0, iw - 1)
        val top = (skill.y - skill.h / 3).coerceIn(0, ih - 1)
        val right = min(iw, max(left + 48, skill.x + skill.w + skill.w))
        val bottom = min(ih, skill.y + skill.h + skill.h / 2)
        val region = subRect(gray, left, top, right, bottom) ?: return null
        var bestN: Int? = null
        var best = 0.36f
        for ((n, g) in digitTmpls) {
            for (sc in floatArrayOf(1.0f, 1.2f, 0.85f, 1.4f)) {
                val scaled = scaleGray(g, sc) ?: continue
                val s = nccBestPos(region, scaled, stepDiv = 6)?.score ?: -1f
                if (s > best) {
                    best = s
                    bestN = n
                }
            }
        }
        return bestN
    }

    private fun matchSlotsBelow(gray: Array<IntArray>, anchorY: Int, anchorX: Int): Int {
        if (slotTmpls.isEmpty()) return -1
        val ih = gray.size
        val iw = gray[0].size
        val top = anchorY.coerceIn(0, ih - 1)
        val bottom = min(ih, top + max(40, (ih * 0.18f).toInt()))
        val left = max(0, anchorX - 20)
        val right = min(iw, anchorX + max(120, iw / 2))
        val region = subRect(gray, left, top, right, bottom) ?: return -1
        val scores = mutableMapOf<Int, Float>()
        for ((n, g) in slotTmpls) {
            var best = -1f
            for (sc in floatArrayOf(1.0f, 1.15f, 0.85f, 1.3f)) {
                val scaled = scaleGray(g, sc) ?: continue
                val s = nccBestPos(region, scaled, stepDiv = 5)?.score ?: -1f
                if (s > best) best = s
            }
            scores[n] = best
        }
        // slot0(---)は区切り線に誤反応しやすいので、他が近いときは非0を優先
        val bestNonZero = scores.filter { it.key > 0 }.maxByOrNull { it.value }
        val score0 = scores[0] ?: -1f
        if (bestNonZero != null && bestNonZero.value >= 0.40f) {
            if (score0 < bestNonZero.value + 0.12f) return bestNonZero.key
        }
        val best = scores.maxByOrNull { it.value } ?: return -1
        return if (best.value >= 0.40f) best.key else -1
    }

    private data class PosHit(val score: Float, val x: Int, val y: Int, val w: Int, val h: Int)

    private fun nccBestPos(image: Array<IntArray>, tmpl: Array<IntArray>, stepDiv: Int = 8): PosHit? {
        if (image.isEmpty() || tmpl.isEmpty()) return null
        val ih = image.size
        val iw = image[0].size
        var th = tmpl.size
        var tw = tmpl[0].size
        var use = tmpl
        if (th > ih || tw > iw) {
            val scale = min(ih.toFloat() / th, iw.toFloat() / tw)
            if (scale < 0.35f) return null
            use = scaleGray(tmpl, scale) ?: return null
            th = use.size
            tw = use[0].size
        }
        val maxY = ih - th
        val maxX = iw - tw
        if (maxY < 0 || maxX < 0) return null
        val n = th * tw
        var tSum = 0L
        for (y in 0 until th) for (x in 0 until tw) tSum += use[y][x]
        val tMean = tSum.toDouble() / n
        var tVar = 0.0
        for (y in 0 until th) for (x in 0 until tw) {
            val d = use[y][x] - tMean
            tVar += d * d
        }
        if (tVar < 1e-3) return null
        var best = -1f
        var bx = 0
        var by = 0
        val step = max(1, min(maxX, maxY).coerceAtLeast(1) / stepDiv)
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
                    val s = (num / sqrt(iVar * tVar)).toFloat()
                    if (s > best) {
                        best = s
                        bx = ox
                        by = oy
                    }
                }
                ox += step
            }
            oy += step
        }
        if (best < 0f) return null
        return PosHit(best, bx, by, tw, th)
    }

    private fun scaleGray(src: Array<IntArray>, scale: Float): Array<IntArray>? {
        if (src.isEmpty()) return null
        val sh = src.size
        val sw = src[0].size
        val nh = max(6, (sh * scale).roundToInt())
        val nw = max(6, (sw * scale).roundToInt())
        if (nh < 4 || nw < 4) return null
        return Array(nh) { y ->
            IntArray(nw) { x ->
                src[(y * sh / nh).coerceIn(0, sh - 1)][(x * sw / nw).coerceIn(0, sw - 1)]
            }
        }
    }

    private fun subRect(
        gray: Array<IntArray>,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Array<IntArray>? {
        if (gray.isEmpty()) return null
        val l = left.coerceIn(0, gray[0].size - 1)
        val t = top.coerceIn(0, gray.size - 1)
        val r = right.coerceIn(l + 1, gray[0].size)
        val b = bottom.coerceIn(t + 1, gray.size)
        return Array(b - t) { y -> gray[t + y].copyOfRange(l, r) }
    }

    private fun findPanel(bmp: Bitmap): Rect? {
        val w = bmp.width
        val h = bmp.height
        val step = max(3, min(w, h) / 60)
        var bestScore = 0
        var best: Rect? = null
        // スマホ撮影でもパネルが上〜中央に来ることが多い
        var y = h / 30
        val y1 = h * 70 / 100
        while (y < y1) {
            var x = w / 20
            val x1 = w * 19 / 20
            while (x < x1) {
                for (ww in intArrayOf(w * 55 / 100, w * 70 / 100, w * 40 / 100)) {
                    for (hh in intArrayOf(h * 22 / 100, h * 30 / 100, h * 16 / 100)) {
                        if (x + ww > w || y + hh > h || ww < 80 || hh < 50) continue
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
                                // 暗いパネル＋青み枠
                                val dark = r < 90 && g < 90 && b < 100
                                val blueish = b > r + 10 && b > g
                                if (dark || blueish) score++
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
                }
                x += step * 4
            }
            y += step * 4
        }
        return if (bestScore > 80) best else null
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

    private fun iou(
        x1: Int, y1: Int, w1: Int, h1: Int,
        x2: Int, y2: Int, w2: Int, h2: Int
    ): Float {
        val l = max(x1, x2)
        val t = max(y1, y2)
        val r = min(x1 + w1, x2 + w2)
        val b = min(y1 + h1, y2 + h2)
        if (r <= l || b <= t) return 0f
        val inter = (r - l) * (b - t).toFloat()
        val union = w1 * h1 + w2 * h2 - inter
        return if (union <= 0f) 0f else inter / union
    }
}
