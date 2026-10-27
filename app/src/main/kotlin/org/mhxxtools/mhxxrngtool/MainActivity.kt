package org.mhxxtools.mhxxrngtool

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.fragment.app.FragmentActivity
import org.mhxxtools.mhxxrngtool.ocr.OpenCvBridge
import org.mhxxtools.mhxxrngtool.ui.MainScreen
import org.mhxxtools.mhxxrngtool.ui.theme.HtmlColors
import org.mhxxtools.mhxxrngtool.ui.theme.MhxxRngTheme

class MainActivity : FragmentActivity() {

    private val appState: AppStateViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OpenCvBridge.init()
        val bar = HtmlColors.Bg.toArgb()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(bar),
            navigationBarStyle = SystemBarStyle.dark(bar)
        )
        window.navigationBarColor = bar
        window.statusBarColor = bar
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
