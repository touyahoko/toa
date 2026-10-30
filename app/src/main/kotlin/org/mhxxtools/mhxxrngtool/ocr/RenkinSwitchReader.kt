package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mhxxtools.mhxxrngtool.rng.KIND_TABLES
import org.mhxxtools.mhxxrngtool.rng.SKILL_NAMES
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max

/**
 * 公式 mhxx-renkin-reader と同じテンプレート照合のみ（OCRなし）。
 * assets/renkin/{skills,digits,slots,label} の見本で照合する。
 */
object RenkinSwitchReader {
    private const val TAG = "RenkinSwitch"

    data class Result(val charm: OcrCharm, val note: String, val confidence: Float)

    private const val SW = 1280
    private const val SH = 720
    private const val TOPHAT_K = 25

    // 公式 read.rs と同じ座標
    private val NAME1 = intArrayOf(655, 165, 820, 191)
    private val PTS1 = intArrayOf(815, 165, 870, 191)
    private val NAME2 = intArrayOf(655, 194, 820, 218)
    private val PTS2 = intArrayOf(815, 194, 870, 218)
    private const val NAME_LEFT = 667
    private const val NAME_SEARCH = 4
    private val LABEL_BLOCK = intArrayOf(476, 166, 570, 247)
    private const val LABEL_SEARCH = 48
    private const val LABEL_MIN = 0.50
    private val SLOT_X = intArrayOf(580, 604, 628)
    private const val SLOT_Y = 224
    private val SLOT_CROP = intArrayOf(-1, -2, 17, 18)
    private const val SLOT_SEARCH = 4

    private const val SKILL_MIN = 0.28
    private const val DIGIT_MIN = 0.35
    private const val LEFTOVER = 1.35

    private data class Tmpl(val id: Int, val mat: Mat)

    @Volatile private var loaded = false
    private val skillTmpls = mutableListOf<Tmpl>()
    private val digitTmpls = mutableListOf<Tmpl>()
    private var labelMat: Mat? = null
    private var circleMat: Mat? = null
    private var dashMat: Mat? = null

    fun isSwitchCapture(bmp: Bitmap): Boolean {
        if (bmp.width < 640 || bmp.height < 360) return false
        return abs(bmp.width.toFloat() / bmp.height - 16f / 9f) < 0.06f
    }

