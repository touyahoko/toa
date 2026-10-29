package org.mhxxtools.mhxxrngtool.ui.settings

import android.Manifest
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mhxxtools.mhxxrngtool.switchtransfer.GalleryItem
import org.mhxxtools.mhxxrngtool.switchtransfer.GalleryKind
import org.mhxxtools.mhxxrngtool.ui.gallery.GalleryViewModel
import org.mhxxtools.mhxxrngtool.ui.theme.HtmlColors

@Composable
fun SettingsScreen(
    galleryVm: GalleryViewModel,
    onClose: () -> Unit = {},
    onUseImage: (Uri) -> Unit = {},
    onUseVideo: (Uri) -> Unit = {}
) {
    val s by galleryVm.state.collectAsState()
    val context = LocalContext.current

    val cameraPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        if (ok) galleryVm.openLiveScan()
        else {
            /* 権限拒否 */
        }
    }

    val importPick = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { galleryVm.importUri(it) } }

    // ライブQRスキャン（全画面）
    if (s.liveScan) {
        val hint = when (s.phase) {
            "connecting" -> "Wi-Fi接続中… 確認ダイアログがあれば許可してください"
            "wait_url", "downloading" -> "② 右側のQR（URL）を枠に合わせてください"
            else -> "① 左側のQR（Wi-Fi）を枠に合わせてください。続けて②も自動で読みます"
        }
        LiveQrScanner(
            hint = hint,
            onQr = { galleryVm.onQrScanned(it) },
            onClose = { galleryVm.closeLiveScan() }
        )
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("設定", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = HtmlColors.Text)
            TextButton(onClick = onClose) { Text("閉じる", color = HtmlColors.Muted) }
        }

        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = HtmlColors.Surface),
            border = BorderStroke(1.dp, HtmlColors.Border)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Switch から取り込み", fontWeight = FontWeight.SemiBold, color = HtmlColors.Text)
                Text(
                    "① Wi-Fi用QRをかざす → 接続許可（または手動でSwitchのSSIDに接続）→ 自動保存。" +
                        "① Wi-Fi用QR → 接続　② URL用QR → 画像・動画を自動保存",
                    fontSize = 12.sp,
                    color = HtmlColors.Muted
                )
                Button(
                    onClick = { cameraPerm.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = HtmlColors.Accent),
                    enabled = !s.isBusy
                ) {
                    Text("カメラでQRをかざす（自動）", color = Color.White)
                }
                OutlinedButton(
                    onClick = {
                        try {
                            val i = android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                            context.startActivity(i)
                        } catch (_: Exception) {}
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !s.isBusy
                ) {
                    Text("Wi-Fi設定を開く（手動接続）")
                }
                OutlinedButton(
                    onClick = { galleryVm.fetchFromSwitch() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !s.isBusy
                ) {
                    Text("接続済みなら再取得")
                }
                if (s.isBusy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            Modifier.padding(end = 8.dp),
                            color = HtmlColors.Accent,
                            strokeWidth = 2.dp
                        )
                        Text(s.status.ifEmpty { "処理中…" }, fontSize = 12.sp, color = HtmlColors.Muted)
                    }
                } else if (s.status.isNotEmpty()) {
                    Text(s.status, fontSize = 12.sp, color = HtmlColors.Success)
                }
                if (s.lastWifiHint.isNotEmpty()) {
                    Text(s.lastWifiHint, fontSize = 11.sp, color = HtmlColors.Muted)
                }
            }
        }

        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = HtmlColors.Surface),
            border = BorderStroke(1.dp, HtmlColors.Border)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "保存済み ${s.items.size} 件",
                        fontWeight = FontWeight.SemiBold,
                        color = HtmlColors.Text
                    )
                    TextButton(onClick = { galleryVm.clearAll() }) {
                        Text("すべて削除", color = HtmlColors.Error, fontSize = 12.sp)
                    }
                }
                OutlinedButton(
                    onClick = { importPick.launch("*/*") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("端末ギャラリーから追加")
                }
                if (s.items.isEmpty()) {
                    Text("まだありません。上のカメラでQRをかざしてください。", fontSize = 12.sp, color = HtmlColors.Muted)
                } else {
                    s.items.chunked(2).forEach { row ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            row.forEach { item ->
                                Box(Modifier.weight(1f)) {
                                    SavedTile(
                                        item = item,
                                        onUse = {
                                            val uri = Uri.fromFile(item.file)
                                            if (item.kind == GalleryKind.IMAGE) {
                                                onUseImage(uri)
                                            } else {
                                                onUseVideo(uri)
                                            }
                                        },
                                        onDelete = { galleryVm.delete(item.id) }
                                    )
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }

        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = HtmlColors.Surface),
            border = BorderStroke(1.dp, HtmlColors.Border)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("アプリ情報", fontWeight = FontWeight.SemiBold, color = HtmlColors.Text)
                Text("MHXX RNG Tool", fontSize = 13.sp, color = HtmlColors.Text)
                Text("鑑定: 錬金リーダー方式（公式134）", fontSize = 12.sp, color = HtmlColors.Muted)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SavedTile(item: GalleryItem, onUse: () -> Unit, onDelete: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = HtmlColors.Surface2),
        border = BorderStroke(1.dp, HtmlColors.Border)
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(HtmlColors.Bg)
                    .clickable(onClick = onUse),
                contentAlignment = Alignment.Center
            ) {
                if (item.kind == GalleryKind.IMAGE) {
                    val bmp = remember(item.id) {
                        runCatching { BitmapFactory.decodeFile(item.file.absolutePath) }.getOrNull()
                    }
                    if (bmp != null) {
                        Image(
                            bmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                } else {
                    val thumb = remember(item.id) {
                        runCatching {
                            val r = android.media.MediaMetadataRetriever()
                            r.setDataSource(item.file.absolutePath)
                            val b = r.getFrameAtTime(0)
                            r.release()
                            b
                        }.getOrNull()
                    }
                    if (thumb != null) {
                        Image(
                            thumb.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        Text(
                            "▶ 動画",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(6.dp)
                                .background(Color(0x99000000), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    } else {
                        Text("▶ 動画", color = HtmlColors.Accent, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Text(
                formatSize(item.sizeBytes),
                fontSize = 10.sp,
                color = HtmlColors.Muted,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onUse) {
                    Text(
                        if (item.kind == GalleryKind.VIDEO) "調合へ" else "鑑定へ",
                        fontSize = 11.sp,
                        color = HtmlColors.Accent
                    )
                }
                TextButton(onClick = onDelete) { Text("削除", fontSize = 11.sp, color = HtmlColors.Error) }
            }
        }
    }
}

private fun formatSize(n: Long): String {
    if (n < 1024) return "$n B"
    if (n < 1024 * 1024) return "%.1f KB".format(n / 1024.0)
    return "%.1f MB".format(n / (1024.0 * 1024.0))
}
