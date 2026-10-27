package org.mhxxtools.mhxxrngtool

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import org.mhxxtools.mhxxrngtool.ocr.OpenCvBridge
import org.mhxxtools.mhxxrngtool.ui.MainScreen
import org.mhxxtools.mhxxrngtool.ui.theme.HtmlColors
import org.mhxxtools.mhxxrngtool.ui.theme.MhxxRngTheme

/** FragmentActivity: AUSBC (CameraFragment) 埋め込みに必要 */
class MainActivity : FragmentActivity() {

    private val appState: AppStateViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OpenCvBridge.init()
        enableEdgeToEdge()
        // ライトテーマ: ステータスバーは暗いアイコン
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = true
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightNavigationBars = true
        setContent {
            MhxxRngTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = HtmlColors.Bg
                ) {
                    MainScreen(appState = appState)
                }
            }
        }
    }
}
