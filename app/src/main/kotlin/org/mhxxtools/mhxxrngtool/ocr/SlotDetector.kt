package org.mhxxtools.mhxxrngtool.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 鑑定画面の「スロット」行だけから 0〜3 を判定する。
 *
 * 画像表示:
 *   --- / - - - → 0
 *   ○           → 1
 *   ○○         → 2
 *   ○○○       → 3
 *
 * 全文OCRの装飾○は使わない。切り出し行のテキストと画素のみ。
 */
object SlotDetector {

    /**
     * @return 0〜3、判定不能なら -1
     */
    fun detect(bitmap: Bitmap, ocrText: String?): Int {
        ocrText?.let { parseFromText(it) }?.let { return it }
        return detectFromPixels(bitmap)
    }

    /**
     * スロット行（または切り出し）の OCR テキストから判定。
     * 丸と横線の両方に対応。全文を渡さないこと。
     */
    fun parseFromText(raw: String): Int? {
        if (raw.isBlank()) return null
        var t = SkillMatcher.normalizeText(raw)
        // 丸系を ◯ に統一
        t = t.replace(Regex("[〇○◯ＯOｏo◎●◉]"), "◯")
        // スペース区切りの丸を連結  ◯ ◯ ◯ → ◯◯◯
        t = t.replace(Regex("(?:◯\\s*){1,3}◯")) { mr ->
            "◯".repeat(mr.value.count { it == '◯' })
        }

        // --- 横線（スペース区切り含む）。丸より先に「丸が無いとき」だけ 0 にするので
        //     ここでは丸を先に数える
        val runs = Regex("◯{1,3}").findAll(t).map { it.value.length }.toList()
        if (runs.isNotEmpty()) return runs.maxOrNull()!!.coerceIn(1, 3)

        // OCR が ○○○ を 000 / 00 と読む
        Regex("(?:スロ(?:ット)?\\s*[:：.]?\\s*)?([0]{2,3})(?![0-9])").find(t)?.let {
            return it.groupValues[1].length.coerceIn(2, 3)
        }
        // 0 0 0
        Regex("(?:スロ(?:ット)?\\s*[:：.]?\\s*)?((?:0\\s+){1,2}0)").find(t)?.let {
            val n = it.groupValues[1].count { ch -> ch == '0' }
            if (n in 2..3) return n
        }

        // 数字 1〜3
        Regex("スロット\\s*[:：.]?\\s*([1-3])(?![0-9])").find(t)?.let {
            return it.groupValues[1].toIntOrNull()
        }
        Regex("([1-3])\\s*スロ").find(t)?.let {
            return it.groupValues[1].toIntOrNull()
        }

        // 横線 → 0（丸が無い場合のみここに来る）
        // - ― ─ — – _ および全角ダッシュ、スペース区切り
        if (Regex("(?:[-－─—–―_ｰ]\\s*){2,}").containsMatchIn(t)) return 0
        if (Regex("なし|無|スロなし|0スロ").containsMatchIn(t)) return 0
        // 単独「スロット 0」
        if (Regex("スロット\\s*[:：.]?\\s*0(?![0-9])").containsMatchIn(t)) return 0

        return null
    }

    /**
     * スロット行 Bitmap から画素で判定。
     * - 円形に近い塊の個数 → 1〜3
     * - 横に伸びる線が主体で丸が無い → 0
     */
    fun detectFromPixels(src: Bitmap): Int {
        if (src.width < 8 || src.height < 4) return -1

        val up = 3
        val w = src.width * up
        val h = src.height * up
        val scaled = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        2.4f, 0f, 0f, 0f, -90f,
                        0f, 2.4f, 0f, 0f, -90f,
                        0f, 0f, 2.4f, 0f, -90f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
        Canvas(scaled).drawBitmap(src, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), paint)

        val gray = IntArray(w * h)
        var sum = 0L
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = scaled.getPixel(x, y)
                val g = ((c shr 16) and 0xFF) * 30 + ((c shr 8) and 0xFF) * 59 + (c and 0xFF) * 11
                val v = g / 100
                gray[y * w + x] = v
                sum += v
            }
        }
        scaled.recycle()
        val mean = (sum / (w * h)).toInt()
        val thr = (mean * 0.70).toInt().coerceIn(35, 175)

        val dark = BooleanArray(w * h)
        for (i in gray.indices) dark[i] = gray[i] < thr

        // 横線スコア
        var longLineRows = 0
        val lineNeed = (w * 0.40).toInt()
        for (y in 0 until h) {
            var run = 0
            var maxRun = 0
            for (x in 0 until w) {
                if (dark[y * w + x]) {
                    run++
                    if (run > maxRun) maxRun = run
                } else run = 0
            }
            if (maxRun >= lineNeed) longLineRows++
        }

        // 連結成分（円形ブロブ）
        val visited = BooleanArray(w * h)
        val blobs = mutableListOf<Triple<Int, Int, Int>>() // count, width, height
        val qx = IntArray(w * h)
        val qy = IntArray(w * h)
        val minBlob = max(8, (w * h) / 400)
        val maxBlob = (w * h) / 3

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (!dark[i] || visited[i]) continue
                var head = 0
                var tail = 0
                qx[tail] = x; qy[tail] = y; tail++
                visited[i] = true
                var count = 0
                var minX = x; var maxX = x; var minY = y; var maxY = y
                while (head < tail) {
                    val cx = qx[head]
                    val cy = qy[head]
                    head++
                    count++
                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy
                    for (dy in -1..1) for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = cx + dx
                        val ny = cy + dy
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                        val ni = ny * w + nx
                        if (!dark[ni] || visited[ni]) continue
                        visited[ni] = true
                        qx[tail] = nx; qy[tail] = ny; tail++
                    }
                }
                if (count in minBlob..maxBlob) {
                    val bw = maxX - minX + 1
                    val bh = maxY - minY + 1
                    // 細長い横線は除外（アスペクト比）
                    if (bw > 0 && bh > 0) {
                        val aspect = bw.toFloat() / bh
                        if (aspect < 3.5f && aspect > 0.35f) {
                            blobs.add(Triple(count, bw, bh))
                        }
                    }
                }
            }
        }

        // 大きさの近いブロブをスロット候補に（最大3）
        if (blobs.isNotEmpty()) {
            val sorted = blobs.sortedByDescending { it.first }
            val ref = sorted[0].first
            val similar = sorted.filter {
                it.first >= ref * 0.35 && it.first <= ref * 2.5
            }.take(3)
            val n = similar.size.coerceIn(1, 3)
            // 丸があるなら 1〜3（横線より優先）
            return n
        }

        // 丸が無く横線が複数 → 0
        if (longLineRows >= max(2, h / 10)) return 0
        return -1
    }

    /** 相対座標でスロット行を切り出す（フォールバック用） */
    fun cropSlotRow(full: Bitmap): Bitmap {
        val r = CropRegion.SLOT
        val x = (r[0] * full.width).toInt().coerceIn(0, full.width - 2)
        val y = (r[1] * full.height).toInt().coerceIn(0, full.height - 2)
        val rw = ((r[2] - r[0]) * full.width).toInt().coerceIn(1, full.width - x)
        val rh = ((r[3] - r[1]) * full.height).toInt().coerceIn(1, full.height - y)
        return Bitmap.createBitmap(full, x, y, rw, rh)
    }
}
