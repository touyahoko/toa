package org.mhxxtools.mhxxrngtool.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import com.google.mlkit.vision.text.Text
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

// ======================================================================
// パネル自動検出（スマホ撮影の構図ズレ対応）
//
// 旧来の CropRegion / FIXED_CROP_* は「画面を矩形でそのまま録った画像」専用の
// 解像度非依存“相対座標”だった。これはキャプチャーボード直取り込みでは機能するが、
// スマホのカメラで鑑定画面を撮影した写真では成立しない。撮影には毎回
//   - 平行移動（画面がフレーム中央に来るとは限らない）
//   - 拡大率の違い（寄り/引き）
//   - 回転（手持ち撮影のわずかな傾き）
//   - あおり（真正面から撮れず多少の台形歪みが乗る）
// が乗る「構図ズレ」が発生する。
//
// そこで本モジュールは、ML Kit のテキスト認識結果が行ごとに持つ
// cornerPoints（4隅座標。傾き・あおりを含む実際の四角形）と boundingBox を使い、
//   - 「スロット」ラベル
//   - 風化/古び/光る/なぞ(謎) などの種類名
//   - 205種の正式スキル名・エイリアス（SkillMatcher 準拠）
//   - 「固有スキル」ラベル
// をテキストの中身そのものでアンカーとして写真内から検索し、それらの位置関係から
// パネルの実位置・傾きを逆算する。解像度にもレイアウト座標にも依存しない。
// ======================================================================
object PanelDetector {

    /** アンカーの種別 */
    enum class AnchorKind { SLOT, KIND, SKILL, SKILL_LABEL }

    /** 1行ぶんのアンカー（マッチしたテキスト行の位置情報） */
    data class Anchor(
        val kind: AnchorKind,
        val text: String,
        /** 4隅座標。左上→右上→右下→左下（画像のピクセル座標系、Yは下向き正） */
        val corners: List<PointF>,
        val boundingBox: Rect
    )

    /** パネル検出結果（アンカー探索に使った画像の座標系） */
    data class PanelResult(
        /** パネルの4隅。左上→右上→右下→左下（アンカー探索に使った画像の座標系） */
        val quad: List<PointF>,
        /** 射影変換で正立化した後の出力サイズ（パネルの見かけ上の実寸相当） */
        val outWidth: Int,
        val outHeight: Int,
        /** 見つかったアンカー種別の集合（2種類以上でここまで来る） */
        val anchorKinds: Set<AnchorKind>,
        /** 推定した傾き（ラジアン）。写真の構図ズレの回転成分 */
        val rotationRadians: Float,
        /** 採用したアンカー行（デバッグ・将来の可視化用） */
        val anchors: List<Anchor>
    )

    // ── アンカーのキーワード ────────────────────────────────────────────────
    private const val SLOT_KEYWORD = "スロ"
    private val KIND_KEYWORDS = listOf("風化", "古び", "光る", "なぞ", "謎")

    // 「固有スキル」ラベル。OCR で「ル」が落ちたり「国有」に誤読されても拾う
    private val SKILL_LABEL_RE = Regex("固有スキ|国有スキ")

    // parseOcrCharm の nameCls と同じ文字種（1文字スキル 匠/笛/怒 にも対応）。
    // 行の一部分だけを切り出して SkillMatcher.findSkillGlobalIdx に渡すことで、
    // 既存のエイリアス解決ロジックをそのまま流用する。
    private const val NAME_CHAR_CLASS = """[ぁ-んァ-ン一-龥々〆〇ーA-Za-z]{1,8}"""
    private val NAME_PTS_RE = Regex("""($NAME_CHAR_CLASS)\s*[+＋]?\s*(\d{1,2})""")

