package org.mhxxtools.mhxxrngtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.mhxxtools.mhxxrngtool.AppStateViewModel
import org.mhxxtools.mhxxrngtool.rng.KIND_NAMES
import org.mhxxtools.mhxxrngtool.ui.aimpoint.AimPointScreen
import org.mhxxtools.mhxxrngtool.ui.aimpoint.AimPointViewModel
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

private val TABS = listOf("検索", "周辺", "調合", "位置", "狙い目", "鑑定", "タイマー")

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

    Column(
        Modifier
            .fillMaxSize()
            .background(HtmlColors.Bg)
            // 時計・電池アイコンとタイトルが重ならないようにする
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
                        timerVm.applyFromFrame(frame)
                        selectedTab = 6
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
                        selectedTab = 6
                    }
                )
                6 -> TimerScreen(vm = timerVm)
            }
        }
    }
}

@Composable
private fun HtmlChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) HtmlColors.Accent else HtmlColors.Surface2
    val borderC = if (selected) HtmlColors.Accent else HtmlColors.Border
    val fg = if (selected) Color.White else HtmlColors.Text
    Box(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .border(1.dp, borderC, RoundedCornerShape(16.dp))
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
            color = if (selected) HtmlColors.Accent else HtmlColors.Muted,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .width(32.dp)
                .height(2.dp)
                .background(if (selected) HtmlColors.Accent else Color.Transparent)
        )
    }
}
