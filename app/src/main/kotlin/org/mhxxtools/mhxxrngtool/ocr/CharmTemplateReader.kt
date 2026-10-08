package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mhxxtools.mhxxrngtool.rng.SKILL_NAMES
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * assets/charm_templates の切り出し画像だけで鑑定を読む（OCRなし・固定座標なし）。
 * 画角が違っても、スキル名・ポイント・スロットのテンプレが写っていれば照合する。
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

            val work = downscale(bitmap, 960)
            val gray = toGray(work)

            // 画像全体からスキル名テンプレを探す（複数スケール）
            val skillHits = findSkillHits(gray)
            if (skillHits.isEmpty()) {
                if (work !== bitmap) work.recycle()
                return@runCatching ReadResult(
                    OcrCharm(-1, -1, emptyList()),
                    "no-skill-hit assets s=${skillTmpls.size} d=${digitTmpls.size} sl=${slotTmpls.size}"
                )
            }

            // 上からスキル1、その下をスキル2
            val ordered = skillHits.sortedBy { it.y }
            val s1Hit = ordered.first()
            val s2Hit = ordered.drop(1).firstOrNull { it.y > s1Hit.y + s1Hit.h / 2 && it.name != s1Hit.name }
                ?: ordered.drop(1).firstOrNull()

            val s1 = toSkillPair(s1Hit.name)
            val s2 = s2Hit?.let { toSkillPair(it.name) }

            // 各スキル行の右側でポイント照合
            val p1 = matchPointsNear(gray, s1Hit)
            val p2 = s2Hit?.let { matchPointsNear(gray, it) }

            // スキル群の下でスロット照合
            val slotAnchorY = (s2Hit?.y ?: s1Hit.y) + (s2Hit?.h ?: s1Hit.h)
            val slots = matchSlotsBelow(gray, slotAnchorY, s1Hit.x)

            val skills = buildSkills(s1, p1, s2, p2)
            val note = "free-match assets s=${skillTmpls.size} d=${digitTmpls.size} sl=${slotTmpls.size} " +
                "hits=${skillHits.size} s1=${s1?.second ?: "?"}($p1) s2=${s2?.second ?: "?"}($p2) slot=$slots"
            if (work !== bitmap) work.recycle()
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
        return skills
    }

    private fun toSkillPair(name: String): Pair<Int, String>? {
        val idx = SKILL_NAMES.indexOfFirst {
            it.replace("　", "").trim() == name ||
                it.replace("ＳＰ", "SP").replace("　", "").trim() == name
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
                val base = stripExt(name)
                val n = base.removePrefix("plus").toIntOrNull() ?: return@mapNotNull null
                n to g
            }
            slotTmpls = loadDir(context, "charm_templates/slots").mapNotNull { (name, g) ->
                val base = stripExt(name)
                val n = base.removePrefix("slot").toIntOrNull() ?: return@mapNotNull null
                n to g
            }
            loaded = true
        }
    }

    private fun stripExt(name: String): String =
        name.removeSuffix(".png").removeSuffix(".jpg").removeSuffix(".jpeg")
            .removeSuffix(".PNG").removeSuffix(".JPG")

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

    /** 画像全体からスキルテンプレの上位ヒットを集める */
    private fun findSkillHits(gray: Array<IntArray>): List<Hit> {
        val hits = mutableListOf<Hit>()
        val minScore = 0.55f
        for (t in skillTmpls) {
            val hit = bestNccHit(gray, t.gray, minScore) ?: continue
            hits += Hit(t.name, hit.score, hit.x, hit.y, hit.w, hit.h)
        }
        // 同位置の重複を除去（スコア高い方）
        hits.sortByDescending { it.score }
        val kept = mutableListOf<Hit>()
        for (h in hits) {
            val overlap = kept.any { k ->
                absOverlap(h.x, h.y, h.w, h.h, k.x, k.y, k.w, k.h) > 0.4f
            }
            if (!overlap) kept += h
            if (kept.size >= 6) break
        }
        return kept
    }

    private fun absOverlap(
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

    private fun matchPointsNear(gray: Array<IntArray>, skill: Hit): Int? {
        if (digitTmpls.isEmpty()) return null
        val ih = gray.size
        val iw = gray[0].size
        // スキル名の右〜やや下
        val left = (skill.x + skill.w * 0.7f).toInt().coerceIn(0, iw - 1)
        val top = (skill.y - skill.h / 2).coerceIn(0, ih - 1)
        val right = min(iw, left + max(80, skill.w))
        val bottom = min(ih, skill.y + skill.h * 2)
        val region = subRect(gray, left, top, right, bottom) ?: return null
        var bestN: Int? = null
        var best = 0.50f
        for ((n, g) in digitTmpls) {
            val s = nccScore(region, g)
            if (s > best) {
                best = s
                bestN = n
            }
        }
        return bestN
    }

    private fun matchSlotsBelow(gray: Array<IntArray>, anchorY: Int, anchorX: Int): Int {
        if (slotTmpls.isEmpty()) return -1
        val ih = gray.size
        val iw = gray[0].size
        val top = anchorY.coerceIn(0, ih - 1)
        val bottom = min(ih, top + max(60, ih / 6))
        val left = max(0, anchorX - 40)
        val right = min(iw, anchorX + max(200, iw / 3))
        val region = subRect(gray, left, top, right, bottom) ?: return -1
        var bestN = -1
        var best = 0.45f
        for ((n, g) in slotTmpls) {
            val s = nccScore(region, g)
            if (s > best) {
                best = s
                bestN = n
            }
        }
        return bestN
    }

    private data class PosHit(val score: Float, val x: Int, val y: Int, val w: Int, val h: Int)

    /** 複数スケールで最良位置を返す */
    private fun bestNccHit(image: Array<IntArray>, tmpl: Array<IntArray>, minScore: Float): PosHit? {
        if (image.isEmpty() || tmpl.isEmpty()) return null
        val scales = floatArrayOf(1.0f, 0.85f, 0.7f, 1.15f, 0.55f, 1.3f)
        var best: PosHit? = null
        for (sc in scales) {
            val scaled = scaleGray(tmpl, sc) ?: continue
            val hit = nccBestPos(image, scaled) ?: continue
            if (hit.score >= minScore && (best == null || hit.score > best.score)) {
                best = hit
            }
        }
        return best
    }

    private fun nccScore(image: Array<IntArray>, tmpl: Array<IntArray>): Float {
        return nccBestPos(image, tmpl)?.score ?: -1f
    }

    private fun nccBestPos(image: Array<IntArray>, tmpl: Array<IntArray>): PosHit? {
        if (image.isEmpty() || tmpl.isEmpty()) return null
        val ih = image.size
        val iw = image[0].size
        var th = tmpl.size
        var tw = tmpl[0].size
        var use = tmpl
        if (th > ih || tw > iw) {
            val scale = min(ih.toFloat() / th, iw.toFloat() / tw)
            if (scale < 0.3f) return null
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
        val step = max(1, min(maxX, maxY).coerceAtLeast(1) / 10)
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
}
