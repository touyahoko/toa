package org.mhxxtools.mhxxrngtool

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/**
 * アプリ全体で共有する状態
 * - kind : お守り種類 (0=風化, 1=古び, 2=光る, 3=なぞの)
 * - fps  : ゲーム環境フレームレート (30 / 60) — HTMLツールの fpsSelect 相当
 */
class AppStateViewModel : ViewModel() {
    var kind by mutableIntStateOf(0)
        private set

    /** 30 or 60（デフォルト 30） */
    var fps by mutableIntStateOf(30)
        private set

    @JvmName("updateKind")
    fun setKind(k: Int) { if (kind != k) kind = k }

    @JvmName("updateFps")
    fun setFps(v: Int) {
        val next = if (v >= 60) 60 else 30
        if (fps != next) fps = next
    }
}
