package org.mhxxtools.mhxxrngtool.ui.gallery

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import org.mhxxtools.mhxxrngtool.ui.theme.HtmlColors
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@Composable
fun GalleryScreen(
    vm: GalleryViewModel,
    onUseImage: (Uri) -> Unit = {},
    onUseVideo: (Uri) -> Unit = {}
) {
    val s by vm.state.collectAsState()
    val context = LocalContext.current
    var pendingQrUri by remember { mutableStateOf<Uri?>(null) }

    val galleryPick = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { vm.importUri(it) } }

    val qrImagePick = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> pendingQrUri = uri }

    val cameraPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) qrImagePick.launch("image/*")
    }

    LaunchedEffect(pendingQrUri) {
        val uri = pendingQrUri ?: return@LaunchedEffect
        pendingQrUri = null
        val raw = decodeQr(context, uri)
        if (raw != null) vm.onQrScanned(raw)
        else vm.setScanning(false)
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("ギャラリー", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = HtmlColors.Text)
        Text(
            "Switch「スマホへ送る」QRからスクショ／録画を取り込み、鑑定・調合で使えます。",
            fontSize = 12.sp,
            color = HtmlColors.Muted
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { cameraPerm.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = HtmlColors.Accent)
            ) { Text("QR読取", color = Color.White, fontSize = 13.sp) }
            OutlinedButton(
                onClick = { qrImagePick.launch("image/*") },
                modifier = Modifier.weight(1f)
            ) { Text("QR画像", fontSize = 13.sp) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { vm.fetchFromSwitch() },
                modifier = Modifier.weight(1f),
                enabled = !s.isBusy
            ) { Text("Switchから取得", fontSize = 12.sp) }
            OutlinedButton(
                onClick = { galleryPick.launch("*/*") },
                modifier = Modifier.weight(1f)
            ) { Text("端末から追加", fontSize = 12.sp) }
        }

        if (s.isBusy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.height(20.dp), color = HtmlColors.Accent, strokeWidth = 2.dp)
                Spacer(Modifier.padding(6.dp))
                Text(s.status, fontSize = 12.sp, color = HtmlColors.Muted)
            }
        } else if (s.status.isNotEmpty()) {
            Text(s.status, fontSize = 12.sp, color = HtmlColors.Success)
        }
        if (s.lastWifiHint.isNotEmpty()) {
            Text(s.lastWifiHint, fontSize = 11.sp, color = HtmlColors.Muted)
        }

        Text("保存 ${s.items.size} 件", fontWeight = FontWeight.Medium, color = HtmlColors.Text)

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(s.items, key = { it.id }) { item ->
                GalleryTile(
                    item = item,
                    onUse = {
                        val uri = Uri.fromFile(item.file)
                        if (item.kind == GalleryKind.VIDEO) onUseVideo(uri) else onUseImage(uri)
                    },
                    onDelete = { vm.delete(item.id) }
                )
            }
        }
    }
}

@Composable
private fun GalleryTile(
    item: GalleryItem,
    onUse: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = HtmlColors.Surface),
        border = BorderStroke(1.dp, HtmlColors.Border)
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(HtmlColors.Surface2)
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
                            contentDescription = item.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else Text("画像", color = HtmlColors.Muted)
                } else {
                    Text("動画", color = HtmlColors.Accent, fontWeight = FontWeight.Bold)
                }
            }
            Text(
                item.name,
                fontSize = 10.sp,
                color = HtmlColors.Muted,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                maxLines = 1
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onUse) { Text("使う", fontSize = 11.sp, color = HtmlColors.Accent) }
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
                    val v = list.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                    cont.resume(v)
                }
                .addOnFailureListener { cont.resume(null) }
        } catch (e: Exception) {
            cont.resume(null)
        }
    }
