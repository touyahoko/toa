package org.mhxxtools.mhxxrngtool.ui.gallery

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.mhxxtools.mhxxrngtool.switchtransfer.GalleryItem
import org.mhxxtools.mhxxrngtool.switchtransfer.GalleryStore
import org.mhxxtools.mhxxrngtool.switchtransfer.SwitchTransfer

data class GalleryUiState(
    val items: List<GalleryItem> = emptyList(),
    val status: String = "",
    val isBusy: Boolean = false,
    val lastWifiHint: String = "",
    val scanning: Boolean = false,
    /** ライブカメラ表示中 */
    val liveScan: Boolean = false,
    /** フェーズ: idle / wait_wifi / connecting / wait_url / downloading / done */
    val phase: String = "idle",
    val wifiConnected: Boolean = false
)

class GalleryViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(GalleryUiState())
    val state: StateFlow<GalleryUiState> = _state.asStateFlow()

    private var lastHandled: String = ""

    init { refresh() }

    fun refresh() {
        _state.update { it.copy(items = GalleryStore.list(getApplication())) }
    }

    fun openLiveScan() {
        lastHandled = ""
        _state.update {
            it.copy(
                liveScan = true,
                scanning = true,
                phase = "wait_wifi",
                status = "① Switchの左側（Wi-Fi）QRを枠に合わせてください",
                wifiConnected = false
            )
        }
    }

    fun closeLiveScan() {
        _state.update {
            it.copy(liveScan = false, scanning = false, phase = if (it.items.isNotEmpty()) "done" else "idle")
        }
    }

    fun importUri(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, status = "インポート中…") }
            val item = GalleryStore.saveFromUri(getApplication(), uri)
            refresh()
            _state.update {
                it.copy(
                    isBusy = false,
                    status = if (item != null) "保存しました: ${item.name}" else "インポート失敗"
                )
            }
        }
    }

    fun delete(id: String) {
        GalleryStore.delete(getApplication(), id)
        refresh()
    }

    fun clearAll() {
        GalleryStore.clearAll(getApplication())
        refresh()
        _state.update { it.copy(status = "ギャラリーを空にしました") }
    }

    fun setScanning(v: Boolean) = _state.update { it.copy(scanning = v) }

    /** ライブ／静止画どちらからでも同一処理 */
    fun onQrScanned(raw: String) {
        if (raw.isBlank()) {
            _state.update { it.copy(status = "QR未検出。角度や明るさを変えてください。") }
            return
        }
        viewModelScope.launch {
            val cleaned = raw.trim()
                .replace("\r", "")
                .replace("\n", "")
            if (cleaned == lastHandled) return@launch
            val parsed = SwitchTransfer.parseQr(cleaned)
            when {
                parsed.wifi != null -> {
                    lastHandled = cleaned
                    handleWifi(parsed.wifi)
                }
                parsed.url != null -> {
                    lastHandled = cleaned
                    handleUrl(parsed.url)
                }
                else -> {
                    val again = SwitchTransfer.parseQr(cleaned.replace(" ", ""))
                    when {
                        again.wifi != null -> {
                            lastHandled = cleaned
                            handleWifi(again.wifi)
                        }
                        again.url != null -> {
                            lastHandled = cleaned
                            handleUrl(again.url)
                        }
                        else -> {
                            lastHandled = ""
                            _state.update {
                                it.copy(
                                    status = "QR形式が不明です。\n" + cleaned.take(100),
                                    phase = "wait_wifi"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun handleWifi(w: SwitchTransfer.WifiInfo) {
        _state.update {
            it.copy(
                isBusy = true,
                phase = "connecting",
                status = "Wi-Fi接続中… SSID: ${w.ssid}\n（端末の接続確認が出たら許可）",
                lastWifiHint = "SSID: ${w.ssid}\nパスワード: ${w.password}"
            )
        }
        val ok = SwitchTransfer.connectWifi(getApplication(), w)
        if (!ok) {
            _state.update {
                it.copy(
                    isBusy = false,
                    wifiConnected = false,
                    phase = "wait_wifi",
                    status = "自動接続できませんでした。端末のWi-Fiで次を接続後、②のQRをかざすか「再取得」を押してください。\nSSID: ${w.ssid}\nPASS: ${w.password}"
                )
            }
            lastHandled = "" // 再試行可
            return
        }
        _state.update {
            it.copy(
                isBusy = false,
                wifiConnected = true,
                phase = "wait_url",
                status = "接続OK。② 右側のQR（またはページ用）を枠に合わせてください。\n自動で画像を保存します。"
            )
        }
        // 接続後、URL QRなしでもサーバーを試す
        tryDownload("http://192.168.0.1/")
    }

    private suspend fun handleUrl(url: String) {
        _state.update {
            it.copy(isBusy = true, phase = "downloading", status = "ダウンロード中…\n$url")
        }
        tryDownload(url)
    }

    private suspend fun tryDownload(base: String) {
        val saved = SwitchTransfer.downloadAllToGallery(getApplication(), base)
        refresh()
        if (saved.isNotEmpty()) {
            _state.update {
                it.copy(
                    isBusy = false,
                    liveScan = false,
                    scanning = false,
                    phase = "done",
                    status = "完了: ${saved.size} 件を自動保存しました"
                )
            }
        } else {
            _state.update {
                it.copy(
                    isBusy = false,
                    phase = if (it.wifiConnected) "wait_url" else "wait_wifi",
                    status = "メディア未取得。②のQRをかざすか、接続後に「再取得」を押してください。"
                )
            }
            lastHandled = ""
        }
    }

    fun fetchFromSwitch() {
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, status = "http://192.168.0.1/ から取得中…") }
            tryDownload("http://192.168.0.1/")
        }
    }
}
