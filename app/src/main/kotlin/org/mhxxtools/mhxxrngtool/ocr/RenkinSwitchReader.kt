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
import org.mhxxtools.mhxxrngtool.rng.SKILL_NAMES
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max

/**
 * mhxx-renkin-reader の read_screen と同じ手順:
 *  max_channel → panel_offset(label) で平行移動 → ROI ごと tophat
 *  → matchTemplate(TM_CCOEFF_NORMED) + leftover 減点 + 上位2件の margin 判定
 */
object RenkinSwitchReader {

    private const val TAG = "RenkinSwitch"

    data class Result(val charm: OcrCharm, val note: String, val confidence: Float)

    private const val SW = 1280
    private const val SH = 720
    private const val TOPHAT_K = 25

    // read.rs 定数
    // 公式 read.rs と同じ座標 (1280x720)
    private val NAME1 = intArrayOf(655, 165, 800, 191)
    private val PTS1  = intArrayOf(795, 165, 850, 191)
    private val NAME2 = intArrayOf(655, 194, 800, 218)
    private val PTS2  = intArrayOf(795, 194, 850, 218)
    private const val NAME_LEFT = 667
    private const val NAME_SEARCH = 6
    private val LABEL_BLOCK = intArrayOf(476, 166, 570, 247) // x0,y0,x1,y1
    private const val LABEL_SEARCH = 30
    private const val LABEL_MIN = 0.50
    private val SLOT_X = intArrayOf(580, 604, 628)
    private const val SLOT_Y = 224
    private val SLOT_CROP = intArrayOf(-1, -2, 17, 18)
    private const val SLOT_SEARCH = 4

    private const val SKILL_MIN = 0.35
    private const val DIGIT_MIN = 0.30
    private const val SLOT_MIN = 0.50
    private const val SLOT_MIN_DIFF = 0.20
    private const val LEFTOVER = 0.5
    private const val RECHECK_MARGIN = 0.20

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

