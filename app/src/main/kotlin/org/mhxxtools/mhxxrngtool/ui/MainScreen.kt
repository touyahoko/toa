package org.mhxxtools.mhxxrngtool.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.mhxxtools.mhxxrngtool.AppStateViewModel
import org.mhxxtools.mhxxrngtool.rng.KIND_NAMES
import org.mhxxtools.mhxxrngtool.ui.search.SearchScreen
import org.mhxxtools.mhxxrngtool.ui.search.SearchViewModel
import org.mhxxtools.mhxxrngtool.ui.around.AroundScreen
import org.mhxxtools.mhxxrngtool.ui.around.AroundViewModel
import org.mhxxtools.mhxxrngtool.ui.combo.ComboScreen
import org.mhxxtools.mhxxrngtool.ui.combo.ComboViewModel
import org.mhxxtools.mhxxrngtool.ui.aimpoint.AimPointScreen
import org.mhxxtools.mhxxrngtool.ui.aimpoint.AimPointViewModel
import org.mhxxtools.mhxxrngtool.ui.ocr.OcrScreen
import org.mhxxtools.mhxxrngtool.ui.ocr.OcrViewModel
import org.mhxxtools.mhxxrngtool.ui.reward.RewardScreen
import org.mhxxtools.mhxxrngtool.ui.reward.RewardViewModel
import org.mhxxtools.mhxxrngtool.ui.timer.TimerScreen
import org.mhxxtools.mhxxrngtool.ui.timer.TimerViewModel

private val TABS = listOf("検索", "周辺", "調合", "位置", "狙い目", "鑑定", "タイマー")

@OptIn(ExperimentalMaterial3Api::class)
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

    LaunchedEffect(appState.kind) {
        searchVm.onKindChanged(appState.kind)
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text("MHXX RNG Tool", fontSize = 16.sp) })
                GlobalControls(
                    kind = appState.kind,
                    onKindChanged = { appState.setKind(it) }
                )
                ScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 0.dp) {
                    TABS.forEachIndexed { idx, title ->
                        Tab(
                            selected = selectedTab == idx,
                            onClick = { selectedTab = idx },
                            text = { Text(title, fontSize = 12.sp) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (selectedTab) {
                0 -> SearchScreen(
                    vm = searchVm,
                    onResultTap = { frame ->
                        aroundVm.setFrame(frame)
                        timerVm.applyFromFrame(frame)
                        selectedTab = 6  // タイマーに自動セットして移動
                    }
                )
                1 -> AroundScreen(vm = aroundVm, kind = appState.kind)
                2 -> ComboScreen(vm = comboVm)
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
                        timerVm.applyFromFrame(frame)
                        selectedTab = 6  // タイマー
                    }
                )
                6 -> TimerScreen(vm = timerVm)
            }
        }
    }
}

@Composable
private fun GlobalControls(
    kind: Int,
    onKindChanged: (Int) -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("種類:", fontSize = 12.sp)
            KIND_NAMES.forEachIndexed { idx, name ->
                FilterChip(
                    selected = kind == idx,
                    onClick = { onKindChanged(idx) },
                    label = { Text(name.take(3), fontSize = 11.sp) },
                    modifier = Modifier.height(28.dp)
                )
            }
        }
    }
}
