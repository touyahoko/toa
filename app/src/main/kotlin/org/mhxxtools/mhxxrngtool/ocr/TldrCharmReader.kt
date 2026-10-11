package org.mhxxtools.mhxxrngtool.ocr

import org.mhxxtools.mhxxrngtool.rng.SKILL_NAMES

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * note.com/fwe TL;DR 方式 + ML Kit 統合
 *
 * 1. 射影で 1280×720 にそろえる
 * 2. トップハット風前処理
 * 3. 固定座標を ML Kit (日+英) で OCR
 * 4. テンプレ照合とマージ（名前はテンプレ優先、ポイントは両方）
 * 5. 低自信は撮り直し
 */
object TldrCharmReader {

    private const val CANON_W = 1280
    private const val CANON_H = 720

    data class Result(
        val charm: OcrCharm,
        val note: String,
        val confidence: Float
    )

    suspend fun read(context: Context, src: Bitmap): kotlin.Result<Result> =
        withContext(Dispatchers.Default) {
            runCatching {
                // OpenCV 優先: ORB+RANSAC 射影 + morphology トップハット
                var usedOpenCv = false
                val aligned: Bitmap
                val enhanced: Bitmap
                if (OpenCvBridge.ensureInit()) {
                    val cvAligned = OpenCvBridge.alignToCanonical(src, CANON_W, CANON_H)
                    if (cvAligned != null) {
                        aligned = cvAligned
                        enhanced = OpenCvBridge.topHat(aligned) ?: topHatEnhance(aligned)
                        usedOpenCv = true
                    } else {
                        val quad = detectPanelQuad(src)
                        aligned = if (quad != null) {
                            warpPerspective(src, quad, CANON_W, CANON_H)
                        } else {
                            letterboxToCanon(src, CANON_W, CANON_H)
                        }
                        enhanced = OpenCvBridge.topHat(aligned) ?: topHatEnhance(aligned)
                    }
                } else {
                    val quad = detectPanelQuad(src)
                    aligned = if (quad != null) {
                        warpPerspective(src, quad, CANON_W, CANON_H)
                    } else {
                        letterboxToCanon(src, CANON_W, CANON_H)
                    }
                    enhanced = topHatEnhance(aligned)
                }

                // ML Kit はカラー射影画像、テンプレはトップハット画像
                val mlCharm = mlKitFixedRead(aligned)
                val mlCharm2 = mlKitFixedRead(enhanced)

                // テンプレ照合
                val tmpl = CharmTemplateReader.read(context, enhanced).getOrNull()
                    ?: CharmTemplateReader.read(context, aligned).getOrNull()

                val slotByHeight = detectSlotsByBlobHeight(enhanced)
                val merged0 = mergeAll(mlCharm, tmpl?.charm, slotByHeight)
                val merged = mergeAll(merged0, mlCharm2, slotByHeight)

                aligned.recycle()
                if (enhanced !== aligned) enhanced.recycle()

                if (merged.skills.isEmpty() && merged.slots < 0) {
                    error(
                        "スマホ写真から護石を読めませんでした。\n" +
                            "・鑑定パネル全体が入るように\n" +
                            "・反射・ピンボケを避けて\n" +
                            "撮り直してください。"
                    )
                }

                var conf = 0.50f
                if (usedOpenCv) conf += 0.15f
                if (mlCharm.skills.isNotEmpty()) conf += 0.15f
                if (tmpl != null && tmpl.charm.skills.isNotEmpty()) conf += 0.12f
                if (merged.skills.isNotEmpty()) conf += 0.08f
                if (merged.slots in 0..3) conf += 0.10f
                if (merged.skills.size >= 2) conf += 0.05f
                conf = conf.coerceIn(0.5f, 0.99f)

                if (merged.skills.isEmpty()) {
                    error(
                        "認識の自信が低いです（conf=${"%.2f".format(conf)}）。\n" +
                            "正面からパネル全体が入るよう撮り直してください。"
                    )
                }

                val note = buildString {
                    append("tldr+mlkit ")
                    append(if (usedOpenCv) "opencv" else "fallback")
                    append(" ml=${mlCharm.skills.size}")
                    append(" tmpl=${tmpl?.charm?.skills?.size ?: 0}")
                    append(" → ")
                    append(merged.skills.joinToString("+") { "${it.name}+${it.pts}" })
                    append(" sl=${merged.slots}")
                }
                Result(merged, note, conf)
            }
        }

