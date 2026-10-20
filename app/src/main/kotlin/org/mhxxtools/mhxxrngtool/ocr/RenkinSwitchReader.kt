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
import kotlin.math.sqrt

/**
 * mhxx-renkin-reader と同じ固定座標 + 見本テンプレート照合。
 * Switch キャプチャ (16:9 / 基準 1280×720) 専用。
 *
 * 座標は renkin-reader/src/vision/read.rs の定数をそのまま使用:
 *   NAME1 (655,165)-(800,191)  PTS1 (795,165)-(850,191)
 *   NAME2 (655,194)-(800,218)  PTS2 (795,194)-(850,218)
 *   SLOT  cells x=580,604,628  y=224
 */
object RenkinSwitchReader {

    data class Result(val charm: OcrCharm, val note: String, val confidence: Float)

    // 1280×720 基準の矩形 (left, top, right, bottom)
    private val NAME1 = intArrayOf(655, 165, 800, 191)
    private val PTS1  = intArrayOf(795, 165, 850, 191)
    private val NAME2 = intArrayOf(655, 194, 800, 218)
    private val PTS2  = intArrayOf(795, 194, 850, 218)
    private val SLOT_CELLS = intArrayOf(580, 604, 628)
    private val SLOT_Y = 224
    // SLOT_CROP relative (l, top, r, bottom) around each cell
    private val SLOT_CROP = intArrayOf(-1, -2, 17, 18)

    private const val BASE_W = 1280
    private const val BASE_H = 720

    private data class Gray(val w: Int, val h: Int, val px: IntArray) // 0..255

    @Volatile private var loaded = false
    private var skillTmpls: List<Pair<Int, Gray>> = emptyList() // skillId, gray
    private var digitTmpls: List<Pair<Int, Gray>> = emptyList()
    private var circleTmpl: Gray? = null
    private var dashTmpl: Gray? = null

    fun isSwitchCapture(bmp: Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        if (w < 900 || h < 500) return false
        val ratio = w.toFloat() / h
        return kotlin.math.abs(ratio - 16f / 9f) < 0.05f
    }

    suspend fun read(context: Context, bitmap: Bitmap): Result? = withContext(Dispatchers.Default) {
        if (!isSwitchCapture(bitmap)) return@withContext null
        ensureLoaded(context)
        if (skillTmpls.isEmpty()) return@withContext null

        val sx = bitmap.width.toFloat() / BASE_W
        val sy = bitmap.height.toFloat() / BASE_H

        fun cropRoi(r: IntArray, padX: Int = 2, padY: Int = 2): Gray? {
            val l = ((r[0] - padX) * sx).toInt().coerceIn(0, bitmap.width - 2)
            val t = ((r[1] - padY) * sy).toInt().coerceIn(0, bitmap.height - 2)
            val rr = ((r[2] + padX) * sx).toInt().coerceIn(l + 1, bitmap.width)
            val b = ((r[3] + padY) * sy).toInt().coerceIn(t + 1, bitmap.height)
            return cropGray(bitmap, l, t, rr - l, b - t)
        }

        val name1 = cropRoi(NAME1) ?: return@withContext null
        val pts1  = cropRoi(PTS1) ?: return@withContext null
        val name2 = cropRoi(NAME2)
        val pts2  = cropRoi(PTS2)

        val s1 = matchSkill(name1)
        val p1 = matchPoints(pts1)
        val s2 = name2?.let { matchSkill(it) }
        val p2 = pts2?.let { matchPoints(it) }

        val slots = matchSlots(bitmap, sx, sy)

        val skills = mutableListOf<OcrSkill>()
        if (s1 != null) {
            val pts = p1 ?: 0
            val name = SKILL_NAMES.getOrElse(s1) { "skill$s1" }
            skills += OcrSkill(s1, name, pts.coerceIn(0, 20))
        }
        if (s2 != null) {
            val pts = p2 ?: 0
            // 「-----」相当: スコアが低すぎる場合はスキップ済み
            val name = SKILL_NAMES.getOrElse(s2) { "skill$s2" }
            skills += OcrSkill(s2, name, pts.coerceIn(0, 20))
        }

        if (skills.isEmpty()) {
            return@withContext Result(
                OcrCharm(-1, slots, emptyList()),
                "renkin-no-skill ${bitmap.width}x${bitmap.height}",
                0.2f
            )
        }

        val conf = when {
            skills.size >= 2 && slots in 0..3 -> 0.95f
            skills.size >= 1 && slots in 0..3 -> 0.88f
            skills.size >= 1 -> 0.75f
            else -> 0.5f
        }
        val note = "renkin-fixed ${bitmap.width}x${bitmap.height} " +
            skills.joinToString("+") { "${it.name}+${it.pts}" } + " sl=$slots"
        Result(OcrCharm(-1, slots, skills), note, conf)
    }

