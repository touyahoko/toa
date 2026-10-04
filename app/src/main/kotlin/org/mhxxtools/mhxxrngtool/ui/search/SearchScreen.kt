package org.mhxxtools.mhxxrngtool.ui.search

import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.mhxxtools.mhxxrngtool.rng.Charm
import org.mhxxtools.mhxxrngtool.rng.MHXXEngine
import org.mhxxtools.mhxxrngtool.rng.ORIGIN_NAMES
import org.mhxxtools.mhxxrngtool.ui.search.SearchMode

@Composable
fun SearchScreen(
    vm: SearchViewModel,
    onResultTap: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val s by vm.state.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize()) {

        // 検索モード切替 (HTML統合ツール準拠)
        TabRow(selectedTabIndex = if (s.mode == SearchMode.SKILL) 0 else 1) {
            Tab(selected = s.mode == SearchMode.SKILL, onClick = { vm.setMode(SearchMode.SKILL) },
                text = { Text("スキルから検索", fontSize = 12.sp) })
            Tab(selected = s.mode == SearchMode.FRAME, onClick = { vm.setMode(SearchMode.FRAME) },
                text = { Text("フレームから検索", fontSize = 12.sp) })
        }

        // ─── 設定パネル ───────────────────────────────────────────────
        LazyColumn(modifier = Modifier.weight(1f)) {
            item {
                Card(Modifier.fillMaxWidth().padding(8.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {

                        if (s.mode == SearchMode.FRAME) {
                            Text("フレーム番号", fontWeight = FontWeight.Bold)
                            OutlinedTextField(
                                value = s.frameQueryText,
                                onValueChange = { vm.setFrameQueryText(it) },
                                label = { Text("Frame") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            LabeledSpinner("出処", ORIGIN_NAMES, ORIGIN_NAMES[s.origin], Modifier.fillMaxWidth()) {
                                vm.setOrigin(ORIGIN_NAMES.indexOf(it).coerceAtLeast(0))
                            }
                            Button(onClick = { vm.lookupFrame() }, modifier = Modifier.fillMaxWidth()) {
                                Text("このフレームのお守りを表示")
                            }
                            s.frameCharm?.let { fr ->
                                HorizontalDivider()
                                // HTML: フレーム | 経過 | お守り | スロ | レア
                                Text("${fr.frame}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text(fr.elapsed.text(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(fr.charm.skillText(), fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Text("${fr.charm.slotDots()}    ${fr.charm.rarityLabel()}", fontSize = 13.sp)
                            }
                            return@Column
                        }

                        // スキル1
                        Text("スキル1", fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SkillSpinner(s.skill1Names, s.skill1Idx, Modifier.weight(1f)) { vm.setSkill1(it) }
                            OutlinedTextField(
                                value = s.skill1PtsText,
                                onValueChange = { vm.setSkill1PtsText(it) },
                                label = { Text("Pt") },
                                modifier = Modifier.width(80.dp),
                                singleLine = true
                            )
                        }
                        Text("推奨Pt: ${s.skill1PtRange.first}〜${s.skill1PtRange.last}（自由入力可）",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                        // スキル2
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(s.useSkill2, { vm.setUseSkill2(it) })
                            Text("スキル2を指定する")
                        }
                        if (s.useSkill2) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SkillSpinner(s.skill2Names, s.skill2Idx, Modifier.weight(1f)) { vm.setSkill2(it) }
                                OutlinedTextField(
                                    value = s.skill2PtsText,
                                    onValueChange = { vm.setSkill2PtsText(it) },
                                    label = { Text("Pt") },
                                    modifier = Modifier.width(80.dp),
                                    singleLine = true
                                )
                            }
                            Text("推奨Pt: ${s.skill2PtRange.first}〜${s.skill2PtRange.last}（自由入力可）",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        HorizontalDivider()

                        // スロット / 出処 / フレーム設定
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LabeledSpinner("スロット", listOf("0","1","2","3"), s.slot.toString(), Modifier.weight(1f)) {
                                vm.setSlot(it.toIntOrNull() ?: 0)
                            }
                            LabeledSpinner("出処", ORIGIN_NAMES, ORIGIN_NAMES[s.origin], Modifier.weight(1f)) {
                                vm.setOrigin(ORIGIN_NAMES.indexOf(it).coerceAtLeast(0))
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = s.startText,
                                onValueChange = { vm.setStartText(it) },
                                label = { Text("開始F") }, singleLine = true, modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = s.stepText,
                                onValueChange = { vm.setStepText(it) },
                                label = { Text("ステップ数") }, singleLine = true, modifier = Modifier.weight(1f)
                            )
                        }

                        // 検索モード
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(s.exactMode, { vm.setExactMode(true) })
                            Text("完全一致", Modifier.padding(end = 16.dp))
                            RadioButton(!s.exactMode, { vm.setExactMode(false) })
                            Text("以上")
                        }

                        // ボタン
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

                        if (s.isSearching) LinearProgressIndicator(progress = { s.progress }, Modifier.fillMaxWidth())

                        Text("検索結果（フレーム / 経過 / お守り / スロ / レア）: ${s.resultCount} 件${if(s.resultCount > 300) " (先頭300件)" else ""}",
                            fontWeight = FontWeight.Medium)
                    }
                }
            }

            // ─── 結果一覧 ─────────────────────────────────────────────
            items(s.results) { result ->
                CharmCard(result, onClick = { onResultTap(result.frame) })
            }
        }
    }
}

@Composable
private fun CharmCard(result: org.mhxxtools.mhxxrngtool.rng.CharmResult, onClick: () -> Unit) {
    val rarityColor = Color(result.charm.rarityColor)
    val c = result.charm
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp, 48.dp).background(rarityColor))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // HTML表と同じ: フレーム | 経過 | お守り | スロ | レア
                Text(
                    "${result.frame}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    result.elapsed.text(),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    c.skillText(),
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
                Text(
                    "${c.slotDots()}    ${c.rarityLabel()}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun SkillSpinner(names: List<String>, selectedIdx: Int, modifier: Modifier, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = names.getOrNull(selectedIdx) ?: ""
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, Modifier.fillMaxWidth()) {
            Text(selected, maxLines = 1)
        }
        DropdownMenu(expanded, { expanded = false }) {
            names.forEachIndexed { idx, name ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(idx); expanded = false })
            }
        }
    }
}

@Composable
fun LabeledSpinner(label: String, values: List<String>, selected: String, modifier: Modifier, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, Modifier.fillMaxWidth()) { Text("$label: $selected") }
        DropdownMenu(expanded, { expanded = false }) {
            values.forEach { v ->
                DropdownMenuItem(text = { Text(v) }, onClick = { onSelect(v); expanded = false })
            }
        }
    }
}

// ── Charm 表示ヘルパー拡張関数 ──────────────────────────────────────────────

/** スロット数を ●○ で表現 (例: 2スロ → "●●○") */
private fun Charm.slotDots(): String =
    "●".repeat(slot.coerceIn(0, 3)) + "○".repeat((3 - slot).coerceIn(0, 3))

/** レアリティラベル (例: "レア8") */
private fun Charm.rarityLabel(): String = "レア$rarity"
