package org.mhxxtools.mhxxrngtool.ocr

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.calib3d.Calib3d
import org.opencv.imgproc.Imgproc
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * OpenCV ラッパ（note.com/fwe TL;DR 方式向け）
 * - ORB 特徴点 + RANSAC ホモグラフィで射影
 * - 形態学トップハットで反射除去
 * - matchTemplate (NCC) でテンプレ照合
 */
object OpenCvBridge {

    private const val TAG = "OpenCvBridge"
    private val ready = AtomicBoolean(false)

    fun isReady(): Boolean = ready.get()

    /** MainActivity / Application 起動時に1回呼ぶ */
    fun init(): Boolean {
        if (ready.get()) return true
        return try {
            val ok = OpenCVLoader.initLocal()
            ready.set(ok)
            Log.i(TAG, "OpenCV initLocal=$ok version=${if (ok) Core.VERSION else "n/a"}")
            ok
        } catch (e: Throwable) {
            Log.e(TAG, "OpenCV init failed", e)
            ready.set(false)
            false
        }
    }

    fun ensureInit(): Boolean = if (ready.get()) true else init()

    /**
     * renkin-reader の型紙 (reference.png) を使って 1280x720 に射影。
     * ORB + RANSAC ホモグラフィ。失敗時は null。
     */
    fun alignWithReference(srcBmp: Bitmap, referenceBmp: Bitmap, canonW: Int = 1280, canonH: Int = 720): Bitmap? {
        if (!ensureInit()) return null
        val src = bitmapToMat(srcBmp)
        val refMat = bitmapToMat(referenceBmp)
        try {
            val srcGray = Mat()
            val refGray = Mat()
            Imgproc.cvtColor(src, srcGray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.cvtColor(refMat, refGray, Imgproc.COLOR_RGBA2GRAY)
            // 型紙を canonical サイズに
            val refCanon = Mat()
            Imgproc.resize(refGray, refCanon, Size(canonW.toDouble(), canonH.toDouble()))

            val orb = ORB.create(3000)
            val kpSrc = MatOfKeyPoint()
            val kpRef = MatOfKeyPoint()
            val desSrc = Mat()
            val desRef = Mat()
            orb.detectAndCompute(srcGray, Mat(), kpSrc, desSrc)
            orb.detectAndCompute(refCanon, Mat(), kpRef, desRef)
            if (desSrc.empty() || desRef.empty() || kpSrc.rows() < 12 || kpRef.rows() < 12) {
                return null
            }
            val matcher = BFMatcher.create(Core.NORM_HAMMING, true)
            val matches = MatOfDMatch()
            matcher.match(desSrc, desRef, matches)
            val good = matches.toList().sortedBy { it.distance }.take(100).filter { it.distance < 70 }
            if (good.size < 15) return null
            val srcPts = MatOfPoint2f()
            val dstPts = MatOfPoint2f()
            val sK = kpSrc.toArray()
            val dK = kpRef.toArray()
            srcPts.fromList(good.map { Point(sK[it.queryIdx].pt.x, sK[it.queryIdx].pt.y) })
            dstPts.fromList(good.map { Point(dK[it.trainIdx].pt.x, dK[it.trainIdx].pt.y) })
            val mask = Mat()
            val H = Calib3d.findHomography(srcPts, dstPts, Calib3d.RANSAC, 5.0, mask)
            if (H.empty()) return null
            val warped = Mat()
            Imgproc.warpPerspective(src, warped, H, Size(canonW.toDouble(), canonH.toDouble()))
            return matToBitmap(warped)
        } catch (e: Throwable) {
            Log.e(TAG, "alignWithReference failed", e)
            return null
        } finally {
            src.release()
            refMat.release()
        }
    }


    fun bitmapToMat(bmp: Bitmap): Mat {
        val mat = Mat()
        val copy = if (bmp.config == Bitmap.Config.ARGB_8888) bmp
        else bmp.copy(Bitmap.Config.ARGB_8888, false)
        Utils.bitmapToMat(copy, mat)
        if (copy !== bmp) copy.recycle()
        return mat
    }

    fun matToBitmap(mat: Mat): Bitmap {
        val bmp = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, bmp)
        return bmp
    }

