package org.mhxxtools.mhxxrngtool.ui.combo

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * mhxx-combo-scan 準拠の動画解析。
 * テンプレート照合で素材/完成品を読み、クロスチェックで累計列を組み立てる。
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

    /**
     * @param frameStep 何フレームおきに読むか（combo-scan は実質全フレームだが、
     *                  速度優先で 1〜3 を推奨。素材変化は数フレーム続くので 2〜3 でも可）
     */
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
                var capped = false

                for ((i, frameIdx) in indices.withIndex()) {
                    coroutineContext.ensureActive()
                    val timeUs = (frameIdx * 1_000_000L / fps).coerceAtMost(durationMs * 1000)
                    val bmp: Bitmap? = try {
                        retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    } catch (_: Exception) {
                        null
                    }
                    if (bmp != null) {
                        try {
                            val t = frameIdx.toDouble() / fps
                            val reading = ComboFrameReader.readFrame(t, bmp)
                            if (reading.crafting) {
                                val done = ComboFrameReader.reachedCap(reading.product, seenBelow)
                                readings.add(
                                    if (done) reading.copy(done = true) else reading
                                )
                                if (done) {
                                    capped = true
                                    onProgress(total, total)
                                    break
                                }
                            } else {
                                readings.add(reading)
                            }
                        } finally {
                            bmp.recycle()
                        }
                    }
                    onProgress(i + 1, total)
                }

                val analysis = ComboCrossCheck.analyze(readings)
                val seq = ComboCrossCheck.toSearchSequence(analysis.cumulative)
                    ?: analysis.cumulative.mapNotNull { it }.let { if (it.size >= 5) it else emptyList() }

                if (seq.isEmpty() && analysis.crafts.isEmpty()) {
                    error(
                        if (readings.none { it.crafting })
                            "調合画面が見つかりませんでした。解像度が 1280×720 か、調合パネルが映っているか確認してください。"
                        else
                            "数値列を組み立てられませんでした。${analysis.issues.joinToString("; ")}"
                    )
                }

                AnalyzeResult(
                    cumulative = seq.ifEmpty {
                        // fallback: product values only in order of segments
                        analysis.crafts.flatMap { c ->
                            listOf(c.before) + when (val r = c.resolution) {
                                is ComboCrossCheck.Resolution.Exact -> {
                                    var a = c.before
                                    r.yields.map { a += it; a }
                                }
                                is ComboCrossCheck.Resolution.Inferred -> {
                                    var a = c.before
                                    r.yields.map { a += it; a }
                                }
                                else -> listOf(c.after)
                            }
                        }.distinct()
                    },
                    crafts = analysis.crafts,
                    materialFrom = analysis.materialFrom,
                    materialTo = analysis.materialTo,
                    issues = analysis.issues,
                    framesRead = readings.size,
                    craftingFrames = readings.count { it.crafting }
                )
            } finally {
                retriever.release()
            }
        }
    }
}
