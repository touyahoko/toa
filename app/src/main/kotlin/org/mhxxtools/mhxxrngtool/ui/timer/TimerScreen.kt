package org.mhxxtools.mhxxrngtool.ui.timer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun TimerScreen(vm: TimerViewModel, modifier: Modifier = Modifier) {
    val s by vm.state.collectAsStateWithLifecycle()

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── カウントダウン ────────────────────────────────────────
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("⏱ カウントダウン", fontWeight = FontWeight.Bold)
                Text(
                    "結果タップ時の自動入力方式",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = s.snipeMode == SnipeMode.TITLE,
                        onClick = { vm.setSnipeMode(SnipeMode.TITLE) },
                        label = { Text("通常スナイプ", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = s.snipeMode == SnipeMode.CONTINUE,
                        onClick = { vm.setSnipeMode(SnipeMode.CONTINUE) },
                        label = { Text("コンティニュー連打", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    when (s.snipeMode) {
                        SnipeMode.TITLE -> "タイトル画面で待機 → Continue 1回 (F-700)/30秒"
                        SnipeMode.CONTINUE -> "連打 + 残り待機 (HTML: FPC735 / BPM57)"
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(s.phaseLabel, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    s.display,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                Text("時間", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(s.countdownMin, vm::setCountdownMin, label = { Text("分") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(s.countdownSec, vm::setCountdownSec, label = { Text("秒") }, modifier = Modifier.weight(1f))
                }
                Text("開始猶予", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(s.delayMin, vm::setDelayMin, label = { Text("分") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(s.delaySec, vm::setDelaySec, label = { Text("秒") }, modifier = Modifier.weight(1f))
                }
                Text("早め終了", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(s.earlyMin, vm::setEarlyMin, label = { Text("分") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(s.earlySec, vm::setEarlySec, label = { Text("秒") }, modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(s.loop, onCheckedChange = vm::setLoop)
                    Text("ループ", fontSize = 13.sp)
                    Spacer(Modifier.width(12.dp))
                    Checkbox(s.sound, onCheckedChange = vm::setSound)
                    Text("サウンド", fontSize = 13.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { if (s.isRunning) vm.stopCountdown() else vm.startCountdown() },
                        modifier = Modifier.weight(1f),
                        colors = if (s.isRunning) ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ) else ButtonDefaults.buttonColors()
                    ) { Text(if (s.isRunning) "停止" else "開始") }
                    OutlinedButton(onClick = { vm.resetCountdown() }) { Text("リセット") }
                }
            }
        }

        // ── メトロノーム ──────────────────────────────────────────
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🎵 1000カウント・メトロノーム", fontWeight = FontWeight.Bold)
                Text(
                    "${s.metCount}",
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(s.bpm, vm::setBpm, label = { Text("BPM") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(s.targetCount, vm::setTargetCount, label = { Text("目標回数") }, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.toggleMetronome() },
                        modifier = Modifier.weight(1f),
                        colors = if (s.metRunning) ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ) else ButtonDefaults.buttonColors()
                    ) { Text(if (s.metRunning) "停止" else "開始") }
                    OutlinedButton(onClick = { vm.resetMetronome() }) { Text("リセット") }
                }
            }
        }
    }
}
