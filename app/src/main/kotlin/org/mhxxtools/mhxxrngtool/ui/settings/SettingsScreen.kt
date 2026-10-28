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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import org.mhxxtools.mhxxrngtool.switchtransfer.GalleryItem
import org.mhxxtools.mhxxrngtool.switchtransfer.GalleryKind
import org.mhxxtools.mhxxrngtool.ui.gallery.GalleryViewModel
import org.mhxxtools.mhxxrngtool.ui.theme.HtmlColors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@Composable
fun SettingsScreen(
    galleryVm: GalleryViewModel,
    onClose: () -> Unit = {},
    onUseImage: (Uri) -> Unit = {}
) {
    val s by galleryVm.state.collectAsState()
    val context = LocalContext.current
    var pendingQr by remember { mutableStateOf<Uri?>(null) }

    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bmp ->
        if (bmp != null) {
            // save temp and decode
            val f = java.io.File(context.cacheDir, "qr_capture.jpg")
            java.io.FileOutputStream(f).use { out ->
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
            }
            pendingQr = Uri.fromFile(f)
        }
    }

    val pickQrImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> pendingQr = uri }

    val cameraPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok -> if (ok) takePicture.launch(null) }

    val importPick = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { galleryVm.importUri(it) } }

    LaunchedEffect(pendingQr) {
        val uri = pendingQr ?: return@LaunchedEffect
        pendingQr = null
        galleryVm.setScanning(true)
        val raw = decodeQr(context, uri)
        if (raw != null) {
            galleryVm.onQrScanned(raw)
        } else {
            galleryVm.setScanning(false)
            // status via busy flag - set message
            galleryVm.onQrScanned("") // will show parse fail - better set status
        }
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
                Text("Switch から自動取り込み", fontWeight = FontWeight.SemiBold, color = HtmlColors.Text)
                Text(
                    "Switch アルバム → 共有 → スマートフォンへ送る のQRをカメラで読み取ります。\n" +
                        "①Wi-Fi用QR → 接続　②URL用QR → 画像・動画をアプリ内に自動保存",
                    fontSize = 12.sp,
                    color = HtmlColors.Muted
                )
                Button(
                    onClick = { cameraPerm.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = HtmlColors.Accent),
                    enabled = !s.isBusy
                ) {
                    Text("カメラでQRを読む", color = Color.White)
                }
                OutlinedButton(
                    onClick = { pickQrImage.launch("image/*") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !s.isBusy
                ) {
                    Text("QR画像を選ぶ")
                }
                OutlinedButton(
                    onClick = { galleryVm.fetchFromSwitch() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !s.isBusy
                ) {
                    Text("接続済みなら Switchから再取得")
                }
                if (s.isBusy || s.scanning) {
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
                    Text("まだありません。上のカメラでQRを読み取ってください。", fontSize = 12.sp, color = HtmlColors.Muted)
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
                                            if (item.kind == GalleryKind.IMAGE) {
                                                onUseImage(Uri.fromFile(item.file))
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
                Text("写真は端末内のみで処理します", fontSize = 12.sp, color = HtmlColors.Muted)
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
                    Text("動画", color = HtmlColors.Accent, fontWeight = FontWeight.Bold)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onUse) { Text("鑑定へ", fontSize = 11.sp, color = HtmlColors.Accent) }
                TextButton(onClick = onDelete) { Text("削除", fontSize = 11.sp, color = HtmlColors.Error) }
            }
        }
    }
}

private suspend fun decodeQr(context: android.content.Context, uri: Uri): String? =
    suspendCoroutine { cont ->
        try {
            val image = InputImage.fromFilePath(context, uri)
            BarcodeScanning.getClient().process(image)
                .addOnSuccessListener { list ->
                    cont.resume(list.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue)
                }
                .addOnFailureListener { cont.resume(null) }
        } catch (_: Exception) {
            cont.resume(null)
        }
    }
