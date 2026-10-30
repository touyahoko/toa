package org.mhxxtools.mhxxrngtool.ui.psim

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

private val PART_LABELS = listOf("頭", "胴", "腕", "腰", "脚")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillSimScreen(vm: SkillSimViewModel = viewModel()) {
    val targets = vm.targets
    val results = vm.results

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("スキルシミュ", style = MaterialTheme.typography.titleMedium)
            Text(
                "欲しいスキルから防具・装飾品・護石の構成を探します",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 職 / 性別
        item {
            SectionCard {
                Text("職", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = vm.job == "s", onClick = { vm.updateJob("s") }, label = { Text("剣士") })
                    FilterChip(selected = vm.job == "g", onClick = { vm.updateJob("g") }, label = { Text("ガンナー") })
                }
                Spacer(Modifier.height(8.dp))
                Text("性別", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = vm.sex == "m", onClick = { vm.updateSex("m") }, label = { Text("男性") })
                    FilterChip(selected = vm.sex == "f", onClick = { vm.updateSex("f") }, label = { Text("女性") })
                }
                Spacer(Modifier.height(8.dp))
                Text("武器スロット", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0..3).forEach { n ->
                        FilterChip(
                            selected = vm.weaponSlots == n,
                            onClick = { vm.updateWeaponSlots(n) },
                            label = { Text("$n") }
                        )
                    }
                }
            }
        }

        // スキル追加
        item {
            SectionCard {
                Text("欲しいスキル", style = MaterialTheme.typography.labelMedium)
                if (!vm.ready) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    Text("データ読み込み中…", fontSize = 12.sp)
                } else {
                    var expanded by remember { mutableStateOf(false) }
                    val opts = vm.options
                    val sel = opts.getOrNull(vm.selectedOptionIndex)
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                        OutlinedTextField(
                            value = sel?.let { "${it.displayName}（${it.system} ${it.points}）" } ?: "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("スキル") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            singleLine = true
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            opts.forEachIndexed { i, o ->
                                DropdownMenuItem(
                                    text = { Text("${o.displayName}（${o.system} ${o.points}）", fontSize = 13.sp) },
                                    onClick = {
                                        vm.setSelectedOption(i)
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.addSelectedSkill() }) { Text("追加") }
                        OutlinedButton(onClick = { vm.clearTargets() }) { Text("クリア") }
                    }
                }
                if (targets.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    targets.forEach { t ->
                        AssistChip(
                            onClick = { vm.removeTarget(t.system) },
                            label = { Text("${t.displayName} ${t.system}${t.points} ×") },
                            modifier = Modifier.padding(end = 4.dp, bottom = 4.dp)
                        )
                    }
                }
            }
        }

        item {
            Text(
                vm.status,
                style = MaterialTheme.typography.bodyMedium,
                color = if (vm.searching) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
            )
            if (vm.searching) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }

        itemsIndexed(results) { index, r ->
            ResultCard(index + 1, r)
        }

        if (results.isNotEmpty()) {
            item {
                CharmTable(results)
            }
        }
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), content = content)
    }
}

@Composable
private fun ResultCard(index: Int, r: PsimResult) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("構成 $index", fontWeight = FontWeight.Bold, fontSize = 14.sp)

            // 部位グリッド
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PART_LABELS.forEachIndexed { i, lab ->
                    val name = r.build.getOrNull(i)?.takeIf { it.isNotBlank() } ?: "–"
                    PartRow(lab, name)
                }
                PartRow("護石", r.charmLabel)
            }

            // 防御・スロット
            var defSum = 0
            val slotNums = mutableListOf<Int>()
            PART_LABELS.indices.forEach { i ->
                val raw = r.build.getOrNull(i) ?: ""
                val base = raw.substringBefore("［").ifBlank { raw }
                val meta = PsimData.metaOf(base)
                defSum += meta?.def ?: 0
                slotNums += meta?.slots ?: 0
            }
            slotNums += r.slots
            Text("防御 $defSum　スロット ${slotNums.joinToString(" ")}", fontSize = 13.sp)

            // 装飾品
            val decos = mutableListOf<String>()
            r.build.forEach { label ->
                if (label == null) return@forEach
                val m = Regex("［(.+)］").find(label) ?: return@forEach
                m.groupValues[1].split("・").forEach { decos += it }
            }
            if (decos.isNotEmpty()) {
                val counts = decos.groupingBy { it }.eachCount()
                Text(
                    "装飾品 " + counts.entries.joinToString("　") { (k, v) -> if (v > 1) "$k×$v" else k },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            // ポイント簡易表（目標スキル）
            val systems = r.needs.keys.toMutableSet()
            PART_LABELS.indices.forEach { i ->
                val raw = r.build.getOrNull(i) ?: return@forEach
                val base = raw.substringBefore("［")
                PsimData.metaOf(base)?.skills?.keys?.let { systems.addAll(it) }
            }
            if (systems.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("ポイント内訳", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                systems.forEach { sys ->
                    var sum = 0
                    val cells = StringBuilder()
                    PART_LABELS.indices.forEach { i ->
                        val raw = r.build.getOrNull(i) ?: ""
                        val base = raw.substringBefore("［")
                        val v = PsimData.metaOf(base)?.skills?.get(sys) ?: 0
                        sum += v
                        if (v != 0) cells.append("${PART_LABELS[i]}$v ")
                    }
                    val ch = r.needs[sys] ?: 0
                    sum += ch
                    if (ch != 0) cells.append("護石$ch ")
                    // 装飾品分はラベルから再計算しない場合あり
                    val act = PsimData.activatedSkill(sys, sum)
                    if (sum != 0) {
                        Text(
                            "$sys  合計$sum ${if (act.isNotEmpty()) "→ $act" else ""}  ($cells)",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // お守り種別
            val am = listOf("光", "古", "風").mapIndexed { i, t ->
                if (r.charmOk.getOrNull(i) == true) t else "·"
            }.joinToString(" ")
            Text("お守り $am", fontSize = 12.sp)
        }
    }
}

@Composable
private fun PartRow(label: String, name: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            modifier = Modifier.width(36.dp),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary
        )
        Text(name, fontSize = 13.sp)
    }
}

@Composable
private fun CharmTable(results: List<PsimResult>) {
    SectionCard {
        Text("必要護石一覧", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        val rows = results.distinctBy { it.charmLabel + it.slots }.take(40)
        rows.forEach { r ->
            val am = listOf("光", "古", "風").mapIndexed { i, t ->
                if (r.charmOk.getOrNull(i) == true) t else "·"
            }.joinToString("")
            Text(
                "スロ${r.slots}  ${r.charmLabel}  [$am]",
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
    }
}
