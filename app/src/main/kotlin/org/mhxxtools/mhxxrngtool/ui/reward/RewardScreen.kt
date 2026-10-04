package org.mhxxtools.mhxxrngtool.ui.reward

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RewardScreen(vm: RewardViewModel, kind: Int, modifier: Modifier = Modifier) {
    val s by vm.state.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("📍 現在位置特定（クエスト報酬）", fontWeight = FontWeight.Bold)
                        Text(
                            "村下位の採取ツアー等をクリアし、報酬スロットのアイテムを画面通りに選択してください。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Text("運気", fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = !s.luckGreat,
                                onClick = { vm.setLuckGreat(false) },
                                label = { Text("通常") }
                            )
                            FilterChip(
                                selected = s.luckGreat,
                                onClick = { vm.setLuckGreat(true) },
                                label = { Text("激運") }
                            )
                        }

                        Text("報酬スロット数", fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(4, 5, 6, 7, 8).forEach { n ->
                                FilterChip(
                                    selected = s.slotCount == n,
                                    onClick = { vm.setSlotCount(n) },
                                    label = { Text("$n") }
                                )
                            }
                        }

                        Text("報酬アイテム", fontSize = 12.sp)
                        s.slots.forEachIndexed { i, item ->
                            var expanded by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(expanded, { expanded = it }) {
                                OutlinedTextField(
                                    value = item,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("スロット ${i + 1}") },
                                    modifier = Modifier
                                        .menuAnchor()
                                        .fillMaxWidth(),
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }
                                )
                                ExposedDropdownMenu(expanded, { expanded = false }) {
                                    vm.itemOptions.forEach { opt ->
                                        DropdownMenuItem(
                                            text = { Text(opt) },
                                            onClick = {
                                                vm.setSlot(i, opt)
                                                expanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = s.start.toString(),
                                onValueChange = { vm.setStart(it.toLongOrNull() ?: 0) },
                                label = { Text("開始F") },
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = s.step.toString(),
                                onValueChange = { vm.setStep(it.toLongOrNull() ?: 1) },
                                label = { Text("ステップ数") },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    if (s.isSearching) vm.stopSearch() else vm.startSearch(kind)
                                },
                                modifier = Modifier.weight(1f),
                                colors = if (s.isSearching) ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                ) else ButtonDefaults.buttonColors()
                            ) { Text(if (s.isSearching) "停止" else "現在位置を特定") }
                            OutlinedButton(onClick = { vm.resetSearch() }) { Text("リセット") }
                        }

                        if (s.isSearching) {
                            LinearProgressIndicator(progress = { s.progress }, Modifier.fillMaxWidth())
                        }
                        if (s.message.isNotEmpty()) {
                            Text(s.message, fontSize = 12.sp)
                        }
                        Text("結果: ${s.resultCount} 件", fontWeight = FontWeight.Medium)
                    }
                }
            }
            items(s.results, key = { it.frame }) { r ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Text(
                        "F ${r.frame}",
                        Modifier.padding(12.dp),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
