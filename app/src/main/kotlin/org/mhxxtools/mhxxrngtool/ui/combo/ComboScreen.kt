package org.mhxxtools.mhxxrngtool.ui.combo

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ComboScreen(
    vm: ComboViewModel,
    onResultTap: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val s by vm.state.collectAsStateWithLifecycle()

    // 動画ファイルピッカー (SAF 経由のため追加権限不要)
    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { vm.setVideoUri(it) }
    }

    Column(modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f)) {

            // ── 🧪 調合スナイプ (フレーム検索) ──────────────────────────────
            item {
                Card(Modifier.fillMaxWidth().padding(8.dp)) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("🧪 調合スナイプ", fontWeight = FontWeight.Bold)
                        Text(
                            "調合数値列を半角スペース区切りで入力してください。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value = s.sequence,
                            onValueChange = { vm.setSequence(it) },
                            label = { Text("調合数値列 例: 00 03 06 09 12 14 16") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2
                        )

                        Text(
                            s.validationMsg,
                            color = if (s.validationOk) Color(0xFF4CAF50)
                                    else MaterialTheme.colorScheme.error,
                            fontSize = 12.sp
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = s.start.toString(),
                                onValueChange = { vm.setStart(it.toLongOrNull() ?: 0) },
                                label = { Text("開始F") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = s.step.toString(),
                                onValueChange = { vm.setStep(it.toLongOrNull() ?: 1) },
                                label = { Text("ステップ数") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { if (s.isSearching) vm.stopSearch() else vm.startSearch() },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (s.isSearching) MaterialTheme.colorScheme.error
                                                     else MaterialTheme.colorScheme.primary
                                )
                            ) { Text(if (s.isSearching) "停止" else "検索") }
                            OutlinedButton(onClick = { vm.resetSearch() }) { Text("リセット") }
                        }

                        if (s.isSearching) {
                            LinearProgressIndicator(
                                progress = { s.progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Text(
                            "検索結果: ${s.resultCount} 件" +
                                if (s.resultCount > 300) " (先頭300件)" else "",
                            fontWeight = FontWeight.Medium
                        )
                        if (s.resultCount > 1) {
                            Text(
                                "候補が複数あります。先頭=最若フレーム。" +
                                    "絞り込みは調合列を長くする／開始Fを寄せてください。" +
                                    "タップでタイマーへ（現在地として使用）。",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // ── 📹 動画解析でフレーム特定 ─────────────────────────────────
            item {
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("📹 動画解析でフレーム特定", fontWeight = FontWeight.Bold)
                        Text(
                            "Switch録画 (1280×720・30fps 推奨) から調合カウンターを自動読み取りして\n" +
                            "調合数値列に適用します。",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

                        // ── 動画ファイル選択 ──────────────────────────────
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { videoPicker.launch("video/*") },
                                modifier = Modifier.wrapContentWidth()
                            ) {
                                Text("📂 動画を選択", fontSize = 13.sp)
                            }
                            Text(
                                text = if (s.videoName.isEmpty()) "未選択" else s.videoName,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // ── フレーム範囲・FPS・ステップ ───────────────────
                        Text(
                            "フレーム範囲 (動画ファイル選択後に自動入力されます)",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                value = s.beginFrame.toString(),
                                onValueChange = { vm.setBeginFrame(it.toIntOrNull() ?: 0) },
                                label = { Text("開始F") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = s.endFrame.toString(),
                                onValueChange = { vm.setEndFrame(it.toIntOrNull() ?: 899) },
                                label = { Text("終了F") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = s.videoFps.toString(),
                                onValueChange = { vm.setVideoFps(it.toIntOrNull() ?: 30) },
                                label = { Text("FPS") },
                                singleLine = true,
                                modifier = Modifier.weight(0.7f)
                            )
                            OutlinedTextField(
                                value = s.frameStep.toString(),
                                onValueChange = { vm.setFrameStep(it.toIntOrNull() ?: 3) },
                                label = { Text("間隔") },
                                singleLine = true,
                                modifier = Modifier.weight(0.7f)
                            )
                        }
                        Text(
                            "間隔: 何フレームおきに読むか (テンプレート照合は 1 推奨)",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                        )

                        // ── 解析ボタン ────────────────────────────────────
                        Button(
                            onClick = {
                                if (s.isAnalyzing) vm.stopAnalysis() else vm.startAnalysis()
                            },
                            enabled = s.videoUri != null,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (s.isAnalyzing)
                                    MaterialTheme.colorScheme.error
                                else
                                    MaterialTheme.colorScheme.secondary
                            )
                        ) {
                            Text(
                                if (s.isAnalyzing) "⏹ 解析を停止" else "▶ 動画を解析する",
                                fontSize = 14.sp
                            )
                        }

                        // ── 進捗バー ──────────────────────────────────────
                        if (s.isAnalyzing) {
                            LinearProgressIndicator(
                                progress = { s.analyzeProgress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "解析中… ${(s.analyzeProgress * 100).toInt()}%",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }

                        if (s.craftSummary.isNotEmpty()) {
                            Text(
                                s.craftSummary,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }

                        // ── 結果メッセージ ────────────────────────────────
                        if (s.analyzeMsg.isNotEmpty()) {
                            Text(
                                s.analyzeMsg,
                                fontSize = 12.sp,
                                color = if (s.detectedNumbers.isNotEmpty()) Color(0xFF2E7D32)
                                        else MaterialTheme.colorScheme.error
                            )
                        }

                        // ── 「調合数値列に適用」ボタン ─────────────────────
                        if (s.detectedNumbers.isNotEmpty()) {
                            Button(
                                onClick = { vm.applyDetectedNumbers() },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF388E3C)
                                )
                            ) {
                                Text("⬆ 調合数値列に適用する", fontSize = 14.sp)
                            }
                            Text(
                                "適用後、上の「検索」ボタンでフレーム特定できます。",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }

            // ── 検索結果一覧 ──────────────────────────────────────────────
            items(s.results.size) { idx ->
                val r = s.results[idx]
                val isPrimary = idx == 0
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .clickable { onResultTap(r.frame) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isPrimary)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(Modifier.padding(10.dp)) {
                        val driftSuffix = if (r.totalDrift != 0) {
                            " (${if (r.totalDrift > 0) "+" else ""}${r.totalDrift})"
                        } else if (r.driftNote != null) " (ずれ検出)" else ""
                        Text(
                            if (isPrimary) "★ 現在地候補  F${r.frame}$driftSuffix"
                            else "候補${idx + 1}  F${r.frame}$driftSuffix",
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            r.elapsed.text(),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (r.driftNote != null) {
                            Text(
                                r.driftNote,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                        Text(
                            "タップ → 現在地としてタイマーへ",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
