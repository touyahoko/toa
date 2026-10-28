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
    val scanning: Boolean = false
)

class GalleryViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(GalleryUiState())
    val state: StateFlow<GalleryUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        _state.update {
            it.copy(items = GalleryStore.list(getApplication()))
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

    fun onQrScanned(raw: String) {
        viewModelScope.launch {
            val parsed = SwitchTransfer.parseQr(raw)
            when {
                parsed.wifi != null -> {
                    val w = parsed.wifi
                    _state.update {
                        it.copy(
                            isBusy = true,
                            status = "Switch AP に接続中…\nSSID: ${w.ssid}",
                            lastWifiHint = "SSID: ${w.ssid}\nパスワード: ${w.password}"
                        )
                    }
                    val ok = SwitchTransfer.connectWifi(getApplication(), w)
                    if (!ok) {
                        _state.update {
                            it.copy(
                                isBusy = false,
                                status = "自動接続に失敗しました。Wi-Fi設定で次を手動接続してから「Switchから取得」を押してください。\n" +
                                    "SSID: ${w.ssid}\nPASS: ${w.password}"
                            )
                        }
                        return@launch
                    }
                    _state.update { it.copy(status = "接続OK。メディア取得中…") }
                    val saved = SwitchTransfer.downloadAllToGallery(getApplication())
                    refresh()
                    _state.update {
                        it.copy(
                            isBusy = false,
                            status = if (saved.isNotEmpty())
                                "${saved.size} 件をギャラリーに保存しました"
                            else
                                "接続はできましたがメディアが見つかりません。2つ目のQRを読み取るか「Switchから取得」を再試行してください。"
                        )
                    }
                }
                parsed.url != null -> {
                    _state.update { it.copy(isBusy = true, status = "URLから取得中…\n${parsed.url}") }
                    val saved = SwitchTransfer.downloadAllToGallery(getApplication(), parsed.url)
                    refresh()
                    _state.update {
                        it.copy(
                            isBusy = false,
                            status = if (saved.isNotEmpty())
                                "${saved.size} 件をギャラリーに保存しました"
                            else
                                "取得失敗。Switchに接続済みか確認してください。"
                        )
                    }
                }
                else -> {
                    _state.update {
                        it.copy(status = "QRを解析できませんでした:\n${parsed.raw.take(120)}")
                    }
                }
            }
        }
    }

    fun fetchFromSwitch() {
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, status = "http://192.168.0.1/ から取得中…") }
            val saved = SwitchTransfer.downloadAllToGallery(getApplication())
            refresh()
            _state.update {
                it.copy(
                    isBusy = false,
                    status = if (saved.isNotEmpty())
                        "${saved.size} 件を保存しました"
                    else
                        "取得できませんでした。SwitchのQR接続後に再試行してください。"
                )
            }
        }
    }

    fun setScanning(v: Boolean) = _state.update { it.copy(scanning = v) }
}
