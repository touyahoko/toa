package org.mhxxtools.mhxxrngtool

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/**
 * アプリ全体で共有する状態
 * - kind : お守り種類 (0=風化, 1=古び, 2=光る, 3=なぞの)
 * 経過時間換算は notebook 準拠で常に 30fps 固定（FPS切替は削除）
 */
class AppStateViewModel : ViewModel() {
    var kind by mutableIntStateOf(0)
        private set

    @JvmName("updateKind")
    fun setKind(k: Int) { if (kind != k) kind = k }
}
