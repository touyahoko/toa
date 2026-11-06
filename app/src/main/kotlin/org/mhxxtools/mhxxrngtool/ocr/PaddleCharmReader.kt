package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log
import org.mhxxtools.mhxxrngtool.rng.SKILL_NAMES
import kotlin.math.max
import kotlin.math.min

/**
 * 画角・解像度を問わず、画像全体から
 * 固有スキル1 / 固有スキル2 / スキル名 / ポイント / スロット を読み取り、
 * OcrCharm にまとめる（後段で RNG 検索）。
 *
 * 方針:
 * 1) 画像全体を複数の横帯に分割して PaddleOCR
 * 2) 全文を parseOcrCharm（ラベル込み）
 * 3) スキル名辞書マッチで補強
 * 4) 標準 Switch レイアウト ROI も補助的に試す
 */
object PaddleCharmReader {
    private const val TAG = "PaddleCharm"

    data class Result(val charm: OcrCharm, val note: String, val confidence: Float)

    suspend fun read(context: Context, bitmap: Bitmap, preferredKind: Int = -1): Result? {
        if (!PaddleOcrEngine.init(context)) {
            Log.e(TAG, "engine init failed")
            return null
        }

        val w = bitmap.width.coerceAtLeast(1)
        val h = bitmap.height.coerceAtLeast(1)
        val texts = ArrayList<String>()

        // --- 1) 全画面を横帯スキャン（画角不定対応）---
        // 高さ方向にオーバーラップする帯を切り出し
        val bandCount = 12
        val bandH = max(32, h / 8)
        for (i in 0 until bandCount) {
            val y0 = (i * (h - bandH).toFloat() / (bandCount - 1).coerceAtLeast(1)).toInt()
                .coerceIn(0, h - 1)
            val y1 = (y0 + bandH).coerceAtMost(h)
            if (y1 - y0 < 16) continue
            // 左右も少し絞る場合と全幅
            for (margin in listOf(0f, 0.05f, 0.15f)) {
                val x0 = (w * margin).toInt()
                val x1 = (w * (1f - margin)).toInt().coerceAtLeast(x0 + 8)
                val crop = Bitmap.createBitmap(bitmap, x0, y0, x1 - x0, y1 - y0)
                val t = PaddleOcrEngine.recognizeLine(enhance(crop))
                if (crop !== bitmap) crop.recycle()
                if (t.isNotBlank()) texts += t
            }
        }

        // --- 2) 中央〜右寄りパネル想定の大きめ矩形（スマホでSwitchを写した場合）---
        val panels = listOf(
            floatArrayOf(0.35f, 0.15f, 0.95f, 0.55f),
            floatArrayOf(0.25f, 0.10f, 1.0f, 0.60f),
            floatArrayOf(0.40f, 0.20f, 0.90f, 0.45f),
            floatArrayOf(0.0f, 0.0f, 1.0f, 1.0f)
        )
        for (p in panels) {
            val x0 = (w * p[0]).toInt().coerceIn(0, w - 1)
            val y0 = (h * p[1]).toInt().coerceIn(0, h - 1)
            val x1 = (w * p[2]).toInt().coerceIn(x0 + 8, w)
            val y1 = (h * p[3]).toInt().coerceIn(y0 + 8, h)
            val crop = Bitmap.createBitmap(bitmap, x0, y0, x1 - x0, y1 - y0)
            // 横長にリサイズして帯分割
            val scaled = Bitmap.createScaledBitmap(crop, 640, max(48, (640f * (y1 - y0) / (x1 - x0)).toInt()), true)
            if (crop !== bitmap) crop.recycle()
            val sh = scaled.height
            val sw = scaled.width
            val nBands = 6
            val bh = max(28, sh / 4)
            for (i in 0 until nBands) {
                val yy = (i * (sh - bh).toFloat() / (nBands - 1).coerceAtLeast(1)).toInt().coerceIn(0, sh - 1)
                val yy2 = (yy + bh).coerceAtMost(sh)
                val line = Bitmap.createBitmap(scaled, 0, yy, sw, yy2 - yy)
                val t = PaddleOcrEngine.recognizeLine(enhance(line))
                line.recycle()
                if (t.isNotBlank()) texts += t
            }
            scaled.recycle()
        }

        // --- 3) 標準 Switch 1170x612 ROI（スクショ直撮り用）---
        val std = Bitmap.createScaledBitmap(bitmap, 1170, 612, true)
        val rois = listOf(
            intArrayOf(430, 100, 800, 230), // panel
            intArrayOf(599, 140, 731, 162), // name1
            intArrayOf(727, 140, 777, 162), // pts1
            intArrayOf(599, 165, 731, 185), // name2
            intArrayOf(727, 165, 777, 185), // pts2
            intArrayOf(520, 185, 620, 215)  // slot
        )
        for (box in rois) {
            val x1 = box[0].coerceIn(0, 1169)
            val y1 = box[1].coerceIn(0, 611)
            val x2 = box[2].coerceIn(x1 + 1, 1170)
            val y2 = box[3].coerceIn(y1 + 1, 612)
            val c = Bitmap.createBitmap(std, x1, y1, x2 - x1, y2 - y1)
            val t = PaddleOcrEngine.recognizeLine(enhance(c))
            c.recycle()
            if (t.isNotBlank()) texts += t
        }
        if (std !== bitmap) std.recycle()

        val joined = texts.distinct().joinToString("\n")
        Log.i(TAG, "ocr lines=${texts.size} text=${joined.take(300)}")

        // --- パース ---
        var charm = try {
            parseOcrCharm(joined)
        } catch (e: Exception) {
            Log.w(TAG, "parseOcrCharm: ${e.message}")
            null
        }

        // 辞書マッチでスキル補強
        val skills = mergeSkills(charm?.skills.orEmpty(), extractSkillsFromTexts(texts))
        val slots = when {
            charm != null && charm.slots in 0..3 -> charm.slots
            else -> extractSlots(joined)
        }
        val kind = when {
            preferredKind in 0..3 -> preferredKind
            charm != null && charm.kind in 0..3 -> charm.kind
            else -> -1
        }

        if (skills.isEmpty()) {
            Log.w(TAG, "no skills from full-image OCR")
            return null
        }

        val out = OcrCharm(kind, slots, skills.take(2))
        val note = "fullscan lines=${texts.size} kind=$kind slots=$slots skills=${skills.map { it.name + it.pts }}"
        val conf = if (skills.size >= 2 && slots in 0..3) 0.85f else 0.65f
        return Result(out, note, conf)
    }

