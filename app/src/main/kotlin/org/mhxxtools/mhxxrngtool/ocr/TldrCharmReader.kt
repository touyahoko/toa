package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * note.com/fwe の TL;DR 方式（スマホ直撮り向け）:
 * 1. 射影で Switch 1280×720 相当にそろえる
 * 2. トップハット風前処理で反射を落とす
 * 3. 固定座標テンプレ照合（CharmTemplateReader）
 * 4. 自信が低いときは失敗（撮り直し）を返す
 *
 * OpenCV なしで Android 標準 API のみ使用。
 */
object TldrCharmReader {

    private const val CANON_W = 1280
    private const val CANON_H = 720

    data class Result(
        val charm: OcrCharm,
        val note: String,
        val confidence: Float,
        val aligned: Bitmap? = null
    )

    suspend fun read(context: Context, src: Bitmap): kotlin.Result<Result> =
        withContext(Dispatchers.Default) {
            runCatching {
                // 1. パネル四隅推定 → 射影変換
                val quad = detectPanelQuad(src)
                val aligned = if (quad != null) {
                    warpPerspective(src, quad, CANON_W, CANON_H)
                } else {
                    // フォールバック: 中央 16:9 クロップ → 正規化
                    letterboxToCanon(src, CANON_W, CANON_H)
                }

                // 2. トップハット風前処理（反射・ムラ軽減）
                val enhanced = topHatEnhance(aligned)

                // 3. テンプレ照合（固定座標相当のパネル探索付き）
                val tmpl = CharmTemplateReader.read(context, enhanced).getOrNull()
                    ?: CharmTemplateReader.read(context, aligned).getOrNull()

                // 4. スロットを記事方式（○/− の高さ）でも推定
                val slotByHeight = detectSlotsByBlobHeight(enhanced)

                val base = tmpl?.charm
                val skills = base?.skills.orEmpty()
                val slots = when {
                    base != null && base.slots in 0..3 -> base.slots
                    slotByHeight in 0..3 -> slotByHeight
                    else -> -1
                }

                if (skills.isEmpty() && slots < 0) {
                    aligned.recycle()
                    if (enhanced !== aligned) enhanced.recycle()
                    error(
                        "スマホ写真から護石を読めませんでした。\n" +
                            "・鑑定パネル全体が入るように\n" +
                            "・反射・ピンボケを避けて\n" +
                            "撮り直してください。"
                    )
                }

                val charm = OcrCharm(
                    kind = base?.kind ?: -1,
                    slots = slots,
                    skills = skills
                )

                // 自信度: スキル数・スロット・射影成功
                var conf = 0.55f
                if (quad != null) conf += 0.15f
                if (skills.isNotEmpty()) conf += 0.15f
                if (skills.size >= 2) conf += 0.05f
                if (slots in 0..3) conf += 0.10f
                if (tmpl != null) conf += 0.05f
                conf = conf.coerceIn(0.5f, 0.99f)

                // 低自信は失敗扱いに近い注意
                if (conf < 0.70f || skills.isEmpty()) {
                    aligned.recycle()
                    if (enhanced !== aligned) enhanced.recycle()
                    error(
                        "認識の自信が低いです（conf=${"%.2f".format(conf)}）。\n" +
                            "もう少し正面から、パネル全体が入るよう撮り直してください。"
                    )
                }

                val note = buildString {
                    append("tldr-photo ")
                    append(if (quad != null) "warp" else "letterbox")
                    append(" tmpl=${tmpl != null}")
                    append(" slotH=$slotByHeight")
                    append(" → ")
                    append(skills.joinToString("+") { "${it.name}+${it.pts}" })
                    append(" sl=$slots")
                }

                // aligned はデバッグ用に返す場合のみ残す。ここでは解放。
                if (enhanced !== aligned) enhanced.recycle()
                aligned.recycle()

                Result(charm, note, conf, aligned = null)
            }
        }

    // ── パネル四隅（簡易: 暗い半透明パネル領域の外接矩形） ──────────

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

        // 縮小してスキャン
        val sw = 160
        val sh = (h * sw / w.toFloat()).roundToInt().coerceAtLeast(90)
        val small = Bitmap.createScaledBitmap(bmp, sw, sh, true)
        val pixels = IntArray(sw * sh)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)

        // 暗い半透明UIっぽい画素（低〜中輝度）をマスク
        val mask = BooleanArray(sw * sh)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            // 暗め〜中間、彩度低め
            mask[i] = mx in 20..140 && (mx - mn) < 55
        }

        // 最大の連結成分（簡易: 行スキャンで bbox）
        var minX = sw; var minY = sh; var maxX = 0; var maxY = 0
        var count = 0
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                if (!mask[y * sw + x]) continue
                count++
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
            }
        }
        if (small !== bmp) small.recycle()
        if (count < sw * sh * 0.08f) return null

        val bw = maxX - minX + 1
        val bh = maxY - minY + 1
        if (bw < sw * 0.25f || bh < sh * 0.2f) return null

        // 余白を少し内側に
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
            val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            canvas.drawBitmap(src, m, p)
        } else {
            // 失敗時レターボックス
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
        val left = (dw - nw) / 2f
        val top = (dh - nh) / 2f
        val m = Matrix()
        m.setScale(scale, scale)
        m.postTranslate(left, top)
        canvas.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /**
     * 記事のトップハット相当:
     * max(R,G,B) を明るさにし、大きいぼかしを引いて文字線を残す。
     */
    private fun topHatEnhance(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val bright = FloatArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            bright[i] = max(r, max(g, b)) / 255f
        }
        // 粗いボックスぼかし（構造要素 ≈ 文字より太い）
        val blur = boxBlur(bright, w, h, radius = max(6, min(w, h) / 40))
        val outPx = IntArray(w * h)
        for (i in bright.indices) {
            // top-hat: original - opening ≈ bright - blur（簡易）
            val v = ((bright[i] - blur[i]) + 0.15f).coerceIn(0f, 1f)
            // コントラスト強調
            val g = (v * 1.6f).coerceIn(0f, 1f)
            val ig = (g * 255).toInt()
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
        // 横
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
        // 縦
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

    /**
     * スロット欄: 二値化後のかたまりの上下長で ○(長) / −(短) を数える。
     * 固定相対座標（正規化後の右パネル下寄り）を使用。
     */
    private fun detectSlotsByBlobHeight(aligned: Bitmap): Int {
        val w = aligned.width
        val h = aligned.height
        // お守り情報パネル内のスロット行付近（経験的相対座標）
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

        // 連結成分の高さ
        val visited = BooleanArray(bin.size)
        var longBlobs = 0
        var shortBlobs = 0
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
            else if (height <= rh * 0.25f) shortBlobs++
        }
        // ○ の個数 = スロット数
        return longBlobs.coerceIn(0, 3)
    }
}
