package com.mahyar.simplevpn

import android.os.SystemClock
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Measures real TCP-connect RTT against public reference servers that are
 * physically located in each supported country.
 */
object PingTester {

    /** @return round-trip time in ms, or null if the host is unreachable. */
    fun tcpPing(host: String, timeoutMs: Int = 2000): Int? {
        return try {
            val socket = Socket()
            val start = SystemClock.elapsedRealtime()
            socket.connect(InetSocketAddress(host, 443), timeoutMs)
            val ms = (SystemClock.elapsedRealtime() - start).toInt()
            try { socket.close() } catch (_: Exception) {}
            ms
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: Exception) {
            null
        }
    }
}