    /** @param preferredKind 0=風化..3=なぞ / -1=全スキル候補 */
    suspend fun read(context: Context, bitmap: Bitmap, preferredKind: Int = -1): Result? = withContext(Dispatchers.Default) {
        if (!OpenCvBridge.ensureInit()) {
            return@withContext Result(OcrCharm(-1, -1, emptyList()), "OpenCV未初期化", 0f)
        }
        ensureLoaded(context)
        if (skillTmpls.isEmpty()) {
            return@withContext Result(OcrCharm(-1, -1, emptyList()), "skills空", 0f)
        }

        // 公式同様: まず reference 位置合わせ。失敗時は画面いっぱいに引き伸ばし
        // （レターボックスだと ROI が外れて未認識・誤認識になる）
        val screenBmp = run {
            val ref = loadBmp(context, "renkin/reference.png")
            if (ref != null) {
                try {
                    OpenCvBridge.alignWithReference(bitmap, ref, SW, SH)
                        ?: OpenCvBridge.alignToCanonical(bitmap, SW, SH)
                        ?: scaleToScreen(bitmap)
                } finally {
                    ref.recycle()
                }
            } else {
                OpenCvBridge.alignToCanonical(bitmap, SW, SH) ?: scaleToScreen(bitmap)
            }
        }

        try {
            val rgba = OpenCvBridge.bitmapToMat(screenBmp)
            var mx = maxChannel(rgba)
            rgba.release()

            val (dx, dy, labScore) = panelOffset(mx)
            if (labScore >= LABEL_MIN) {
                val shifted = translate(mx, dx, dy)
                mx.release()
                mx = shifted
                Log.i(TAG, "panel_offset dx=$dx dy=$dy score=%.3f".format(labScore))
            }

            val name1 = tophatRegion(mx, NAME1)
            val pts1 = tophatRegion(mx, PTS1)
            val name2 = tophatRegion(mx, NAME2)
            val pts2 = tophatRegion(mx, PTS2)

            val (s1Id, s1Sc) = matchSkill(name1, preferredKind)
            val (p1, p1Sc) = matchDigit(pts1)
            val skill2Empty = isDashes(name2)
            val s2Match = if (skill2Empty) null to 1.0 else matchSkill(name2, preferredKind)
            var s2Id: Int? = s2Match.first
            var s2Sc: Double = s2Match.second
            // 重撃弾強化(50) ↔ 強撃瓶追加(59) 取り違え対策
            // 種類テーブル外のIDは後段 Hybrid で補正。ここでは候補を種類で絞っている。
            if (!skill2Empty && s2Id != null && preferredKind in 0..3) {
                val tbl = KIND_TABLES[preferredKind]
                val allowed = if (tbl != null) tbl.skill1.toSet() + tbl.skill2.toSet() else emptySet()
                if (s2Id !in allowed) {
                    // 種類内で再照合
                    val re = matchSkill(name2, preferredKind)
                    if (re.first != null) {
                        s2Id = re.first
                        s2Sc = re.second
                        Log.i(TAG, "re-match skill2 within kind=$preferredKind -> $s2Id")
                    }
                }
            }
            val (p2, p2Sc) = if (skill2Empty) null to 1.0 else matchDigit(pts2)
            val (slots, slotNote) = matchSlots(mx)

            name1.release(); pts1.release(); name2.release(); pts2.release()
            mx.release()

            val skills = mutableListOf<OcrSkill>()
            if (s1Id != null && s1Sc >= SKILL_MIN) {
                skills += OcrSkill(s1Id, SKILL_NAMES.getOrElse(s1Id) { "s$s1Id" }, (p1 ?: 0).coerceIn(0, 20))
            }
            if (!skill2Empty && s2Id != null && s2Sc >= SKILL_MIN) {
                skills += OcrSkill(s2Id, SKILL_NAMES.getOrElse(s2Id) { "s$s2Id" }, (p2 ?: 0).coerceIn(0, 20))
            }

            if (skills.isEmpty()) {
                return@withContext Result(
                    OcrCharm(-1, slots, emptyList()),
                    "fail s1=%.2f s2=%.2f empty2=$skill2Empty lab=%.2f $slotNote".format(s1Sc, s2Sc, labScore),
                    0.1f
                )
            }

            val conf = when {
                skills.size >= 2 && slots in 0..3 && s1Sc >= 0.5 -> 0.97f
                skills.size >= 1 && slots in 0..3 -> 0.90f
                else -> 0.7f
            }
            val note = "tmpl " + skills.joinToString("+") { "${it.name}+${it.pts}" } +
                " sl=$slots s1=%.2f".format(s1Sc) +
                (if (skills.size > 1) " s2=%.2f".format(s2Sc) else " (no-s2)") +
                " p1=%.2f p2=%.2f lab=%.2f $slotNote".format(p1Sc, p2Sc, labScore)
            Log.i(TAG, note)
            Result(OcrCharm(-1, slots, skills), note, conf)
        } finally {
            if (screenBmp !== bitmap) screenBmp.recycle()
        }
    }

    private fun loadBmp(context: Context, path: String): Bitmap? =
        runCatching { context.assets.open(path).use { BitmapFactory.decodeStream(it) } }.getOrNull()

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            if (!OpenCvBridge.ensureInit()) return
            skillTmpls.clear(); digitTmpls.clear()
            val am = context.assets
            fun listPng(dir: String) =
                runCatching { am.list(dir)?.filter { it.endsWith(".png", true) }.orEmpty() }
                    .getOrDefault(emptyList())

            fun loadTh(path: String, k: Int = TOPHAT_K): Mat? {
                return runCatching {
                    am.open(path).use { ins ->
                        val bmp = BitmapFactory.decodeStream(ins) ?: return@use null
                        val mat = OpenCvBridge.bitmapToMat(bmp)
                        bmp.recycle()
                        val g = maxChannel(mat)
                        mat.release()
                        val th = topHat(g, k)
                        g.release()
                        th
                    }
                }.getOrNull()
            }

