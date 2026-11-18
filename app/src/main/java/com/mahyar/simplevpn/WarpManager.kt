package com.mahyar.simplevpn

import android.content.Context
import com.wireguard.config.Config
import com.wireguard.crypto.KeyPair
import org.json.JSONArray
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
 * Provides the WireGuard configuration for the WARP tunnel.
 *
 * v1.4 credential policy (fixes "nothing ever connects"):
 * - A credential set is ONLY persisted after a real WireGuard handshake has
 *   been observed on the device ("verified" cache). v1.2/v1.3 cached every
 *   registration up front — including dead embedded-pool credentials — so
 *   one bad config poisoned every future connect attempt.
 * - Every connect that cannot use the verified cache registers a brand-new
 *   anonymous WARP account and takes the peer public key straight from the
 *   API response instead of trusting a hard-coded fallback.
 * - The embedded pool is a last resort for networks where the registration
 *   API itself is unreachable.
 */
object WarpManager {

    private const val PREFS = "warp_prefs"
    /** Unverified cache written by v1.2/v1.3 — abandoned, cleared on reset. */
    private const val KEY_LEGACY_CONFIG = "wg_config"
    private const val KEY_VERIFIED_CFG = "verified_config"
    private const val KEY_VERIFIED_EP = "verified_endpoint"
    private const val KEY_POOL_IDX = "pool_idx"
    private const val UA = "1.1.1.1/6.26"
    private val API_VERSIONS = listOf("v0i1707", "v0a2158", "v0a448")
    private const val PEER_FALLBACK_KEY = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
    private const val TIMEOUT_MS = 6000

    private val prefsLock = Any()

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun parse(text: String): Config =
        Config.parse(BufferedReader(StringReader(text)))

    /** Config text + endpoint proven by a real handshake on this device, or null. */
    fun getVerified(ctx: Context): Pair<String, String>? {
        val text = prefs(ctx).getString(KEY_VERIFIED_CFG, null) ?: return null
        val ep = prefs(ctx).getString(KEY_VERIFIED_EP, null) ?: return null
        return Pair(text, ep)
    }

    fun markVerified(ctx: Context, configText: String, endpoint: String) {
        prefs(ctx).edit()
            .putString(KEY_VERIFIED_CFG, configText)
            .putString(KEY_VERIFIED_EP, endpoint)
            .apply()
    }

    fun clearVerified(ctx: Context) {
        prefs(ctx).edit()
            .remove(KEY_VERIFIED_CFG)
            .remove(KEY_VERIFIED_EP)
            .apply()
    }

    /** Drops every stored credential (verified cache + legacy v1.2 cache). */
    fun reset(ctx: Context) {
        prefs(ctx).edit()
            .remove(KEY_VERIFIED_CFG)
            .remove(KEY_VERIFIED_EP)
            .remove(KEY_LEGACY_CONFIG)
            .remove(KEY_POOL_IDX)
            .apply()
    }

    /**
     * Registers a brand-new anonymous WARP account and builds its config.
     * Never persisted here — the controller stores it only after a verified
     * handshake. Throws with the last API error when all versions fail.
     */
    fun freshConfig(progress: (Int, Int) -> Unit = { _, _ -> }): Config {
        val reg = register(progress)
        return parse(buildConfigText(reg.v4, reg.v6, reg.peerPub, reg.privKey))
    }

    /**
     * Last-resort config from credentials embedded in the APK, used only when
     * the registration API is unreachable from the user's network.
     */
    fun poolConfig(ctx: Context): Config {
        val e = pickPoolEntry(ctx)
            ?: throw RuntimeException("no embedded credentials available")
        return parse(buildConfigText(e.v4, e.v6, e.peerPub, e.privKey))
    }

    /** Rebuilds the config with a different Endpoint (used for route rotation). */
    fun withEndpoint(cfg: Config, endpoint: String): Config =
        parse(withEndpointText(cfg, endpoint))

