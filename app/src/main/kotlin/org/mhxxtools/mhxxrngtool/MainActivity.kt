package org.mhxxtools.mhxxrngtool

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import org.mhxxtools.mhxxrngtool.ui.MainScreen
import org.mhxxtools.mhxxrngtool.ui.theme.MhxxRngTheme

/** FragmentActivity: AUSBC (CameraFragment) 埋め込みに必要 */
class MainActivity : FragmentActivity() {

    private val appState: AppStateViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MhxxRngTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(appState = appState)
                }
            }
        }
    }
}
