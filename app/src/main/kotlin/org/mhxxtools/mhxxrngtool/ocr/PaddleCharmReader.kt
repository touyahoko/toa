package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log
import org.mhxxtools.mhxxrngtool.rng.SKILL_NAMES
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 鑑定画像 → PaddleOCR（行認識）+ スキル辞書マッチ
 * テンプレート照合（RenkinSwitchReader）は使わない。
 *
 * 座標系は本家錬金リーダーと同じ 1170x612 正規化後の ROI。
 */
object PaddleCharmReader {
    private const val TAG = "PaddleCharm"
    private const val SW = 1170
    private const val SH = 612

    // RenkinSwitchReader 準拠
    private val NAME1 = intArrayOf(599, 140, 731, 162)
    private val PTS1 = intArrayOf(727, 140, 777, 162)
    private val NAME2 = intArrayOf(599, 165, 731, 185)
    private val PTS2 = intArrayOf(727, 165, 777, 185)
    private val SLOT_REGION = intArrayOf(520, 185, 600, 210)
    private val PANEL = intArrayOf(430, 100, 800, 230)

    data class Result(val charm: OcrCharm, val note: String, val confidence: Float)

    suspend fun read(context: Context, bitmap: Bitmap, preferredKind: Int = -1): Result? {
        if (!PaddleOcrEngine.init(context)) {
            Log.e(TAG, "PaddleOCR models missing under assets/paddle_ocr/")
            return null
        }
        val scaled = Bitmap.createScaledBitmap(bitmap, SW, SH, true)
        try {
            // 1) パネル全体OCR → parseOcrCharm（既存のスキル全文パーサ）
            val panel = crop(scaled, PANEL)
            val panelText = PaddleOcrEngine.recognizeLine(enhance(panel))
            Log.i(TAG, "panelText='$panelText'")

            var charm = tryParse(panelText)

            // 2) ROI単位で補強
            val n1 = cleanName(PaddleOcrEngine.recognizeLine(enhance(crop(scaled, NAME1))))
            val p1 = parsePts(PaddleOcrEngine.recognizeLine(enhance(crop(scaled, PTS1))))
            val n2 = cleanName(PaddleOcrEngine.recognizeLine(enhance(crop(scaled, NAME2))))
            val p2 = parsePts(PaddleOcrEngine.recognizeLine(enhance(crop(scaled, PTS2))))
            val slotTxt = PaddleOcrEngine.recognizeLine(enhance(crop(scaled, SLOT_REGION)))
            Log.i(TAG, "roi n1='$n1' p1=$p1 n2='$n2' p2=$p2 slot='$slotTxt'")

            val skills = mutableListOf<OcrSkill>()
            matchSkill(n1)?.let { skills += OcrSkill(it, SKILL_NAMES[it], p1 ?: 0) }
            matchSkill(n2)?.let { idx ->
                if (skills.none { it.globalIdx == idx }) {
                    skills += OcrSkill(idx, SKILL_NAMES[idx], p2 ?: 0)
                }
            }

            if (skills.isNotEmpty()) {
                val slots = parseSlots(slotTxt, charm?.slots ?: -1)
                charm = OcrCharm(preferredKind, slots, skills)
            } else if (charm != null && charm.skills.isNotEmpty()) {
                // panel parse を採用
            } else {
                return null
            }

            val conf = if (skills.isNotEmpty()) 0.85f else 0.55f
            val note = "paddle n1=$n1 p1=$p1 n2=$n2 p2=$p2 slot=$slotTxt panel=${panelText.take(40)}"
            return Result(charm!!, note, conf)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun tryParse(text: String): OcrCharm? {
        if (text.isBlank()) return null
        return try {
            parseOcrCharm(text)
        } catch (e: Exception) {
            Log.w(TAG, "parseOcrCharm: ${e.message}")
            null
        }
    }

    private fun crop(src: Bitmap, box: IntArray): Bitmap {
        val x1 = box[0].coerceIn(0, src.width - 1)
        val y1 = box[1].coerceIn(0, src.height - 1)
        val x2 = box[2].coerceIn(x1 + 1, src.width)
        val y2 = box[3].coerceIn(y1 + 1, src.height)
        return Bitmap.createBitmap(src, x1, y1, x2 - x1, y2 - y1)
    }

    private fun enhance(src: Bitmap): Bitmap {
        // コントラスト強調（OCR向け）
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint()
        val cm = ColorMatrix(floatArrayOf(
            1.4f, 0f, 0f, 0f, -40f,
            0f, 1.4f, 0f, 0f, -40f,
            0f, 0f, 1.4f, 0f, -40f,
            0f, 0f, 0f, 1f, 0f
        ))
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private fun cleanName(s: String): String {
        return s.replace(Regex("[\\s　+＋\\d\\-ー〜~:：]"), "")
            .replace("固有スキル", "")
            .replace("国有スキル", "")
            .trim()
    }

    private fun parsePts(s: String): Int? {
        val m = Regex("([+＋\\-−])?\\s*(\\d{1,2})").find(s.replace('＋', '+').replace('−', '-'))
            ?: Regex("(\\d{1,2})").find(s)
            ?: return null
        val sign = when {
            m.groupValues.size > 2 && (m.groupValues[1] == "-" || m.groupValues[1] == "−") -> -1
            else -> 1
        }
        val n = (m.groups.last { it != null && it.value.all(Char::isDigit) }?.value?.toIntOrNull()) ?: return null
        return (sign * n).coerceIn(-20, 20)
    }

    private fun parseSlots(slotTxt: String, fallback: Int): Int {
        val t = slotTxt
        val circles = t.count { it == '○' || it == '●' || it == 'o' || it == 'O' }
        if (circles in 1..3) return circles
        val dashes = t.count { it == '―' || it == 'ー' || it == '-' || it == '－' || it == '—' }
        if (dashes >= 3 && circles == 0) return 0
        val m = Regex("スロ[ット]*\\s*([0-3])").find(t)
        if (m != null) return m.groupValues[1].toInt()
        return fallback.coerceIn(-1, 3)
    }

    private fun matchSkill(raw: String): Int? {
        if (raw.isBlank()) return null
        val s = raw.replace("　", "").trim()
        // exact
        val exact = SKILL_NAMES.indexOfFirst { it.replace("　", "") == s }
        if (exact >= 0) return exact
        // contains
        val hit = SKILL_NAMES.mapIndexed { i, n -> i to n.replace("　", "") }
            .filter { (_, n) -> n.isNotEmpty() && (s.contains(n) || n.contains(s)) }
            .maxByOrNull { it.second.length }
        if (hit != null && hit.second.length >= 2) return hit.first
        // edit distance
        var bestI = -1
        var bestD = 99
        for (i in SKILL_NAMES.indices) {
            val n = SKILL_NAMES[i].replace("　", "")
            if (n.isEmpty()) continue
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
}