    /**
     * 写真を canonical (1280x720) に射影。
     * 参照は「型紙」相当のエッジ画像をその場で生成（固定UIの輪郭）。
     * 失敗時 null。
     */
    fun alignToCanonical(srcBmp: Bitmap, canonW: Int = 1280, canonH: Int = 720): Bitmap? {
        if (!ensureInit()) return null
        val src = bitmapToMat(srcBmp)
        try {
            val gray = Mat()
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)

            // 参照キャンバス: 中央パネル風の矩形枠（型紙の簡易代替）
            val ref = Mat.zeros(canonH, canonW, CvType.CV_8UC1)
            // 鑑定パネルおおよそ
            Imgproc.rectangle(
                ref,
                Point(canonW * 0.28, canonH * 0.10),
                Point(canonW * 0.72, canonH * 0.42),
                org.opencv.core.Scalar(255.0),
                3
            )
            // 鑑定ボックス左側
            Imgproc.rectangle(
                ref,
                Point(canonW * 0.05, canonH * 0.12),
                Point(canonW * 0.26, canonH * 0.55),
                org.opencv.core.Scalar(200.0),
                2
            )
            // 見出し帯
            Imgproc.rectangle(
                ref,
                Point(canonW * 0.30, canonH * 0.08),
                Point(canonW * 0.55, canonH * 0.14),
                org.opencv.core.Scalar(220.0),
                2
            )

            val srcEdges = Mat()
            val refEdges = Mat()
            Imgproc.Canny(gray, srcEdges, 40.0, 120.0)
            Imgproc.Canny(ref, refEdges, 40.0, 120.0)

            val orb = ORB.create(2000)
            val kpSrc = MatOfKeyPoint()
            val kpRef = MatOfKeyPoint()
            val desSrc = Mat()
            val desRef = Mat()
            orb.detectAndCompute(srcEdges, Mat(), kpSrc, desSrc)
            orb.detectAndCompute(refEdges, Mat(), kpRef, desRef)

            if (desSrc.empty() || desRef.empty() || kpSrc.rows() < 8 || kpRef.rows() < 8) {
                // フォールバック: 最大輪郭の外接矩形でアフィン近似
                return alignByLargestContour(src, gray, canonW, canonH)
            }

            val matcher = BFMatcher.create(Core.NORM_HAMMING, true)
            val matches = MatOfDMatch()
            matcher.match(desSrc, desRef, matches)
            val list = matches.toList().sortedBy { it.distance }
            val good = list.take(min(80, list.size)).filter { it.distance < 60 }
            if (good.size < 12) {
                return alignByLargestContour(src, gray, canonW, canonH)
            }

            val srcPts = MatOfPoint2f()
            val dstPts = MatOfPoint2f()
            val sK = kpSrc.toArray()
            val dK = kpRef.toArray()
            srcPts.fromList(good.map { Point(sK[it.queryIdx].pt.x, sK[it.queryIdx].pt.y) })
            dstPts.fromList(good.map { Point(dK[it.trainIdx].pt.x, dK[it.trainIdx].pt.y) })

            val mask = Mat()
            val H = Calib3d.findHomography(srcPts, dstPts, Calib3d.RANSAC, 4.0, mask)
            if (H.empty()) {
                return alignByLargestContour(src, gray, canonW, canonH)
            }

            val warped = Mat()
            Imgproc.warpPerspective(src, warped, H, Size(canonW.toDouble(), canonH.toDouble()))
            val out = matToBitmap(warped)
            warped.release()
            H.release()
            mask.release()
            srcEdges.release()
            refEdges.release()
            ref.release()
            gray.release()
            desSrc.release()
            desRef.release()
            kpSrc.release()
            kpRef.release()
            matches.release()
            return out
        } catch (e: Throwable) {
            Log.e(TAG, "alignToCanonical failed", e)
            return null
        } finally {
            src.release()
        }
    }

    private fun alignByLargestContour(src: Mat, gray: Mat, canonW: Int, canonH: Int): Bitmap? {
        return try {
            val blur = Mat()
            Imgproc.GaussianBlur(gray, blur, Size(5.0, 5.0), 0.0)
            val bin = Mat()
            Imgproc.threshold(blur, bin, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            val cnts = ArrayList<org.opencv.core.MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(bin, cnts, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            var best: org.opencv.core.MatOfPoint? = null
            var bestArea = 0.0
            for (c in cnts) {
                val a = Imgproc.contourArea(c)
                if (a > bestArea) {
                    bestArea = a
                    best = c
                }
            }
            if (best == null || bestArea < gray.rows() * gray.cols() * 0.05) {
                // letterbox scale
                val scaled = Mat()
                val sc = min(canonW.toDouble() / src.cols(), canonH.toDouble() / src.rows())
                Imgproc.resize(src, scaled, Size(src.cols() * sc, src.rows() * sc))
                val canvas = Mat.zeros(canonH, canonW, src.type())
                val x = ((canonW - scaled.cols()) / 2).coerceAtLeast(0)
                val y = ((canonH - scaled.rows()) / 2).coerceAtLeast(0)
                val roi = canvas.submat(y, y + scaled.rows(), x, x + scaled.cols())
                scaled.copyTo(roi)
                val bmp = matToBitmap(canvas)
                scaled.release(); canvas.release(); blur.release(); bin.release(); hierarchy.release()
                cnts.forEach { it.release() }
                return bmp
            }
            val rect = Imgproc.boundingRect(best)
            val roi = Mat(src, rect)
            val resized = Mat()
            Imgproc.resize(roi, resized, Size(canonW.toDouble(), canonH.toDouble()))
            val bmp = matToBitmap(resized)
            resized.release(); blur.release(); bin.release(); hierarchy.release()
            cnts.forEach { it.release() }
            bmp
        } catch (e: Throwable) {
            Log.e(TAG, "alignByLargestContour failed", e)
            null
        }
    }

    /** トップハット: 文字線を残して反射・ムラを落とす */
    fun topHat(srcBmp: Bitmap): Bitmap? {
        if (!ensureInit()) return null
        val src = bitmapToMat(srcBmp)
        return try {
            val gray = Mat()
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
            // max channel 相当: すでに gray。記事は max(R,G,B)
            val bgr = Mat()
            Imgproc.cvtColor(src, bgr, Imgproc.COLOR_RGBA2BGR)
            val channels = ArrayList<Mat>()
            Core.split(bgr, channels)
            val maxCh = Mat()
            Core.max(channels[0], channels[1], maxCh)
            Core.max(maxCh, channels[2], maxCh)

            val kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_ELLIPSE,
                Size(max(9, srcBmp.width / 40).toDouble(), max(9, srcBmp.height / 40).toDouble())
            )
            val th = Mat()
            Imgproc.morphologyEx(maxCh, th, Imgproc.MORPH_TOPHAT, kernel)
            // コントラスト強調
            Core.normalize(th, th, 0.0, 255.0, Core.NORM_MINMAX)
            val rgba = Mat()
            Imgproc.cvtColor(th, rgba, Imgproc.COLOR_GRAY2RGBA)
            val out = matToBitmap(rgba)
            channels.forEach { it.release() }
            maxCh.release(); kernel.release(); th.release(); rgba.release()
            gray.release(); bgr.release()
            out
        } catch (e: Throwable) {
            Log.e(TAG, "topHat failed", e)
            null
        } finally {
            src.release()
        }
    }

    /**
     * matchTemplate (TM_CCOEFF_NORMED)。
     * @return best score 0..1 and top-left
     */
    fun matchTemplateScore(imageGray: Mat, tmplGray: Mat): Pair<Float, Point>? {
        if (!ensureInit()) return null
        if (tmplGray.rows() >= imageGray.rows() || tmplGray.cols() >= imageGray.cols()) return null
        return try {
            val result = Mat()
            Imgproc.matchTemplate(imageGray, tmplGray, result, Imgproc.TM_CCOEFF_NORMED)
            val mm = Core.minMaxLoc(result)
            result.release()
            mm.maxVal.toFloat() to mm.maxLoc
        } catch (e: Throwable) {
            Log.e(TAG, "matchTemplate failed", e)
            null
        }
    }

    fun toGrayMat(bmp: Bitmap): Mat {
        val src = bitmapToMat(bmp)
        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
        src.release()
        return gray
    }
}
