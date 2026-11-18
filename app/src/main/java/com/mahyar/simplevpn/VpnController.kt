package com.mahyar.simplevpn

import android.app.Activity
import android.content.Context
import android.net.TrafficStats
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * App-wide VPN state holder. Exactly one WARP tunnel; every fragment observes
 * the state through [addListener] callbacks posted on the main thread.
 */
object VpnController {

    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    const val TUNNEL_NAME = "SimpleVPN"

    private lateinit var backend: Backend
    private var ready = false

    private val tunnel = object : Tunnel {
        override fun getName(): String = TUNNEL_NAME
        override fun onStateChange(newState: Tunnel.State) {}
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val pingExecutor = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    @Volatile var state: State = State.DISCONNECTED
        private set
    @Volatile var connectEpoch: Long = 0L
        private set
    @Volatile var lastError: String? = null
        private set

    /** Measured regional RTT per node code (null = unreachable). */
    val pingResults = ConcurrentHashMap<String, Int?>()

    /** Selected exit node, persisted. */
    var selectedCode: String = "DE"
        private set

    /** Baseline TrafficStats totals captured when the tunnel came up. */
    private var baseRx = TrafficStats.getTotalRxBytes()
    private var baseTx = TrafficStats.getTotalTxBytes()
    private var lastRx = baseRx
    private var lastTx = baseTx
    private var lastTick = SystemClock.elapsedRealtime()

    private val listeners = mutableListOf<() -> Unit>()

    fun init(appContext: Context) {
        if (ready) return
        backend = GoBackend(appContext)
        val prefs = appContext.getSharedPreferences("vault_prefs", Context.MODE_PRIVATE)
        selectedCode = prefs.getString("selected_code", "DE") ?: "DE"
        if (backend.runningTunnelNames.contains(TUNNEL_NAME)) {
            // Service survived process death: resync state.
            connectEpoch = System.currentTimeMillis()
            state = State.CONNECTED
            baseRx = TrafficStats.getTotalRxBytes()
            baseTx = TrafficStats.getTotalTxBytes()
        }
        ready = true
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private fun notifyChanged() {
        ui.post {
            val snapshot = listeners.toList()
            snapshot.forEach { it() }
        }
    }

    fun persistSelection(ctx: Context) {
        ctx.getSharedPreferences("vault_prefs", Context.MODE_PRIVATE)
            .edit().putString("selected_code", selectedCode).apply()
    }

    fun select(node: Node, ctx: Context) {
        selectedCode = node.code
        persistSelection(ctx)
        notifyChanged()
    }

    // ---------- connection flow ----------

    fun connect(activity: Activity) {
        lastError = null
        if (state == State.CONNECTED) { disconnect(); return }
        state = State.CONNECTING
        notifyChanged()
        val intent = android.net.VpnService.prepare(activity)
        if (intent != null) {
            pendingPermission = true
            activity.startActivityForResult(intent, REQ_VPN)
        } else {
            onVpnPermissionResult(true, activity)
        }
    }

    private var pendingPermission = false
    const val REQ_VPN = 1001

    fun onVpnPermissionResult(granted: Boolean, activity: Activity) {
        if (!pendingPermission) return
        pendingPermission = false
        if (!granted) {
            state = State.DISCONNECTED
            lastError = activity.getString(R.string.perm_denied)
            notifyChanged()
            return
        }
        startTunnel(activity)
    }

    private fun startTunnel(activity: Activity) {
        state = State.CONNECTING
        notifyChanged()
        executor.execute {
            try {
                val cfg = WarpManager.getOrCreateConfig(activity.applicationContext)
                backend.setState(tunnel, Tunnel.State.UP, cfg)
                connectEpoch = System.currentTimeMillis()
                baseRx = TrafficStats.getTotalRxBytes()
                baseTx = TrafficStats.getTotalTxBytes()
                lastRx = baseRx; lastTx = baseTx; lastTick = SystemClock.elapsedRealtime()
                state = State.CONNECTED
            } catch (e: Exception) {
                state = State.DISCONNECTED
                lastError = e.message ?: e.javaClass.simpleName
                ui.post {
                    Toast.makeText(activity, activity.getString(R.string.reg_fail), Toast.LENGTH_LONG).show()
                }
            }
            notifyChanged()
        }
    }

    fun disconnect() {
        executor.execute {
            try {
                backend.setState(tunnel, Tunnel.State.DOWN, null)
            } catch (_: Exception) {
            }
            connectEpoch = 0
            state = State.DISCONNECTED
            notifyChanged()
        }
    }

    fun isConnected(): Boolean = ready && backend.runningTunnelNames.contains(TUNNEL_NAME)

    // ---------- telemetry ----------

    /**
     * One-second sliding-window throughput in Mbps.
     * Returns null when the tunnel is not up.
     */
    fun throughputMbps(): Pair<Double, Double>? {
        if (!isConnected()) return null
        val now = SystemClock.elapsedRealtime()
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        val dt = (now - lastTick) / 1000.0
        if (dt < 0.4 || rx < 0 || tx < 0) return null
        val dRx = ((rx - lastRx).coerceAtLeast(0)) * 8 / dt / 1e6
        val dTx = ((tx - lastTx).coerceAtLeast(0)) * 8 / dt / 1e6
        lastRx = rx; lastTx = tx; lastTick = now
        return Pair(dRx, dTx)
    }

    /** Total MB moved through the tunnel since connect. */
    fun sessionTotalsMb(): Pair<Double, Double> {
        val rx = (TrafficStats.getTotalRxBytes() - baseRx).coerceAtLeast(0)
        val tx = (TrafficStats.getTotalTxBytes() - baseTx).coerceAtLeast(0)
        return Pair(rx / 1e6, tx / 1e6)
    }

    fun sessionSeconds(): Long =
        if (connectEpoch == 0L) 0L else (System.currentTimeMillis() - connectEpoch) / 1000

    // ---------- ping ----------

    fun measurePing(node: Node, done: (Int?) -> Unit) {
        pingExecutor.execute {
            val ms = PingTester.tcpPing(node.pingHost)
            pingResults[node.code] = ms
            ui.post { done(ms) }
        }
    }

    fun measureAllPings(done: () -> Unit) {
        val nodes = NodeCatalog.nodes
        nodes.forEach { n ->
            pingExecutor.execute {
                pingResults[n.code] = PingTester.tcpPing(n.pingHost)
                ui.post { notifyChanged() }
                if (n.code == nodes.last().code) ui.post { done() }
            }
        }
    }
}