    private fun mergeSkills(a: List<OcrSkill>, b: List<OcrSkill>): List<OcrSkill> {
        val out = LinkedHashMap<Int, OcrSkill>()
        for (s in a + b) {
            val prev = out[s.globalIdx]
            if (prev == null || (prev.pts == 0 && s.pts != 0)) out[s.globalIdx] = s
        }
        return out.values.toList()
    }

    /** 行テキストからスキル名＋ポイントを抽出 */
    private fun extractSkillsFromTexts(texts: List<String>): List<OcrSkill> {
        val found = ArrayList<OcrSkill>()
        val ptRegex = Regex("([+＋\\-−])?\\s*(\\d{1,2})")
        for (raw in texts) {
            val line = raw.replace("　", " ").trim()
            if (line.isEmpty()) continue
            if (line.contains("スロット") && !line.contains("スキル")) continue

            // 「固有スキル1 攻撃 +4」形式
            val cleaned = line
                .replace(Regex("固有スキル\\s*[12１２]?"), " ")
                .replace(Regex("国有スキル\\s*[12１２]?"), " ")
                .replace(Regex("スキル\\s*[12１２]"), " ")
                .trim()

            // ポイント
            var pts = 0
            ptRegex.findAll(cleaned).lastOrNull()?.let { m ->
                val sign = if (m.groupValues[1] == "-" || m.groupValues[1] == "−") -1 else 1
                pts = (sign * (m.groupValues[2].toIntOrNull() ?: 0)).coerceIn(-20, 20)
            }

            val namePart = cleaned
                .replace(ptRegex, " ")
                .replace(Regex("[+＋\\-−\\d\\s:：]"), "")
                .trim()
            if (namePart.length < 2) continue

            val idx = matchSkill(namePart) ?: continue
            if (found.none { it.globalIdx == idx }) {
                found += OcrSkill(idx, SKILL_NAMES[idx], pts)
            }
            if (found.size >= 2) break
        }

        // 全文連結でもう一度スキル名検索
        if (found.size < 2) {
            val all = texts.joinToString("")
            for (i in SKILL_NAMES.indices) {
                val n = SKILL_NAMES[i].replace("　", "")
                if (n.length < 2) continue
                if (all.contains(n) && found.none { it.globalIdx == i }) {
                    found += OcrSkill(i, SKILL_NAMES[i], 0)
                }
                if (found.size >= 2) break
            }
        }
        return found.take(2)
    }

    private fun extractSlots(text: String): Int {
        val unified = text.replace(Regex("[〇○◯ＯOｏo◎●◉]"), "◯")
        val area = Regex("スロ(?:ット)?[^\\n]{0,40}").find(unified)?.value ?: unified
        val runs = Regex("◯{1,3}").findAll(area).map { it.value.length }.toList()
        if (runs.isNotEmpty()) return runs.maxOrNull()!!.coerceIn(1, 3)
        if (Regex("(?:[-－─—–―_]\\s*){2,}").containsMatchIn(area)) return 0
        Regex("スロット\\s*[:：]?\\s*([0-3])").find(area)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return -1
    }

    private fun matchSkill(raw: String): Int? {
        val s = raw.replace("　", "").trim()
        if (s.isEmpty()) return null
        val exact = SKILL_NAMES.indexOfFirst { it.replace("　", "") == s }
        if (exact >= 0) return exact
        val hit = SKILL_NAMES.mapIndexed { i, n -> i to n.replace("　", "") }
            .filter { (_, n) -> n.length >= 2 && (s.contains(n) || n.contains(s)) }
            .maxByOrNull { it.second.length }
        if (hit != null) return hit.first
        var bestI = -1
        var bestD = 99
        for (i in SKILL_NAMES.indices) {
            val n = SKILL_NAMES[i].replace("　", "")
            if (n.length < 2) continue
            val d = levenshtein(s, n)
            if (d < bestD && d <= max(1, n.length / 3)) {
                bestD = d
                bestI = i
            }
        }
        return if (bestI >= 0) bestI else null
    }

    private fun levenshtein(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        if (m == 0) return n
        if (n == 0) return m
        val dp = IntArray(n + 1) { it }
        for (i in 1..m) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..n) {
                val tmp = dp[j]
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[j] = min(min(dp[j] + 1, dp[j - 1] + 1), prev + cost)
                prev = tmp
            }
        }
        return dp[n]
    }

    private fun enhance(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint()
        val cm = ColorMatrix(
            floatArrayOf(
                1.5f, 0f, 0f, 0f, -30f,
                0f, 1.5f, 0f, 0f, -30f,
                0f, 0f, 1.5f, 0f, -30f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }
}