    /** 正規化画像に対し CropRegion を ML Kit で読む */
    private suspend fun mlKitFixedRead(canon: Bitmap): OcrCharm = coroutineScope {
        val j1n = async { ocrCrop(canon, CropRegion.SKILL1_NAME) }
        val j1p = async { ocrCrop(canon, CropRegion.SKILL1_PTS) }
        val j2n = async { ocrCrop(canon, CropRegion.SKILL2_NAME) }
        val j2p = async { ocrCrop(canon, CropRegion.SKILL2_PTS) }
        val js = async { ocrCrop(canon, CropRegion.SLOT) }
        val jp = async { ocrCrop(canon, CropRegion.PANEL) }

        val t1n = j1n.await()
        val t1p = j1p.await()
        val t2n = j2n.await()
        val t2p = j2p.await()
        val ts = js.await()
        val tp = jp.await()

        val skills = mutableListOf<OcrSkill>()
        parseSkillLine(t1n, t1p)?.let { skills += it }
        parseSkillLine(t2n, t2p)?.let { skills += it }

        // パネル全体から補完
        if (skills.isEmpty() && tp.isNotBlank()) {
            skills += parseOcrCharm(tp).skills
        } else if (skills.size < 2 && tp.isNotBlank()) {
            val panel = parseOcrCharm(tp).skills
            for (s in panel) {
                if (skills.none { it.globalIdx == s.globalIdx }) {
                    skills += s
                    if (skills.size >= 2) break
                }
            }
        }

        var slots = parseSlotText(ts)
        if (slots < 0 && tp.isNotBlank()) {
            slots = parseOcrCharm(tp).slots
        }
        OcrCharm(-1, slots, skills)
    }

    private suspend fun ocrCrop(bmp: Bitmap, r: FloatArray): String =
        AndroidOcr.recognizeCropped(bmp, r[0], r[1], r[2], r[3]).getOrDefault("")

    private fun parseSkillLine(nameText: String, ptsText: String): OcrSkill? {
        val combined = "$nameText $ptsText"
        if (isEmptySkill(nameText) && isEmptySkill(ptsText)) return null

        val pts = Regex("\\+?\\s*(\\d{1,2})")
            .findAll(ptsText + " " + nameText)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .firstOrNull { it in 1..20 }
            ?: 0

        val fromParse = parseOcrCharm(combined).skills.firstOrNull()
        if (fromParse != null) {
            return if (pts > 0) OcrSkill(fromParse.globalIdx, fromParse.name, pts) else fromParse
        }

        val norm = nameText
            .replace(Regex("[　\\s]+"), "")
            .replace(Regex("[+＋]\\d+"), "")
            .replace(Regex("固有スキル[12]?"), "")
        if (isEmptySkill(norm)) return null

        for ((alias, idx) in SKILL_ALIASES.entries.sortedByDescending { it.key.length }) {
            if (alias.length < 2) continue
            if (norm.contains(alias)) {
                val name = SKILL_NAMES.getOrNull(idx) ?: alias
                return OcrSkill(idx, name, pts)
            }
        }
        return null
    }

    private fun isEmptySkill(t: String): Boolean {
        val s = t.replace(Regex("[\\s　\\-_ー−–—―=＝]+"), "").trim()
        return s.isEmpty() || s in setOf("なし", "無", "(無)", "（なし）")
    }

