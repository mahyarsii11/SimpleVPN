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
import com.wireguard.crypto.Key
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * App-wide VPN state holder. Exactly one WARP tunnel; every fragment observes
 * the state through [addListener] callbacks posted on the main thread.
 *
 * Connection strategy: register once (live API → embedded fallback), then walk
 * a list of WARP endpoints until a real WireGuard handshake completes, so
 * "CONNECTED" only ever means the tunnel actually passes traffic. Cancelling
 * is possible at every step and disconnect runs on its own executor so it is
 * never queued behind connect work.
 */
object VpnController {

    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    const val TUNNEL_NAME = "SimpleVPN"

    /**
     * WARP endpoints tried in order. Anycast IPs first (no DNS lookup), then
     * the hostname plus alternate UDP ports for networks where the default
     * port 2408 is throttled or blocked.
     */
    private val ENDPOINTS = listOf(
        "162.159.192.1:2408",
        "engage.cloudflareclient.com:2408",
        "162.159.192.1:500",
        "162.159.192.1:854",
        "162.159.192.1:8542",
        "162.159.192.1:4500",
        "162.159.192.1:1701",
        "162.159.193.10:2408",
        "engage.cloudflareclient.com:500",
        "engage.cloudflareclient.com:854"
    )

    private const val HANDSHAKE_WAIT_MS = 6000L
    private const val PREFS_VAULT = "vault_prefs"
    private const val KEY_ENDPOINT = "working_endpoint"

    private lateinit var backend: Backend
    private var ready = false

    private val tunnel = object : Tunnel {
        override fun getName(): String = TUNNEL_NAME
        override fun onStateChange(newState: Tunnel.State) {}
    }

    private val connectExecutor = Executors.newSingleThreadExecutor()
    private val disconnectExecutor = Executors.newSingleThreadExecutor()
    private val pingExecutor = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    @Volatile var state: State = State.DISCONNECTED
        private set
    @Volatile var connectEpoch: Long = 0L
        private set
    @Volatile var lastError: String? = null
        private set

    /** Short progress line shown while connecting (e.g. "ROUTE 2/10"). */
    @Volatile var statusDetail: String? = null
        private set

    @Volatile private var cancelRequested = false

    /** Bumped on every new connect and on disconnect; stale runs abort silently. */
    @Volatile private var runId = 0L
    @Volatile private var pendingPermissionRun = -1L

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
        val prefs = appContext.getSharedPreferences(PREFS_VAULT, Context.MODE_PRIVATE)
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

    private fun setDetail(s: String?) {
        statusDetail = s
        notifyChanged()
    }

    fun persistSelection(ctx: Context) {
        ctx.getSharedPreferences(PREFS_VAULT, Context.MODE_PRIVATE)
            .edit().putString("selected_code", selectedCode).apply()
    }

    fun select(node: Node, ctx: Context) {
        selectedCode = node.code
        persistSelection(ctx)
        notifyChanged()
    }

    // ---------- connection flow ----------

    fun connect(activity: Activity) {
        if (state == State.CONNECTED) { disconnect(); return }
        lastError = null
        cancelRequested = false
        val myRun = ++runId
        pendingPermissionRun = myRun
        state = State.CONNECTING
        statusDetail = null
        notifyChanged()
        val intent = android.net.VpnService.prepare(activity)
        if (intent != null) {
            activity.startActivityForResult(intent, REQ_VPN)
        } else {
            pendingPermissionRun = -1L
            startTunnel(activity, myRun)
        }
    }

    const val REQ_VPN = 1001

    fun onVpnPermissionResult(granted: Boolean, activity: Activity) {
        val myRun = pendingPermissionRun
        if (myRun < 0) return
        pendingPermissionRun = -1L
        if (!granted) {
            if (myRun != runId) return
            state = State.DISCONNECTED
            lastError = activity.getString(R.string.perm_denied)
            notifyChanged()
            return
        }
        if (myRun == runId) startTunnel(activity, myRun)
    }