            for (n in listPng("renkin/skills")) {
                val id = n.substringBeforeLast('.').toIntOrNull() ?: continue
                loadTh("renkin/skills/$n")?.let { skillTmpls += Tmpl(id, it) }
            }
            skillTmpls.sortBy { it.id }
            for (n in listPng("renkin/digits")) {
                val id = n.substringBeforeLast('.').toIntOrNull() ?: continue
                loadTh("renkin/digits/$n")?.let { digitTmpls += Tmpl(id, it) }
            }
            labelMat = loadTh("renkin/label.png")
            circleMat = loadTh("renkin/slots/circle.png", 9)
            dashMat = loadTh("renkin/slots/dash.png", 9)
            loaded = true
            Log.i(TAG, "loaded skills=${skillTmpls.size} digits=${digitTmpls.size}")
        }
    }

    private fun panelOffset(mx: Mat): Triple<Int, Int, Double> {
        val label = labelMat ?: return Triple(0, 0, 0.0)
        val s = LABEL_SEARCH
        val l = (LABEL_BLOCK[0] - s).coerceAtLeast(0)
        val t = (LABEL_BLOCK[1] - s).coerceAtLeast(0)
        val r = (LABEL_BLOCK[2] + s).coerceAtMost(mx.cols())
        val b = (LABEL_BLOCK[3] + s).coerceAtMost(mx.rows())
        val win = Mat(mx, org.opencv.core.Rect(l, t, r - l, b - t)).clone()
        val result = Mat()
        return try {
            if (label.cols() > win.cols() || label.rows() > win.rows()) {
                Triple(0, 0, 0.0)
            } else {
                Imgproc.matchTemplate(win, label, result, Imgproc.TM_CCOEFF_NORMED)
                val mm = Core.minMaxLoc(result)
                val dx = (LABEL_BLOCK[0] - (l + mm.maxLoc.x.toInt()))
                val dy = (LABEL_BLOCK[1] - (t + mm.maxLoc.y.toInt()))
                Triple(dx, dy, mm.maxVal)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "panelOffset", e)
            Triple(0, 0, 0.0)
        } finally {
            win.release(); result.release()
        }
    }

    private fun translate(src: Mat, dx: Int, dy: Int): Mat {
        val m = Mat.zeros(2, 3, org.opencv.core.CvType.CV_32F)
        m.put(0, 0, 1.0, 0.0, -dx.toDouble(), 0.0, 1.0, -dy.toDouble())
        val out = Mat()
        Imgproc.warpAffine(src, out, m, Size(src.cols().toDouble(), src.rows().toDouble()))
        m.release()
        return out
    }


    /** 指定IDの見本だけを照合（取り違え再判定用） */
    private fun matchSkillForceId(roiTh: Mat, skillId: Int): Double? {
        val tmpl = skillTmpls.firstOrNull { it.id == skillId } ?: return null
        var best = -1.0
        for (sc in doubleArrayOf(0.90, 0.95, 0.98, 1.0, 1.02, 1.05, 1.10, 1.15)) {
            var tw = (tmpl.mat.cols() * sc).toInt().coerceAtLeast(1)
            var th = (tmpl.mat.rows() * sc).toInt().coerceAtLeast(1)
            if (th > roiTh.rows() || tw > roiTh.cols()) {
                val fit = minOf(
                    roiTh.rows().toDouble() / tmpl.mat.rows().toDouble(),
                    roiTh.cols().toDouble() / tmpl.mat.cols().toDouble()
                ) * 0.98
                if (fit < 0.4) continue
                tw = (tmpl.mat.cols() * fit).toInt().coerceAtLeast(1)
                th = (tmpl.mat.rows() * fit).toInt().coerceAtLeast(1)
            }
            if (th > roiTh.rows() || tw > roiTh.cols()) continue
            val scaled = Mat()
            Imgproc.resize(tmpl.mat, scaled, Size(tw.toDouble(), th.toDouble()))
            val pair = matchNcc(roiTh, scaled)
            scaled.release()
            if (pair != null && pair.first > best) {
                best = pair.first
            }
        }
        return if (best < 0.0) null else best
    }

    private fun matchSkill(roiTh: Mat, preferredKind: Int = -1): Pair<Int?, Double> {
        // 錬金リーダーと同じ: NCC + 余りインク減点。公式・追加スキルを同一条件で照合。
        data class Cand(val id: Int, val score: Double, val x: Int, val width: Int)
        val cands = mutableListOf<Cand>()
        val preferLeft = NAME_LEFT - NAME1[0] - NAME_SEARCH
        val preferRight = NAME_LEFT - NAME1[0] + NAME_SEARCH
        val allowedIds: Set<Int>? = if (preferredKind in 0..3) {
            val tbl = KIND_TABLES[preferredKind]
            if (tbl != null) (tbl.skill1.toSet() + tbl.skill2.toSet()) else null
        } else null
        // インク範囲に切り詰めてから照合（余白で長い見本が有利になるのを防ぐ）
        val inkRoi = cropToInk(roiTh)
        for (tmpl in skillTmpls) {
            if (allowedIds != null && tmpl.id !in allowedIds) continue
            for (sc in doubleArrayOf(0.92, 0.96, 1.0, 1.04, 1.08)) {
                var tw = (tmpl.mat.cols() * sc).toInt().coerceAtLeast(1)
                var th = (tmpl.mat.rows() * sc).toInt().coerceAtLeast(1)
                if (th > roiTh.rows() || tw > roiTh.cols()) {
                    val fit = minOf(
                        roiTh.rows().toDouble() / tmpl.mat.rows(),
                        roiTh.cols().toDouble() / tmpl.mat.cols()
                    ) * 0.98
                    if (fit < 0.5) continue
                    tw = (tmpl.mat.cols() * fit).toInt().coerceAtLeast(1)
                    th = (tmpl.mat.rows() * fit).toInt().coerceAtLeast(1)
                }
                if (th > roiTh.rows() || tw > roiTh.cols()) continue
                val scaled = Mat()
                Imgproc.resize(tmpl.mat, scaled, Size(tw.toDouble(), th.toDouble()))
                val pair = matchNcc(inkRoi, scaled)
                if (pair != null) {
                    val left = leftoverRatio(inkRoi, pair.second, scaled.cols())
                    // NCC - 余り減点。インク外にインクが残る長い誤マッチを落とす
                    val adj = pair.first - LEFTOVER * left
                    // 見本幅がインク幅に近いほど加点（短いスキルを自然に優先）
                    val inkW = inkRoi.cols().toDouble().coerceAtLeast(1.0)
                    val widthRatio = scaled.cols().toDouble() / inkW
                    val widthFit = 1.0 - kotlin.math.abs(1.0 - widthRatio).coerceAtMost(1.0)
                    val finalScore = adj + 0.08 * widthFit
                    cands += Cand(tmpl.id, finalScore, pair.second, scaled.cols())
                }
                scaled.release()
            }
        }
        if (inkRoi !== roiTh) inkRoi.release()
        if (cands.isEmpty()) return null to -1.0
        // id ごとに最良スコアを残す
        val bestById = cands.groupBy { it.id }.map { (_, list) -> list.maxBy { it.score } }
        val ranked = bestById.sortedByDescending { it.score }
        // 上位から「幅が広くスコアが十分な」候補を優先
        // 短い誤マッチ（特殊攻撃→精密射撃の一部 等）を幅で落とす
        // スコア最大を採用。僅差(0.04以内)ならインク幅との差が小さい方
        var best = ranked[0]
        if (ranked.size > 1) {
            val topScore = ranked[0].score
            val close = ranked.filter { it.score >= topScore - 0.04 }
            best = close.maxBy { it.score }
        }
        // 自然選択: 調整スコア最大。僅差ならインク幅に近い見本を優先
        if (best.score < SKILL_MIN) return null to best.score
        return best.id to best.score
    }

    /** ROI 内のインク外接矩形で切り詰め（短いスキルと長いスキルの公平な照合用） */
    private fun cropToInk(src: Mat): Mat {
        val cols = src.cols(); val rows = src.rows()
        if (cols < 4 || rows < 4) return src
        val data = ByteArray(cols * rows)
        src.get(0, 0, data)
        var minX = cols; var maxX = -1; var minY = rows; var maxY = -1
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                if ((data[y * cols + x].toInt() and 0xFF) > 40) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < minX || maxY < minY) return src
        val pad = 2
        val l = (minX - pad).coerceAtLeast(0)
        val t = (minY - pad).coerceAtLeast(0)
        val r = (maxX + 1 + pad).coerceAtMost(cols)
        val b = (maxY + 1 + pad).coerceAtMost(rows)
        if (r - l < 4 || b - t < 4) return src
        return Mat(src, org.opencv.core.Rect(l, t, r - l, b - t)).clone()
    }


    private fun matchDigit(roiTh: Mat): Pair<Int?, Double> {
        if (roiTh.rows() < 4 || roiTh.cols() < 4) return null to -1.0
        data class DC(val id: Int, val score: Double)
        val cands = mutableListOf<DC>()
        for (tmpl in digitTmpls) {
            var localBest = -1.0
            for (sc in doubleArrayOf(0.97, 1.0, 1.03)) {
                var tw = (tmpl.mat.cols() * sc).toInt().coerceAtLeast(1)
                var th = (tmpl.mat.rows() * sc).toInt().coerceAtLeast(1)
                if (th > roiTh.rows() || tw > roiTh.cols()) {
                    val fit = minOf(
                        roiTh.rows().toDouble() / tmpl.mat.rows(),
                        roiTh.cols().toDouble() / tmpl.mat.cols()
                    ) * 0.98
                    if (fit < 0.5) continue
                    tw = (tmpl.mat.cols() * fit).toInt().coerceAtLeast(1)
                    th = (tmpl.mat.rows() * fit).toInt().coerceAtLeast(1)
                }
                if (th > roiTh.rows() || tw > roiTh.cols()) continue
                val scaled = Mat()
                Imgproc.resize(tmpl.mat, scaled, Size(tw.toDouble(), th.toDouble()))
                val pair = matchNcc(roiTh, scaled)
                val adj = if (pair == null) -1.0
                else pair.first - LEFTOVER * leftoverRatio(roiTh, pair.second, scaled.cols())
                scaled.release()
                if (adj > localBest) localBest = adj
            }
            if (localBest >= DIGIT_MIN * 0.9) cands += DC(tmpl.id, localBest)
        }
        if (cands.isEmpty()) return null to -1.0
        cands.sortByDescending { it.score }
        var best = cands[0]
        // 1桁と2桁が近いとき2桁を優先（余り減点済み）
        val multi = cands.filter { it.id >= 10 }
        if (best.id < 10 && multi.isNotEmpty()) {
            val m = multi.maxBy { it.score }
            if (m.score >= best.score - 0.08) best = m
        }
        return if (best.score >= DIGIT_MIN) best.id to best.score else null to best.score
    }

    private fun matchSlots(mx: Mat): Pair<Int, String> {
        val circle = circleMat ?: return -1 to "no-tmpl"
        val dash = dashMat ?: return -1 to "no-tmpl"
        var count = 0
        val notes = mutableListOf<String>()
        val s = SLOT_SEARCH
        for (cx in SLOT_X) {
            val l = (cx + SLOT_CROP[0] - s).coerceAtLeast(0)
            val top = (SLOT_Y + SLOT_CROP[1] - s).coerceAtLeast(0)
            val r = (cx + SLOT_CROP[2] + s).coerceAtMost(mx.cols())
            val bottom = (SLOT_Y + SLOT_CROP[3] + s).coerceAtMost(mx.rows())
            if (r - l < 4 || bottom - top < 4) {
                notes += "skip"; continue
            }
            val raw = Mat(mx, org.opencv.core.Rect(l, top, r - l, bottom - top)).clone()
            val roi = topHat(raw, 9)
            raw.release()
            var bestC = -1.0
            var bestD = -1.0
            for (sc in doubleArrayOf(0.97, 1.0, 1.03)) {
                fun tryM(tmpl: Mat): Double {
                    val tw = (tmpl.cols() * sc).toInt().coerceAtLeast(1)
                    val th = (tmpl.rows() * sc).toInt().coerceAtLeast(1)
                    if (tw > roi.cols() || th > roi.rows()) return -1.0
                    val scMat = Mat()
                    Imgproc.resize(tmpl, scMat, Size(tw.toDouble(), th.toDouble()))
                    val p = matchNcc(roi, scMat)
                    scMat.release()
                    return p?.first ?: -1.0
                }
                bestC = max(bestC, tryM(circle))
                bestD = max(bestD, tryM(dash))
            }
            roi.release()
            notes += "c=%.2f/d=%.2f".format(bestC, bestD)
            // 公式: 丸スコア > 線スコア なら ○
            if (bestC > bestD) count++
        }
        return count.coerceIn(0, 3) to notes.joinToString(",")
    }

    private fun matchNcc(image: Mat, tmpl: Mat): Pair<Double, Int>? {
        if (tmpl.cols() > image.cols() || tmpl.rows() > image.rows()) return null
        val result = Mat()
        return try {
            Imgproc.matchTemplate(image, tmpl, result, Imgproc.TM_CCOEFF_NORMED)
            val mm = Core.minMaxLoc(result)
            mm.maxVal to mm.maxLoc.x.toInt()
        } catch (e: Throwable) {
            Log.e(TAG, "matchTemplate", e)
            null
        } finally {
            result.release()
        }
    }

    private fun leftoverRatio(roi: Mat, matchX: Int, tmplW: Int): Double {
        val cols = roi.cols(); val rows = roi.rows()
        val data = ByteArray(cols * rows)
        roi.get(0, 0, data)
        var total = 0; var outside = 0
        val left = (matchX - 2).coerceAtLeast(0)
        val right = (matchX + tmplW + 2).coerceAtMost(cols)
        for (x in 0 until cols) {
            var ink = 0
            for (y in 0 until rows) if ((data[y * cols + x].toInt() and 0xFF) > 30) ink++
            total += ink
            if (x < left || x >= right) outside += ink
        }
        return if (total == 0) 0.0 else outside.toDouble() / total
    }

    /** 公式 is_dashes */
    private fun isDashes(roi: Mat): Boolean {
        val cols = roi.cols(); val rows = roi.rows()
        if (cols < 4 || rows < 4) return true
        val data = ByteArray(cols * rows)
        roi.get(0, 0, data)
        fun inkAt(x: Int, y: Int) = (data[y * cols + x].toInt() and 0xFF) > 40
        val rowInk = IntArray(rows) { y ->
            var n = 0
            for (x in 0 until cols) if (inkAt(x, y)) n++
            n
        }
        val totalInk = rowInk.sum()
        if (totalInk < 5) return true
        val thr = (0.25 * cols).toInt().coerceAtLeast(2)
        val strong = (0 until rows).filter { rowInk[it] >= thr }
        if (strong.isEmpty()) return false
        val first = strong.first()
        val last = strong.last()
        if (last - first + 1 > 6) return false
        val bandFrom = (first - 2).coerceAtLeast(0)
        val bandTo = (last + 3).coerceAtMost(rows)
        val band = (bandFrom until bandTo).sumOf { rowInk[it] }
        return band >= 0.85 * totalInk
    }

    private fun tophatRegion(mx: Mat, rect: IntArray): Mat {
        val m = TOPHAT_K - 1
        val l = (rect[0] - m).coerceAtLeast(0)
        val t = (rect[1] - m).coerceAtLeast(0)
        val r = (rect[2] + m).coerceAtMost(mx.cols())
        val b = (rect[3] + m).coerceAtMost(mx.rows())
        val raw = Mat(mx, org.opencv.core.Rect(l, t, r - l, b - t)).clone()
        val th = topHat(raw, TOPHAT_K)
        raw.release()
        val il = rect[0] - l
        val it = rect[1] - t
        val iw = rect[2] - rect[0]
        val ih = rect[3] - rect[1]
        if (il + iw > th.cols() || it + ih > th.rows() || iw < 2 || ih < 2) return th
        val out = Mat(th, org.opencv.core.Rect(il, it, iw, ih)).clone()
        th.release()
        return out
    }

    private fun scaleToScreen(src: Bitmap): Bitmap {
        if (src.width == SW && src.height == SH) return src
        // 画面全体に引き伸ばし（公式フォールバックと同じ考え方。ROIを画面座標に載せる）
        val out = Bitmap.createBitmap(SW, SH, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, null, Rect(0, 0, SW, SH), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun maxChannel(rgba: Mat): Mat {
        val ch = ArrayList<Mat>(4)
        Core.split(rgba, ch)
        val out = Mat()
        Core.max(ch[0], ch[1], out)
        Core.max(out, ch[2], out)
        ch.forEach { it.release() }
        return out
    }

    private fun topHat(src: Mat, k: Int): Mat {
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(k.toDouble(), k.toDouble()))
        val out = Mat()
        Imgproc.morphologyEx(src, out, Imgproc.MORPH_TOPHAT, kernel)
        kernel.release()
        return out
    }
}
