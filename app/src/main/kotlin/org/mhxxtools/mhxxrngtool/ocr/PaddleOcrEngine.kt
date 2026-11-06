package org.mhxxtools.mhxxrngtool.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.util.Collections

/**
 * PaddleOCR 認識（PP-OCR rec ONNX）
 * assets/paddle_ocr/rec.onnx + keys.txt 必須
 */
object PaddleOcrEngine {
    private const val TAG = "PaddleOcr"

    @Volatile private var session: OrtSession? = null
    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var keys: List<String> = emptyList()
    @Volatile private var ready: Boolean = false
    private var inputName: String = "x"

    private const val REC_H = 48
    private const val REC_W = 320

    fun isReady(): Boolean = ready

    @Synchronized
    fun init(context: Context): Boolean {
        if (ready) return true
        return try {
            val am = context.assets
            val keyText = am.open("paddle_ocr/keys.txt").bufferedReader(Charsets.UTF_8).readText()
            keys = keyText.lines().map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
            if (keys.isEmpty()) {
                Log.e(TAG, "keys.txt empty")
                return false
            }
            val modelBytes = am.open("paddle_ocr/rec.onnx").use { it.readBytes() }
            val e = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions()
            opts.setIntraOpNumThreads(2)
            val s = e.createSession(modelBytes, opts)
            session = s
            env = e
            inputName = s.inputNames.iterator().next()
            ready = true
            Log.i(TAG, "ready keys=${keys.size} input=$inputName")
            true
        } catch (ex: Exception) {
            Log.e(TAG, "init failed: ${ex.message}", ex)
            ready = false
            false
        }
    }

    @Synchronized
    fun recognizeLine(bitmap: Bitmap): String {
        if (!ready) return ""
        val s = session ?: return ""
        val e = env ?: return ""
        val (fb, shape) = resizeNormImg(bitmap, REC_H, REC_W)
        var tensor: OnnxTensor? = null
        return try {
            tensor = OnnxTensor.createTensor(e, fb, shape)
            val inputs = Collections.singletonMap(inputName, tensor as OnnxTensor)
            s.run(inputs).use { result ->
                val value = result[0].value
                decodeCtc(value)
            }
        } catch (ex: Exception) {
            Log.w(TAG, "recognizeLine: ${ex.message}")
            ""
        } finally {
            try { tensor?.close() } catch (_: Exception) {}
        }
    }

    private fun resizeNormImg(bmp: Bitmap, imgH: Int, imgW: Int): Pair<FloatBuffer, LongArray> {
        val w0 = maxOf(1, bmp.width)
        val h0 = maxOf(1, bmp.height)
        val ratio = w0.toFloat() / h0.toFloat()
        val resizeW = (imgH * ratio).toInt().coerceIn(8, imgW)
        val scaled = Bitmap.createScaledBitmap(bmp, resizeW, imgH, true)
        val n = 1 * 3 * imgH * imgW
        val arr = FloatArray(n)
        val pixels = IntArray(scaled.width * scaled.height)
        scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        for (y in 0 until imgH) {
            for (x in 0 until imgW) {
                val r: Float
                val g: Float
                val b: Float
                if (x < scaled.width) {
                    val c = pixels[y * scaled.width + x]
                    r = ((c shr 16) and 0xff) / 255f
                    g = ((c shr 8) and 0xff) / 255f
                    b = (c and 0xff) / 255f
                } else {
                    r = 0f; g = 0f; b = 0f
                }
                val nr = (r - 0.5f) / 0.5f
                val ng = (g - 0.5f) / 0.5f
                val nb = (b - 0.5f) / 0.5f
                arr[0 * imgH * imgW + y * imgW + x] = nr
                arr[1 * imgH * imgW + y * imgW + x] = ng
                arr[2 * imgH * imgW + y * imgW + x] = nb
            }
        }
        if (scaled !== bmp) scaled.recycle()
        val fb = FloatBuffer.allocate(n)
        fb.put(arr)
        fb.rewind()
        return fb to longArrayOf(1L, 3L, imgH.toLong(), imgW.toLong())
    }

    private fun decodeCtc(raw: Any?): String {
        if (raw == null) return ""
        val indices = argmaxSequence(raw)
        return decodeCtcStandard(indices)
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
                if (ch.isNotEmpty() && ch != "blank") sb.append(ch)
            }
        }
        return sb.toString()
    }

    @Suppress("UNCHECKED_CAST")
    private fun argmaxSequence(raw: Any): List<Int> {
        val out = ArrayList<Int>()
        try {
            when (raw) {
                is Array<*> -> {
                    if (raw.isEmpty()) return out
                    val a0 = raw[0]
                    if (a0 is Array<*>) {
                        // [1][T][C]
                        for (t in a0.indices) {
                            out.add(argmaxAny(a0[t]))
                        }
                    } else if (a0 is FloatArray) {
                        for (t in raw.indices) {
                            out.add(argmaxFloat(raw[t] as FloatArray))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "argmaxSequence: ${e.message}")
        }
        return out
    }

    private fun argmaxAny(row: Any?): Int {
        return when (row) {
            is FloatArray -> argmaxFloat(row)
            is Array<*> -> {
                var bi = 0
                var bv = (row[0] as? Number)?.toFloat() ?: -1e9f
                for (i in 1 until row.size) {
                    val v = (row[i] as? Number)?.toFloat() ?: continue
                    if (v > bv) {
                        bv = v
                        bi = i
                    }
                }
                bi
            }
            else -> 0
        }
    }

    private fun argmaxFloat(row: FloatArray): Int {
        var bi = 0
        var bv = row[0]
        for (i in 1 until row.size) {
            if (row[i] > bv) {
                bv = row[i]
                bi = i
            }
        }
        return bi
    }

    @Synchronized
    fun close() {
        try {
            session?.close()
        } catch (_: Exception) {
        }
        session = null
        ready = false
    }
}
