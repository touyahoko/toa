package org.mhxxtools.mhxxrngtool.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import org.mhxxtools.mhxxrngtool.AppStateViewModel
import org.mhxxtools.mhxxrngtool.rng.KIND_NAMES
import org.mhxxtools.mhxxrngtool.ui.aimpoint.AimPointScreen
import org.mhxxtools.mhxxrngtool.ui.aimpoint.AimPointViewModel
import org.mhxxtools.mhxxrngtool.ui.arduino.ArduinoScreen
import org.mhxxtools.mhxxrngtool.ui.around.AroundScreen
import org.mhxxtools.mhxxrngtool.ui.around.AroundViewModel
import org.mhxxtools.mhxxrngtool.ui.combo.ComboScreen
import org.mhxxtools.mhxxrngtool.ui.combo.ComboViewModel
import org.mhxxtools.mhxxrngtool.ui.ocr.OcrScreen
import org.mhxxtools.mhxxrngtool.ui.ocr.OcrViewModel
import org.mhxxtools.mhxxrngtool.ui.reward.RewardScreen
import org.mhxxtools.mhxxrngtool.ui.reward.RewardViewModel
import org.mhxxtools.mhxxrngtool.ui.search.SearchScreen
import org.mhxxtools.mhxxrngtool.ui.search.SearchViewModel
import org.mhxxtools.mhxxrngtool.ui.theme.HtmlColors
import org.mhxxtools.mhxxrngtool.ui.timer.TimerScreen
import org.mhxxtools.mhxxrngtool.ui.timer.TimerViewModel
import org.mhxxtools.mhxxrngtool.voice.VoiceCommandController
import org.mhxxtools.mhxxrngtool.voice.VoiceState
import org.mhxxtools.mhxxrngtool.voice.buildAppVoiceCommands

private val TABS = listOf("検索", "周辺", "調合", "位置", "狙い目", "鑑定", "タイマー", "Arduino")

