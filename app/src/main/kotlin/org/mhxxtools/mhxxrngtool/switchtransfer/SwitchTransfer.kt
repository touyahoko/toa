package org.mhxxtools.mhxxrngtool.switchtransfer

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern
import kotlin.coroutines.resume

/**
 * Switch「スマートフォンへ送る」対応。
 *
 * 1つ目のQR: WIFI:T:WPA;S:switch_xxx;P:password;;
 * 2つ目のQR: http://192.168.0.1/… または index URL
 * 接続後: http://192.168.0.1/ から画像・動画を取得して GalleryStore に保存
 */
object SwitchTransfer {
    private const val TAG = "SwitchTransfer"
    private val WIFI_RE = Pattern.compile(
        "WIFI:T:([^;]*);S:([^;]*);P:([^;]*);;",
        Pattern.CASE_INSENSITIVE
    )
    private val URL_RE = Pattern.compile("https?://[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE)

    data class WifiInfo(val ssid: String, val password: String, val type: String)
    data class ParseResult(
        val wifi: WifiInfo? = null,
        val url: String? = null,
        val raw: String
    )

    fun parseQr(raw: String): ParseResult {
        val text = raw.trim()
        val wm = WIFI_RE.matcher(text)
        if (wm.find()) {
            return ParseResult(
                wifi = WifiInfo(wm.group(2) ?: "", wm.group(3) ?: "", wm.group(1) ?: "WPA"),
                raw = text
            )
        }
        val um = URL_RE.matcher(text)
        if (um.find()) {
            return ParseResult(url = um.group(), raw = text)
        }
        // 裸のホスト
        if (text.contains("192.168.0.1") || text.startsWith("http")) {
            val u = if (text.startsWith("http")) text else "http://$text"
            return ParseResult(url = u, raw = text)
        }
        return ParseResult(raw = text)
    }

    suspend fun connectWifi(context: Context, info: WifiInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "WifiNetworkSpecifier needs API 29+")
            return false
        }
        return withTimeoutOrNull(45_000L) {
            suspendCancellableCoroutine { cont ->
                val specifier = WifiNetworkSpecifier.Builder()
                    .setSsid(info.ssid)
                    .setWpa2Passphrase(info.password)
                    .build()
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(specifier)
                    .build()
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        cm.bindProcessToNetwork(network)
                        if (cont.isActive) cont.resume(true)
                    }
                    override fun onUnavailable() {
                        if (cont.isActive) cont.resume(false)
                    }
                }
                cont.invokeOnCancellation {
                    try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) {}
                    try { cm.bindProcessToNetwork(null) } catch (_: Exception) {}
                }
                try {
                    cm.requestNetwork(request, cb)
                } catch (e: Exception) {
                    Log.e(TAG, "requestNetwork", e)
                    if (cont.isActive) cont.resume(false)
                }
            }
        } ?: false
    }

    suspend fun downloadAllToGallery(context: Context, baseUrl: String = "http://192.168.0.1/"): List<GalleryItem> =
        withContext(Dispatchers.IO) {
            val saved = mutableListOf<GalleryItem>()
            val root = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            val candidates = listOf(
                root,
                root + "index.html",
                "http://192.168.0.1/",
                "http://192.168.0.1/index.html"
            )
            var html: String? = null
            for (u in candidates) {
                html = httpGetString(u)
                if (!html.isNullOrBlank()) break
            }
            val page = html ?: return@withContext emptyList()
            val links = extractMediaLinks(page, root)
            for (link in links) {
                try {
                    val bytes = httpGetBytes(link) ?: continue
                    if (bytes.size < 100) continue
                    val name = link.substringAfterLast('/').substringBefore('?').ifBlank { null }
                    saved += GalleryStore.saveBytes(context, bytes, name)
                } catch (e: Exception) {
                    Log.e(TAG, "download $link", e)
                }
            }
            // 単枚直リンク想定で data 系も試す
            if (saved.isEmpty()) {
                for (i in 0..9) {
                    for (path in listOf("img/$i.jpg", "data/$i.jpg", "image$i.jpg", "screen$i.jpg")) {
                        val bytes = httpGetBytes(root + path) ?: continue
                        if (bytes.size > 1000) {
                            saved += GalleryStore.saveBytes(context, bytes, path.substringAfterLast('/'))
                        }
                    }
                }
            }
            saved
        }

    private fun extractMediaLinks(html: String, base: String): List<String> {
        val out = LinkedHashSet<String>()
        val patterns = listOf(
            Pattern.compile("""src\s*=\s*["']([^"']+\.(?:jpg|jpeg|png|webp|mp4))["']""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""href\s*=\s*["']([^"']+\.(?:jpg|jpeg|png|webp|mp4))["']""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""(https?://[^"' \s]+\.(?:jpg|jpeg|png|webp|mp4))""", Pattern.CASE_INSENSITIVE)
        )
        for (p in patterns) {
            val m = p.matcher(html)
            while (m.find()) {
                var u = m.group(1) ?: continue
                if (u.startsWith("//")) u = "http:$u"
                else if (u.startsWith("/")) u = base.trimEnd('/') + u
                else if (!u.startsWith("http")) u = base.trimEnd('/') + "/" + u
                out += u
            }
        }
        return out.toList()
    }

    private fun httpGetString(url: String): String? {
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 15000
                instanceFollowRedirects = true
                requestMethod = "GET"
            }
            if (c.responseCode !in 200..299) return null
            c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "GET $url fail: ${e.message}")
            null
        }
    }

    private fun httpGetBytes(url: String): ByteArray? {
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 60000
                instanceFollowRedirects = true
                requestMethod = "GET"
            }
            if (c.responseCode !in 200..299) return null
            c.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            Log.w(TAG, "GET bytes $url fail: ${e.message}")
            null
        }
    }
}
