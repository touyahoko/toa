package org.mhxxtools.mhxxrngtool.ui.reward

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.mhxxtools.mhxxrngtool.rng.*

/** 村下位採取ツアー系のデフォルト報酬テーブル (notebook 準拠) */
val DEFAULT_REWARD_TABLE = linkedMapOf(
    "謎骨" to 20,
    "釣り" to 40,
    "生肉" to 65,
    "砥石" to 85,
    "力餌" to 90,
    "重餌" to 95,
    "速餌" to 100
)

data class RewardUiState(
    val slots: List<String> = List(6) { "なし" },
    val slotCount: Int = 6,
    val luckGreat: Boolean = false, // false=通常(bonusTh=28)
    val start: Long = 0L,
    val step: Long = 10_000_000L,
    val results: List<FrameResult> = emptyList(),
    val resultCount: Int = 0,
    val progress: Float = 0f,
    val isSearching: Boolean = false,
    val message: String = ""
)

class RewardViewModel : ViewModel() {
    private val _state = MutableStateFlow(RewardUiState())
    val state: StateFlow<RewardUiState> = _state.asStateFlow()
    private var job: Job? = null

    val itemOptions: List<String> = listOf("なし") + DEFAULT_REWARD_TABLE.keys.toList()

    fun setSlotCount(n: Int) {
        val c = n.coerceIn(4, 8)
        _state.update {
            val slots = it.slots.toMutableList()
            while (slots.size < c) slots.add("なし")
            it.copy(slotCount = c, slots = slots.take(c))
        }
    }

    fun setSlot(index: Int, item: String) {
        _state.update {
            val slots = it.slots.toMutableList()
            if (index in slots.indices) slots[index] = item
            it.copy(slots = slots)
        }
    }

    fun setLuckGreat(v: Boolean) = _state.update { it.copy(luckGreat = v) }
    fun setStart(v: Long) = _state.update { it.copy(start = v.coerceAtLeast(0)) }
    fun setStep(v: Long) = _state.update { it.copy(step = v.coerceAtLeast(1)) }

    fun startSearch(kind: Int = 0) {
        if (job?.isActive == true) return
        val s = _state.value
        val items = s.slots.filter { it != "なし" && it.isNotBlank() }
        if (items.size !in 4..8) {
            _state.update { it.copy(message = "⚠ 報酬アイテムを4〜8個選んでください（現在 ${items.size}）") }
            return
        }
        _state.update {
            it.copy(isSearching = true, progress = 0f, results = emptyList(), resultCount = 0, message = "検索中…")
        }
        job = viewModelScope.launch(Dispatchers.Default) {
            val engine = MHXXEngine(kind)
            val bonusTh = if (s.luckGreat) 35 else 28
            val found: List<FrameResult> = engine.searchReward(
                start = s.start,
                step = s.step,
                bonusTh = bonusTh,
                targetItems = items,
                rewardTable = DEFAULT_REWARD_TABLE,
                shouldStop = { !isActive },
                onProgress = { done, total ->
                    _state.update { it.copy(progress = done.toFloat() / total.coerceAtLeast(1L)) }
                }
            )
            val shown = found.take(200)
            withContext(Dispatchers.Main) {
                _state.update {
                    it.copy(
                        isSearching = false,
                        progress = 0f,
                        results = shown,
                        resultCount = found.size,
                        message = if (found.isEmpty()) "❌ 一致なし（範囲を広げてください）" else "✓ ${found.size}件ヒット"
                    )
                }
            }
        }
    }

    fun stopSearch() {
        job?.cancel()
        _state.update { it.copy(isSearching = false, progress = 0f, message = "停止しました") }
    }

    fun resetSearch() {
        job?.cancel()
        _state.value = RewardUiState()
    }
}
