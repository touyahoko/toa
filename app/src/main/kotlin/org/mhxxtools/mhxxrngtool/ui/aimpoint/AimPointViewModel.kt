package org.mhxxtools.mhxxrngtool.ui.aimpoint

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.*
import org.mhxxtools.mhxxrngtool.rng.*

enum class AimPointMode { QUEST, HALCYON, JUJU }

data class AimPointUiState(
    val frame: Long = 0L,
    val mode: AimPointMode = AimPointMode.QUEST,
    val charmCount: Int = 10,
    val showAllRanks: Boolean = true,
    val output: String = ""
)

class AimPointViewModel : ViewModel() {
    private val _state = MutableStateFlow(AimPointUiState())
    val state: StateFlow<AimPointUiState> = _state.asStateFlow()

    fun setFrame(f: Long) = _state.update { it.copy(frame = f) }
    fun setMode(m: AimPointMode) = _state.update { it.copy(mode = m) }
    fun setCharmCount(c: Int) = _state.update { it.copy(charmCount = c.coerceIn(2, 40)) }
    fun setShowAllRanks(v: Boolean) = _state.update { it.copy(showAllRanks = v) }

    fun calc(kind: Int) {
        val s = _state.value
        val engine = MHXXEngine(kind)
        engine.jump(s.frame)
        val lines = mutableListOf("フレーム: ${s.frame}", "種類: ${KIND_NAMES[kind]}", "")

        when (s.mode) {
            AimPointMode.QUEST -> {
                val row = engine.aimpointQuest(s.charmCount)
                lines += listOf("クエスト (チャーム数=${s.charmCount})", "  有効数: ${row.count}", "  ${row.pattern}")
            }
            AimPointMode.HALCYON -> {
                lines += "マカフシギ"
                engine.aimpointHalcyon(s.showAllRanks).forEach { r ->
                    lines += listOf("  [${r.label}] 有効数=${r.count}", "    ${r.pattern}")
                }
            }
            AimPointMode.JUJU -> {
                lines += "天運 (ジュジュ)"
                engine.aimpointJuju(s.showAllRanks).forEach { r ->
                    lines += listOf("  [${r.label}] 有効数=${r.count}", "    ${r.pattern}")
                }
            }
        }
        _state.update { it.copy(output = lines.joinToString("\n")) }
    }
}