    suspend fun read(context: Context, bitmap: Bitmap): Result? = withContext(Dispatchers.Default) {
        if (!OpenCvBridge.ensureInit()) {
            return@withContext Result(OcrCharm(-1, -1, emptyList()), "OpenCV未初期化", 0f)
        }
        ensureLoaded(context)
        if (skillTmpls.isEmpty()) {
            return@withContext Result(OcrCharm(-1, -1, emptyList()), "skills空", 0f)
        }

        val screenBmp = if (isSwitchCapture(bitmap)) scaleToScreen(bitmap) else {
            val ref = loadBmp(context, "renkin/reference.png")
            if (ref != null) {
                try {
                    OpenCvBridge.alignWithReference(bitmap, ref, SW, SH) ?: scaleToScreen(bitmap)
                } finally { ref.recycle() }
            } else scaleToScreen(bitmap)
        }

        try {
            val rgba = OpenCvBridge.bitmapToMat(screenBmp)
            var mx = maxChannel(rgba)
            rgba.release()

            // panel_offset: ラベルでずれを測り、見つかったら画像を戻す
            val (dx, dy, labScore) = panelOffset(mx)
            if (labScore >= LABEL_MIN) {
                val shifted = translate(mx, dx, dy)
                mx.release()
                mx = shifted
                Log.i(TAG, "panel_offset dx=$dx dy=$dy score=%.3f".format(labScore))
            } else {
                Log.i(TAG, "panel_offset skip score=%.3f".format(labScore))
            }

            // ROI は tophat 済みで切る（tophat_region）
            val name1 = tophatRegion(mx, NAME1)
            val pts1  = tophatRegion(mx, PTS1)
            val name2 = tophatRegion(mx, NAME2)
            val pts2  = tophatRegion(mx, PTS2)

            var (s1Id, s1Sc) = matchSkill(name1)
            val (p1, p1Sc) = matchDigit(pts1)
            val skill2Empty = isDashes(name2)
            var (s2Id, s2Sc) = if (skill2Empty) null to 1.0 else matchSkill(name2)
            val (p2, p2Sc) = if (skill2Empty) null to 1.0 else matchDigit(pts2)
            val (slots, slotNote) = matchSlots(mx)

            name1.release(); pts1.release(); name2.release(); pts2.release()
            mx.release()

            // ── 錬金134以外も含む全 SKILL_NAMES を OCR で補強 ──
            // テンプレ照合は公式134向け見本が強く、非公式スキルは誤マッチしやすいため
            // スキル名ROIを ML Kit で読み、SkillMatcher で全205スキルに解決する
            var ocrNote = ""
            try {
                val ocr1 = refineSkillByOcr(screenBmp, NAME1, s1Id, s1Sc)
                if (ocr1 != null) {
                    s1Id = ocr1.first
                    s1Sc = ocr1.second
                    ocrNote += " ocr1=${SKILL_NAMES.getOrElse(s1Id!!) { "?" }}"
                }
                if (!skill2Empty) {
                    val ocr2 = refineSkillByOcr(screenBmp, NAME2, s2Id, s2Sc)
                    if (ocr2 != null) {
                        s2Id = ocr2.first
                        s2Sc = ocr2.second
                        ocrNote += " ocr2=${SKILL_NAMES.getOrElse(s2Id!!) { "?" }}"
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "ocr refine failed", e)
            }

            val skills = mutableListOf<OcrSkill>()
            if (s1Id != null && s1Sc >= SKILL_MIN) {
                val pts = (p1 ?: 0).coerceIn(0, 20)
                skills += OcrSkill(s1Id, SKILL_NAMES.getOrElse(s1Id) { "s$s1Id" }, pts)
            }
            if (!skill2Empty && s2Id != null && s2Sc >= SKILL_MIN) {
                val pts = (p2 ?: 0).coerceIn(0, 20)
                skills += OcrSkill(s2Id, SKILL_NAMES.getOrElse(s2Id) { "s$s2Id" }, pts)
            }

            if (skills.isEmpty()) {
                return@withContext Result(
                    OcrCharm(-1, slots, emptyList()),
                    "fail s1=%.2f s2=%.2f empty2=$skill2Empty lab=%.2f".format(s1Sc, s2Sc, labScore),
                    0.1f
                )
            }

            val conf = when {
                skills.size >= 2 && slots in 0..3 && s1Sc >= 0.5 -> 0.97f
                skills.size >= 1 && slots in 0..3 -> 0.90f
                else -> 0.7f
            }
            val note = "renkin " + skills.joinToString("+") { "${it.name}+${it.pts}" } +
                " sl=$slots s1=%.2f".format(s1Sc) +
                (if (skills.size > 1) " s2=%.2f".format(s2Sc) else " (no-s2)") +
                " lab=%.2f $slotNote$ocrNote".format(labScore)
            Log.i(TAG, note)
            Result(OcrCharm(-1, slots, skills), note, conf)
        } finally {
            if (screenBmp !== bitmap) screenBmp.recycle()
        }
    }

    // ── load ──────────────────────────────────────────────────────────

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
            Log.i(TAG, "loaded skills=${skillTmpls.size} digits=${digitTmpls.size} label=${labelMat != null}")
        }
    }

    // ── panel offset (label.png) ──────────────────────────────────────

    private fun panelOffset(mx: Mat): Triple<Int, Int, Double> {
        val label = labelMat ?: return Triple(0, 0, 0.0)
        val x0 = LABEL_BLOCK[0]; val y0 = LABEL_BLOCK[1]
        val x1 = LABEL_BLOCK[2]; val y1 = LABEL_BLOCK[3]
        val r = LABEL_SEARCH
        // 探索窓: LABEL_BLOCK ± SEARCH の tophat
        val wl = (x0 - r).coerceAtLeast(0)
        val wt = (y0 - r).coerceAtLeast(0)
        val wr = (x1 + r).coerceAtMost(mx.cols())
        val wb = (y1 + r).coerceAtMost(mx.rows())
        val winRaw = Mat(mx, org.opencv.core.Rect(wl, wt, wr - wl, wb - wt)).clone()
        val win = topHat(winRaw, TOPHAT_K)
        winRaw.release()
        if (label.cols() > win.cols() || label.rows() > win.rows()) {
            win.release(); return Triple(0, 0, 0.0)
        }
        val result = Mat()
        Imgproc.matchTemplate(win, label, result, Imgproc.TM_CCOEFF_NORMED)
        val mm = Core.minMaxLoc(result)
        result.release()
        win.release()
        // 窓内の max 位置 → 画像全体でのラベル左上
        // 理想ラベル位置は (x0,y0)、検出は (wl+maxX, wt+maxY)
        // translate 量 = 検出 - 理想（renkin: x as i64 - r）
        val detX = wl + mm.maxLoc.x.toInt()
        val detY = wt + mm.maxLoc.y.toInt()
        val dx = detX - x0
        val dy = detY - y0
        // renkin translate は「ずれの分だけ画像を戻す」= -offset 方向
        // panel_offset 戻り値は (x - r, y - r, v) where x,y are in the search window
        // then translate(mx, panel.0, panel.1)
        // match_template on win of size covering (x0-r..x1+r), max at (x,y) in win coords
        // return (x - r, y - r, v)
        val px = mm.maxLoc.x.toInt() - r
        val py = mm.maxLoc.y.toInt() - r
        return Triple(px, py, mm.maxVal)
    }

    private fun translate(src: Mat, dx: Int, dy: Int): Mat {
        if (dx == 0 && dy == 0) return src.clone()
        val out = Mat.zeros(src.size(), src.type())
        val M = Mat.zeros(2, 3, CvType.CV_64F)
        M.put(0, 0, 1.0, 0.0, -dx.toDouble())
        M.put(1, 0, 0.0, 1.0, -dy.toDouble())
        // renkin translate(dx,dy): 画素を +dx,+dy 方向へ？ 
        // "ずれの分だけ画像を戻す" → if label appeared at +dx from expected, shift image by -dx
        // panel returns (x-r, y-r) which is the offset of detected vs expected origin of search
        // Then translate(&mx, panel.0, panel.1) 
        // Looking at translate implementation needed...
        Imgproc.warpAffine(src, out, M, src.size(), Imgproc.INTER_NEAREST, Core.BORDER_CONSTANT, org.opencv.core.Scalar(0.0))
        M.release()
        return out
    }

    // ── matching ─────────────────────────────────────────────────────

    private fun matchSkill(roiTh: Mat): Pair<Int?, Double> {
        data class Cand(val id: Int, val score: Double, val x: Int)
        val cands = mutableListOf<Cand>()
        for (t in skillTmpls) {
            // 見本がROIより大きい場合は高さいっぱいに縮小（サイトの 0.97〜1.03 に加え必要ならさらに）
            val scales = doubleArrayOf(0.85, 0.90, 0.94, 0.97, 1.0, 1.03, 1.06)
            for (sc in scales) {
                var tw = (t.mat.cols() * sc).toInt().coerceAtLeast(1)
                var th = (t.mat.rows() * sc).toInt().coerceAtLeast(1)
                if (th > roiTh.rows() || tw > roiTh.cols()) {
                    val fit = minOf(
                        roiTh.rows().toDouble() / t.mat.rows(),
                        roiTh.cols().toDouble() / t.mat.cols()
                    ) * 0.98
                    if (fit < 0.5) continue
                    tw = (t.mat.cols() * fit).toInt().coerceAtLeast(1)
                    th = (t.mat.rows() * fit).toInt().coerceAtLeast(1)
                }
                if (th > roiTh.rows() || tw > roiTh.cols()) continue
                val scaled = Mat()
                Imgproc.resize(t.mat, scaled, Size(tw.toDouble(), th.toDouble()))
                val pair = matchNcc(roiTh, scaled)
                if (pair == null) { scaled.release(); continue }
                // 公式: 名前は左揃え。右に大きくずれて短い別名に吸われるのを防ぐ
                val preferLeft = (NAME_LEFT - NAME1[0] - NAME_SEARCH)
                val preferRight = (NAME_LEFT - NAME1[0] + NAME_SEARCH + 8)
                val xOk = pair.second in preferLeft..preferRight || t.mat.cols() >= 60
                if (!xOk && pair.first < 0.75) { scaled.release(); continue }
                val adj = pair.first - LEFTOVER * leftoverRatio(roiTh, pair.second, scaled.cols())
                cands += Cand(t.id, adj, pair.second)
                // scaled は Cand に保持しないので解放（スコアだけ使う）
                scaled.release()
            }
        }
        if (cands.isEmpty()) return null to -1.0
        cands.sortByDescending { it.score }
        var best = cands[0]
        var margin = if (cands.size > 1) best.score - cands[1].score else best.score + 1.0

        // margin が極端に小さいときは 2 位を棄却しやすくする
        if (cands.size > 1 && margin < 0.02 && best.score < 0.55) {
            // ほぼ同点 → 信頼できない
            return null to best.score
        }
        if (best.score < SKILL_MIN) return null to best.score
        return best.id to best.score
    }

    private fun recheckScore(roi: Mat, tmpl: Mat, x: Int): Double {
        // 簡易: マッチ位置での NCC をそのまま使う
        if (x + tmpl.cols() > roi.cols()) return -1.0
        val sub = Mat(roi, org.opencv.core.Rect(x, 0, minOf(tmpl.cols() + 2, roi.cols() - x), roi.rows())).clone()
        val pair = matchNcc(sub, tmpl)
        sub.release()
        return pair?.first ?: -1.0
    }

    private fun matchDigit(roiTh: Mat): Pair<Int?, Double> {
        var bestN: Int? = null
        var best = -1.0
        for (t in digitTmpls) {
            for (sc in doubleArrayOf(0.9, 1.0, 1.15, 1.3)) {
                val scaled = Mat()
                Imgproc.resize(t.mat, scaled, Size(t.mat.cols() * sc, t.mat.rows() * sc))
                if (scaled.cols() > roiTh.cols() || scaled.rows() > roiTh.rows()) {
                    scaled.release(); continue
                }
                val pair = matchNcc(roiTh, scaled)
                scaled.release()
                if (pair != null && pair.first > best) {
                    best = pair.first
                    bestN = t.id
                }
            }
        }
        return if (best >= DIGIT_MIN) bestN to best else null to best
    }

    private fun matchSlots(mx: Mat): Pair<Int, String> {
        val circle = circleMat ?: return -1 to "no-tmpl"
        val dash = dashMat ?: return -1 to "no-tmpl"
        var count = 0
        val notes = mutableListOf<String>()
        val s = SLOT_SEARCH
        for (cx in SLOT_X) {
            val l = (cx + SLOT_CROP[0] - s).coerceAtLeast(0)
            val t = (SLOT_Y + SLOT_CROP[1] - s).coerceAtLeast(0)
            val r = (cx + SLOT_CROP[2] + s).coerceAtMost(mx.cols())
            val b = (SLOT_Y + SLOT_CROP[3] + s).coerceAtMost(mx.rows())
            val raw = Mat(mx, org.opencv.core.Rect(l, t, r - l, b - t)).clone()
            val roi = topHat(raw, 9)
            raw.release()
            var bestC = -1.0
            var bestD = -1.0
            for (sc in doubleArrayOf(0.9, 1.0, 1.1)) {
                fun tryM(tmpl: Mat): Double {
                    val scMat = Mat()
                    Imgproc.resize(tmpl, scMat, Size(tmpl.cols() * sc, tmpl.rows() * sc))
                    if (scMat.cols() > roi.cols() || scMat.rows() > roi.rows()) {
                        scMat.release(); return -1.0
                    }
                    val p = matchNcc(roi, scMat)
                    scMat.release()
                    return p?.first ?: -1.0
                }
                bestC = max(bestC, tryM(circle))
                bestD = max(bestD, tryM(dash))
            }
            roi.release()
            notes += "c=%.2f/d=%.2f".format(bestC, bestD)
            if (bestC >= SLOT_MIN && bestC - bestD >= SLOT_MIN_DIFF) count++
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

    /**
     * 公式 is_dashes: 強い横線が狭い帯に集中していれば「-----」。
     * 短いスキル名の一画を横線と誤認しないよう、帯内インク比率も見る。
     */
        /**
     * 公式 is_dashes: 強い横線が狭い帯に集中していれば「-----」。
     */

    private suspend fun refineSkillByOcr(
        screen: android.graphics.Bitmap,
        rect: IntArray,
        tmplId: Int?,
        tmplScore: Double
    ): Pair<Int, Double>? {
        val l = rect[0].toFloat() / SW
        val t0 = rect[1].toFloat() / SH
        val r = rect[2].toFloat() / SW
        val b = rect[3].toFloat() / SH
        val text = AndroidOcr.recognizeCropped(screen, l, t0, r, b).getOrNull()
            ?.trim().orEmpty()
        if (text.isBlank()) return null
        val idx = SkillMatcher.findSkillGlobalIdx(text)
        if (idx < 0) {
            android.util.Log.i(TAG, "ocr skill miss text='$text' tmpl=$tmplId sc=$tmplScore")
            return null
        }
        if (tmplId != null && tmplId == idx && tmplScore >= 0.50) return null
        val preferOcr = tmplId == null || tmplScore < 0.55 || tmplId != idx
        if (!preferOcr) return null
        val sc = if (tmplId == idx) maxOf(tmplScore, 0.60) else 0.62
        android.util.Log.i(TAG, "ocr refine '$text' -> ${SKILL_NAMES.getOrElse(idx){ "?" }}($idx) was=$tmplId/$tmplScore")
        return idx to sc
    }

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
        if (totalInk < 8) return true
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
        // 余白付きで crop → tophat → 中央を切り出し（renkin tophat_region）
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