@Composable
fun MainScreen(appState: AppStateViewModel) {
    val searchVm: SearchViewModel = viewModel()
    val aroundVm: AroundViewModel = viewModel()
    val comboVm: ComboViewModel = viewModel()
    val rewardVm: RewardViewModel = viewModel()
    val aimVm: AimPointViewModel = viewModel()
    val ocrVm: OcrViewModel = viewModel()
    val timerVm: TimerViewModel = viewModel()

    var selectedTab by remember { mutableIntStateOf(0) }
    var arduinoFrame by remember { mutableStateOf<Long?>(null) }
    var arduinoCharm by remember { mutableStateOf("") }

    LaunchedEffect(appState.kind) {
        searchVm.onKindChanged(appState.kind)
    }

    // ── 音声コマンド (Google 音声入力と連携) ──────────────────────────
    // 「種類」チップの右端のマイクボタンから、タブ移動・種類切替・検索/タイマー操作
    // などアプリの主要機能を音声で呼び出せる。コマンド定義は voice/AppVoiceCommands.kt。
    val context = LocalContext.current
    val voiceCommands = remember {
        buildAppVoiceCommands(
            appState = appState,
            searchVm = searchVm,
            timerVm = timerVm,
            onSelectTab = { selectedTab = it }
        )
    }
    val voiceController = remember {
        VoiceCommandController(context) { heard ->
            voiceCommands.dispatch(heard)?.let { "✓ $it" }
                ?: "「$heard」に対応するコマンドが見つかりませんでした"
        }
    }
    val voiceAvailable = remember { voiceController.isAvailable }
    DisposableEffect(Unit) {
        onDispose { voiceController.destroy() }
    }
    val recordAudioPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) voiceController.start() else voiceController.onPermissionDenied()
    }
    LaunchedEffect(voiceController.statusMessage, voiceController.state) {
        if (voiceController.state == VoiceState.IDLE && voiceController.statusMessage.isNotBlank()) {
            delay(4000)
            voiceController.clearStatus()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(HtmlColors.Bg)
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(HtmlColors.Surface)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "MHXX RNG Tool",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = HtmlColors.Text
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(HtmlColors.Surface2)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("HTML全機能移植", fontSize = 11.sp, color = HtmlColors.Muted)
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("種類:", fontSize = 12.sp, color = HtmlColors.Muted)
                KIND_NAMES.forEachIndexed { idx, name ->
                    val short = when {
                        name.startsWith("風化") -> "風化"
                        name.startsWith("古") -> "古び"
                        name.startsWith("光") -> "光る"
                        else -> "なぞ"
                    }
                    HtmlChip(
                        label = short,
                        selected = appState.kind == idx,
                        onClick = { appState.setKind(idx) }
                    )
                }

                Spacer(Modifier.weight(1f))

                VoiceMicButton(
                    state = voiceController.state,
                    enabled = voiceAvailable,
                    onClick = {
                        if (voiceController.state == VoiceState.IDLE) {
                            recordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            voiceController.cancel()
                        }
                    }
                )
            }

            if (voiceController.statusMessage.isNotBlank()) {
                Text(
                    voiceController.statusMessage,
                    fontSize = 11.sp,
                    color = HtmlColors.Accent,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            Spacer(Modifier.height(2.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp)
            ) {
                TABS.forEachIndexed { idx, title ->
                    HtmlTab(
                        title = title,
                        selected = selectedTab == idx,
                        onClick = { selectedTab = idx }
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(HtmlColors.Border)
            )
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(HtmlColors.Bg)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            when (selectedTab) {
                0 -> SearchScreen(
                    vm = searchVm,
                    onResultTap = { frame ->
                        aroundVm.setFrame(frame)
                        timerVm.setTargetFrame(frame)
                        arduinoFrame = frame
                        selectedTab = 7
                    }
                )
                1 -> AroundScreen(vm = aroundVm, kind = appState.kind)
                2 -> ComboScreen(
                    vm = comboVm,
                    onResultTap = { frame ->
                        timerVm.setCurrentPosFrame(frame)
                        arduinoFrame = frame
                        selectedTab = 6
                    }
                )
                3 -> RewardScreen(vm = rewardVm, kind = appState.kind)
                4 -> AimPointScreen(vm = aimVm, kind = appState.kind)
                5 -> OcrScreen(
                    vm = ocrVm,
                    kind = appState.kind,
                    onApplyToSearch = { data ->
                        searchVm.applyExternalResult(
                            data.skill1Name, data.skill1Pts,
                            data.skill2Name, data.skill2Pts,
                            data.slot
                        )
                        searchVm.startSearch()
                        selectedTab = 0
                    },
                    onFrameTap = { frame ->
                        timerVm.setTargetFrame(frame)
                        arduinoFrame = frame
                        selectedTab = 7
                    }
                )
                6 -> TimerScreen(vm = timerVm)
                7 -> ArduinoScreen(
                    vm = searchVm,
                    kind = appState.kind,
                    onKind = { appState.setKind(it) },
                    targetFrame = arduinoFrame,
                    charmLabel = arduinoCharm,
                    onPick = { frame, label ->
                        arduinoFrame = frame
                        arduinoCharm = label
                        timerVm.setTargetFrame(frame)
                    }
                )
            }
        }
    }
}

@Composable
private fun HtmlChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) HtmlColors.Accent else HtmlColors.Surface2
    val fg = if (selected) Color.White else HtmlColors.Text
    Box(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(label, fontSize = 12.sp, color = fg)
    }
}

@Composable
private fun HtmlTab(title: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            title,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) HtmlColors.Accent else HtmlColors.Muted
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .width(28.dp)
                .height(2.dp)
                .background(if (selected) HtmlColors.Accent else Color.Transparent)
        )
    }
}

@Composable
private fun VoiceMicButton(state: VoiceState, enabled: Boolean, onClick: () -> Unit) {
    val listening = state == VoiceState.LISTENING
    val tint = when {
        !enabled -> HtmlColors.Border
        listening -> Color(0xFFE05252)
        else -> HtmlColors.Muted
    }
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(28.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = if (listening) "音声コマンド 聞き取り中（タップで停止）" else "音声コマンドを開始",
            tint = tint
        )
    }
}
