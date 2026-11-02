package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mhxxtools.mhxxrngtool.rng.SKILL_NAMES
import kotlin.math.roundToInt

/**
 * Switch 鑑定パネル想定の固定ROIに切り出してから ML Kit をかける。
 * 全画面OCRだと「対防御DOWN」等の別UIを拾って誤認するため。
 *
 * 基準解像度 1280x720（引き伸ばし後）。
 */
object MlKitRoiReader {
    private const val TAG = "MlKitRoi"
    private const val SW = 1280
    private const val SH = 720

    // renkin-reader 準拠に近いスキル名 / ポイント領域
    private val NAME1 = intArrayOf(640, 158, 820, 192)
    private val PTS1 = intArrayOf(800, 158, 870, 192)
    private val NAME2 = intArrayOf(640, 186, 820, 222)
    private val PTS2 = intArrayOf(800, 186, 870, 222)
    private val SLOT = intArrayOf(560, 218, 700, 248)

    data class Result(
        val charm: OcrCharm,
        val note: String,
        val confidence: Float
    )

    suspend fun read(context: Context, src: Bitmap): Result? = withContext(Dispatchers.Default) {
        try {
            val scaled = scaleToScreen(src)
            val n1 = ocrCrop(scaled, NAME1)
            val n2 = ocrCrop(scaled, NAME2)
            val p1t = ocrCrop(scaled, PTS1)
            val p2t = ocrCrop(scaled, PTS2)
            val slotT = ocrCrop(scaled, SLOT)
            Log.i(TAG, "n1='$n1' p1='$p1t' n2='$n2' p2='$p2t' slot='$slotT'")

            val s1 = toSkill(n1, p1t)
            val s2 = toSkill(n2, p2t)
            val slots = parseSlots(slotT)

            val skills = listOfNotNull(s1, s2)
            if (skills.isEmpty()) return@withContext null

            val conf = (0.5f + skills.size * 0.2f + if (slots in 0..3) 0.1f else 0f)
                .coerceAtMost(0.95f)
            Result(
                OcrCharm(-1, slots, skills),
                "mlkit-roi n1=$n1 n2=$n2",
                conf
            )
        } catch (e: Exception) {
            Log.w(TAG, "read: ${e.message}")
            null
        }
    }

    private fun scaleToScreen(src: Bitmap): Bitmap {
        if (src.width == SW && src.height == SH) return src
        return Bitmap.createScaledBitmap(src, SW, SH, true)
    }

    private fun crop(src: Bitmap, box: IntArray): Bitmap {
        val x1 = box[0].coerceIn(0, src.width - 1)
        val y1 = box[1].coerceIn(0, src.height - 1)
        val x2 = box[2].coerceIn(x1 + 1, src.width)
        val y2 = box[3].coerceIn(y1 + 1, src.height)
        return Bitmap.createBitmap(src, x1, y1, x2 - x1, y2 - y1)
    }

    private suspend fun ocrCrop(src: Bitmap, box: IntArray): String {
        val c = crop(src, box)
        // 小さくて読めないので拡大
        val w = (c.width * 3).coerceAtLeast(1)
        val h = (c.height * 3).coerceAtLeast(1)
        val big = Bitmap.createScaledBitmap(c, w, h, true)
        return AndroidOcr.ocrBitmap(big).replace("\n", "").trim()
    }

    private fun toSkill(nameRaw: String, ptsRaw: String): OcrSkill? {
        val name = nameRaw
            .replace(Regex("[0-9+＋\\s　]"), "")
            .replace(Regex("固有スキル[12]?"), "")
            .trim()
        if (name.isEmpty()) return null
        var pts = Regex("[+＋]?(\\d{1,2})").find(ptsRaw)?.groupValues?.get(1)?.toIntOrNull()
        if (pts == null) {
            pts = Regex("[+＋](\\d{1,2})").find(nameRaw)?.groupValues?.get(1)?.toIntOrNull()
        }
        if (pts == null || pts !in 1..20) {
            // 名前OCRにポイントが混ざる場合
            pts = Regex("(\\d{1,2})$").find(nameRaw.replace(" ", ""))?.groupValues?.get(1)?.toIntOrNull()
        }
        if (pts == null || pts !in 1..20) return null

        val gIdx = SkillMatcher.findSkillGlobalIdx(name)
        if (gIdx < 0) return null
        val disp = SKILL_NAMES.getOrElse(gIdx) { name }
        return OcrSkill(gIdx, disp, pts)
    }

    private fun parseSlots(raw: String): Int {
        var u = raw.replace(Regex("[〇○◯ＯOｏo◎●◉]"), "◯")
        val circles = Regex("◯+").findAll(u).map { it.value.length }.maxOrNull() ?: 0
        if (circles in 1..3) return circles
        if (Regex("(?:[-－─—–―_]\\s*){2,}").containsMatchIn(u)) return 0
        if (u.contains("なし") || u.contains("---")) return 0
        Regex("([0-3])").find(u)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return -1
    }
}
