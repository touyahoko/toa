package org.mhxxtools.mhxxrngtool.ui.ocr

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.mhxxtools.mhxxrngtool.rng.continueMashInfo
import java.io.File

@Composable
fun OcrScreen(
    vm: OcrViewModel,
    kind: Int,
    onApplyToSearch: (ApplyData) -> Unit,
    onFrameTap: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val s       = vm.state.collectAsStateWithLifecycle().value
    val context = LocalContext.current

    // ── カメラ / ギャラリーランチャー ─────────────────────────────────────
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
            val uri  = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            cameraUri = uri
            cameraLauncher.launch(uri)
        }
    }

    // ── UI ────────────────────────────────────────────────────────────────
    LazyColumn(
        modifier          = modifier.fillMaxSize().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // カード 1: 撮影 / OCR
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("📷 鑑定読取", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        "Switchの画面を撮影するだけで\nスキル・スロットを認識し、何フレーム目に出現するか自動計算します。",
                        fontSize = 12.sp,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick  = { cameraPermission.launch(Manifest.permission.CAMERA) },
                            enabled  = !s.isProcessing && !s.isSearching,
                            modifier = Modifier.weight(1f)
                        ) { Text("写真を撮る") }

                        OutlinedButton(
                            onClick  = { galleryLauncher.launch("image/*") },
                            enabled  = !s.isProcessing && !s.isSearching,
                            modifier = Modifier.weight(1f)
                        ) { Text("ギャラリー") }
                    }

                    // OCR 処理中
                    if (s.isProcessing) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Spacer(Modifier.height(4.dp))
                            Text(s.ocrStatus, fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    // ステータス
                    if (!s.isProcessing && s.ocrStatus.isNotEmpty()) {
                        Text(
                            s.ocrStatus,
                            fontSize = 12.sp,
                            color    = if (s.hasError) MaterialTheme.colorScheme.error
                                       else Color(0xFF4CAF50)
                        )
                    }

                    // 認識成功時: 検出スキル表示
                    AnimatedVisibility(!s.hasError && s.detectedSkill1.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            HorizontalDivider()
                            Text("■ 認識結果", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text("スキル1: ${s.detectedSkill1}", fontSize = 13.sp)
                            if (s.detectedSkill2.isNotEmpty())
                                Text("スキル2: ${s.detectedSkill2}", fontSize = 13.sp)
                            Text(
                                "スロット: ${if (s.detectedSlot != null) "${s.detectedSlot}" else "不明"}",
                                fontSize = 13.sp
                            )

                            // 検索タブへ手動移動ボタン (任意)
                            s.autoApplyReady?.let { data ->
                                TextButton(onClick = {
                                    onApplyToSearch(data)
                                    vm.clearAutoApply()
                                }) {
                                    Text("検索タブで詳細を見る →", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // カード 2: フレーム計算中プログレス
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        if (s.isSearching) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("⏳ フレーム計算中", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        LinearProgressIndicator(
                            progress = { s.searchProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment     = Alignment.CenterVertically
                        ) {
                            Text(s.searchStatus, fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { vm.cancelSearch() }) {
                                Text("キャンセル", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("鑑定の並びでフレーム特定", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("画像を認識するたびに追加し、2件以上で検索します。次の鑑定は10秒以内として探します。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (s.sequence.isEmpty()) "並び: なし" else s.sequence.joinToString(" → "), fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.addCurrentToSequence() }, enabled = s.autoApplyReady != null) { Text("この結果を追加") }
                        OutlinedButton(onClick = { vm.searchSequence() }, enabled = s.sequence.size >= 2) { Text("並びで検索") }
                        TextButton(onClick = { vm.clearSequence() }) { Text("クリア") }
                    }
                    if (s.sequenceFrame != null) {
                        Text("★ F${s.sequenceFrame}", fontWeight = FontWeight.Bold, color = Color(0xFF90CAF9))
                    }
                    if (s.sequenceNote.isNotBlank()) Text(s.sequenceNote, fontSize = 12.sp)
                }
            }
        }

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // カード 3: フレーム計算結果（検索中でも随時表示）
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        if (s.frameResults.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (s.isSearching) "📍 ヒット（検索中）" else "📍 完全一致フレーム",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                if (s.totalFound > s.frameResults.size)
                                    "全${s.totalFound}件中 ${s.frameResults.size}件表示"
                                else
                                    "全${s.totalFound}件",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        HorizontalDivider(Modifier.padding(vertical = 4.dp))

                        s.frameResults.forEachIndexed { idx, r ->
                            FrameResultRow(
                                index = idx + 1,
                                result = r,
                                onClick = { onFrameTap(r.frame) }
                            )
                            if (idx < s.frameResults.lastIndex)
                                HorizontalDivider(
                                    Modifier.padding(vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                        }
                    }
                }
            }
        }

        // 計算完了 & 0 件
        if (!s.isSearching && !s.hasError && s.detectedSkill1.isNotEmpty() && s.frameResults.isEmpty() && s.ocrStatus.contains("見つかりません")) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("⚠ フレームが見つかりませんでした",
                            fontWeight = FontWeight.Bold, fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "・護石の種類 (風化し/古びた...) が正しく選択されているか確認\n" +
                            "・スキルポイントが正しく認識されているか確認\n" +
                            "・検索範囲を延ばすには検索タブで手動設定してください",
                            fontSize = 12.sp,
                            color    = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ── フレーム1件の表示コンポーネント ────────────────────────────────────────
@Composable
private fun FrameResultRow(
    index: Int,
    result: org.mhxxtools.mhxxrngtool.rng.CharmResult,
    onClick: () -> Unit = {}
) {
    val e   = result.elapsed
    val (mashes, _, rem) = continueMashInfo(result.frame)

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            "タップ → タイマーに自動入力（通常/連打はタイマータブで切替）",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.tertiary
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "#$index  ${result.frame}f",
                fontWeight = FontWeight.Bold,
                fontSize   = 14.sp,
                color      = MaterialTheme.colorScheme.primary
            )
            Text(
                "${e.days}日 ${e.hours}時間 ${e.mins}分 ${e.secs}秒 ${e.frames}f",
                fontSize = 12.sp,
                color    = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            "コンテニュー: ${mashes}回 (残 ${rem}f)",
            fontSize = 12.sp,
            color    = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val c = result.charm
        Text(
            "確認: ${c.skill1Name.replace("　","")} +${c.skill1Pts}" +
            (if (c.skill2Name != null && c.skill2Pts > 0) " / ${c.skill2Name.replace("　","")} +${c.skill2Pts}" else "") +
            " スロット${c.slot}",
            fontSize = 11.sp,
            color    = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
