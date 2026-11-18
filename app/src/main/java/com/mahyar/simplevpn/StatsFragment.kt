package com.mahyar.simplevpn

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import java.util.Locale

class StatsFragment : Fragment() {

    private val ui = Handler(Looper.getMainLooper())
    private val controller = VpnController

    private var statusText: TextView? = null
    private var timeText: TextView? = null
    private var downText: TextView? = null
    private var upText: TextView? = null
    private var nodeText: TextView? = null
    private var pingText: TextView? = null

    private val listener = { refresh(); Unit }

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1000L)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_stats, container, false)

    override fun onViewCreated(v: View, savedInstanceState: Bundle?) {
        statusText = v.findViewById(R.id.stats_status)
        timeText = v.findViewById(R.id.stats_time)
        downText = v.findViewById(R.id.stats_down)
        upText = v.findViewById(R.id.stats_up)
        nodeText = v.findViewById(R.id.stats_node)
        pingText = v.findViewById(R.id.stats_ping)
        controller.addListener(listener)
    }

    override fun onResume() {
        super.onResume()
        ui.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(ticker)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        controller.removeListener(listener)
    }

    private fun refresh() {
        if (!isAdded) return
        val live = controller.isConnected()
        statusText?.text = getString(
            if (live) R.string.state_connected else R.string.state_disconnected
        )
        statusText?.setTextColor(
            resources.getColor(if (live) R.color.cyan_glow else R.color.text_dim, requireContext().theme)
        )
        val s = controller.sessionSeconds()
        timeText?.text = String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
        val t = controller.sessionTotalsMb()
        downText?.text = String.format(Locale.US, "%.1f MB", t.first)
        upText?.text = String.format(Locale.US, "%.1f MB", t.second)
        val node = NodeCatalog.byCode(controller.selectedCode)
        nodeText?.text = "${node.city}, ${node.country}"
        val ping = controller.pingResults[node.code]
        pingText?.text = ping?.let { "${it} ms" } ?: getString(R.string.ping_untested)
    }
}
