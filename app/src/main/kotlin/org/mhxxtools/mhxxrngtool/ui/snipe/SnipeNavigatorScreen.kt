package org.mhxxtools.mhxxrngtool.ui.snipe

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.mhxxtools.mhxxrngtool.snipe.*

/**
 * mhxx-snipe-navigator の 4 タブ（検索 / スナイプ / 記録 / 練習）を Compose で移植。
 * 護石検索エンジン本体は既存 RNG と接続する想定（ここではフレーム計算・タイマー中心）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnipeNavigatorScreen(
    modifier: Modifier = Modifier,
    /** 外部から渡す現在地フレーム（調合・鑑定結果） */
    externalCurrentFrame: Int? = null,
    /** 外部から渡す目標フレーム */
    externalTargetFrame: Int? = null
) {
    var tab by remember { mutableIntStateOf(0) }
    var settings by remember { mutableStateOf(SnipeSettings()) }
    var currentFrame by remember { mutableIntStateOf(externalCurrentFrame ?: 0) }
    var targetFrame by remember { mutableIntStateOf(externalTargetFrame ?: 0) }
    var trials by remember { mutableStateOf(listOf<Trial>()) }

    LaunchedEffect(externalCurrentFrame) {
        externalCurrentFrame?.let { currentFrame = it }
    }
    LaunchedEffect(externalTargetFrame) {
        externalTargetFrame?.let { targetFrame = it }
    }

    Column(modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            listOf("検索", "スナイプ", "記録", "練習").forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
            }
        }
        when (tab) {
            0 -> TargetTab(
                currentFrame = currentFrame,
                onCurrent = { currentFrame = it },
                targetFrame = targetFrame,
                onTarget = { targetFrame = it },
                settings = settings
            )
            1 -> SnipeTab(
                currentFrame = currentFrame,
                targetFrame = targetFrame,
                settings = settings,
                onSettings = { settings = it },
                onRecord = { trial -> trials = listOf(trial) + trials }
            )
            2 -> RecordsTab(trials = trials, onClear = { trials = emptyList() })
            3 -> PracticeTab(settings = settings, onSettings = { settings = it })
        }
    }
}

