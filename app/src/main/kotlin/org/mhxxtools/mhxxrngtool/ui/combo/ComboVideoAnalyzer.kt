package org.mhxxtools.mhxxrngtool.ui.combo

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * mhxx-combo-scan 準拠の動画解析。
 *
 * - 1280×720 に縮小取得 (API 27+)
 * - ROI テンプレート照合
 * - 完成品上限で打ち切り
 * - 曖昧区間は候補で補完して数列を返す
 */
object ComboVideoAnalyzer {

    data class AnalyzeResult(
        val cumulative: List<Int>,
        val crafts: List<ComboCrossCheck.Craft>,
        val materialFrom: Int?,
        val materialTo: Int?,
        val issues: List<String>,
        val framesRead: Int,
        val craftingFrames: Int
    )

    fun getDisplayName(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && col >= 0) c.getString(col) else null
        } ?: uri.lastPathSegment
    }.getOrNull() ?: "動画ファイル"

    fun getTotalFrames(context: Context, uri: Uri, fps: Int): Int {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            ((ms / 1000.0) * fps).toInt().coerceAtLeast(0)
        } catch (_: Exception) {
            0
        } finally {
            r.release()
        }
    }

    suspend fun analyze(
        context: Context,
        uri: Uri,
        beginFrame: Int,
        endFrame: Int,
        fps: Int,
        frameStep: Int = 1,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Result<AnalyzeResult> = withContext(Dispatchers.IO) {
        runCatching {
            val sequential = runCatching {
                decodeSequential(context, uri, beginFrame, endFrame, fps, frameStep, onProgress)
            }.getOrNull()
            if (sequential != null && sequential.isNotEmpty()) {
                return@withContext Result.success(buildResult(sequential))
            }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val durationMs = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull() ?: 0L

                val start = beginFrame.coerceAtLeast(0)
                val end = endFrame.coerceAtLeast(start)
                val step = frameStep.coerceIn(1, 30)
                val indices = (start..end step step).toList()
                val total = indices.size
                val readings = mutableListOf<ComboFrameReader.FrameReading>()
                val seenBelow = booleanArrayOf(false)

                for ((i, frameIdx) in indices.withIndex()) {
                    coroutineContext.ensureActive()
                    val timeUs = (frameIdx * 1_000_000L / fps).coerceAtMost(durationMs * 1000)
                    val bmp = getFrame(retriever, timeUs)
                    if (bmp == null) {
                        onProgress(i + 1, total)
                    } else {
                        try {
                            val t = frameIdx.toDouble() / fps
                            val reading = ComboFrameReader.readFrame(t, bmp)
                            if (reading.crafting) {
                                val done = ComboFrameReader.reachedCap(reading.product, seenBelow)
                                readings.add(if (done) reading.copy(done = true) else reading)
                                if (done) {
                                    onProgress(total, total)
                                    break
                                }
                            } else {
                                readings.add(reading)
                            }
                        } finally {
                            bmp.recycle()
                        }
                        onProgress(i + 1, total)
                    }
                }

                val analysis = ComboCrossCheck.analyze(readings)
                val seq = ComboCrossCheck.toSearchSequence(analysis.cumulative)
                    ?: ComboCrossCheck.toSearchSequenceRelaxed(analysis)

                if (seq == null && analysis.crafts.isEmpty()) {
                    error(
                        if (readings.none { it.crafting })
                            "調合画面が見つかりませんでした。Switch録画は 1280×720・30fps を推奨します。"
                        else
                            "数値列を組み立てられませんでした。${analysis.issues.joinToString("; ")}"
                    )
                }

                val finalSeq = seq ?: emptyList()
                val ambiguousCount = analysis.crafts.count {
                    it.resolution is ComboCrossCheck.Resolution.Ambiguous ||
                        it.resolution is ComboCrossCheck.Resolution.Failed
                }
                val extraIssues = mutableListOf<String>()
                if (ambiguousCount > 0 && finalSeq.isNotEmpty()) {
                    extraIssues.add(
                        "曖昧な区間 ${ambiguousCount} 件を候補から補完しました。" +
                            "結果がずれる場合は数値列を手修正してください。"
                    )
                } else if (finalSeq.isEmpty() && analysis.cumulative.isNotEmpty()) {
                    extraIssues.add("途中に不明な増分があります。数値列を手入力してください。")
                }

                AnalyzeResult(
                    cumulative = finalSeq,
                    crafts = analysis.crafts,
                    materialFrom = analysis.materialFrom,
                    materialTo = analysis.materialTo,
                    issues = analysis.issues + extraIssues,
                    framesRead = readings.size,
                    craftingFrames = readings.count { it.crafting }
                )
            } finally {
                retriever.release()
            }
        }
    }


    private fun buildResult(readings: List<ComboFrameReader.FrameReading>): AnalyzeResult {
        val analysis = ComboCrossCheck.analyze(readings)
        val seq = ComboCrossCheck.toSearchSequence(analysis.cumulative)
            ?: ComboCrossCheck.toSearchSequenceRelaxed(analysis)
        if (seq == null && analysis.crafts.isEmpty()) {
            error(
                if (readings.none { it.crafting })
                    "調合画面が見つかりませんでした。Switch録画は 1280×720・30fps を推奨します。"
                else
                    "数値列を組み立てられませんでした。${analysis.issues.joinToString("; ")}"
            )
        }
        val finalSeq = seq ?: emptyList()
        val ambiguousCount = analysis.crafts.count {
            it.resolution is ComboCrossCheck.Resolution.Ambiguous ||
                it.resolution is ComboCrossCheck.Resolution.Failed
        }
        val extraIssues = mutableListOf<String>()
        if (ambiguousCount > 0 && finalSeq.isNotEmpty()) {
            extraIssues.add(
                "曖昧な区間 ${ambiguousCount} 件を候補から補完しました。結果がずれる場合は数値列を手修正してください。"
            )
        } else if (finalSeq.isEmpty() && analysis.cumulative.isNotEmpty()) {
            extraIssues.add("途中に不明な増分があります。数値列を手入力してください。")
        }
        return AnalyzeResult(
            cumulative = finalSeq,
            crafts = analysis.crafts,
            materialFrom = analysis.materialFrom,
            materialTo = analysis.materialTo,
            issues = analysis.issues + extraIssues,
            framesRead = readings.size,
            craftingFrames = readings.count { it.crafting }
        )
    }

    /**
     * サイト版と同じく先頭から連続デコードする。1コマごとにシークしない。
     * 輝度は Y プレーンの ROI だけを読む。
     */
    private suspend fun decodeSequential(
        context: Context,
        uri: Uri,
        beginFrame: Int,
        endFrame: Int,
        fps: Int,
        frameStep: Int,
        onProgress: (Int, Int) -> Unit
    ): List<ComboFrameReader.FrameReading> {
        val extractor = MediaExtractor()
        val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("動画を開けません")
        val readings = mutableListOf<ComboFrameReader.FrameReading>()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(fd.fileDescriptor)
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    extractor.selectTrack(i)
                    format = f
                    break
                }
            }
            val fmt = format ?: error("映像トラックがありません")
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val width = fmt.getInteger(MediaFormat.KEY_WIDTH)
            val height = fmt.getInteger(MediaFormat.KEY_HEIGHT)
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(fmt, null, null, 0)
            decoder.start()

            val start = beginFrame.coerceAtLeast(0)
            val end = endFrame.coerceAtLeast(start)
            val step = frameStep.coerceIn(1, 30)
            val total = ((end - start) / step) + 1
            extractor.seekTo(start * 1_000_000L / fps, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val seenBelow = booleanArrayOf(false)
            var reported = 0
            val dec = decoder!!

            while (!outputDone) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val inIndex = dec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = dec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = dec.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    val frameIdx = ((info.presentationTimeUs * fps) / 1_000_000L).toInt()
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (frameIdx > end || eos && frameIdx > end) {
                        dec.releaseOutputBuffer(outIndex, false)
                        outputDone = true
                        break
                    }
                    val take = frameIdx >= start && (frameIdx - start) % step == 0 && info.size > 0
                    if (take) {
                        val image = dec.getOutputImage(outIndex)
                        if (image != null) {
                            try {
                                val roi = yRoi(image, width, height)
                                val t = frameIdx.toDouble() / fps
                                var reading = ComboFrameReader.readRoi(t, roi)
                                if (reading.crafting) {
                                    val done = ComboFrameReader.reachedCap(reading.product, seenBelow)
                                    if (done) reading = reading.copy(done = true)
                                    readings.add(reading)
                                    if (done) {
                                        dec.releaseOutputBuffer(outIndex, false)
                                        outputDone = true
                                        break
                                    }
                                } else {
                                    readings.add(reading)
                                }
                            } finally {
                                image.close()
                            }
                        }
                        reported++
                        onProgress(reported.coerceAtMost(total), total)
                    }
                    if (!outputDone) dec.releaseOutputBuffer(outIndex, false)
                    if (eos) outputDone = true
                }
            }
            onProgress(total, total)
            if (readings.none { it.crafting }) error("連続デコードで調合画面が読めません")
            return readings
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            try { fd.close() } catch (_: Exception) {}
        }
    }

    private fun yRoi(image: Image, codedW: Int, codedH: Int): IntArray {
        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val vw = image.width.coerceAtLeast(1)
        val vh = image.height.coerceAtLeast(1)
        val out = IntArray(ComboFrameReader.ROI_W * ComboFrameReader.ROI_H)
        var i = 0
        for (ry in 0 until ComboFrameReader.ROI_H) {
            val sy = ((ComboFrameReader.ROI_Y + ry) * vh / ComboFrameReader.SOURCE_H).coerceIn(0, vh - 1)
            val row = sy * rowStride
            for (rx in 0 until ComboFrameReader.ROI_W) {
                val sx = ((ComboFrameReader.ROI_X + rx) * vw / ComboFrameReader.SOURCE_W).coerceIn(0, vw - 1)
                val pos = row + sx * pixelStride
                out[i++] = if (pos in 0 until buf.limit()) buf.get(pos).toInt() and 0xFF else 0
            }
        }
        return out
    }

    private fun getFrame(retriever: MediaMetadataRetriever, timeUs: Long): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                // CLOSEST_SYNC はキーフレームのみで速いが数字がずれるので CLOSEST を使う
                retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    ComboFrameReader.SOURCE_W,
                    ComboFrameReader.SOURCE_H
                )
            } else {
                @Suppress("DEPRECATION")
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            }
        } catch (_: Exception) {
            null
        }
    }
}
