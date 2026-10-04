package org.mhxxtools.mhxxrngtool.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import org.mhxxtools.mhxxrngtool.rng.*

/** 検索モード: skill=スキル検索 / frame=フレーム指定で内容表示 (HTML統合ツール準拠) */
enum class SearchMode { SKILL, FRAME }

data class SearchUiState(
    val mode: SearchMode = SearchMode.SKILL,
    val kind: Int = 0,
    val skill1Names: List<String> = emptyList(),
    val skill2Names: List<String> = emptyList(),
    val skill1Idx: Int = 0,
    val skill1Pts: Int = 7,
    val skill1PtsText: String = "7",
    val skill1PtRange: IntRange = 3..7,
    val useSkill2: Boolean = false,
    val skill2Idx: Int = 0,
    val skill2Pts: Int = 5,
    val skill2PtsText: String = "5",
    val skill2PtRange: IntRange = 3..5,
    val slot: Int = 0,
    val origin: Int = 0,
    val start: Long = 0L,
    val startText: String = "0",
    val step: Long = 10_000_000L,
    val stepText: String = "10000000",
    val exactMode: Boolean = false,
    val frameQuery: Long = 1L,
    val frameQueryText: String = "1",
    val frameCharm: CharmResult? = null,
    val results: List<CharmResult> = emptyList(),
    val resultCount: Int = 0,
    val progress: Float = 0f,
    val isSearching: Boolean = false,
)