    // ── テンプレート読込 ──────────────────────────────────────────────────

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val am = context.assets
            fun loadDir(path: String): List<Pair<String, Gray>> {
                val names = runCatching { am.list(path)?.toList().orEmpty() }.getOrDefault(emptyList())
                return names.filter { it.endsWith(".png", true) }.mapNotNull { n ->
                    runCatching {
                        am.open("$path/$n").use { stream ->
                            val bmp = BitmapFactory.decodeStream(stream) ?: return@runCatching null
                            val g = bitmapToGray(bmp)
                            bmp.recycle()
                            n.removeSuffix(".png").removeSuffix(".PNG") to g
                        }
                    }.getOrNull()
                }
            }
            skillTmpls = loadDir("renkin/skills").mapNotNull { (name, g) ->
                name.toIntOrNull()?.let { it to g }
            }.sortedBy { it.first }
            digitTmpls = loadDir("renkin/digits").mapNotNull { (name, g) ->
                name.toIntOrNull()?.let { it to g }
            }
            val slots = loadDir("renkin/slots")
            circleTmpl = slots.find { it.first == "circle" }?.second
            dashTmpl = slots.find { it.first == "dash" }?.second
            loaded = true
        }
    }

    // ── スキル照合 ──────────────────────────────────────────────────────

    private fun matchSkill(roi: Gray): Int? {
        var bestId: Int? = null
        var best = 0.45f
        var second = 0f
        for ((id, tmpl) in skillTmpls) {
            // 3スケール (0.97, 1.0, 1.03) + 画像サイズ差対応
            for (sc in floatArrayOf(0.90f, 0.97f, 1.0f, 1.03f, 1.10f, 1.20f)) {
                val scaled = scaleGray(tmpl, sc) ?: continue
                if (scaled.w > roi.w || scaled.h > roi.h) continue
                val s = nccBest(roi, scaled)
                if (s > best) {
                    second = best
                    best = s
                    bestId = id
                } else if (s > second) {
                    second = s
                }
            }
        }
        // 1位と2位の差が小さいと誤認識しやすい
        if (best - second < 0.03f && best < 0.70f) return null
        return if (best >= 0.48f) bestId else null
    }

    // ── ポイント照合 ────────────────────────────────────────────────────

    private fun matchPoints(roi: Gray): Int? {
        if (digitTmpls.isEmpty()) return null
        // 符号 + 数字1〜2桁を想定。各数字テンプレを試し最大スコアの値を採用
        var bestN: Int? = null
        var best = 0.40f
        for ((n, tmpl) in digitTmpls) {
            for (sc in floatArrayOf(0.85f, 1.0f, 1.15f, 1.3f, 1.5f)) {
                val scaled = scaleGray(tmpl, sc) ?: continue
                if (scaled.w > roi.w || scaled.h > roi.h) continue
                val s = nccBest(roi, scaled)
                if (s > best) {
                    best = s
                    bestN = n
                }
            }
        }
        // 2桁の可能性: 十の位と一の位を別々に読むのは簡略化し、1桁優先
        // 高スコアの数字を返す (renkin の digits は 1..13 など)
        return if (best >= 0.45f) bestN else null
    }

    // ── スロット照合 ────────────────────────────────────────────────────

    private fun matchSlots(bitmap: Bitmap, sx: Float, sy: Float): Int {
        val circle = circleTmpl ?: return -1
        val dash = dashTmpl ?: return -1
        var circles = 0
        for (cx in SLOT_CELLS) {
            val l = ((cx + SLOT_CROP[0] - 4) * sx).toInt().coerceIn(0, bitmap.width - 2)
            val t = ((SLOT_Y + SLOT_CROP[1] - 4) * sy).toInt().coerceIn(0, bitmap.height - 2)
            val r = ((cx + SLOT_CROP[2] + 4) * sx).toInt().coerceIn(l + 1, bitmap.width)
            val b = ((SLOT_Y + SLOT_CROP[3] + 4) * sy).toInt().coerceIn(t + 1, bitmap.height)
            val roi = cropGray(bitmap, l, t, r - l, b - t) ?: continue

            var bestC = -1f
            var bestD = -1f
            for (sc in floatArrayOf(0.8f, 1.0f, 1.2f, sx.coerceIn(0.7f, 1.5f))) {
                scaleGray(circle, sc)?.let {
                    if (it.w <= roi.w && it.h <= roi.h) bestC = max(bestC, nccBest(roi, it))
                }
                scaleGray(dash, sc)?.let {
                    if (it.w <= roi.w && it.h <= roi.h) bestD = max(bestD, nccBest(roi, it))
                }
            }
            // 丸の方が明らかに高ければ ○
            if (bestC > bestD + 0.05f && bestC >= 0.45f) circles++
        }
        return circles.coerceIn(0, 3)
    }

    // ── 画像処理 ────────────────────────────────────────────────────────

    private fun bitmapToGray(bmp: Bitmap): Gray {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        val tmp = IntArray(w * h)
        bmp.getPixels(tmp, 0, w, 0, 0, w, h)
        for (i in tmp.indices) {
            val c = tmp[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            // renkin: max(R,G,B) を明るさに使う (チャンネル最大)
            px[i] = max(r, max(g, b))
        }
        return Gray(w, h, px)
    }

    private fun cropGray(bmp: Bitmap, x: Int, y: Int, w: Int, h: Int): Gray? {
        if (w < 2 || h < 2) return null
        val sub = Bitmap.createBitmap(bmp, x, y, w, h)
        val g = bitmapToGray(sub)
        if (sub !== bmp) sub.recycle()
        // 簡易トップハット風: ローカル平均を引いて文字を強調
        return enhance(g)
    }

    private fun enhance(g: Gray): Gray {
        // 軽いコントラスト強調
        var minV = 255
        var maxV = 0
        for (v in g.px) {
            if (v < minV) minV = v
            if (v > maxV) maxV = v
        }
        val range = (maxV - minV).coerceAtLeast(1)
        val out = IntArray(g.px.size)
        for (i in g.px.indices) {
            out[i] = ((g.px[i] - minV) * 255 / range).coerceIn(0, 255)
        }
        return Gray(g.w, g.h, out)
    }

    private fun scaleGray(src: Gray, scale: Float): Gray? {
        if (scale < 0.4f || scale > 3.0f) return null
        val nw = (src.w * scale).toInt().coerceAtLeast(1)
        val nh = (src.h * scale).toInt().coerceAtLeast(1)
        if (nw == src.w && nh == src.h) return src
        val out = IntArray(nw * nh)
        for (y in 0 until nh) {
            val sy = (y / scale).toInt().coerceIn(0, src.h - 1)
            for (x in 0 until nw) {
                val sx = (x / scale).toInt().coerceIn(0, src.w - 1)
                out[y * nw + x] = src.px[sy * src.w + sx]
            }
        }
        return Gray(nw, nh, out)
    }

    /** 正規化相互相関の最大値 (0..1) */
    private fun nccBest(image: Gray, tmpl: Gray): Float {
        if (tmpl.w > image.w || tmpl.h > image.h) return -1f
        val tw = tmpl.w
        val th = tmpl.h
        val iw = image.w
        val ih = image.h

        // テンプレ平均・ノルム
        var tSum = 0.0
        var tSq = 0.0
        val tN = tw * th
        for (v in tmpl.px) {
            tSum += v
            tSq += v.toDouble() * v
        }
        val tMean = tSum / tN
        var tVar = 0.0
        for (v in tmpl.px) {
            val d = v - tMean
            tVar += d * d
        }
        if (tVar < 1e-6) return -1f
        val tNorm = sqrt(tVar)

        var best = -1f
        val stepX = max(1, tw / 10)
        val stepY = max(1, th / 8)
        var y = 0
        while (y <= ih - th) {
            var x = 0
            while (x <= iw - tw) {
                var iSum = 0.0
                var iSq = 0.0
                var cross = 0.0
                var ti = 0
                for (ty in 0 until th) {
                    val row = (y + ty) * iw + x
                    for (tx in 0 until tw) {
                        val iv = image.px[row + tx].toDouble()
                        val tv = tmpl.px[ti].toDouble()
                        iSum += iv
                        iSq += iv * iv
                        cross += (iv - tMean) // 後で調整
                        ti++
                    }
                }
                // 再計算 cross properly
                cross = 0.0
                ti = 0
                val iMean = iSum / tN
                for (ty in 0 until th) {
                    val row = (y + ty) * iw + x
                    for (tx in 0 until tw) {
                        val iv = image.px[row + tx] - iMean
                        val tv = tmpl.px[ti] - tMean
                        cross += iv * tv
                        ti++
                    }
                }
                var iVar = 0.0
                for (ty in 0 until th) {
                    val row = (y + ty) * iw + x
                    for (tx in 0 until tw) {
                        val d = image.px[row + tx] - iMean
                        iVar += d * d
                    }
                }
                if (iVar > 1e-6) {
                    val score = (cross / (sqrt(iVar) * tNorm)).toFloat()
                    if (score > best) best = score
                }
                x += stepX
            }
            y += stepY
        }
        // 最良付近を密に再探索
        if (best > 0.3f) {
            // 簡易: step=1 で全体は重いので、step を小さくした2パス目
            y = 0
            while (y <= ih - th) {
                var x = 0
                while (x <= iw - tw) {
                    var iSum = 0.0
                    var ti = 0
                    for (ty in 0 until th) {
                        val row = (y + ty) * iw + x
                        for (tx in 0 until tw) {
                            iSum += image.px[row + tx]
                            ti++
                        }
                    }
                    val iMean = iSum / tN
                    var cross = 0.0
                    var iVar = 0.0
                    ti = 0
                    for (ty in 0 until th) {
                        val row = (y + ty) * iw + x
                        for (tx in 0 until tw) {
                            val iv = image.px[row + tx] - iMean
                            val tv = tmpl.px[ti] - tMean
                            cross += iv * tv
                            iVar += iv * iv
                            ti++
                        }
                    }
                    if (iVar > 1e-6) {
                        val score = (cross / (sqrt(iVar) * tNorm)).toFloat()
                        if (score > best) best = score
                    }
                    x += max(1, stepX / 2)
                }
                y += max(1, stepY / 2)
            }
        }
        return best
    }
}
