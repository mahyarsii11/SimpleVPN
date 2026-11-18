package com.mahyar.simplevpn

import android.content.Context
import android.util.Base64
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the live public VPN Gate server list (CSV) from multiple mirrors,
 * aggregates servers by country and exports ready-to-import .ovpn files.
 */
object VpnGate {

    data class Server(
        val host: String,
        val ip: String,
        val score: Long,
        val ping: Int,
        val speed: Long,
        val countryLong: String,
        val countryShort: String,
        val ovpnB64: String
    )

    data class Country(
        val name: String,
        val code: String,
        val count: Int,
        val bestScore: Long
    )

    private val SOURCES = listOf(
        "https://rainsstory.github.io/vpngate-mirror/api/iphone/",
        "https://raw.githubusercontent.com/AbolDev/vpngate-mirror/main/vpngate.csv",
        "https://www.vpngate.net/api/iphone/"
    )

    /** Downloads the CSV from the first working mirror. */
    fun fetchServers(): List<Server> {
        var lastErr = "unknown"
        for (src in SOURCES) {
            try {
                val text = httpGet(src)
                val servers = parse(text)
                if (servers.isNotEmpty()) return servers
                lastErr = "empty list"
            } catch (e: Exception) {
                lastErr = e.message ?: e.javaClass.simpleName
            }
        }
        throw RuntimeException(lastErr)
    }

    /** Robust CSV parsing: Message/Operator fields may contain commas, base64 never does. */
    fun parse(text: String): List<Server> {
        val out = ArrayList<Server>()
        for (line in text.split('\n')) {
            val l = line.trim()
            if (l.isEmpty() || l.startsWith("*") || l.startsWith("#")) continue
            val cols = l.split(',')
            if (cols.size < 15) continue
            val ovpn = cols[cols.size - 1]
            if (ovpn.isBlank()) continue
            try {
                out.add(
                    Server(
                        host = cols[0],
                        ip = cols[1],
                        score = cols[2].toLongOrNull() ?: 0L,
                        ping = cols[3].toIntOrNull() ?: 9999,
                        speed = cols[4].toLongOrNull() ?: 0L,
                        countryLong = cols[5],
                        countryShort = cols[6],
                        ovpnB64 = ovpn
                    )
                )
            } catch (_: Exception) {
            }
        }
        return out
    }

    fun countries(servers: List<Server>): List<Country> {
        val map = LinkedHashMap<String, Country>()
        for (s in servers) {
            if (s.ovpnB64.isBlank()) continue
            val cur = map[s.countryLong]
            if (cur == null) {
                map[s.countryLong] = Country(s.countryLong, s.countryShort, 1, s.score)
            } else {
                map[s.countryLong] = Country(
                    cur.name, cur.code, cur.count + 1, maxOf(cur.bestScore, s.score)
                )
            }
        }
        return map.values.sortedByDescending { it.bestScore }
    }

    fun bestServer(servers: List<Server>, country: String): Server? =
        servers.filter { it.countryLong == country && it.ovpnB64.isNotBlank() }
            .maxByOrNull { it.score }

    /** Decodes the embedded base64 OpenVPN config into an .ovpn file. */
    fun writeOvpn(ctx: Context, s: Server): File {
        val dir = File(ctx.cacheDir, "ovpn")
        dir.mkdirs()
        val safeHost = s.host.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val f = File(dir, "vpngate_${s.countryShort}_$safeHost.ovpn")
        val decoded = Base64.decode(s.ovpnB64, Base64.DEFAULT)
        f.writeText(String(decoded, Charsets.UTF_8))
        return f
    }

    private fun httpGet(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 25000
        conn.readTimeout = 30000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; SimpleVPN)")
        conn.instanceFollowRedirects = true
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) throw RuntimeException("HTTP $code")
        return text
    }
}
