package org.mhxxtools.mhxxrngtool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import org.mhxxtools.mhxxrngtool.ui.gallery.GalleryScreen
import org.mhxxtools.mhxxrngtool.ui.gallery.GalleryViewModel
import org.mhxxtools.mhxxrngtool.ui.settings.SettingsScreen
import android.net.Uri

private val TABS = listOf("検索", "周辺", "調合", "位置", "狙い目", "鑑定", "タイマー", "ギャラリー")

@Composable
fun MainScreen(appState: AppStateViewModel) {
    val searchVm: SearchViewModel = viewModel()
    val aroundVm: AroundViewModel = viewModel()
    val comboVm: ComboViewModel = viewModel()
    val rewardVm: RewardViewModel = viewModel()
    val aimVm: AimPointViewModel = viewModel()
    val ocrVm: OcrViewModel = viewModel()
    val timerVm: TimerViewModel = viewModel()
    val galleryVm: GalleryViewModel = viewModel()
    var showSettings by remember { mutableStateOf(false) }

    var selectedTab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    LaunchedEffect(appState.kind) {
        searchVm.onKindChanged(appState.kind)
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
                .background(HtmlColors.Bg)
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
                HtmlChip(
                    label = "設定",
                    selected = showSettings,
                    onClick = { showSettings = !showSettings }
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
                    .background(HtmlColors.Border.copy(alpha = 0.6f))
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
                        timerVm.applyTargetFromSearch(frame)
                        selectedTab = 6
                    }
                )
                1 -> AroundScreen(vm = aroundVm, kind = appState.kind)
                2 -> ComboScreen(
                    vm = comboVm,
                    onResultTap = { frame ->
                        timerVm.setCurrentPosFrame(frame)
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
                        timerVm.applyTargetFromSearch(frame)
                        selectedTab = 6
                    }
                )
                6 -> TimerScreen(vm = timerVm)
                7 -> GalleryScreen(
                    vm = galleryVm,
                    onUseImage = { uri ->
                        ocrVm.recognizeFromUri(context, uri, appState.kind)
                        selectedTab = 5
                    },
                    onUseVideo = { uri ->
                        // 調合タブへ（動画パス）
                        selectedTab = 2
                    }
                )
            }
            if (showSettings) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(HtmlColors.Bg)
                ) {
                    SettingsScreen(
                        onOpenGallery = {
                            showSettings = false
                            selectedTab = 7
                        },
                        onClearGallery = { galleryVm.refresh() }
                    )
                }
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
