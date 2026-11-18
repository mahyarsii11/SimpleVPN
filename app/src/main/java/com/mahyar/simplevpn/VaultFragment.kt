package com.mahyar.simplevpn

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import java.util.Locale

class VaultFragment : Fragment() {

    private val ui = Handler(Looper.getMainLooper())
    private val controller = VpnController

    private var statusText: TextView? = null
    private var badgeText: TextView? = null
    private var badgeDot: View? = null
    private var globe: GlobeView? = null
    private var powerBtn: View? = null
    private var powerIcon: ImageView? = null
    private var powerRing: View? = null
    private var timerText: TextView? = null
    private var downText: TextView? = null
    private var upText: TextView? = null
    private var cardFlag: ImageView? = null
    private var cardCity: TextView? = null
    private var cardCode: TextView? = null
    private var cardSub: TextView? = null
    private var cardPing: TextView? = null

    private val listener = {
        refreshAll()
        Unit
    }

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            ui.postDelayed(this, 1000L)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_vault, container, false)

    override fun onViewCreated(v: View, savedInstanceState: Bundle?) {
        statusText = v.findViewById(R.id.status_text)
        badgeText = v.findViewById(R.id.tunnel_badge_text)
        badgeDot = v.findViewById(R.id.tunnel_badge_dot)
        globe = v.findViewById(R.id.globe)
        powerBtn = v.findViewById(R.id.power_button)
        powerIcon = v.findViewById(R.id.power_icon)
        powerRing = v.findViewById(R.id.power_ring)
        timerText = v.findViewById(R.id.session_timer)
        downText = v.findViewById(R.id.metric_down)
        upText = v.findViewById(R.id.metric_up)
        cardFlag = v.findViewById(R.id.card_flag)
        cardCity = v.findViewById(R.id.card_city)
        cardCode = v.findViewById(R.id.card_code)
        cardSub = v.findViewById(R.id.card_sub)
        cardPing = v.findViewById(R.id.card_ping)

        powerBtn?.setOnClickListener {
            val act = activity ?: return@setOnClickListener
            when (controller.state) {
                VpnController.State.DISCONNECTED -> controller.connect(act)
                VpnController.State.CONNECTING -> controller.disconnect()
                VpnController.State.CONNECTED -> controller.disconnect()
            }
        }

        v.findViewById<View>(R.id.server_card).setOnClickListener {
            (activity as? MainActivity)?.showTab(1)
        }

        refreshAll()
        controller.addListener(listener)
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
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

    private fun refreshAll() {
        val st = controller.state
        val live = st == VpnController.State.CONNECTED

        globe?.isConnected = live
        powerRing?.alpha = if (live) 1f else 0.45f
        powerIcon?.setColorFilter(
            resources.getColor(
                if (live) R.color.cyan_glow else R.color.text_dim,
                requireContext().theme
            )
        )

        when (st) {
            VpnController.State.CONNECTED -> {
                statusText?.text = getString(R.string.state_connected)
                statusText?.setTextColor(resources.getColor(R.color.cyan_glow, requireContext().theme))
                badgeText?.text = getString(R.string.badge_active)
                badgeDot?.setBackgroundResource(R.drawable.dot_cyan)
            }
            VpnController.State.CONNECTING -> {
                val detail = controller.statusDetail
                statusText?.text = detail ?: getString(R.string.state_connecting)
                statusText?.setTextColor(resources.getColor(R.color.amber, requireContext().theme))
                badgeText?.text = getString(R.string.badge_handshake)
                badgeDot?.setBackgroundResource(R.drawable.dot_amber)
            }
            VpnController.State.DISCONNECTED -> {
                if (controller.lastError != null) {
                    statusText?.text = getString(R.string.state_failed)
                    statusText?.setTextColor(resources.getColor(R.color.amber, requireContext().theme))
                } else {
                    statusText?.text = getString(R.string.state_disconnected)
                    statusText?.setTextColor(resources.getColor(R.color.text_dim, requireContext().theme))
                }
                badgeText?.text = getString(R.string.badge_offline)
                badgeDot?.setBackgroundResource(R.drawable.dot_gray)
            }
        }

        val node = NodeCatalog.byCode(controller.selectedCode)
        cardFlag?.setImageResource(node.flag)
        cardCity?.text = node.city
        cardCode?.text = node.code
        cardSub?.text = getString(R.string.card_sub, node.country)
        val ping = controller.pingResults[node.code]
        cardPing?.text = ping?.let { "${it} ms" } ?: getString(R.string.ping_untested)
    }

    private fun tick() {
        if (!isAdded) return
        val live = controller.isConnected()
        if (live) {
            val s = controller.sessionSeconds()
            timerText?.text = String.format(
                Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60
            )
            val t = controller.throughputMbps()
            if (t != null) {
                downText?.text = String.format(Locale.US, "%.1f", t.first)
                upText?.text = String.format(Locale.US, "%.1f", t.second)
            }
        } else {
            timerText?.text = "00:00:00"
            downText?.text = getString(R.string.metric_idle)
            upText?.text = getString(R.string.metric_idle)
        }
    }
}
