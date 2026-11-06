package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.util.Collections
import kotlin.math.max

/**
 * PaddleOCR 認識（PP-OCR rec ONNX）エンジン。
 * assets/paddle_ocr/rec.onnx + keys.txt が必要。
 * 検出は行わず、呼び出し側が行ROIを切り出して recognizeLine する。
 */
object PaddleOcrEngine {
    private const val TAG = "PaddleOcr"
    @Volatile private var session: OrtSession? = null
    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var keys: List<String> = emptyList()
    @Volatile private var ready = false
    private var inputName: String = "x"
    private var recH = 48
    private var recW = 320

    fun isReady(): Boolean = ready

    @Synchronized
    fun init(context: Context): Boolean {
        if (ready) return true
        return try {
            val am = context.assets
            val keyText = try {
                am.open("paddle_ocr/keys.txt").bufferedReader().readText()
            } catch (e: Exception) {
                Log.e(TAG, "keys.txt missing: ${e.message}")
                return false
            }
            keys = keyText.lines().map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
            // blank index = keys.size（CTC）
            val modelBytes = try {
                am.open("paddle_ocr/rec.onnx").use { it.readBytes() }
            } catch (e: Exception) {
                Log.e(TAG, "rec.onnx missing: ${e.message}")
                return false
            }
            val e = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions()
            opts.setIntraOpNumThreads(2)
            val s = e.createSession(modelBytes, opts)
            session = s
            env = e
            s.inputNames.firstOrNull()?.let { inputName = it }
            ready = true
            Log.i(TAG, "Paddle OCR ready keys=${keys.size} input=$inputName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "init failed", e)
            ready = false
            false
        }
    }

    /**
     * 行画像（ARGB Bitmap）→ 文字列
     * PP-OCR rec 前処理: H=48, W可変, normalize
     */
    @Synchronized
    fun recognizeLine(bitmap: Bitmap): String {
        if (!ready) return ""
        val s = session ?: return ""
        val e = env ?: return ""
        val resized = resizeNormImg(bitmap, recH, recW)
        val (fb, shape) = resized
        var tensor: OnnxTensor? = null
        return try {
            tensor = OnnxTensor.createTensor(e, fb, shape)
            val result = s.run(Collections.singletonMap(inputName, tensor))
            result.use { out ->
                val value = out.get(0)
                decodeCtc(value.getValue())
            }
        } catch (ex: Exception) {
            Log.w(TAG, "recognizeLine: ${ex.message}")
            ""
        } finally {
            tensor?.close()
        }
    }

    private fun resizeNormImg(bmp: Bitmap, imgH: Int, imgW: Int): Pair<FloatBuffer, LongArray> {
        val w0 = bmp.width.coerceAtLeast(1)
        val h0 = bmp.height.coerceAtLeast(1)
        val ratio = w0.toFloat() / h0.toFloat()
        var resizeW = (imgH * ratio).toInt().coerceIn(8, imgW)
        val scaled = Bitmap.createScaledBitmap(bmp, resizeW, imgH, true)
        val n = 1 * 3 * imgH * imgW
        val buf = FloatArray(n)
        val pixels = IntArray(scaled.width * scaled.height)
        scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        // CHW, pad right with 0, normalize (img/255 - 0.5)/0.5
        for (y in 0 until imgH) {
            for (x in 0 until imgW) {
                val v = if (x < scaled.width) {
                    val c = pixels[y * scaled.width + x]
                    val r = ((c shr 16) and 0xff) / 255f
                    val g = ((c shr 8) and 0xff) / 255f
                    val b = (c and 0xff) / 255f
                    floatArrayOf(r, g, b)
                } else floatArrayOf(0f, 0f, 0f)
                for (c in 0..2) {
                    val norm = (v[c] - 0.5f) / 0.5f
                    buf[c * imgH * imgW + y * imgW + x] = norm
                }
            }
        }
        if (scaled !== bmp) scaled.recycle()
        val fb = FloatBuffer.allocate(n)
        fb.put(buf)
        fb.rewind()
        return fb to longArrayOf(1, 3, imgH.toLong(), imgW.toLong())
    }

    @Suppress("UNCHECKED_CAST")
    private fun decodeCtc(raw: Any?): String {
        // 出力形状想定: [1, T, numClass] または [1, numClass, T]
        val sb = StringBuilder()
        try {
            when (raw) {
                is Array<*> -> {
                    // nested arrays
                    val seq = flattenToIndices(raw)
                    var prev = -1
                    for (idx in seq) {
                        if (idx != prev && idx != 0 && idx < keys.size) {
                            // 多くのPP-OCRは index0=blank
                            val ch = keys.getOrNull(idx) ?: keys.getOrNull(idx - 1)
                            if (ch != null && ch != "blank") sb.append(ch)
                        }
                        // standard: blank=0, char = keys[idx-1]
                        prev = idx
                    }
                    // 再デコード standard CTC
                    return decodeCtcStandard(seq)
                }
                else -> Log.w(TAG, "unknown output ${raw?.javaClass}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "decode: ${e.message}")
        }
        return sb.toString()
    }

    private fun decodeCtcStandard(indices: List<Int>): String {
        val sb = StringBuilder()
        var prev = -1
        for (idx in indices) {
            if (idx == prev) continue
            prev = idx
            if (idx == 0) continue // blank
            val k = idx - 1
            if (k in keys.indices) {
                val ch = keys[k]
                if (ch.isNotEmpty()) sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun flattenToIndices(raw: Array<*>): List<Int> {
        // [1][T][C] -> argmax on last dim
        val out = ArrayList<Int>()
        try {
            val a0 = raw[0] as Array<*>
            // a0 is T x C or C x T
            if (a0.isEmpty()) return out
            val first = a0[0]
            if (first is FloatArray) {
                // T x C as FloatArray rows? or nested
            }
            if (first is Array<*>) {
                // T x C
                for (t in a0.indices) {
                    val row = a0[t]
                    out.add(argmaxRow(row))
                }
                return out
            }
            if (first is FloatArray) {
                for (t in a0.indices) {
                    val row = a0[t] as FloatArray
                    var bi = 0
                    var bv = row[0]
                    for (i in 1 until row.size) if (row[i] > bv) { bv = row[i]; bi = i }
                    out.add(bi)
                }
                return out
            }
        } catch (e: Exception) {
            Log.w(TAG, "flatten: ${e.message}")
        }
        return out
    }

    private fun argmaxRow(row: Any?): Int {
        when (row) {
            is FloatArray -> {
                var bi = 0; var bv = row[0]
                for (i in 1 until row.size) if (row[i] > bv) { bv = row[i]; bi = i }
                return bi
            }
            is Array<*> -> {
                var bi = 0
                var bv = (row[0] as? Number)?.toFloat() ?: -1e9f
                for (i in 1 until row.size) {
                    val v = (row[i] as? Number)?.toFloat() ?: continue
                    if (v > bv) { bv = v; bi = i }
                }
                return bi
            }
        }
        return 0
    }

    @Synchronized
    fun close() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        ready = false
    }
}
