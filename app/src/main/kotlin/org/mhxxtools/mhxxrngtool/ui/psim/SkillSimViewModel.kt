package org.mhxxtools.mhxxrngtool.ui.psim

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SkillSimViewModel(app: Application) : AndroidViewModel(app) {

    var job by mutableStateOf("s")       // s 剣士 / g ガンナー
        private set
    var sex by mutableStateOf("m")       // m / f
        private set
    var weaponSlots by mutableIntStateOf(3)
        private set

    val targets = mutableStateListOf<PsimTarget>()
    var options by mutableStateOf<List<SkillOption>>(emptyList())
        private set
    var selectedOptionIndex by mutableIntStateOf(0)
        private set

    var results by mutableStateOf<List<PsimResult>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set
    var status by mutableStateOf("スキルを選んでください")
        private set
    var ready by mutableStateOf(false)
        private set

    private var searchJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                PsimData.ensureLoaded(getApplication())
                val opts = PsimData.skillOptions()
                withContext(Dispatchers.Main) {
                    options = opts
                    ready = true
                    status = "スキルを選んで追加してください"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    status = "データ読込失敗: ${e.message}"
                }
            }
        }
    }

    fun updateJob(v: String) {
        if (job == v) return
        job = v
        targets.clear()
        results = emptyList()
        status = "スキルを選んで追加してください"
    }

    fun updateSex(v: String) {
        if (sex == v) return
        sex = v
        rerun()
    }

    fun updateWeaponSlots(v: Int) {
        if (weaponSlots == v) return
        weaponSlots = v
        rerun()
    }

    fun setSelectedOption(index: Int) {
        selectedOptionIndex = index.coerceIn(0, (options.size - 1).coerceAtLeast(0))
    }

    fun addSelectedSkill() {
        if (options.isEmpty()) return
        val o = options[selectedOptionIndex]
        val idx = targets.indexOfFirst { it.system == o.system }
        if (idx >= 0) {
            targets[idx] = PsimTarget(o.system, o.points, o.displayName)
        } else {
            if (targets.size >= 6) {
                status = "スキルは最大6つまでです"
                return
            }
            targets += PsimTarget(o.system, o.points, o.displayName)
        }
        rerun()
    }

    fun removeTarget(system: String) {
        targets.removeAll { it.system == system }
        rerun()
    }

    fun clearTargets() {
        targets.clear()
        results = emptyList()
        status = "スキルを選んで追加してください"
    }

    private fun rerun() {
        searchJob?.cancel()
        if (targets.isEmpty()) {
            results = emptyList()
            status = "スキルを選んで追加してください"
            searching = false
            return
        }
        searching = true
        status = "計算中…"
        val map = targets.associate { it.system to it.points }
        val j = job
        val s = sex
        val w = weaponSlots
        searchJob = viewModelScope.launch(Dispatchers.Default) {
            val res = try {
                PsimSolver.solve(map, s, j, w)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    searching = false
                    status = "エラー: ${e.message}"
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                results = res
                searching = false
                status = if (res.isEmpty()) "条件を満たす構成が見つかりませんでした"
                else "検索結果 ${res.size} 件"
            }
        }
    }
}
