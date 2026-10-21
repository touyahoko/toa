package org.mhxxtools.mhxxrngtool.ui.gan

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mhxxtools.mhxxrngtool.gan.ArmorSet

@Composable
fun GanScreen(vm: GanViewModel) {
    val s by vm.state.collectAsState()
    val bg = Color(0xFF1A1D23)
    val card = Color(0xFF252A33)
    val accent = Color(0xFF4C8BF5)

    Column(
        Modifier
            .fillMaxSize()
            .background(bg)
            .padding(12.dp)
    ) {
        Text(
            "願シミュ（スキル検索）",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
        Text(
            "発動スキルを選び防具組み合わせを検索（頑シミュ MHXX 0.9 データ）",
            Modifier.padding(top = 4.dp, bottom = 8.dp),
            color = Color(0xFF9AA3B2),
            fontSize = 12.sp
        )

        if (s.loadError != null) {
            Text("読込エラー: ${s.loadError}", color = Color(0xFFFF6B6B))
            return
        }
        if (!s.loaded) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            Text("データ読込中…", color = Color.Gray)
            return
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0 to "両方", 1 to "剣士", 2 to "ガンナー").forEach { (v, label) ->
                FilterChip(
                    selected = s.typeFilter == v,
                    onClick = { vm.setType(v) },
                    label = { Text(label) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = s.skillQuery,
            onValueChange = { vm.setQuery(it) },
            label = { Text("スキル検索") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(6.dp))

        if (s.selected.isNotEmpty()) {
            Text(
                "選択中: ${s.selected.joinToString(", ")}",
                color = accent,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(4.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.search() },
                enabled = !s.searching && s.selected.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = accent)
            ) { Text(if (s.searching) "検索中…" else "検索") }
            OutlinedButton(onClick = { vm.stop() }, enabled = s.searching) { Text("停止") }
            OutlinedButton(onClick = { vm.clearSelected() }) { Text("クリア") }
        }
        if (s.status.isNotBlank()) {
            Text(
                s.status,
                Modifier.padding(vertical = 4.dp),
                color = Color(0xFFB0B8C4),
                fontSize = 12.sp
            )
        }

        LazyColumn(Modifier = Modifier.weight(1f)) {
            if (s.results.isEmpty()) {
                items(s.filteredSkills.take(80), key = { it.name }) { skill ->
                    val on = skill.name in s.selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .background(
                                if (on) accent.copy(alpha = 0.25f) else card,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { vm.toggleSkill(skill.name) }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = on,
                            onCheckedChange = { vm.toggleSkill(skill.name) }
                        )
                        Column {
                            Text(skill.name, color = Color.White, fontSize = 14.sp)
                            Text(
                                "${skill.tree} ${if (skill.points > 0) "+" else ""}${skill.points}",
                                color = Color(0xFF8B93A7),
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
            items(s.results) { set ->
                ResultCard(set)
            }
        }
    }
}

@Composable
private fun ResultCard(set: ArmorSet) {
    val card = Color(0xFF252A33)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(card, RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Text(
            set.activated.joinToString(" / "),
            color = Color(0xFF7DCEA0),
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(6.dp))
        Text("頭: ${set.head?.name ?: "—"}", color = Color.White, fontSize = 13.sp)
        Text("胴: ${set.body?.name ?: "—"}", color = Color.White, fontSize = 13.sp)
        Text("腕: ${set.arm?.name ?: "—"}", color = Color.White, fontSize = 13.sp)
        Text("腰: ${set.wst?.name ?: "—"}", color = Color.White, fontSize = 13.sp)
        Text("脚: ${set.leg?.name ?: "—"}", color = Color.White, fontSize = 13.sp)
        if (set.decos.isNotEmpty()) {
            Text(
                "珠: ${set.decos.joinToString(", ")}",
                color = Color(0xFFAEB6C5),
                fontSize = 12.sp
            )
        }
        val pts = set.skillTotals.entries.joinToString(" ") { "${it.key}${it.value}" }
        Text(pts, color = Color(0xFF8B93A7), fontSize = 11.sp)
    }
}
