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
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern
import kotlin.coroutines.resume

/**
 * Switch「スマートフォンへ送る」
 * ① Wi-Fi QR → 接続 → data.json / img/ から自動保存（②不要）
 */
object SwitchTransfer {
    private const val TAG = "SwitchTransfer"
    private const val ROOT = "http://192.168.0.1/"

    private val URL_RE = Pattern.compile("https?://[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE)
    private val WIFI_KV = Pattern.compile("([STP]):([^;]*)", Pattern.CASE_INSENSITIVE)

    @Volatile private var boundNetwork: Network? = null
    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var connectivityManager: ConnectivityManager? = null

    data class WifiInfo(val ssid: String, val password: String, val type: String)
    data class ParseResult(
        val wifi: WifiInfo? = null,
        val url: String? = null,
        val raw: String
    )

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
                when ((m.group(1) ?: "").uppercase()) {
                    "S" -> ssid = m.group(2) ?: ""
                    "P" -> pass = m.group(2) ?: ""
                    "T" -> type = (m.group(2) ?: "").ifBlank { "WPA" }
                }
            }
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
        if (um.find()) return ParseResult(url = um.group(), raw = text)
        val bare = text.trim()
        if (bare.contains("192.168.0.1") || bare.contains("index.html", ignoreCase = true)) {
            val u = when {
                bare.startsWith("http", ignoreCase = true) -> bare
                bare.startsWith("/") -> "http://192.168.0.1$bare"
                bare.contains("/") -> "http://$bare"
                else -> ROOT + "index.html"
            }
            return ParseResult(url = u, raw = text)
        }
        return ParseResult(raw = text)
    }

    suspend fun connectWifi(context: Context, info: WifiInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "API 29+ required")
            return false
        }
        releaseNetwork()
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager = cm

        val ok = withTimeoutOrNull(60_000L) {
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
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        Log.i(TAG, "onAvailable $network")
                        boundNetwork = network
                        try {
                            cm.bindProcessToNetwork(network)
                        } catch (e: Exception) {
                            Log.e(TAG, "bindProcessToNetwork", e)
                        }
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onLost(network: Network) {
                        Log.w(TAG, "onLost $network")
                        if (boundNetwork == network) boundNetwork = null
                    }

                    override fun onUnavailable() {
                        Log.w(TAG, "onUnavailable")
                        if (cont.isActive) cont.resume(false)
                    }
                }
                networkCallback = cb
                try {
                    cm.requestNetwork(request, cb)
                } catch (e: Exception) {
                    Log.e(TAG, "requestNetwork", e)
                    if (cont.isActive) cont.resume(false)
                }
            }
        } ?: false

        if (ok) {
            try {
                delay(800)
            } catch (_: Exception) {
            }
        }
        return ok
    }

    fun releaseNetwork() {
        try {
            val cm = connectivityManager
            val cb = networkCallback
            if (cm != null && cb != null) {
                try {
                    cm.unregisterNetworkCallback(cb)
                } catch (_: Exception) {
                }
            }
            try {
                cm?.bindProcessToNetwork(null)
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
        }
        networkCallback = null
        boundNetwork = null
    }

    /** 手動接続後: data.json が返るまで待つ */
    suspend fun waitUntilReachable(timeoutMs: Long = 90_000L): Boolean =
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + timeoutMs
            var n = 0
            while (System.currentTimeMillis() < deadline) {
                n++
                val j = httpGetString(ROOT + "data.json")
                if (!j.isNullOrBlank()) {
                    Log.i(TAG, "reachable after $n tries")
                    return@withContext true
                }
                val h = httpGetString(ROOT)
                if (!h.isNullOrBlank()) {
                    Log.i(TAG, "root reachable after $n tries")
                    return@withContext true
                }
                try {
                    Thread.sleep(1000L)
                } catch (_: Exception) {
                }
            }
            false
        }

    suspend fun downloadAllToGallery(
        context: Context,
        baseUrl: String = ROOT
    ): List<GalleryItem> = withContext(Dispatchers.IO) {
        val saved = mutableListOf<GalleryItem>()
        val net = boundNetwork
        val cm = connectivityManager
        if (net != null && cm != null) {
            try {
                cm.bindProcessToNetwork(net)
            } catch (_: Exception) {
            }
        }

        var json: String? = null
        repeat(12) { attempt ->
            json = httpGetString(ROOT + "data.json")
            if (!json.isNullOrBlank()) return@repeat
            Log.i(TAG, "data.json retry $attempt")
            try {
                Thread.sleep(700L)
            } catch (_: Exception) {
            }
        }

        if (!json.isNullOrBlank()) {
            val names = extractFileNames(json!!)
            Log.i(TAG, "FileNames=$names")
            for (name in names) {
                val url = ROOT + "img/" + name
                val item = httpSaveToGallery(context, url, name)
                if (item != null) {
                    saved += item
                    Log.i(TAG, "saved ${item.name} ${item.sizeBytes} bytes")
                } else {
                    Log.w(TAG, "failed $url")
                }
            }
            if (saved.isNotEmpty()) return@withContext saved
        }

        val html = httpGetString(ROOT + "index.html")
            ?: httpGetString(ROOT)
            ?: httpGetString(baseUrl)
        if (!html.isNullOrBlank()) {
            for (name in extractFileNames(html)) {
                httpSaveToGallery(context, ROOT + "img/" + name, name)?.let { saved += it }
            }
            for (link in extractMediaLinks(html, ROOT)) {
                val name = link.substringAfterLast('/').substringBefore('?').ifBlank { null }
                httpSaveToGallery(context, link, name)?.let { saved += it }
            }
        }
        saved
    }

    private fun extractFileNames(json: String): List<String> {
        val out = mutableListOf<String>()
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
            Pattern.compile(
                """src\s*=\s*["']([^"']+\.(?:jpg|jpeg|png|webp|mp4))["']""",
                Pattern.CASE_INSENSITIVE
            ),
            Pattern.compile(
                """href\s*=\s*["']([^"']+\.(?:jpg|jpeg|png|webp|mp4))["']""",
                Pattern.CASE_INSENSITIVE
            ),
            Pattern.compile(
                """(["'])(/img/[^"']+\.(?:jpg|jpeg|png|webp|mp4))\1""",
                Pattern.CASE_INSENSITIVE
            )
        )
        for (p in patterns) {
            val m = p.matcher(html)
            while (m.find()) {
                var u = m.group(1) ?: continue
                if (u.length <= 2 && m.groupCount() >= 2) {
                    u = m.group(2) ?: continue
                }
                if (u.startsWith("//")) u = "http:$u"
                else if (u.startsWith("/")) u = base.trimEnd('/') + u
                else if (!u.startsWith("http")) u = base.trimEnd('/') + "/" + u
                out += u
            }
        }
        return out.toList()
    }

    private fun httpSaveToGallery(context: Context, url: String, suggestedName: String?): GalleryItem? {
        return try {
            val c = openConn(url)
            val code = c.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "save $url code=$code")
                return null
            }
            val len = c.contentLengthLong
            c.inputStream.use { input ->
                GalleryStore.saveFromStream(context, input, suggestedName, len)
            }
        } catch (e: Exception) {
            Log.w(TAG, "save $url: ${e.message}")
            // フォールバック: 全読み込み
            try {
                val bytes = httpGetBytes(url) ?: return null
                GalleryStore.saveBytes(context, bytes, suggestedName)
            } catch (e2: Exception) {
                Log.w(TAG, "save fallback $url: ${e2.message}")
                null
            }
        }
    }

    private fun openConn(url: String): HttpURLConnection {
        val u = URL(url)
        val net = boundNetwork
        val conn = if (net != null) {
            net.openConnection(u) as HttpURLConnection
        } else {
            u.openConnection() as HttpURLConnection
        }
        return conn.apply {
            connectTimeout = 10_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Connection", "close")
        }
    }

    private fun httpGetString(url: String): String? {
        return try {
            val c = openConn(url)
            val code = c.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "GET $url code=$code")
                return null
            }
            c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "GET $url: ${e.message}")
            null
        }
    }

    private fun httpGetBytes(url: String): ByteArray? {
        return try {
            val c = openConn(url)
            val code = c.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "GET bytes $url code=$code")
                return null
            }
            c.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            Log.w(TAG, "GET bytes $url: ${e.message}")
            null
        }
    }
}
