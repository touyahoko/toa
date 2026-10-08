package org.mhxxtools.mhxxrngtool.voice

import org.mhxxtools.mhxxrngtool.AppStateViewModel
import org.mhxxtools.mhxxrngtool.ui.search.SearchViewModel
import org.mhxxtools.mhxxrngtool.ui.timer.TimerViewModel

/**
 * アプリ全体の音声コマンド一覧を組み立てる。
 *
 * 「種類」選択チップの右端にあるマイクボタンから呼び出され、タブ移動・お守り種類の
 * 切り替え・検索／タイマーの開始・停止・リセットなど、アプリの主要機能を音声で
 * 実行できるようにする。
 *
 * コマンドを増やしたいときはこの関数内に register(...) を追加するだけでよい。
 * 1つのコマンドに複数のフレーズを渡せば、言い回しの違い
 * （「検索して」「検索開始」「サーチ開始」等）にも対応できる。
 */
fun buildAppVoiceCommands(
    appState: AppStateViewModel,
    searchVm: SearchViewModel,
    timerVm: TimerViewModel,
    onSelectTab: (Int) -> Unit
): VoiceCommandRegistry = VoiceCommandRegistry().apply {

    // ── タブ移動（MainScreen の TABS 配列と対応） ──────────────────────
    register("検索タブへ移動", "検索タブ", "検索", "サーチ") { onSelectTab(0) }
    register("周辺タブへ移動", "周辺タブ", "周辺") { onSelectTab(1) }
    register("調合タブへ移動", "調合タブ", "調合") { onSelectTab(2) }
    register("位置タブへ移動", "位置タブ", "位置") { onSelectTab(3) }
    register("狙い目タブへ移動", "狙い目タブ", "狙い目", "狙いめ") { onSelectTab(4) }
    register("鑑定タブへ移動", "鑑定タブ", "鑑定読取", "鑑定読み取り", "鑑定") { onSelectTab(5) }
    register("タイマータブへ移動", "タイマータブ", "タイマー") { onSelectTab(6) }
    register("Arduinoタブへ移動", "アルドゥイーノ", "アルデュイーノ", "アルディーノ", "arduino") { onSelectTab(7) }

    // ── お守り種類の切り替え（AppState.kind） ─────────────────────────
    register("種類を風化したお守りに変更", "風化したお守り", "風化のお守り", "風化") { appState.setKind(0) }
    register("種類を古びたお守りに変更", "古びたお守り", "古びのお守り", "古び") { appState.setKind(1) }
    register("種類を光るお守りに変更", "光るお守り", "光る") { appState.setKind(2) }
    register("種類をなぞのお守りに変更", "なぞのお守り", "謎のお守り", "謎", "なぞ") { appState.setKind(3) }

    // ── 検索タブの操作 ─────────────────────────────────────────────
    register("検索を開始", "検索開始", "検索して", "検索実行", "サーチ開始") {
        onSelectTab(0)
        searchVm.startSearch()
    }
    register("検索を停止", "検索停止", "検索ストップ", "サーチ停止") {
        searchVm.stopSearch()
    }
    register("検索結果をリセット", "検索リセット", "検索クリア") {
        searchVm.resetSearch()
    }

    // ── タイマータブの操作 ─────────────────────────────────────────
    register("タイマーを開始", "タイマー開始", "タイマースタート", "カウントダウン開始") {
        onSelectTab(6)
        timerVm.startCountdown()
    }
    register("タイマーを停止", "タイマー停止", "タイマーストップ") {
        timerVm.stopCountdown()
    }
    register("タイマーをリセット", "タイマーリセット") {
        timerVm.resetCountdown()
    }
}
