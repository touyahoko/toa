package org.mhxxtools.mhxxrngtool.ui.gan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mhxxtools.mhxxrngtool.gan.ArmorSet
import org.mhxxtools.mhxxrngtool.gan.GanData
import org.mhxxtools.mhxxrngtool.gan.GanSearcher
import org.mhxxtools.mhxxrngtool.gan.SearchRequest
import org.mhxxtools.mhxxrngtool.gan.SkillDef

data class GanUiState(
    val loaded: Boolean = false,
    val loadError: String? = null,
    val skillQuery: String = "",
    val filteredSkills: List<SkillDef> = emptyList(),
    val selected: List<String> = emptyList(),
    val typeFilter: Int = 0,
    val searching: Boolean = false,
    val status: String = "",
    val results: List<ArmorSet> = emptyList()
)

class GanViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(GanUiState())
    val state: StateFlow<GanUiState> = _state.asStateFlow()
    private var searchJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                GanData.ensureLoaded(getApplication())
                val positive = GanData.skills.filter { it.points > 0 }.sortedBy { it.name }
                _state.update {
                    it.copy(loaded = true, filteredSkills = positive)
                }
            } catch (e: Exception) {
                _state.update { it.copy(loadError = e.message ?: "load failed") }
            }
        }
    }

    fun setQuery(q: String) {
        val all = GanData.skills.filter { it.points > 0 }
        val filtered = if (q.isBlank()) {
            all.sortedBy { it.name }
        } else {
            all.filter { it.name.contains(q) || it.tree.contains(q) }.sortedBy { it.name }
        }
        _state.update { it.copy(skillQuery = q, filteredSkills = filtered) }
    }

    fun toggleSkill(name: String) {
        _state.update { s ->
            val sel = s.selected.toMutableList()
            if (name in sel) sel.remove(name) else if (sel.size < 8) sel.add(name)
            s.copy(selected = sel)
        }
    }

    fun clearSelected() {
        _state.update { it.copy(selected = emptyList(), results = emptyList(), status = "") }
    }

    fun setType(t: Int) {
        _state.update { it.copy(typeFilter = t) }
    }

    fun search() {
        val sel = _state.value.selected
        if (sel.isEmpty()) {
            _state.update { it.copy(status = "select skills") }
            return
        }
        searchJob?.cancel()
        _state.update { it.copy(searching = true, status = "searching...", results = emptyList()) }
        val type = _state.value.typeFilter
        searchJob = viewModelScope.launch {
            val results = withContext(Dispatchers.Default) {
                GanSearcher.search(SearchRequest(sel, typeFilter = type, maxResults = 40)) { n, msg ->
                    _state.update { it.copy(status = "$msg ($n)") }
                }
            }
            _state.update {
                it.copy(
                    searching = false,
                    results = results,
                    status = if (results.isEmpty()) "no results" else "${results.size} hits"
                )
            }
        }
    }

    fun stop() {
        searchJob?.cancel()
        _state.update { it.copy(searching = false, status = "stopped") }
    }
}
