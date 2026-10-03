package org.mhxxtools.mhxxrngtool.ui.combo

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.mhxxtools.mhxxrngtool.rng.*

data class ComboUiState(
    // ── 調合スナイプ (フレーム検索) ──────────────────────────────────────
    val sequence: String = "",
    val validationMsg: String = "半角スペース区切りの数字を入力してください",
    val validationOk: Boolean = false,
    val start: Long = 0L,
    val step: Long = 100_000_000L,
    val results: List<FrameResult> = emptyList(),
    val resultCount: Int = 0,
    val progress: Float = 0f,
    val isSearching: Boolean = false,

    // ── 動画解析 (mhxx-combo-scan 準拠テンプレート照合) ─────────────────
    val videoUri: Uri? = null,
    val videoName: String = "",
    val beginFrame: Int = 0,
    val endFrame: Int = 899,
    val videoFps: Int = 30,
    val frameStep: Int = 1,           // テンプレート照合は 1 推奨
    val isAnalyzing: Boolean = false,
    val analyzeProgress: Float = 0f,
    val analyzeMsg: String = "",
    val detectedNumbers: List<Int> = emptyList(),
    val craftSummary: String = ""
)

class ComboViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(ComboUiState())
    val state: StateFlow<ComboUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var analyzeJob: Job? = null

    // ── 調合スナイプ (フレーム検索) ──────────────────────────────────────

    fun setSequence(text: String) {
        val values = parseValues(text)
        val (msg, ok) = validate(values)
        _state.update { it.copy(sequence = text, validationMsg = msg, validationOk = ok) }
    }

    fun setStart(v: Long) = _state.update { it.copy(start = v.coerceAtLeast(0)) }
    fun setStep(v: Long) = _state.update { it.copy(step = v.coerceAtLeast(1)) }

    private fun parseValues(text: String): List<Int>? =
        runCatching { text.trim().split(Regex("\\s+")).map { it.toInt() } }.getOrNull()

    private fun validate(values: List<Int>?): Pair<String, Boolean> {
        if (values == null) return "半角スペース区切りの数字を入力してください" to false
        if (values.size < 5) return "⚠ 数値列が短すぎます（5件以上推奨）" to false
        val (dif, invalid) = MHXXEngine.parseComboSequence(values)
        if (dif.isEmpty()) return "⚠ 数値列が短すぎます (先頭3件を除いた後に2件以上必要です)" to false
        if (invalid.isNotEmpty()) {
            val marked = dif.mapIndexed { i, d -> if (i in invalid) "[$d]" else "$d" }.joinToString(" ")
            return "⚠ 一部増分が2/3/4外: $marked\n→ 有効区間で検索します" to true
        }
        return "✓ 増分列 (${dif.size}件): ${dif.joinToString(" ")}" to true
    }

    fun startSearch() {
        if (searchJob?.isActive == true) return
        val s = _state.value
        val values = parseValues(s.sequence) ?: return
        _state.update { it.copy(results = emptyList(), resultCount = 0, progress = 0f, isSearching = true) }

        searchJob = viewModelScope.launch(Dispatchers.Default) {
            val engine = MHXXEngine(0)
            val results = mutableListOf<FrameResult>()
            var count = 0
            for (r in engine.searchCombo(s.start, s.step, values,
                shouldStop = { !isActive },
                onProgress = { done, total ->
                    CoroutineScope(Dispatchers.Main).launch {
                        _state.update { it.copy(progress = done.toFloat() / total.coerceAtLeast(1)) }
                    }
                })) {
                if (!isActive) break
                count++
                if (results.size < 300) results.add(r)
                val snap = results.toList(); val c = count
                withContext(Dispatchers.Main) { _state.update { it.copy(results = snap, resultCount = c) } }
            }
            withContext(Dispatchers.Main) { _state.update { it.copy(isSearching = false, progress = 0f) } }
        }
    }

    fun stopSearch() {
        searchJob?.cancel()
        _state.update { it.copy(isSearching = false, progress = 0f) }
    }

    fun resetSearch() {
        searchJob?.cancel()
        analyzeJob?.cancel()
        _state.update {
            it.copy(
                isSearching = false, progress = 0f, results = emptyList(), resultCount = 0,
                isAnalyzing = false, analyzeProgress = 0f, analyzeMsg = "",
                detectedNumbers = emptyList(), sequence = "", craftSummary = "",
                validationMsg = "半角スペース区切りの数字を入力してください", validationOk = false
            )
        }
    }

    // ── 動画解析 ─────────────────────────────────────────────────────────

    fun setVideoUri(uri: Uri) {
        _state.update {
            it.copy(videoUri = uri, videoName = "…", analyzeMsg = "", detectedNumbers = emptyList(), craftSummary = "")
        }
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val name = ComboVideoAnalyzer.getDisplayName(ctx, uri)
            val fps = _state.value.videoFps
            val total = ComboVideoAnalyzer.getTotalFrames(ctx, uri, fps)
            _state.update {
                it.copy(
                    videoName = name,
                    endFrame = if (total > 0) total - 1 else it.endFrame
                )
            }
        }
    }

    fun setBeginFrame(v: Int) = _state.update { it.copy(beginFrame = v.coerceAtLeast(0)) }
    fun setEndFrame(v: Int) = _state.update { it.copy(endFrame = v.coerceAtLeast(0)) }
    fun setVideoFps(v: Int) = _state.update { it.copy(videoFps = v.coerceIn(1, 120)) }
    fun setFrameStep(v: Int) = _state.update { it.copy(frameStep = v.coerceIn(1, 30)) }

    fun startAnalysis() {
        if (analyzeJob?.isActive == true) return
        val s = _state.value
        val uri = s.videoUri ?: return
        val ctx = getApplication<Application>()

        _state.update {
            it.copy(
                isAnalyzing = true,
                analyzeProgress = 0f,
                analyzeMsg = "テンプレート照合で解析中…",
                detectedNumbers = emptyList(),
                craftSummary = ""
            )
        }

        analyzeJob = viewModelScope.launch {
            val result = ComboVideoAnalyzer.analyze(
                context = ctx,
                uri = uri,
                beginFrame = s.beginFrame,
                endFrame = s.endFrame,
                fps = s.videoFps,
                frameStep = s.frameStep,
                onProgress = { done, total ->
                    _state.update { it.copy(analyzeProgress = done.toFloat() / total.coerceAtLeast(1)) }
                }
            )

            result.fold(
                onSuccess = { ar ->
                    val nums = ar.cumulative
                    val formatted = nums.joinToString(" ") { "%02d".format(it) }
                    val seqText = nums.joinToString(" ") { "%02d".format(it) }
                    val (msg, ok) = validate(nums)
                    val summary = buildString {
                        append("読取 ${ar.craftingFrames}/${ar.framesRead} コマ")
                        if (ar.materialFrom != null) append(" 素材 ${ar.materialFrom}→${ar.materialTo ?: "?"}")
                        append(" 調合 ${ar.crafts.size} 区間")
                        if (ar.issues.isNotEmpty()) append("\n⚠ ${ar.issues.joinToString(" / ")}")
                    }
                    _state.update {
                        it.copy(
                            isAnalyzing = false,
                            analyzeProgress = 1f,
                            detectedNumbers = nums,
                            sequence = if (nums.isNotEmpty()) seqText else it.sequence,
                            validationMsg = if (nums.isNotEmpty()) msg else it.validationMsg,
                            validationOk = if (nums.isNotEmpty()) ok else it.validationOk,
                            craftSummary = summary,
                            analyzeMsg = if (nums.isEmpty())
                                "⚠ 数値を検出できませんでした"
                            else if (ok)
                                "✓ ${nums.size}個検出 → 自動フレーム検索: $formatted"
                            else
                                "⚠ ${nums.size}個検出: $formatted / $msg"
                        )
                    }
                    if (nums.isNotEmpty() && ok) {
                        startSearch()
                    }
                },
                onFailure = { err ->
                    _state.update {
                        it.copy(
                            isAnalyzing = false,
                            analyzeProgress = 0f,
                            analyzeMsg = "❌ ${err.message}"
                        )
                    }
                }
            )
        }
    }

    fun stopAnalysis() {
        analyzeJob?.cancel()
        _state.update { it.copy(isAnalyzing = false, analyzeMsg = "停止しました") }
    }

    fun applyDetectedNumbers() {
        val nums = _state.value.detectedNumbers
        if (nums.isNotEmpty()) {
            val text = nums.joinToString(" ") { "%02d".format(it) }
            setSequence(text)
        }
    }
}
