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
 * Connection strategy (v1.4) — short, bounded, honest:
 * 1. fast path: the identity + endpoint proven by a real handshake before,
 * 2. a brand-new on-device WARP registration, then up to 8 endpoints
 *    (3.5 s handshake window each),
 * 3. embedded-pool credentials when the registration API is unreachable.
 *
 * CONNECTED is only ever shown after a real WireGuard handshake, progress is
 * a calm percentage (no "ROUTE 7/28" marathon), the whole attempt is bounded
 * to about one minute, and disconnect cancels everything instantly.
 */
object VpnController {

    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    const val TUNNEL_NAME = "SimpleVPN"

    /**
     * Short, prioritized WARP endpoint list for fast sweeps: core anycast and
     * the optimized 188.114.x ranges on the default UDP port first (these look
     * like ordinary CDN IPs and are rarely throttled), then alternate WARP
     * UDP ports, hostname last (needs DNS). The handshake-proven endpoint is
     * always tried before this list.
     */
    private val SWEEP_ROUTES = listOf(
        "162.159.192.1:2408",
        "188.114.96.1:2408",
        "162.159.193.1:2408",
        "188.114.97.1:2408",
        "162.159.192.1:500",
        "162.159.192.1:4500",
        "162.159.192.1:1701",
        "engage.cloudflareclient.com:2408"
    )

    private const val FAST_WAIT_MS = 5000L
    private const val ROUTE_WAIT_MS = 3500L
    private const val POOL_ROUTES = 6

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
    /** Technical reason the last attempt failed (for diagnostics). */
    @Volatile var lastRouteError: String? = null
        private set

    /** Short progress line shown while connecting (e.g. "ESTABLISHING… 42%"). */
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
        lastRouteError = null
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
     * Three bounded phases; every phase ends in either CONNECTED or moves on.
     * Nothing is cached unless a handshake really happened.
     */
    private fun establish(appCtx: Context, myRun: Long) {
        fun stale() = myRun != runId || cancelRequested
        fun pct(p: Int) {
            if (!stale()) setDetail(appCtx.getString(R.string.detail_pct, p))
        }
        if (stale()) return

        // Phase A — handshake-proven identity + endpoint from a previous connect
        val verified = WarpManager.getVerified(appCtx)
        if (verified != null) {
            val cfg = try {
                WarpManager.parse(verified.first)
            } catch (_: Exception) {
                WarpManager.clearVerified(appCtx)
                null
            }
            if (cfg != null) {
                val ok = sweep(appCtx, myRun, cfg,
                    listOf(verified.second), 2, 6, FAST_WAIT_MS)
                if (ok) return
                if (stale()) { safeDown(); return }
                // That combo is dead on this network now — never trust it again.
                WarpManager.clearVerified(appCtx)
            }
        }

        // Phase B — brand-new anonymous identity from the WARP registration API
        pct(8)
        var regCfg: Config? = null
        try {
            regCfg = WarpManager.freshConfig { a, t -> pct(8 + 2 * a) }
        } catch (e: Exception) {
            lastRouteError = "register: " + (e.message ?: e.javaClass.simpleName)
        }
        if (regCfg != null) {
            val ok = sweep(appCtx, myRun, regCfg, SWEEP_ROUTES, 14, 70, ROUTE_WAIT_MS)
            if (ok) return
            if (stale()) { safeDown(); return }
        }

        // Phase C — embedded pool credentials (registration API unreachable)
        pct(74)
        try {
            val pool = WarpManager.poolConfig(appCtx)
            val ok = sweep(appCtx, myRun, pool,
                SWEEP_ROUTES.take(POOL_ROUTES), 74, 92, ROUTE_WAIT_MS)
            if (ok) return
            if (stale()) { safeDown(); return }
        } catch (e: Exception) {
            lastRouteError = (lastRouteError ?: "") + " | pool: " +
                (e.message ?: e.javaClass.simpleName)
        }

        if (!stale()) fail(appCtx, appCtx.getString(R.string.handshake_fail))
    }

    /**
     * Tries each endpoint with the same credentials, showing a percentage
     * between [lo] and [hi]. Returns true when the tunnel reached CONNECTED.
     */
    private fun sweep(
        appCtx: Context,
        myRun: Long,
        cfg: Config,
        routes: List<String>,
        lo: Int,
        hi: Int,
        waitMs: Long
    ): Boolean {
        fun stale() = myRun != runId || cancelRequested
        val pubKey = cfg.getPeers()[0].getPublicKey()
        for ((i, ep) in routes.withIndex()) {
            if (stale()) return false
            setDetail(appCtx.getString(R.string.detail_pct,
                lo + (hi - lo) * i / routes.size))
            val text = WarpManager.withEndpointText(cfg, ep)
            try {
                backend.setState(tunnel, Tunnel.State.DOWN, null)
                backend.setState(tunnel, Tunnel.State.UP, WarpManager.parse(text))
            } catch (e: Exception) {
                lastRouteError = "[$ep] " + e.javaClass.simpleName +
                    (e.message?.let { m -> ": ${m.take(140)}" } ?: "")
                continue // unreachable endpoint / DNS failure / service problem
            }
            if (verifyHandshake(pubKey, waitMs)) {
                if (stale()) { safeDown(); return false }
                onConnected(appCtx, text, ep)
                return true
            }
            lastRouteError = "[$ep] no handshake in ${waitMs / 1000}s"
            safeDown()
        }
        return false
    }

    private fun onConnected(appCtx: Context, configText: String, endpoint: String) {
        // Remember the proven combo for an instant reconnect next time.
        WarpManager.markVerified(appCtx, configText, endpoint)
        appCtx.getSharedPreferences(PREFS_VAULT, Context.MODE_PRIVATE)
            .edit().putString(KEY_ENDPOINT, endpoint).apply()
        connectEpoch = System.currentTimeMillis()
        baseRx = TrafficStats.getTotalRxBytes()
        baseTx = TrafficStats.getTotalTxBytes()
        lastRx = baseRx; lastTx = baseTx
        lastTick = SystemClock.elapsedRealtime()
        state = State.CONNECTED
        statusDetail = null
        notifyChanged()
    }

    /** Waits up to [waitMs] for the first handshake or RX bytes on the tunnel. */
    private fun verifyHandshake(pubKey: Key, waitMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + waitMs
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
                Thread.sleep(400)
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
            val detail = lastRouteError
            val text = if (detail != null) "${appCtx.getString(R.string.reg_fail)}\n\n$detail"
                       else appCtx.getString(R.string.reg_fail)
            Toast.makeText(appCtx, text, Toast.LENGTH_LONG).show()
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