    private fun startTunnel(activity: Activity, myRun: Long) {
        state = State.CONNECTING
        notifyChanged()
        val appCtx = activity.applicationContext
        connectExecutor.execute { establish(appCtx, myRun) }
    }

    /**
     * Loads or creates credentials, then walks the endpoint list until a
     * WireGuard handshake completes. Cancellable at every step.
     */
    private fun establish(appCtx: Context, myRun: Long) {
        fun stale() = myRun != runId || cancelRequested

        // 1) credentials: cached → live registration → embedded pool
        val cfg: Config = try {
            if (stale()) return
            setDetail(appCtx.getString(R.string.detail_registering))
            WarpManager.getOrCreateConfig(appCtx) { a, t ->
                if (!stale()) setDetail(appCtx.getString(R.string.detail_reg, a, t))
            }
        } catch (e: Exception) {
            if (!stale()) fail(appCtx, e.message ?: e.javaClass.simpleName)
            return
        }
        if (stale()) { safeDown(); return }

        // 2) endpoint rotation with handshake verification
        val prefs = appCtx.getSharedPreferences(PREFS_VAULT, Context.MODE_PRIVATE)
        val savedEp = prefs.getString(KEY_ENDPOINT, null)
        val list = (listOfNotNull(savedEp) + ENDPOINTS).distinct()
        val pubKey = cfg.getPeers()[0].getPublicKey()

        for ((i, ep) in list.withIndex()) {
            if (stale()) { safeDown(); return }
            setDetail(appCtx.getString(R.string.detail_route, i + 1, list.size))
            try {
                backend.setState(tunnel, Tunnel.State.DOWN, null)
                backend.setState(tunnel, Tunnel.State.UP, WarpManager.withEndpoint(cfg, ep))
            } catch (_: Exception) {
                continue // unreachable endpoint / DNS failure / service problem
            }
            if (verifyHandshake(pubKey)) {
                if (stale()) { safeDown(); return }
                prefs.edit().putString(KEY_ENDPOINT, ep).apply()
                connectEpoch = System.currentTimeMillis()
                baseRx = TrafficStats.getTotalRxBytes()
                baseTx = TrafficStats.getTotalTxBytes()
                lastRx = baseRx; lastTx = baseTx
                lastTick = SystemClock.elapsedRealtime()
                state = State.CONNECTED
                statusDetail = null
                notifyChanged()
                return
            }
            safeDown()
        }
        if (!stale()) fail(appCtx, appCtx.getString(R.string.handshake_fail))
    }

    /** Waits up to [HANDSHAKE_WAIT_MS] for the first handshake or RX bytes. */
    private fun verifyHandshake(pubKey: Key): Boolean {
        val deadline = SystemClock.elapsedRealtime() + HANDSHAKE_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (cancelRequested) return false
            try {
                val ps = backend.getStatistics(tunnel).peer(pubKey)
                if (ps != null &&
                    (ps.latestHandshakeEpochMillis() > 0L || ps.rxBytes() > 0L)
                ) return true
            } catch (_: Exception) {
            }
            try {
                Thread.sleep(700)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }

    private fun fail(appCtx: Context, msg: String) {
        safeDown()
        state = State.DISCONNECTED
        lastError = msg
        statusDetail = null
        notifyChanged()
        ui.post {
            Toast.makeText(appCtx, appCtx.getString(R.string.reg_fail), Toast.LENGTH_LONG).show()
        }
    }

    private fun safeDown() {
        try {
            backend.setState(tunnel, Tunnel.State.DOWN, null)
        } catch (_: Exception) {
        }
    }

    /**
     * Always available and instant: runs on a dedicated executor so it is
     * never queued behind connect work, and cancels any active attempt.
     */
    fun disconnect() {
        cancelRequested = true
        runId++
        pendingPermissionRun = -1L
        state = State.DISCONNECTED
        connectEpoch = 0
        statusDetail = null
        notifyChanged()
        disconnectExecutor.execute { safeDown() }
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
