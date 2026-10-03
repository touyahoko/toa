package org.mhxxtools.mhxxrngtool

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import org.mhxxtools.mhxxrngtool.ui.MainScreen
import org.mhxxtools.mhxxrngtool.ui.theme.HtmlColors
import org.mhxxtools.mhxxrngtool.ui.theme.MhxxRngTheme

class MainActivity : ComponentActivity() {

    private val appState: AppStateViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MhxxRngTheme {
                Surface(Modifier.fillMaxSize(), color = HtmlColors.Bg) {
                    MainScreen(appState = appState)
                }
            }
        }
    }
}
