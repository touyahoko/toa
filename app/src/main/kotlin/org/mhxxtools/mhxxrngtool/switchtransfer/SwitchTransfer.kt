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
 * 1つ目のQR: WIFI:S:switch_xxx;T:WPA;P:password;; （キー順は任意）
 * 2つ目のQR: http://192.168.0.1/… または index URL
 * 接続後: http://192.168.0.1/ から画像・動画を取得して GalleryStore に保存
 */
object SwitchTransfer {
    private const val TAG = "SwitchTransfer"
    private val URL_RE = Pattern.compile("https?://[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE)
    private val WIFI_KV = Pattern.compile("([STP]):([^;]*)", Pattern.CASE_INSENSITIVE)

    data class WifiInfo(val ssid: String, val password: String, val type: String)
    data class ParseResult(
        val wifi: WifiInfo? = null,
        val url: String? = null,
        val raw: String
    )

    /**
     * Switch 実機例: WIFI:S:switch_xxx;T:WPA;P:password;;
     * 一般形:      WIFI:T:WPA;S:ssid;P:password;;
     * キー順は問わない。
     */
    fun parseQr(raw: String): ParseResult {
        val text = raw.trim().replace("\r", "").replace("\n", "")
        val upper = text.uppercase()
        if (upper.startsWith("WIFI:")) {
            var ssid = ""
            var pass = ""
            var type = "WPA"
            val body = text.substring(5)
            val m = WIFI_KV.matcher(body)
            while (m.find()) {
                val k = (m.group(1) ?: "").uppercase()
                val v = m.group(2) ?: ""
                when (k) {
                    "S" -> ssid = v
                    "P" -> pass = v
                    "T" -> type = v.ifBlank { "WPA" }
                }
            }
            // フォールバック: セミコロン分割
            if (ssid.isEmpty()) {
                body.split(';').forEach { part ->
                    val p = part.trim()
                    when {
                        p.startsWith("S:", true) -> ssid = p.substring(2)
                        p.startsWith("P:", true) -> pass = p.substring(2)
                        p.startsWith("T:", true) -> type = p.substring(2).ifBlank { "WPA" }
                    }
                }
            }
            if (ssid.isNotEmpty()) {
                return ParseResult(wifi = WifiInfo(ssid, pass, type), raw = text)
            }
        }
        val um = URL_RE.matcher(text)
        if (um.find()) {
            return ParseResult(url = um.group(), raw = text)
        }
        // 2つ目QR: httpなしの 192.168.0.1/index.html など
        val bare = text.trim()
        if (bare.contains("192.168.0.1") || bare.startsWith("index.html", ignoreCase = true)) {
            val u = when {
                bare.startsWith("http", ignoreCase = true) -> bare
                bare.startsWith("/") -> "http://192.168.0.1$bare"
                bare.contains("/") -> "http://$bare"
                else -> "http://192.168.0.1/index.html"
            }
            return ParseResult(url = u, raw = text)
        }
        if (bare.startsWith("http", ignoreCase = true)) {
            return ParseResult(url = bare, raw = text)
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
            val root = "http://192.168.0.1/"

            // 公式: data.json → FileNames → /img/<name>
            var json: String? = null
            repeat(8) { attempt ->
                json = httpGetString(root + "data.json")
                if (!json.isNullOrBlank()) return@repeat
                try { Thread.sleep(500L + attempt * 300L) } catch (_: Exception) {}
            }

            if (!json.isNullOrBlank()) {
                val names = extractFileNames(json!!)
                Log.i(TAG, "data.json files: $names")
                for (name in names) {
                    val url = root + "img/" + name
                    try {
                        val bytes = httpGetBytes(url) ?: continue
                        if (bytes.size < 100) continue
                        saved += GalleryStore.saveBytes(context, bytes, name)
                    } catch (e: Exception) {
                        Log.e(TAG, "img $name", e)
                    }
                }
                if (saved.isNotEmpty()) return@withContext saved
            }

            // フォールバック: index.html からリンク抽出
            val html = httpGetString(root + "index.html")
                ?: httpGetString(root)
                ?: httpGetString(baseUrl)
            if (!html.isNullOrBlank()) {
                val links = extractMediaLinks(html, root)
                for (link in links) {
                    try {
                        val bytes = httpGetBytes(link) ?: continue
                        if (bytes.size < 100) continue
                        val name = link.substringAfterLast('/').substringBefore('?').ifBlank { null }
                        saved += GalleryStore.saveBytes(context, bytes, name)
                    } catch (e: Exception) {
                        Log.e(TAG, "link $link", e)
                    }
                }
            }
            saved
        }

    /** data.json の FileNames 配列を抜く（簡易JSON） */
    private fun extractFileNames(json: String): List<String> {
        val out = mutableListOf<String>()
        // "FileNames":["a.jpg","b.mp4"] または "FileNames": [ ... ]
        val m = Pattern.compile(
            "\"FileNames\"\\s*:\\s*\\[([^\\]]*)\\]",
            Pattern.CASE_INSENSITIVE
        ).matcher(json)
        if (m.find()) {
            val body = m.group(1) ?: return emptyList()
            val sm = Pattern.compile("\"([^\"]+)\"").matcher(body)
            while (sm.find()) {
                val n = sm.group(1) ?: continue
                if (n.isNotBlank()) out += n
            }
        }
        return out
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
