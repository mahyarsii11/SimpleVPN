package com.mahyar.simplevpn

import android.content.Context
import android.util.Base64
import com.wireguard.config.Config
import com.wireguard.crypto.KeyPair
import org.json.JSONObject
import java.io.BufferedReader
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Registers a free anonymous Cloudflare WARP account on first run and
 * converts it into a WireGuard configuration. No user input required.
 */
object WarpManager {

    private const val PREFS = "warp_prefs"
    private const val KEY_CONFIG = "wg_config"
    private const val UA = "1.1.1.1/6.26"
    private val API_VERSIONS = listOf("v0i1707", "v0a2158", "v0a448")
    private const val DEFAULT_ENDPOINT = "engage.cloudflareclient.com:2408"
    private const val PEER_FALLBACK_KEY = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="

    private data class WarpReg(
        val v4: String,
        val v6: String,
        val peerPub: String,
        val endpoint: String,
        val privKey: String
    )

    fun hasRegistration(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CONFIG, null) != null

    fun reset(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_CONFIG).apply()
    }

    /** Returns a working WireGuard config, registering a new WARP account if needed. */
    fun getOrCreateConfig(ctx: Context): Config {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_CONFIG, null)
        if (saved != null) {
            try {
                return Config.parse(BufferedReader(StringReader(saved)))
            } catch (_: Exception) {
                prefs.edit().remove(KEY_CONFIG).apply()
            }
        }
        val reg = register()
        val text = buildConfigText(reg)
        prefs.edit().putString(KEY_CONFIG, text).apply()
        return Config.parse(BufferedReader(StringReader(text)))
    }

    private fun register(): WarpReg {
        val kp = KeyPair()
        val pub = kp.publicKey.toBase64()
        val body = JSONObject().apply {
            put("install_id", "")
            put("fcm_token", UUID.randomUUID().toString() + ":APA91b" + randomToken(43))
            put("key", pub)
            put("locale", "en_US")
            put("model", "Android")
            put("tos", isoNow())
            put("type", "Android")
            put("serial_number", UUID.randomUUID().toString())
            put("warp_enabled", true as Any)
        }
        var lastErr = "unknown"
        for (v in API_VERSIONS) {
            try {
                val (code, text) = post("https://api.cloudflareclient.com/$v/reg", body.toString())
                if (code == 200 || code == 201) {
                    val resp = JSONObject(text)
                    val cfg = resp.getJSONObject("config")
                    val iface = cfg.getJSONObject("interface")
                    val addrs = iface.getJSONObject("addresses")
                    val peer = cfg.getJSONArray("peers").getJSONObject(0)
                    var ep = peer.optString("endpoint", DEFAULT_ENDPOINT)
                    if (ep.isBlank() || ep.endsWith(":0")) ep = DEFAULT_ENDPOINT
                    val peerPub = peer.optString("public_key", PEER_FALLBACK_KEY)
                    return WarpReg(
                        v4 = addrs.getString("v4"),
                        v6 = addrs.optString("v6", ""),
                        peerPub = peerPub,
                        endpoint = ep,
                        privKey = kp.privateKey.toBase64()
                    )
                }
                lastErr = "HTTP $code " + text.take(120)
            } catch (e: Exception) {
                lastErr = e.message ?: e.javaClass.simpleName
            }
        }
        throw RuntimeException(lastErr)
    }

    private fun buildConfigText(r: WarpReg): String {
        val sb = StringBuilder()
        sb.append("[Interface]\n")
        sb.append("PrivateKey = ").append(r.privKey).append('\n')
        sb.append("Address = ").append(r.v4.trim()).append("/32\n")
        if (r.v6.isNotBlank()) sb.append("Address = ").append(r.v6.trim()).append("/128\n")
        sb.append("DNS = 1.1.1.1, 1.0.0.1\n")
        sb.append("MTU = 1280\n")
        sb.append("\n[Peer]\n")
        sb.append("PublicKey = ").append(r.peerPub).append('\n')
        sb.append("AllowedIPs = 0.0.0.0/0\n")
        sb.append("AllowedIPs = ::/0\n")
        sb.append("Endpoint = ").append(r.endpoint).append('\n')
        sb.append("PersistentKeepalive = 25\n")
        return sb.toString()
    }

    private fun post(urlStr: String, body: String): Pair<Int, String> {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 20000
        conn.readTimeout = 20000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("User-Agent", UA)
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        conn.disconnect()
        return Pair(code, text)
    }

    private fun randomToken(len: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val rnd = SecureRandom()
        val sb = StringBuilder(len)
        repeat(len) { sb.append(chars[rnd.nextInt(chars.length)]) }
        return sb.toString()
    }

    private fun isoNow(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }
}
