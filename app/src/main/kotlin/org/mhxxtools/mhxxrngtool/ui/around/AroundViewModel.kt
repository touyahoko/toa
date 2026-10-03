package org.mhxxtools.mhxxrngtool.ui.around

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.*
import org.mhxxtools.mhxxrngtool.rng.*

data class AroundUiState(
    val frame: Long = 0L,
    val frameText: String = "",
    val count: Int = 20,
    val countText: String = "20",
    val origin: Int = 0,
    val rows: List<Pair<Long, org.mhxxtools.mhxxrngtool.rng.Charm>> = emptyList(),
    val label: String = ""
)

class AroundViewModel : ViewModel() {
    private val _state = MutableStateFlow(AroundUiState())
    val state: StateFlow<AroundUiState> = _state.asStateFlow()

    fun setFrameText(text: String) {
        if (text.isEmpty() || text.toLongOrNull() != null) {
            _state.update {
                it.copy(frameText = text, frame = text.toLongOrNull()?.coerceAtLeast(0) ?: 0L)
            }
        }
    }
    fun setFrame(f: Long) = _state.update {
        it.copy(frame = f, frameText = if (f == 0L) "" else f.toString())
    }
    fun setCountText(text: String) {
        if (text.isEmpty() || text.toIntOrNull() != null) {
            _state.update {
                it.copy(countText = text, count = text.toIntOrNull()?.coerceIn(1, 500) ?: it.count)
            }
        }
    }
    fun setCount(c: Int) = _state.update {
        it.copy(count = c.coerceIn(1, 500), countText = c.coerceIn(1, 500).toString())
    }
    fun setOrigin(o: Int) = _state.update { it.copy(origin = o) }
    fun setKind(kind: Int) { /* kind picked up at show() time */ }

    fun show(kind: Int) {
        val s = _state.value
        val engine = MHXXEngine(kind)
        val rows = engine.around(s.frame, s.count, s.origin)
        _state.update { it.copy(rows = rows, label = "${rows.size} 件表示中 (中心フレーム: ${s.frame})") }
    }
}