    private fun parseSlotText(t: String): Int {
        if (t.isBlank()) return -1
        parseSlotCrop(t)?.let { return it }
        val circles = Regex("[○〇◯●◎◉]").findAll(t).count()
        if (circles in 1..3) return circles
        val dashes = t.replace(Regex("[\\s　]"), "")
        if (Regex("^[-_ー−–—―]{2,}$").matches(dashes)) return 0
        return -1
    }

    private fun mergeAll(ml: OcrCharm, tmpl: OcrCharm?, slotH: Int): OcrCharm {
        val skills = mutableListOf<OcrSkill>()
        val n = maxOf(ml.skills.size, tmpl?.skills?.size ?: 0)
        for (i in 0 until n) {
            val m = ml.skills.getOrNull(i)
            val t = tmpl?.skills?.getOrNull(i)
            when {
                t != null && m != null -> {
                    // 名前はテンプレ（正式名）優先、ポイントは有効な方
                    val pts = when {
                        t.pts > 0 -> t.pts
                        m.pts > 0 -> m.pts
                        else -> 0
                    }
                    skills += OcrSkill(t.globalIdx, t.name, pts)
                }
                t != null -> skills += t
                m != null -> skills += m
            }
        }
        // テンプレに無いが ML だけ読めたスキルを末尾に追加
        if (tmpl != null) {
            for (m in ml.skills) {
                if (skills.none { it.globalIdx == m.globalIdx }) skills += m
            }
        }
        val slots = when {
            tmpl != null && tmpl.slots in 0..3 -> tmpl.slots
            ml.slots in 0..3 -> ml.slots
            slotH in 0..3 -> slotH
            else -> -1
        }
        return OcrCharm(-1, slots, skills.take(2))
    }

    // ── 射影・前処理（前回と同じ軽量版） ────────────────────────────

    private data class Quad(
        val tlX: Float, val tlY: Float,
        val trX: Float, val trY: Float,
        val brX: Float, val brY: Float,
        val blX: Float, val blY: Float
    )

