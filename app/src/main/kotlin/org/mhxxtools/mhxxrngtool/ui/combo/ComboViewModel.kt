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

    // ── 動画解析 ─────────────────────────────────────────────────────────
    val videoUri: Uri? = null,
    val videoName: String = "",
    val beginFrame: Int = 0,
    val endFrame: Int = 899,          // 30fps × 30秒 = 900フレーム (0始まり)
    val videoFps: Int = 30,
    val frameStep: Int = 3,           // mhxx-snipe 準拠 3フレーム間隔
    val isAnalyzing: Boolean = false,
    val analyzeProgress: Float = 0f,
    val analyzeMsg: String = "",
    val detectedNumbers: List<Int> = emptyList()
)

class ComboViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(ComboUiState())
    val state: StateFlow<ComboUiState> = _state.asStateFlow()

    private var searchJob:  Job? = null
    private var analyzeJob: Job? = null

    // ── 調合スナイプ (フレーム検索) ──────────────────────────────────────

    fun setSequence(text: String) {
        val values = parseValues(text)
        val (msg, ok) = validate(values)
        _state.update { it.copy(sequence = text, validationMsg = msg, validationOk = ok) }
    }

    fun setStart(v: Long) = _state.update { it.copy(start = v.coerceAtLeast(0)) }
    fun setStep(v: Long)  = _state.update { it.copy(step = v.coerceAtLeast(1)) }

    private fun parseValues(text: String): List<Int>? =
        runCatching { text.trim().split(Regex("\\s+")).map { it.toInt() } }.getOrNull()

    private fun validate(values: List<Int>?): Pair<String, Boolean> {
        if (values == null) return "半角スペース区切りの数字を入力してください" to false
        val (dif, invalid) = MHXXEngine.parseComboSequence(values)
        if (dif.isEmpty()) return "⚠ 数値列が短すぎます (先頭3件を除いた後に2件以上必要です)" to false
        if (invalid.isNotEmpty()) {
            val marked = dif.mapIndexed { i, d -> if (i in invalid) "[$d]" else "$d" }.joinToString(" ")
            return "⚠ 2/3/4以外の増分があります:\n$marked" to false
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
                        _state.update { it.copy(progress = done.toFloat() / total) }
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
                detectedNumbers = emptyList(), sequence = "",
                validationMsg = "半角スペース区切りの数字を入力してください", validationOk = false
            )
        }
    }

    // ── 動画解析 ─────────────────────────────────────────────────────────

    /** 動画ファイルが選択されたときに呼ぶ。ファイル名・総フレーム数をIOスレッドで取得する。 */
    fun setVideoUri(uri: Uri) {
        _state.update {
            it.copy(videoUri = uri, videoName = "…", analyzeMsg = "", detectedNumbers = emptyList())
        }
        viewModelScope.launch(Dispatchers.IO) {
            val ctx  = getApplication<Application>()
            val name = ComboVideoAnalyzer.getDisplayName(ctx, uri)
            val fps  = _state.value.videoFps
            val total = ComboVideoAnalyzer.getTotalFrames(ctx, uri, fps)
            _state.update {
                it.copy(
                    videoName = name,
                    endFrame  = if (total > 0) total - 1 else it.endFrame
                )
            }
        }
    }

    fun setBeginFrame(v: Int) = _state.update { it.copy(beginFrame = v.coerceAtLeast(0)) }
    fun setEndFrame(v: Int)   = _state.update { it.copy(endFrame   = v.coerceAtLeast(0)) }
    fun setVideoFps(v: Int)   = _state.update { it.copy(videoFps   = v.coerceIn(1, 120)) }
    fun setFrameStep(v: Int)  = _state.update { it.copy(frameStep  = v.coerceIn(1, 30)) }

    /** 動画解析を開始する */
    fun startAnalysis() {
        if (analyzeJob?.isActive == true) return
        val s   = _state.value
        val uri = s.videoUri ?: return
        val ctx = getApplication<Application>()

        _state.update {
            it.copy(
                isAnalyzing      = true,
                analyzeProgress  = 0f,
                analyzeMsg       = "解析中…",
                detectedNumbers  = emptyList()
            )
        }

        analyzeJob = viewModelScope.launch {
            // 短尺（10秒未満）は間隔1で取りこぼし防止
            val span = (s.endFrame - s.beginFrame).coerceAtLeast(0)
            val useStep = if (span < 300) 1 else s.frameStep
            val result = ComboVideoAnalyzer.analyze(
                context    = ctx,
                uri        = uri,
                beginFrame = s.beginFrame,
                endFrame   = s.endFrame,
                fps        = s.videoFps,
                frameStep  = useStep,
                onProgress = { done, total ->
                    // StateFlow.update はスレッドセーフなのでIOスレッドからも呼べる
                    _state.update { it.copy(analyzeProgress = done.toFloat() / total) }
                }
            )

            result.fold(
                onSuccess = { nums ->
                    val formatted = nums.joinToString(" ") { "%02d".format(it) }
                    val seqText = nums.joinToString(" ") { "%02d".format(it) }
                    val values = nums
                    val (msg, ok) = validate(values)
                    _state.update {
                        it.copy(
                            isAnalyzing     = false,
                            analyzeProgress = 1f,
                            detectedNumbers = nums,
                            sequence        = if (nums.isNotEmpty()) seqText else it.sequence,
                            validationMsg   = if (nums.isNotEmpty()) msg else it.validationMsg,
                            validationOk    = if (nums.isNotEmpty()) ok else it.validationOk,
                            analyzeMsg      = if (nums.isEmpty())
                                "⚠ 数値を検出できませんでした。フレーム範囲・FPSを確認してください。"
                            else if (ok)
                                "✓ ${nums.size}個検出 → 自動フレーム検索開始: $formatted"
                            else
                                "⚠ ${nums.size}個検出（増分チェック要確認）: $formatted / $msg"
                        )
                    }
                    // 認識成功かつ増分OKなら自動で現在位置検索
                    if (nums.isNotEmpty() && ok) {
                        startSearch()
                    }
                },
                onFailure = { err ->
                    _state.update {
                        it.copy(
                            isAnalyzing    = false,
                            analyzeProgress = 0f,
                            analyzeMsg     = "❌ エラー: ${err.message}"
                        )
                    }
                }
            )
        }
    }

    /** 動画解析を停止する */
    fun stopAnalysis() {
        analyzeJob?.cancel()
        _state.update { it.copy(isAnalyzing = false, analyzeMsg = "停止しました") }
    }

    /**
     * 検出した数値を調合数値列フィールドに適用する。
     * applyDetectedNumbers 後にそのまま「検索」ボタンを押せる。
     */
    fun applyDetectedNumbers() {
        val nums = _state.value.detectedNumbers
        if (nums.isNotEmpty()) {
            val text = nums.joinToString(" ") { "%02d".format(it) }
            setSequence(text)
        }
    }
}
