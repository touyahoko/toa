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
 * japan_PP-OCRv3_rec ONNX 専用。
 * この配布モデルの入力は固定 shape: [3, 3, 32, 160]
 * （バッチ3必須・高さ32・幅160）
 */
object PaddleOcrEngine {
    private const val TAG = "PaddleOcr"

    @Volatile private var session: OrtSession? = null
    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var keys: List<String> = emptyList()
    @Volatile private var ready: Boolean = false
    private var inputName: String = "x"

    // japan_PP-OCRv3_rec_infer.onnx 実測値
    private const val BATCH = 3
    private const val REC_H = 32
    private const val REC_W = 160

    fun isReady(): Boolean = ready

    @Synchronized
    fun init(context: Context): Boolean {
        if (ready) return true
        return try {
            val am = context.assets
            // 存在確認を先に（エラーメッセージ用）
            try {
                am.open("paddle_ocr/keys.txt").close()
            } catch (e: Exception) {
                Log.e(TAG, "missing assets/paddle_ocr/keys.txt")
                return false
            }
            try {
                am.open("paddle_ocr/rec.onnx").close()
            } catch (e: Exception) {
                Log.e(TAG, "missing assets/paddle_ocr/rec.onnx")
                return false
            }

            val keyText = am.open("paddle_ocr/keys.txt").bufferedReader(Charsets.UTF_8).readText()
            keys = keyText.lines().map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
            if (keys.isEmpty()) {
                Log.e(TAG, "keys.txt empty")
                return false
            }

            val modelBytes = am.open("paddle_ocr/rec.onnx").use { it.readBytes() }
            if (modelBytes.size < 1000) {
                Log.e(TAG, "rec.onnx too small (${modelBytes.size}) — Git LFS pointer?")
                return false
            }

            val e = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions()
            opts.setIntraOpNumThreads(2)
            val s = e.createSession(modelBytes, opts)
            session = s
            env = e
            inputName = s.inputNames.iterator().next()
            ready = true
            Log.i(TAG, "ready keys=${keys.size} input=$inputName modelBytes=${modelBytes.size}")
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
        val (fb, shape) = resizeNormImgBatch3(bitmap)
        var tensor: OnnxTensor? = null
        return try {
            tensor = OnnxTensor.createTensor(e, fb, shape)
            @Suppress("UNCHECKED_CAST")
            val inputs = Collections.singletonMap(inputName, tensor as OnnxTensor)
            s.run(inputs).use { result ->
                val value = result[0].value
                // 出力 [3, T, C] のバッチ0だけ使う
                decodeCtcBatch0(value)
            }
        } catch (ex: Exception) {
            Log.w(TAG, "recognizeLine: ${ex.message}")
            ""
        } finally {
            try { tensor?.close() } catch (_: Exception) {}
        }
    }

    /** [3,3,32,160] に正規化。実画像は batch0、batch1/2 はゼロ。 */
    private fun resizeNormImgBatch3(bmp: Bitmap): Pair<FloatBuffer, LongArray> {
        val scaled = Bitmap.createScaledBitmap(bmp, REC_W, REC_H, true)
        val n = BATCH * 3 * REC_H * REC_W
        val arr = FloatArray(n) // 0 埋め
        val pixels = IntArray(scaled.width * scaled.height)
        scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        val plane = 3 * REC_H * REC_W
        for (y in 0 until REC_H) {
            for (x in 0 until REC_W) {
                val c = pixels[y * scaled.width + x]
                val r = ((c shr 16) and 0xff) / 255f
                val g = ((c shr 8) and 0xff) / 255f
                val b = (c and 0xff) / 255f
                // PP-OCR normalize: (v-0.5)/0.5
                val nr = (r - 0.5f) / 0.5f
                val ng = (g - 0.5f) / 0.5f
                val nb = (b - 0.5f) / 0.5f
                // batch 0 only
                arr[0 * plane + 0 * REC_H * REC_W + y * REC_W + x] = nr
                arr[0 * plane + 1 * REC_H * REC_W + y * REC_W + x] = ng
                arr[0 * plane + 2 * REC_H * REC_W + y * REC_W + x] = nb
            }
        }
        if (scaled !== bmp) scaled.recycle()
        val fb = FloatBuffer.allocate(n)
        fb.put(arr)
        fb.rewind()
        return fb to longArrayOf(BATCH.toLong(), 3L, REC_H.toLong(), REC_W.toLong())
    }

    @Suppress("UNCHECKED_CAST")
    private fun decodeCtcBatch0(raw: Any?): String {
        if (raw == null) return ""
        try {
            // 期待: Array[batch][T][C] or similar
            if (raw is Array<*>) {
                val batch0 = raw[0] ?: return ""
                val indices = ArrayList<Int>()
                if (batch0 is Array<*>) {
                    for (t in batch0.indices) {
                        indices.add(argmaxAny(batch0[t]))
                    }
                } else if (batch0 is FloatArray) {
                    // unlikely flat
                    return ""
                }
                return decodeCtcStandard(indices)
            }
        } catch (e: Exception) {
            Log.w(TAG, "decode: ${e.message}")
        }
        return ""
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

    private fun argmaxAny(row: Any?): Int {
        return when (row) {
            is FloatArray -> {
                var bi = 0
                var bv = row[0]
                for (i in 1 until row.size) if (row[i] > bv) { bv = row[i]; bi = i }
                bi
            }
            is Array<*> -> {
                var bi = 0
                var bv = (row[0] as? Number)?.toFloat() ?: -1e9f
                for (i in 1 until row.size) {
                    val v = (row[i] as? Number)?.toFloat() ?: continue
                    if (v > bv) { bv = v; bi = i }
                }
                bi
            }
            else -> 0
        }
    }

    @Synchronized
    fun close() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        ready = false
    }
}