@Composable
private fun TargetTab(
    currentFrame: Int,
    onCurrent: (Int) -> Unit,
    targetFrame: Int,
    onTarget: (Int) -> Unit,
    settings: SnipeSettings
) {
    var curText by remember(currentFrame) { mutableStateOf(currentFrame.toString()) }
    var tgtText by remember(targetFrame) { mutableStateOf(targetFrame.toString()) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("狙い設定（ナビゲーター）", style = MaterialTheme.typography.titleMedium)
        Text(
            "護石の詳細検索は既存の「検索」タブの RNG を利用し、ここで現在地と目標フレームを渡してスナイプ計算します。",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = curText,
            onValueChange = {
                curText = it
                it.toIntOrNull()?.let(onCurrent)
            },
            label = { Text("現在地フレーム") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = tgtText,
            onValueChange = {
                tgtText = it
                it.toIntOrNull()?.let(onTarget)
            },
            label = { Text("目標フレーム") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        val wait = SnipeMath.waitFrames(currentFrame, targetFrame, settings.timing.waitCorrection)
        val mash = SnipeMath.mashPlan(currentFrame, targetFrame, settings.mash, settings.timing.waitCorrection)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("差分: ${targetFrame - currentFrame} F")
                Text("通常待機: $wait F（${SnipeMath.waitSeconds(wait)} 秒）")
                Text("時間: ${FrameFormat.frameToShortTime(wait)}")
                Text("連打案: ${mash.count} 回 + 余り ${mash.restWaitFrames} F")
                Text("目標までの目安: ${FrameFormat.approximateTime(wait)}")
            }
        }
    }
}

@Composable
private fun SnipeTab(
    currentFrame: Int,
    targetFrame: Int,
    settings: SnipeSettings,
    onSettings: (SnipeSettings) -> Unit,
    onRecord: (Trial) -> Unit
) {
    val context = LocalContext.current
    val metronome = remember { SnipeMetronome(context) }
    DisposableEffect(Unit) { onDispose { metronome.stop() } }

    var running by remember { mutableStateOf(false) }
    var remain by remember { mutableIntStateOf(0) }
    var modeMash by remember { mutableStateOf(false) }

    val waitF = SnipeMath.waitFrames(currentFrame, targetFrame, settings.timing.waitCorrection)
    val mash = SnipeMath.mashPlan(currentFrame, targetFrame, settings.mash, settings.timing.waitCorrection)

    LaunchedEffect(running, remain) {
        if (!running) return@LaunchedEffect
        if (remain <= 0) {
            running = false
            metronome.stop()
            return@LaunchedEffect
        }
        delay(FRAME_MS.toLong().coerceAtLeast(1L))
        remain = (remain - 1).coerceAtLeast(0)
        val (sec, _) = SnipeMath.splitFrames(remain)
        if (sec <= settings.timing.beepFrom && sec >= 0 && remain % FPS == 0) {
            // 秒境界でビープ相当（メトロノーム流用）
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("スナイプ", style = MaterialTheme.typography.titleMedium)
        Text("現在 $currentFrame → 目標 $targetFrame")
        Text("待機 $waitF F / 連打 ${mash.count} 回 + ${mash.restWaitFrames} F")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !modeMash,
                onClick = { modeMash = false },
                label = { Text("通常待機") }
            )
            FilterChip(
                selected = modeMash,
                onClick = { modeMash = true },
                label = { Text("連打法") }
            )
        }

        val (sec, fr) = SnipeMath.splitFrames(remain)
        Text(
            text = if (running) "%d.%02d".format(sec, fr) else "—",
            style = MaterialTheme.typography.displayMedium
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    remain = if (modeMash) mash.restWaitFrames else waitF
                    // 連打モード: 先にメトロノーム
                    if (modeMash && mash.count > 0) {
                        metronome.start(settings.mash.bpm)
                    }
                    running = true
                },
                enabled = !running
            ) { Text("開始") }
            OutlinedButton(
                onClick = {
                    running = false
                    metronome.stop()
                }
            ) { Text("停止") }
            OutlinedButton(
                onClick = {
                    running = false
                    remain = 0
                    metronome.stop()
                }
            ) { Text("リセット") }
        }

        if (modeMash) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("メトロノーム")
                Spacer(Modifier.width(8.dp))
                if (metronome.isRunning) {
                    OutlinedButton(onClick = { metronome.stop() }) { Text("停止") }
                } else {
                    Button(onClick = { metronome.start(settings.mash.bpm) }) { Text("BPM ${settings.mash.bpm}") }
                }
            }
        }

        Text("補正・連打設定", style = MaterialTheme.typography.titleSmall)
        SettingsEditor(settings, onSettings)

        Button(
            onClick = {
                val t = Target(
                    melding = Melding.JUJU,
                    charm = SnipeCharm(0, 0),
                    kind = 0,
                    position = targetFrame,
                    aims = listOf(Aim(targetFrame, 1)),
                    frame = targetFrame,
                    index = 1
                )
                onRecord(
                    Trial(
                        at = System.currentTimeMillis(),
                        target = t,
                        waitCorrection = settings.timing.waitCorrection,
                        soundCorrection = settings.timing.soundCorrection,
                        actual = targetFrame,
                        wait = waitF
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("この試行を記録") }
    }
}

@Composable
private fun SettingsEditor(settings: SnipeSettings, onSettings: (SnipeSettings) -> Unit) {
    var waitC by remember(settings) { mutableStateOf(settings.timing.waitCorrection.toString()) }
    var soundC by remember(settings) { mutableStateOf(settings.timing.soundCorrection.toString()) }
    var bpm by remember(settings) { mutableStateOf(settings.mash.bpm.toString()) }
    var advance by remember(settings) { mutableStateOf(settings.mash.advance.toString()) }
    var offset by remember(settings) { mutableStateOf(settings.mash.offset.toString()) }
    var beepFrom by remember(settings) { mutableStateOf(settings.timing.beepFrom.toString()) }

    fun commit() {
        onSettings(
            SnipeSettings(
                timing = TimingSettings(
                    pattern = settings.timing.pattern,
                    waitCorrection = waitC.toIntOrNull() ?: 0,
                    soundCorrection = soundC.toIntOrNull() ?: 0,
                    beepFrom = beepFrom.toIntOrNull() ?: 5
                ),
                mash = MashSettings(
                    bpm = bpm.toIntOrNull() ?: 60,
                    advance = advance.toIntOrNull() ?: 702,
                    offset = offset.toIntOrNull() ?: 0
                )
            )
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(waitC, { waitC = it; commit() }, label = { Text("待機補正 F") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(soundC, { soundC = it; commit() }, label = { Text("音ずれ補正 F") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(beepFrom, { beepFrom = it; commit() }, label = { Text("ビープ開始 残り秒") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(bpm, { bpm = it; commit() }, label = { Text("連打 BPM") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(advance, { advance = it; commit() }, label = { Text("1回の進行 F (既定702)") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(offset, { offset = it; commit() }, label = { Text("起動〜コンティニュー offset F") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    }
}

@Composable
private fun RecordsTab(trials: List<Trial>, onClear: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("記録 (${trials.size})", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onClear) { Text("クリア") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(trials, key = { it.at }) { t ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("目標 F=${t.target.frame} index=${t.target.index}")
                        Text("待機補正=${t.waitCorrection} 音補正=${t.soundCorrection}")
                        t.actual?.let {
                            Text("actual=$it drift=${FrameFormat.showDrift(SnipeMath.drift(it, t.target.frame))}")
                        }
                        t.wait?.let { Text("wait=${it}F") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PracticeTab(settings: SnipeSettings, onSettings: (SnipeSettings) -> Unit) {
    val context = LocalContext.current
    val metronome = remember { SnipeMetronome(context) }
    DisposableEffect(Unit) { onDispose { metronome.stop() } }
    var practiceRemain by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }

    LaunchedEffect(running, practiceRemain) {
        if (!running) return@LaunchedEffect
        if (practiceRemain <= 0) {
            running = false
            return@LaunchedEffect
        }
        delay(FRAME_MS.toLong().coerceAtLeast(1L))
        practiceRemain--
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("練習（目押し・メトロノーム）", style = MaterialTheme.typography.titleMedium)
        val (sec, fr) = SnipeMath.splitFrames(practiceRemain)
        Text("%d.%02d".format(sec, fr), style = MaterialTheme.typography.displayMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                practiceRemain = 10 * FPS // 10秒
                running = true
            }) { Text("10秒練習") }
            OutlinedButton(onClick = { running = false; practiceRemain = 0 }) { Text("停止") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { metronome.start(settings.mash.bpm) }) { Text("メトロノーム開始") }
            OutlinedButton(onClick = { metronome.stop() }) { Text("停止") }
        }
        SettingsEditor(settings, onSettings)
    }
}
