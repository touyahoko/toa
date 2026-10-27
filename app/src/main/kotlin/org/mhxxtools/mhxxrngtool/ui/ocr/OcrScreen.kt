package org.mhxxtools.mhxxrngtool.ui.ocr

import android.Manifest
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.mhxxtools.mhxxrngtool.rng.continueMashInfo
import org.mhxxtools.mhxxrngtool.ui.theme.HtmlColors
import java.io.File

/**
 * mhxx-renkin-reader サイトの鑑定UIに揃えた画面。
 * https://skst328.github.io/mhxx-renkin-reader/
 */
@Composable
fun OcrScreen(
    vm: OcrViewModel,
    kind: Int,
    onApplyToSearch: (ApplyData) -> Unit,
    onFrameTap: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val s = vm.state.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        if (ok) cameraUri?.let { vm.recognizeFromUri(context, it, kind) }
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { vm.recognizeFromUri(context, it, kind) }
    }
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val file = File(context.cacheDir, "mhxx_scan_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            cameraUri = uri
            cameraLauncher.launch(uri)
        }
    }

    val dashColor = HtmlColors.Border
    val cardShape = RoundedCornerShape(12.dp)

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(HtmlColors.Bg)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── タイトル（サイト風） ──
        item {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "MHXX 鑑定スキャン",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = HtmlColors.Text
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "鑑定画面の写真から錬金時のフレームを特定",
                    fontSize = 13.sp,
                    color = HtmlColors.Muted
                )
                Text(
                    "端末内処理・通信なし",
                    fontSize = 12.sp,
                    color = HtmlColors.Muted
                )
            }
        }

        // ── プライバシーバナー（サイトと同じ） ──
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(HtmlColors.InfoBg)
                    .border(1.dp, HtmlColors.InfoBorder, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Shield,
                        contentDescription = null,
                        tint = HtmlColors.Accent,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "写真は外部に送信されません",
                        fontSize = 13.sp,
                        color = HtmlColors.Text,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // ── メインカード（サイトの photo + form 相当） ──
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = cardShape,
                colors = CardDefaults.cardColors(containerColor = HtmlColors.Surface),
                border = BorderStroke(1.dp, HtmlColors.Border)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 点線の写真エリア
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (s.selectedImageUri != null) 200.dp else 160.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(HtmlColors.Surface2.copy(alpha = 0.35f))
                            .drawBehind {
                                val stroke = Stroke(
                                    width = 2.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f)
                                )
                                drawRoundRect(
                                    color = dashColor,
                                    style = stroke,
                                    cornerRadius = CornerRadius(10.dp.toPx())
                                )
                            }
                            .clickable(enabled = !s.isProcessing && !s.isSearching) {
                                galleryLauncher.launch("image/*")
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        val previewBmp = remember(s.selectedImageUri) {
                            s.selectedImageUri?.let { uri ->
                                runCatching {
                                    context.contentResolver.openInputStream(uri)?.use {
                                        BitmapFactory.decodeStream(it)
                                    }
                                }.getOrNull()
                            }
                        }
                        if (previewBmp != null) {
                            Image(
                                bitmap = previewBmp.asImageBitmap(),
                                contentDescription = "選択した鑑定画像",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(8.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(HtmlColors.Surface3),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Outlined.Upload,
                                        contentDescription = null,
                                        tint = HtmlColors.Muted,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    "タップして写真を選択",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = HtmlColors.Text
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "鑑定画面のスクショを選ぶと自動で読み取ります",
                                    fontSize = 12.sp,
                                    color = HtmlColors.Muted,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                        }
                    }

                    // カメラ / ギャラリー
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { cameraPermission.launch(Manifest.permission.CAMERA) },
                            enabled = !s.isProcessing && !s.isSearching,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = HtmlColors.Accent,
                                contentColor = Color.White
                            )
                        ) {
                            Icon(Icons.Outlined.PhotoCamera, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("写真を撮る")
                        }
                        OutlinedButton(
                            onClick = { galleryLauncher.launch("image/*") },
                            enabled = !s.isProcessing && !s.isSearching,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = HtmlColors.Accent),
                            border = BorderStroke(1.dp, HtmlColors.Border)
                        ) {
                            Icon(Icons.Outlined.Image, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("ギャラリー")
                        }
                    }

                    if (s.isProcessing) {
                        Column {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = HtmlColors.Accent,
                                trackColor = HtmlColors.Surface2
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(s.ocrStatus, fontSize = 12.sp, color = HtmlColors.Muted)
                        }
                    }

                    if (!s.isProcessing && s.ocrStatus.isNotEmpty()) {
                        Text(
                            s.ocrStatus,
                            fontSize = 12.sp,
                            color = if (s.hasError) HtmlColors.Error else HtmlColors.Success
                        )
                    }

                    // 読み取り結果（サイトの護石フォーム風）
                    AnimatedVisibility(!s.hasError && s.detectedSkill1.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            HorizontalDivider(color = HtmlColors.Border)
                            Text("護石", fontWeight = FontWeight.SemiBold, color = HtmlColors.Text)
                            ResultField("第1スキル", s.detectedSkill1)
                            ResultField(
                                "第2スキル",
                                if (s.detectedSkill2.isNotEmpty() && s.detectedSkill2 != "（なし）")
                                    s.detectedSkill2 else "なし"
                            )
                            ResultField("スロット", s.detectedSlot.ifEmpty { "不明" })
                        }
                    }
                }
            }
        }

        // ── フレーム検索進捗 ──
        if (s.isSearching) {
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    shape = cardShape,
                    colors = CardDefaults.cardColors(containerColor = HtmlColors.Surface),
                    border = BorderStroke(1.dp, HtmlColors.Border)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("フレーム検索中", fontWeight = FontWeight.Medium, color = HtmlColors.Text)
                            TextButton(onClick = { vm.cancelSearch() }) {
                                Text("キャンセル", color = HtmlColors.Muted)
                            }
                        }
                        LinearProgressIndicator(
                            progress = { s.searchProgress },
                            modifier = Modifier.fillMaxWidth(),
                            color = HtmlColors.Accent,
                            trackColor = HtmlColors.Surface2
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(s.searchStatus, fontSize = 12.sp, color = HtmlColors.Muted)
                    }
                }
            }
        }

        // ── 結果 ──
        if (!s.isSearching && s.frameResults.isNotEmpty()) {
            item {
                Text(
                    "検索結果 ${s.totalFound} 件",
                    fontWeight = FontWeight.SemiBold,
                    color = HtmlColors.Text
                )
            }
            itemsIndexed(s.frameResults) { index, result ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onFrameTap(result.frame) },
                    shape = cardShape,
                    colors = CardDefaults.cardColors(containerColor = HtmlColors.Surface),
                    border = BorderStroke(1.dp, HtmlColors.Border)
                ) {
                    FrameResultRow(index = index + 1, result = result)
                }
            }
        }

        if (!s.isSearching && !s.isProcessing &&
            s.detectedSkill1.isNotEmpty() && s.frameResults.isEmpty() && !s.hasError &&
            s.searchStatus.contains("なし")
        ) {
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    shape = cardShape,
                    colors = CardDefaults.cardColors(containerColor = HtmlColors.Surface),
                    border = BorderStroke(1.dp, HtmlColors.Error.copy(alpha = 0.4f))
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "完全一致フレームなし",
                            fontWeight = FontWeight.Bold,
                            color = HtmlColors.Error
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "スキル・スロット・種類が合っているか確認してください",
                            fontSize = 12.sp,
                            color = HtmlColors.Muted
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ResultField(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, color = HtmlColors.Muted)
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(HtmlColors.Surface2)
                .border(1.dp, HtmlColors.Border, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(value, fontSize = 13.sp, color = HtmlColors.Text)
        }
    }
}

@Composable
private fun FrameResultRow(
    index: Int,
    result: org.mhxxtools.mhxxrngtool.rng.CharmResult,
    onClick: () -> Unit = {}
) {
    val e = result.elapsed
    val (mashes, _, rem) = continueMashInfo(result.frame)
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "#$index  ${result.frame}f",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = HtmlColors.Accent
            )
            Text(
                "${e.days}日 ${e.hours}時間 ${e.mins}分 ${e.secs}秒 ${e.frames}f",
                fontSize = 12.sp,
                color = HtmlColors.Muted
            )
        }
        Text("コンテニュー: ${mashes}回 (残 ${rem}f)", fontSize = 12.sp, color = HtmlColors.Muted)
        val c = result.charm
        Text(
            "${c.skill1Name.replace("　", "")} +${c.skill1Pts}" +
                (if (c.skill2Name != null && c.skill2Pts > 0)
                    " / ${c.skill2Name.replace("　", "")} +${c.skill2Pts}" else "") +
                "  スロ${c.slot}",
            fontSize = 12.sp,
            color = HtmlColors.Text
        )
        Text(
            "タップ → タイマーに自動入力",
            fontSize = 10.sp,
            color = HtmlColors.Success
        )
    }
}