class SearchViewModel : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    fun onKindChanged(kind: Int) {
        val table = KIND_TABLES[kind]!!
        // 全角スペース(U+3000)は .trim() では除去されないため明示的に除去する
        val s1Names = table.skill1.map { SKILL_NAMES[it].replace("　", "").trim() }
        val s2Names = table.skill2.map { SKILL_NAMES[it].replace("　", "").trim() }
        val (lo1, hi1) = ptRange(table.sp1[0])
        val (lo2, hi2) = ptRange(table.sp2[0])
        _state.update { it.copy(
            kind = kind,
            skill1Names = s1Names, skill2Names = s2Names,
            skill1Idx = 0, skill1Pts = hi1, skill1PtsText = hi1.toString(), skill1PtRange = lo1..hi1,
            skill2Idx = 0, skill2Pts = hi2, skill2PtsText = hi2.toString(), skill2PtRange = lo2..hi2,
        )}
    }

    fun setSkill1(idx: Int) {
        val table = KIND_TABLES[_state.value.kind]!!
        val (lo, hi) = ptRange(table.sp1[idx])
        _state.update { it.copy(skill1Idx = idx, skill1Pts = hi, skill1PtsText = hi.toString(), skill1PtRange = lo..hi) }
    }

    fun setSkill2(idx: Int) {
        val table = KIND_TABLES[_state.value.kind]!!
        val (lo, hi) = ptRange(table.sp2[idx])
        _state.update { it.copy(skill2Idx = idx, skill2Pts = hi, skill2PtsText = hi.toString(), skill2PtRange = lo..hi) }
    }

    fun setSkill1PtsText(text: String) {
        // 空欄・数字のみ許可（0を消せる）
        if (text.isEmpty() || text.toIntOrNull() != null) {
            _state.update {
                it.copy(
                    skill1PtsText = text,
                    skill1Pts = text.toIntOrNull() ?: it.skill1Pts
                )
            }
        }
    }
    fun setSkill2PtsText(text: String) {
        if (text.isEmpty() || text.toIntOrNull() != null) {
            _state.update {
                it.copy(
                    skill2PtsText = text,
                    skill2Pts = text.toIntOrNull() ?: it.skill2Pts
                )
            }
        }
    }
    fun setSkill1Pts(pts: Int) = _state.update {
        it.copy(skill1Pts = pts, skill1PtsText = pts.toString())
    }
    fun setSkill2Pts(pts: Int) = _state.update {
        it.copy(skill2Pts = pts, skill2PtsText = pts.toString())
    }
    fun setUseSkill2(v: Boolean) = _state.update { it.copy(useSkill2 = v) }
    fun setSlot(s: Int) = _state.update { it.copy(slot = s) }
    fun setOrigin(o: Int) = _state.update { it.copy(origin = o) }
    fun setStartText(text: String) {
        if (text.isEmpty() || text.toLongOrNull() != null) {
            _state.update {
                it.copy(startText = text, start = text.toLongOrNull()?.coerceAtLeast(0) ?: it.start)
            }
        }
    }
    fun setStepText(text: String) {
        if (text.isEmpty() || text.toLongOrNull() != null) {
            _state.update {
                it.copy(stepText = text, step = text.toLongOrNull()?.coerceAtLeast(1) ?: it.step)
            }
        }
    }
    fun setStart(v: Long) = _state.update { it.copy(start = v.coerceAtLeast(0), startText = v.coerceAtLeast(0).toString()) }
    fun setStep(v: Long) = _state.update { it.copy(step = v.coerceAtLeast(1), stepText = v.coerceAtLeast(1).toString()) }
    fun setExactMode(v: Boolean) = _state.update { it.copy(exactMode = v) }
    fun setMode(m: SearchMode) = _state.update { it.copy(mode = m, results = emptyList(), frameCharm = null, resultCount = 0) }
    fun setFrameQueryText(text: String) {
        if (text.isEmpty() || text.toLongOrNull() != null) {
            _state.update {
                it.copy(frameQueryText = text, frameQuery = text.toLongOrNull()?.coerceAtLeast(0) ?: it.frameQuery)
            }
        }
    }
    fun setFrameQuery(v: Long) = _state.update {
        it.copy(frameQuery = v.coerceAtLeast(0), frameQueryText = v.coerceAtLeast(0).toString())
    }

    /** HTML「フレームから検索」相当: 指定フレームのお守り内容を即時表示 */
    fun lookupFrame() {
        val s = _state.value
        val engine = MHXXEngine(s.kind)
        val result = engine.charmAt(s.frameQuery, s.origin)
        _state.update {
            it.copy(
                frameCharm = result,
                results = listOfNotNull(result),
                resultCount = if (result != null) 1 else 0
            )
        }
    }

    /** 外部 (OCR タブ) からの検索条件反映 */
    fun applyExternalResult(skill1Name: String?, skill1Pts: Int?, skill2Name: String?, skill2Pts: Int?, slot: Int?) {
        val s = _state.value
        skill1Name?.let { n ->
            val idx = s.skill1Names.indexOf(n)
            if (idx >= 0) { setSkill1(idx); skill1Pts?.let { setSkill1Pts(it) } }
        }
        skill2Name?.let { n ->
            val idx = s.skill2Names.indexOf(n)
            if (idx >= 0) { _state.update { it.copy(useSkill2 = true) }; setSkill2(idx); skill2Pts?.let { setSkill2Pts(it) } }
        }
        slot?.let { setSlot(it) }
    }

    fun startSearch() {
        if (searchJob?.isActive == true) return
        val s = _state.value
        _state.update { it.copy(results = emptyList(), resultCount = 0, progress = 0f, isSearching = true) }

        val target = SearchTarget(
            skill1Idx = s.skill1Idx,
            skill1Pts = s.skill1Pts,
            skill2Idx = if (s.useSkill2) s.skill2Idx else null,
            skill2Pts = s.skill2Pts,
            slot = s.slot,
            origin = s.origin
        )

        searchJob = viewModelScope.launch(Dispatchers.Default) {
            val engine = MHXXEngine(s.kind)
            val results = mutableListOf<CharmResult>()
            var count = 0
            val seq = if (s.exactMode)
                engine.search(s.start, s.step, target,
                    shouldStop = { !isActive },
                    onProgress = { done, total -> CoroutineScope(Dispatchers.Main).launch { _state.update { it.copy(progress = done.toFloat() / total) } } })
            else
                engine.searchGreater(s.start, s.step, target,
                    shouldStop = { !isActive },
                    onProgress = { done, total -> CoroutineScope(Dispatchers.Main).launch { _state.update { it.copy(progress = done.toFloat() / total) } } })

            for (r in seq) {
                if (!isActive) break
                count++
                if (results.size < MAX_DISPLAY) results.add(r)
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
        _state.update {
            it.copy(
                isSearching = false, progress = 0f,
                results = emptyList(), resultCount = 0,
                frameCharm = null
            )
        }
    }

    private fun ptRange(sp: IntArray): Pair<Int, Int> = minOf(sp[0], sp[1]) to maxOf(sp[0], sp[1])

    companion object { private const val MAX_DISPLAY = 300 }
}