    // ── チューニング定数（実写真で切り出しがズレる場合はここを調整する） ──────
    /** 一番上のアンカー行の上に足す余白（行高さの倍数）。種類名見出し等を含める */
    private const val PAD_TOP_LINES = 1.6f
    /** 一番下のアンカー行の下に足す余白（行高さの倍数） */
    private const val PAD_BOTTOM_LINES = 1.4f
    /** 左右の余白（アンカー群の幅に対する比率） */
    private const val PAD_SIDE_RATIO = 0.22f
    /** 左右の最小余白（行高さの倍数。短い行しか無い場合の下限） */
    private const val PAD_SIDE_MIN_LINES = 1.8f
    /** この行高さの倍数以内にある中心点同士は同一パネルとみなしてクラスタ化する */
    private const val CLUSTER_LINK_LINES = 6f
    /** 傾き推定の異常値対策（これ以上の傾きは検出ミスとみなしクランプする） */
    private val MAX_ROTATION = Math.toRadians(25.0).toFloat()

    /**
     * ML Kit の Text 認識結果からパネル候補を検出する。
     *
     * @param text 画像全体（またはダウンスケール版）に対する ML Kit の認識結果
     * @param imageWidth / imageHeight `text` の認識に使った画像のサイズ（同じ座標系であること）
     * @return 検出できなければ null（呼び出し側は従来の固定クロップ等にフォールバックする）
     */
    fun locate(text: Text, imageWidth: Int, imageHeight: Int): PanelResult? {
        val anchors = collectAnchors(text)
        if (anchors.isEmpty()) return null

        // 写真の背景に写り込んだ無関係な文字列（誤検出）に引っ張られないよう、
        // 近接するアンカーだけをまとめてクラスタ化し、最も「らしい」集団を採用する。
        val best = clusterAnchors(anchors).maxByOrNull { clusterScore(it) } ?: return null

        val kinds = best.map { it.kind }.toSet()
        val skillCount = best.count { it.kind == AnchorKind.SKILL }
        // 最低条件: 異なる種類のアンカーが2つ以上、またはスキル名の行を2行とも拾えている。
        if (kinds.size < 2 && skillCount < 2) return null

        val rotation = estimateRotation(best).coerceIn(-MAX_ROTATION, MAX_ROTATION)
        val (quad, outW, outH) = buildPaddedQuad(best, rotation, imageWidth, imageHeight)
        return PanelResult(quad, outW, outH, kinds, rotation, best)
    }

    /**
     * 検出した4隅 [quad]（左上→右上→右下→左下、`src` と同じ座標系）を
     * [outWidth]×[outHeight] の矩形へ射影変換しながら切り出す。
     * 回転・多少のあおり（台形）歪みをまとめて補正するため、単純な矩形クロップと違い
     * 構図ズレのある写真でもパネルを正立化できる。
     */
    fun warpToBitmap(src: Bitmap, quad: List<PointF>, outWidth: Int, outHeight: Int): Bitmap {
        val w = outWidth.coerceAtLeast(1)
        val h = outHeight.coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        if (quad.size == 4) {
            val srcPts = FloatArray(8)
            for (i in 0 until 4) {
                srcPts[i * 2] = quad[i].x
                srcPts[i * 2 + 1] = quad[i].y
            }
            val dstPts = floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())
            val matrix = Matrix()
            // src(写真内のパネル4隅) → dst(正立矩形) への変換。4点とも対応させるので
            // 単純平行四辺形なら affine、台形なら射影変換として解ける。
            if (matrix.setPolyToPoly(srcPts, 0, dstPts, 0, 4)) {
                val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                canvas.drawBitmap(src, matrix, paint)
                return out
            }
        }

