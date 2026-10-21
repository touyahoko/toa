package org.mhxxtools.mhxxrngtool.ocr

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.mhxxtools.mhxxrngtool.rng.SKILL_NAMES
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 公式 mhxx-renkin-reader WASM（GitHub Pages と同じバイナリ）を WebView で実行。
 * https://github.com/skst328/mhxx-renkin-reader
 */
object RenkinWasmBridge {

    private const val TAG = "RenkinWasm"
    private const val ASSET_BASE = "https://appassets.androidplatform.net/assets/renkin_wasm/"

    @Volatile private var webView: WebView? = null
    @Volatile private var pageReady = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val resultRef = AtomicReference<((String) -> Unit)?>(null)

    class JsBridge {
        @JavascriptInterface
        fun postResult(json: String) {
            Log.i(TAG, "postResult len=${json.length}")
            resultRef.getAndSet(null)?.invoke(json)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(context: Context) {
        if (webView != null) return
        val appCtx = context.applicationContext
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                val assetLoader = WebViewAssetLoader.Builder()
                    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(appCtx))
                    .build()
                val wv = WebView(appCtx)
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.allowFileAccess = true
                wv.settings.cacheMode = WebSettings.LOAD_NO_CACHE
                // Module worker 用
                try {
                    wv.settings.javaScriptCanOpenWindowsAutomatically = true
                } catch (_: Throwable) {}
                wv.addJavascriptInterface(JsBridge(), "RenkinAndroid")
                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        return request?.url?.let { assetLoader.shouldInterceptRequest(it) }
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        pageReady = true
                        Log.i(TAG, "page ready $url")
                    }
                }
                wv.loadUrl(ASSET_BASE + "bridge.html")
                webView = wv
            } catch (e: Throwable) {
                Log.e(TAG, "init failed", e)
            } finally {
                latch.countDown()
            }
        }
        latch.await()
    }

    private suspend fun waitReady() {
        repeat(80) {
            if (pageReady) {
                // worker ready も待つ
                val ping = eval("""
                    (function(){ return window.__renkinPing ? 'ok' : 'no'; })()
                """.trimIndent())
                if (ping.contains("ok")) return
            }
            kotlinx.coroutines.delay(100)
        }
    }

    private suspend fun eval(script: String): String = withContext(Dispatchers.Main) {
        val wv = webView ?: return@withContext ""
        suspendCancellableCoroutine { cont ->
            wv.evaluateJavascript(script) { r ->
                cont.resume(r ?: "")
            }
        }
    }

    suspend fun read(context: Context, bitmap: Bitmap): RenkinSwitchReader.Result? =
        withContext(Dispatchers.Default) {
            ensureWebView(context)
            waitReady()

            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, baos)
            val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

            val json = withTimeout(90_000) {
                suspendCancellableCoroutine { cont ->
                    resultRef.set { s ->
                        if (cont.isActive) cont.resume(s)
                    }
                    mainHandler.post {
                        webView?.evaluateJavascript(
                            """
                            (async function(){
                              try {
                                var r = await window.__renkinReadFile("$b64", "image/jpeg");
                                RenkinAndroid.postResult(typeof r === 'string' ? r : JSON.stringify(r));
                              } catch(e) {
                                RenkinAndroid.postResult(JSON.stringify({ok:false,error:String(e)}));
                              }
                            })();
                            """.trimIndent(),
                            null
                        )
                    }
                }
            }
            parseResult(json)
        }

    private fun parseResult(json: String): RenkinSwitchReader.Result? {
        if (json.isBlank()) return null
        return try {
            val root = JSONObject(json)
            if (!root.optBoolean("ok", false)) {
                return RenkinSwitchReader.Result(
                    OcrCharm(-1, -1, emptyList()),
                    "wasm: ${root.optString("error")}",
                    0.1f
                )
            }
            val result = root.getJSONObject("result")
            // serde は enum を {"read": {...}} または {"type":"read",...} にする場合がある
            val payload = when {
                result.has("read") -> result.getJSONObject("read")
                result.has("Read") -> result.getJSONObject("Read")
                result.optString("type").equals("read", true) -> result
                result.has("fields") -> result
                result.has("rejected") || result.has("Rejected") -> {
                    val rej = result.optJSONObject("rejected") ?: result.optJSONObject("Rejected")
                    return RenkinSwitchReader.Result(
                        OcrCharm(-1, -1, emptyList()),
                        "rejected: ${rej ?: result}",
                        0.2f
                    )
                }
                else -> result
            }
            val fields = payload.optJSONObject("fields") ?: return RenkinSwitchReader.Result(
                OcrCharm(-1, -1, emptyList()),
                "no fields: $json",
                0.2f
            )
            val skills = mutableListOf<OcrSkill>()
            fun fieldInt(obj: JSONObject?, key: String = "value"): Int? {
                if (obj == null || obj.isNull(key)) return null
                return obj.optInt(key)
            }
            val s1 = fieldInt(fields.optJSONObject("skill1"))
            val p1 = fieldInt(fields.optJSONObject("pts1")) ?: 0
            if (s1 != null && s1 >= 0) {
                skills += OcrSkill(s1, SKILL_NAMES.getOrElse(s1) { "s$s1" }, p1)
            }
            val s2Obj = fields.optJSONObject("skill2")
            val s2 = fieldInt(s2Obj)
            if (s2 != null && s2 >= 0) {
                val p2 = fieldInt(fields.optJSONObject("pts2")) ?: 0
                skills += OcrSkill(s2, SKILL_NAMES.getOrElse(s2) { "s$s2" }, p2)
            }
            val slots = fieldInt(fields.optJSONObject("slots")) ?: -1
            val note = "renkin-wasm " + skills.joinToString("+") { "${it.name}+${it.pts}" } + " sl=$slots"
            Log.i(TAG, note)
            RenkinSwitchReader.Result(OcrCharm(-1, slots, skills), note, if (skills.isNotEmpty()) 0.99f else 0.2f)
        } catch (e: Exception) {
            Log.e(TAG, "parse: $json", e)
            null
        }
    }
}