    private fun detectPanelQuad(bmp: Bitmap): Quad? {
        val w = bmp.width
        val h = bmp.height
        if (w < 200 || h < 200) return null
        val sw = 160
        val sh = (h * sw / w.toFloat()).roundToInt().coerceAtLeast(90)
        val small = Bitmap.createScaledBitmap(bmp, sw, sh, true)
        val pixels = IntArray(sw * sh)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        val mask = BooleanArray(sw * sh)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            mask[i] = mx in 20..140 && (mx - mn) < 55
        }
        var minX = sw; var minY = sh; var maxX = 0; var maxY = 0
        var count = 0
        for (y in 0 until sh) for (x in 0 until sw) {
            if (!mask[y * sw + x]) continue
            count++
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }
        if (small !== bmp) small.recycle()
        if (count < sw * sh * 0.08f) return null
        val bw = maxX - minX + 1
        val bh = maxY - minY + 1
        if (bw < sw * 0.25f || bh < sh * 0.2f) return null
        val padX = bw * 0.04f
        val padY = bh * 0.04f
        val sx = w / sw.toFloat()
        val sy = h / sh.toFloat()
        return Quad(
            (minX + padX) * sx, (minY + padY) * sy,
            (maxX - padX) * sx, (minY + padY) * sy,
            (maxX - padX) * sx, (maxY - padY) * sy,
            (minX + padX) * sx, (maxY - padY) * sy
        )
    }

    private fun warpPerspective(src: Bitmap, q: Quad, dw: Int, dh: Int): Bitmap {
        val m = Matrix()
        val ok = m.setPolyToPoly(
            floatArrayOf(q.tlX, q.tlY, q.trX, q.trY, q.brX, q.brY, q.blX, q.blY),
            0,
            floatArrayOf(0f, 0f, dw.toFloat(), 0f, dw.toFloat(), dh.toFloat(), 0f, dh.toFloat()),
            0,
            4
        )
        val out = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        if (ok) {
            canvas.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        } else {
            return letterboxToCanon(src, dw, dh)
        }
        return out
    }

    private fun letterboxToCanon(src: Bitmap, dw: Int, dh: Int): Bitmap {
        val out = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val scale = min(dw / src.width.toFloat(), dh / src.height.toFloat())
        val nw = src.width * scale
        val nh = src.height * scale
        val m = Matrix()
        m.setScale(scale, scale)
        m.postTranslate((dw - nw) / 2f, (dh - nh) / 2f)
        canvas.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun topHatEnhance(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val bright = FloatArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            bright[i] = max((c shr 16) and 0xFF, max((c shr 8) and 0xFF, c and 0xFF)) / 255f
        }
        val blur = boxBlur(bright, w, h, radius = max(6, min(w, h) / 40))
        val outPx = IntArray(w * h)
        for (i in bright.indices) {
            val v = ((bright[i] - blur[i]) + 0.15f).coerceIn(0f, 1f)
            val ig = ((v * 1.6f).coerceIn(0f, 1f) * 255).toInt()
            outPx[i] = Color.rgb(ig, ig, ig)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(outPx, 0, w, 0, 0, w, h)
        return out
    }

    private fun boxBlur(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        val r = radius.coerceAtLeast(1)
        for (y in 0 until h) {
            var sum = 0f
            val row = y * w
            for (x in 0 until min(w, r)) sum += src[row + x]
            for (x in 0 until w) {
                val add = x + r
                val sub = x - r - 1
                if (add < w) sum += src[row + add]
                if (sub >= 0) sum -= src[row + sub]
                val cnt = min(w - 1, x + r) - max(0, x - r) + 1
                tmp[row + x] = sum / cnt
            }
        }
        for (x in 0 until w) {
            var sum = 0f
            for (y in 0 until min(h, r)) sum += tmp[y * w + x]
            for (y in 0 until h) {
                val add = y + r
                val sub = y - r - 1
                if (add < h) sum += tmp[add * w + x]
                if (sub >= 0) sum -= tmp[sub * w + x]
                val cnt = min(h - 1, y + r) - max(0, y - r) + 1
                out[y * w + x] = sum / cnt
            }
        }
        return out
    }

    private fun detectSlotsByBlobHeight(aligned: Bitmap): Int {
        val w = aligned.width
        val h = aligned.height
        val left = (w * 0.42f).toInt()
        val top = (h * 0.28f).toInt()
        val right = (w * 0.62f).toInt()
        val bottom = (h * 0.36f).toInt()
        if (right - left < 10 || bottom - top < 5) return -1
        val rw = right - left
        val rh = bottom - top
        val px = IntArray(rw * rh)
        aligned.getPixels(px, 0, rw, left, top, rw, rh)
        var minB = 255
        var maxB = 0
        val br = IntArray(rw * rh)
        for (i in px.indices) {
            val c = px[i]
            val v = max((c shr 16) and 0xFF, max((c shr 8) and 0xFF, c and 0xFF))
            br[i] = v
            if (v < minB) minB = v
            if (v > maxB) maxB = v
        }
        if (maxB - minB < 15) return -1
        val thr = (minB + maxB) / 2
        val bin = BooleanArray(br.size) { br[it] >= thr }
        val visited = BooleanArray(bin.size)
        var longBlobs = 0
        val stack = ArrayDeque<Int>()
        for (i in bin.indices) {
            if (!bin[i] || visited[i]) continue
            stack.clear()
            stack.add(i)
            visited[i] = true
            var minY = i / rw
            var maxY = minY
            var size = 0
            while (stack.isNotEmpty()) {
                val p = stack.removeFirst()
                size++
                val y = p / rw
                val x = p % rw
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until rw || ny !in 0 until rh) continue
                    val ni = ny * rw + nx
                    if (!bin[ni] || visited[ni]) continue
                    visited[ni] = true
                    stack.add(ni)
                }
            }
            if (size < 8) continue
            val height = maxY - minY + 1
            if (height >= rh * 0.35f) longBlobs++
        }
        return longBlobs.coerceIn(0, 3)
    }
}
