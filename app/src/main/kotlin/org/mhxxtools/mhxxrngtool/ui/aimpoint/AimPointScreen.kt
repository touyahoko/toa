package org.mhxxtools.mhxxrngtool.ui.aimpoint

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AimPointScreen(vm: AimPointViewModel, kind: Int, modifier: Modifier = Modifier) {
    val s by vm.state.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize().padding(8.dp).verticalScroll(rememberScrollState())) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🎯 狙い目", fontWeight = FontWeight.Bold)

                var frameText by remember { mutableStateOf(if (s.frame == 0L) "" else s.frame.toString()) }
                OutlinedTextField(
                    value = frameText,
                    onValueChange = { new ->
                        if (new.isEmpty() || new.toLongOrNull() != null) {
                            frameText = new
                            vm.setFrame(new.toLongOrNull() ?: 0L)
                        }
                    },
                    label = { Text("フレーム") },
                    placeholder = { Text("0") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // モード選択
                Text("モード:")
                Row {
                    AimPointMode.entries.forEach { mode ->
                        val label = when(mode) {
                            AimPointMode.QUEST   -> "クエスト"
                            AimPointMode.HALCYON -> "マカフシギ"
                            AimPointMode.JUJU    -> "天運"
                        }
                        FilterChip(
                            selected = s.mode == mode,
                            onClick = { vm.setMode(mode) },
                            label = { Text(label) },
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                }

                if (s.mode == AimPointMode.QUEST) {
                    var charmText by remember { mutableStateOf(s.charmCount.toString()) }
                    OutlinedTextField(
                        value = charmText,
                        onValueChange = { new ->
                            if (new.isEmpty() || new.toIntOrNull() != null) {
                                charmText = new
                                new.toIntOrNull()?.let { vm.setCharmCount(it) }
                            }
                        },
                        label = { Text("チャーム数 (2〜40)") },
                        singleLine = true,
                        modifier = Modifier.width(140.dp)
                    )
                } else {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(s.showAllRanks, { vm.setShowAllRanks(it) })
                        Text("全ランク表示")
                    }
                }

                Button(onClick = { vm.calc(kind) }, Modifier.fillMaxWidth()) { Text("計算") }

                if (s.output.isNotEmpty()) {
                    HorizontalDivider()
                    Text(s.output, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}