        // setPolyToPoly が解けない退化ケース（点が一直線等）のフォールバック:
        // 外接矩形で単純クロップする。
        val left = (quad.minOfOrNull { it.x } ?: 0f).toInt().coerceIn(0, max(0, src.width - 1))
        val top = (quad.minOfOrNull { it.y } ?: 0f).toInt().coerceIn(0, max(0, src.height - 1))
        val right = (quad.maxOfOrNull { it.x } ?: src.width.toFloat()).toInt().coerceIn(left + 1, src.width)
        val bottom = (quad.maxOfOrNull { it.y } ?: src.height.toFloat()).toInt().coerceIn(top + 1, src.height)
        canvas.drawBitmap(src, Rect(left, top, right, bottom), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    // ======================================================================
    // 内部実装
    // ======================================================================

    private fun collectAnchors(text: Text): List<Anchor> {
        val anchors = mutableListOf<Anchor>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val raw = line.text ?: continue
                if (raw.isBlank()) continue
                val box = line.boundingBox ?: continue
                val corners = normalizedCorners(line.cornerPoints, box) ?: continue

                if (raw.contains(SLOT_KEYWORD)) {
                    anchors += Anchor(AnchorKind.SLOT, raw, corners, box)
                }
                if (KIND_KEYWORDS.any { raw.contains(it) }) {
                    anchors += Anchor(AnchorKind.KIND, raw, corners, box)
                }
                if (SKILL_LABEL_RE.containsMatchIn(raw)) {
                    anchors += Anchor(AnchorKind.SKILL_LABEL, raw, corners, box)
                }

                // パターンA: 同一行に「スキル名+数値(SP)」が揃っている
                var foundSkill = false
                NAME_PTS_RE.findAll(raw).forEach { m ->
                    if (!foundSkill && SkillMatcher.findSkillGlobalIdx(m.groupValues[1]) >= 0) {
                        anchors += Anchor(AnchorKind.SKILL, raw, corners, box)
                        foundSkill = true
                    }
                }
                // パターンB: 行がスキル名そのもの（SPは別要素/別行でレイアウトされた場合）
                if (!foundSkill) {
                    val trimmed = raw.trim()
                    if (trimmed.length in 1..10 &&
                        !SKILL_LABEL_RE.containsMatchIn(trimmed) &&
                        SkillMatcher.findSkillGlobalIdx(trimmed) >= 0
                    ) {
                        anchors += Anchor(AnchorKind.SKILL, raw, corners, box)
                    }
                }
            }
        }
        return anchors
    }

    /** cornerPoints が無ければ boundingBox から4隅を作る（常に4点返す） */
    private fun normalizedCorners(pts: Array<Point>?, box: Rect): List<PointF>? {
        if (pts != null && pts.size == 4) {
            return List(4) { i -> PointF(pts[i].x.toFloat(), pts[i].y.toFloat()) }
        }
        return listOf(
            PointF(box.left.toFloat(), box.top.toFloat()),
            PointF(box.right.toFloat(), box.top.toFloat()),
            PointF(box.right.toFloat(), box.bottom.toFloat()),
            PointF(box.left.toFloat(), box.bottom.toFloat())
        )
    }

    /**
     * アンカーを位置の近さでクラスタ化する（Union-Find）。
     * 写真の背景に無関係なテキストが写り込んでいても、鑑定パネル由来のアンカー群だけを
     * 1つのクラスタとして分離できるようにする。
     */
    private fun clusterAnchors(anchors: List<Anchor>): List<List<Anchor>> {
        if (anchors.size <= 1) return listOf(anchors)

        val centers = anchors.map { a ->
            PointF(
                (a.corners.sumOf { it.x.toDouble() } / a.corners.size).toFloat(),
                (a.corners.sumOf { it.y.toDouble() } / a.corners.size).toFloat()
            )
        }
        val medianH = anchors.map { it.boundingBox.height().toFloat() }
            .sorted()
            .let { it[it.size / 2] }
            .coerceAtLeast(6f)
        val linkDist = medianH * CLUSTER_LINK_LINES

        val parent = IntArray(anchors.size) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != c) {
                val next = parent[c]
                parent[c] = r
                c = next
            }
            return r
        }

        for (i in anchors.indices) {
            for (j in (i + 1) until anchors.size) {
                val dist = hypot(
                    (centers[i].x - centers[j].x).toDouble(),
                    (centers[i].y - centers[j].y).toDouble()
                )
                if (dist <= linkDist) {
                    val ri = find(i)
                    val rj = find(j)
                    if (ri != rj) parent[ri] = rj
                }
            }
        }

        return anchors.indices.groupBy { find(it) }.values.map { idxs -> idxs.map { anchors[it] } }
    }

    private fun clusterScore(cluster: List<Anchor>): Int {
        val kindVariety = cluster.map { it.kind }.toSet().size
        // 種類の多様性を強く優先（スロット+スキル+種類が揃う本物のパネルを選びやすくする）
        return kindVariety * 10 + cluster.size
    }

    /**
     * アンカー行の上辺ベクトルから傾きを推定する（行の幅で重み付けした平均）。
     * ML Kit の cornerPoints は「左上起点の時計回り」で返るため、
     * corners[0]=左上, corners[1]=右上 として上辺ベクトルを取る。
     */
    private fun estimateRotation(anchors: List<Anchor>): Float {
        var weightSum = 0.0
        var angleWeightSum = 0.0
        for (a in anchors) {
            val tl = a.corners[0]
            val tr = a.corners[1]
            val dx = (tr.x - tl.x).toDouble()
            val dy = (tr.y - tl.y).toDouble()
            val len = hypot(dx, dy)
            if (len < 4.0) continue // 短すぎる行はノイズとして無視
            angleWeightSum += atan2(dy, dx) * len
            weightSum += len
        }
        if (weightSum <= 0.0) return 0f
        return (angleWeightSum / weightSum).toFloat()
    }

    private fun rotatePoint(p: PointF, pivot: PointF, angle: Float): PointF {
        if (angle == 0f) return PointF(p.x, p.y)
        val cosA = cos(angle.toDouble())
        val sinA = sin(angle.toDouble())
        val dx = (p.x - pivot.x).toDouble()
        val dy = (p.y - pivot.y).toDouble()
        return PointF(
            (pivot.x + dx * cosA - dy * sinA).toFloat(),
            (pivot.y + dx * sinA + dy * cosA).toFloat()
        )
    }

    /**
     * アンカー群の外接矩形に余白を足してパネル全体を覆う4隅を作る。
     * 1) 傾き分だけ逆回転してほぼ軸並行にする
     * 2) 軸並行のまま外接矩形＋余白を計算する
     * 3) 同じ回転を掛け戻して元画像座標系に戻す
     * こうすることで、写真が傾いていてもパネルの傾きに追従した4隅が作れる。
     */
    private fun buildPaddedQuad(
        anchors: List<Anchor>,
        rotation: Float,
        imageWidth: Int,
        imageHeight: Int
    ): Triple<List<PointF>, Int, Int> {
        val allCorners = anchors.flatMap { it.corners }
        val pivot = PointF(
            (allCorners.sumOf { it.x.toDouble() } / allCorners.size).toFloat(),
            (allCorners.sumOf { it.y.toDouble() } / allCorners.size).toFloat()
        )

        val deRotated = allCorners.map { rotatePoint(it, pivot, -rotation) }
        var left = deRotated.minOf { it.x }
        var right = deRotated.maxOf { it.x }
        var top = deRotated.minOf { it.y }
        var bottom = deRotated.maxOf { it.y }

        val avgLineHeight = anchors.map { it.boundingBox.height().toFloat() }
            .average().toFloat().coerceAtLeast(6f)
        val rectWidth = (right - left).coerceAtLeast(1f)

        top -= avgLineHeight * PAD_TOP_LINES
        bottom += avgLineHeight * PAD_BOTTOM_LINES
        val sidePad = max(rectWidth * PAD_SIDE_RATIO, avgLineHeight * PAD_SIDE_MIN_LINES)
        left -= sidePad
        right += sidePad

        val outW = (right - left).roundToInt().coerceAtLeast(8)
        val outH = (bottom - top).roundToInt().coerceAtLeast(8)

        val localQuad = listOf(
            PointF(left, top), PointF(right, top),
            PointF(right, bottom), PointF(left, bottom)
        )
        val quad = localQuad.map { p ->
            val r = rotatePoint(p, pivot, rotation)
            PointF(
                r.x.coerceIn(0f, imageWidth.toFloat()),
                r.y.coerceIn(0f, imageHeight.toFloat())
            )
        }
        return Triple(quad, outW, outH)
    }
}