    /** Same as [withEndpoint] but returns the config text (for the verified cache). */
    fun withEndpointText(cfg: Config, endpoint: String): String {
        val iface = cfg.getInterface()
        val peer = cfg.getPeers()[0]
        val sb = StringBuilder()
        sb.append("[Interface]\n")
        sb.append("PrivateKey = ")
            .append(iface.getKeyPair().getPrivateKey().toBase64()).append('\n')
        for (a in iface.getAddresses()) {
            sb.append("Address = ").append(a.toString()).append('\n')
        }
        sb.append("DNS = 1.1.1.1, 1.0.0.1\n")
        sb.append("MTU = 1280\n\n[Peer]\n")
        sb.append("PublicKey = ").append(peer.getPublicKey().toBase64()).append('\n')
        sb.append("AllowedIPs = 0.0.0.0/0, ::/0\n")
        sb.append("Endpoint = ").append(endpoint).append('\n')
        sb.append("PersistentKeepalive = 25\n")
        return sb.toString()
    }

    // ---------- internals ----------

    private data class WarpReg(
        val v4: String,
        val v6: String,
        val peerPub: String,
        val privKey: String
    )

    private fun register(progress: (Int, Int) -> Unit): WarpReg {
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
        for ((i, v) in API_VERSIONS.withIndex()) {
            progress(i + 1, API_VERSIONS.size)
            try {
                val (code, text) = post("https://api.cloudflareclient.com/$v/reg", body.toString())
                if (code == 200 || code == 201) {
                    val resp = JSONObject(text)
                    val cfg = resp.getJSONObject("config")
                    val iface = cfg.getJSONObject("interface")
                    val addrs = iface.getJSONObject("addresses")
                    val peer = cfg.getJSONArray("peers").getJSONObject(0)
                    return WarpReg(
                        v4 = addrs.getString("v4"),
                        v6 = addrs.optString("v6", ""),
                        peerPub = peer.optString("public_key", PEER_FALLBACK_KEY),
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

    /** Round-robin through the embedded credentials so devices spread out. */
    private fun pickPoolEntry(ctx: Context): PoolEntry? {
        synchronized(prefsLock) {
            try {
                val json = ctx.assets.open("warp_pool.json")
                    .bufferedReader().use { it.readText() }
                val arr = JSONArray(json)
                if (arr.length() == 0) return null
                val idx = Math.floorMod(prefs(ctx).getInt(KEY_POOL_IDX, 0), arr.length())
                prefs(ctx).edit().putInt(KEY_POOL_IDX, idx + 1).apply()
                val o = arr.getJSONObject(idx)
                return PoolEntry(
                    o.getString("privKey"),
                    o.getString("v4"),
                    o.optString("v6", ""),
                    o.optString("peerPub", PEER_FALLBACK_KEY)
                )
            } catch (_: Exception) {
                return null
            }
        }
    }

    private data class PoolEntry(
        val privKey: String,
        val v4: String,
        val v6: String,
        val peerPub: String
    )

    fun buildConfigText(v4: String, v6: String, peerPub: String, privKey: String): String {
        val sb = StringBuilder()
        sb.append("[Interface]\n")
        sb.append("PrivateKey = ").append(privKey).append('\n')
        sb.append("Address = ").append(v4.trim()).append("/32\n")
        if (v6.isNotBlank()) sb.append("Address = ").append(v6.trim()).append("/128\n")
        sb.append("DNS = 1.1.1.1, 1.0.0.1\n")
        sb.append("MTU = 1280\n\n[Peer]\n")
        sb.append("PublicKey = ").append(peerPub).append('\n')
        sb.append("AllowedIPs = 0.0.0.0/0, ::/0\n")
        sb.append("Endpoint = engage.cloudflareclient.com:2408\n")
        sb.append("PersistentKeepalive = 25\n")
        return sb.toString()
    }

    private fun post(urlStr: String, body: String): Pair<Int, String> {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
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
