package org.mhxxtools.mhxxrngtool.ui.psim

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.mhxxtools.mhxxrngtool.ui.theme.HtmlColors

/** HTML版Pシミュをassetsから表示。計算・全スキル・結果表示はHTMLと同一。 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SkillSimScreen() {
    Box(
        Modifier
            .fillMaxSize()
            .background(HtmlColors.Bg)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(AndroidColor.parseColor("#090C13"))
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = true
                        allowContentAccess = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        cacheMode = WebSettings.LOAD_DEFAULT
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        builtInZoomControls = true
                        displayZoomControls = false
                        setSupportZoom(true)
                    }
                    webViewClient = WebViewClient()
                    loadUrl("file:///android_asset/psim/index.html")
                }
            }
        )
    }
}
