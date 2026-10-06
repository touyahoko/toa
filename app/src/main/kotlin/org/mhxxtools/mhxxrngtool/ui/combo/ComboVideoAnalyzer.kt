package org.mhxxtools.mhxxrngtool.ui.combo

import android.content.Context
import android.graphics.Bitmap
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
