package com.mahyar.simplevpn

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TUNNEL_NAME = "SimpleVPN"
        private const val REQ_VPN = 1001
    }

    private lateinit var backend: Backend
    private var tunnel: WarpTunnel? = null
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var status: TextView
    private lateinit var btnWarp: Button
    private lateinit var btnRefresh: Button
    private lateinit var list: ListView
    private lateinit var adapter: ArrayAdapter<String>

    private var countries: List<VpnGate.Country> = emptyList()
    private var lastServers: List<VpnGate.Server> = emptyList()

    private class WarpTunnel(private val tunnelName: String) : Tunnel {
        override fun getName(): String = tunnelName
        override fun onStateChange(newState: Tunnel.State) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        backend = GoBackend(applicationContext)

        status = findViewById(R.id.status)
        btnWarp = findViewById(R.id.btn_warp)
        btnRefresh = findViewById(R.id.btn_refresh)
        list = findViewById(R.id.country_list)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList())
        list.adapter = adapter

        btnWarp.setOnClickListener { onWarpClicked() }
        btnRefresh.setOnClickListener { refreshCountries() }
        list.setOnItemClickListener { _, _, position, _ ->
            if (position in countries.indices) exportCountry(countries[position])
        }

        updateStatus()
        pollStatus()
    }

    // ---------- WARP mode ----------

    private fun onWarpClicked() {
        val connected = backend.runningTunnelNames.contains(TUNNEL_NAME)
        if (connected) {
            status.text = getString(R.string.status_connecting)
            executor.execute {
                try {
                    val t = tunnel
                    if (t != null) backend.setState(t, Tunnel.State.DOWN, null)
                } catch (_: Exception) {
                }
                ui.post { updateStatus() }
            }
        } else {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                startActivityForResult(intent, REQ_VPN)
            } else {
                startWarp()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN) {
            if (resultCode == Activity.RESULT_OK) startWarp()
            else status.text = getString(R.string.perm_denied)
        }
    }

    private fun startWarp() {
        status.text = getString(R.string.status_connecting)
        executor.execute {
            try {
                val cfg = WarpManager.getOrCreateConfig(applicationContext)
                val t = WarpTunnel(TUNNEL_NAME)
                tunnel = t
                backend.setState(t, Tunnel.State.UP, cfg)
                ui.post { updateStatus() }
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                ui.post {
                    status.text = getString(R.string.err_prefix) + msg
                    Toast.makeText(this, getString(R.string.reg_fail), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun pollStatus() {
        ui.postDelayed({
            updateStatus()
            pollStatus()
        }, 2000)
    }

    private fun updateStatus() {
        val connected = backend.runningTunnelNames.contains(TUNNEL_NAME)
        if (connected) {
            status.text = getString(R.string.status_on)
            btnWarp.text = getString(R.string.warp_disconnect)
        } else {
            status.text = getString(R.string.status_off)
            btnWarp.text = getString(R.string.warp_connect)
        }
    }

    // ---------- VPN Gate country mode ----------

    private fun refreshCountries() {
        btnRefresh.isEnabled = false
        status.text = getString(R.string.loading)
        executor.execute {
            try {
                lastServers = VpnGate.fetchServers()
                countries = VpnGate.countries(lastServers)
                ui.post {
                    adapter.clear()
                    for (c in countries) {
                        adapter.add("${c.name} (${c.code}) - ${c.count}")
                    }
                    adapter.notifyDataSetChanged()
                    status.text = getString(R.string.list_loaded)
                }
            } catch (e: Exception) {
                ui.post {
                    status.text = getString(R.string.err_prefix) + (e.message ?: "?")
                    Toast.makeText(this, getString(R.string.list_fail), Toast.LENGTH_LONG).show()
                }
            }
            ui.post { btnRefresh.isEnabled = true }
        }
    }

    private fun exportCountry(country: VpnGate.Country) {
        status.text = getString(R.string.loading)
        executor.execute {
            try {
                val server = VpnGate.bestServer(lastServers, country.name)
                    ?: VpnGate.bestServer(VpnGate.fetchServers(), country.name)
                    ?: throw RuntimeException("no server for " + country.name)
                val f = VpnGate.writeOvpn(applicationContext, server)
                ui.post { openOvpn(f) }
            } catch (e: Exception) {
                ui.post {
                    status.text = getString(R.string.err_prefix) + (e.message ?: "?")
                    Toast.makeText(this, getString(R.string.list_fail), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun openOvpn(file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            this, packageName + ".fileprovider", file
        )
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/x-openvpn")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(view, getString(R.string.ovpn_ready)))
        } catch (_: Exception) {
            val send = Intent(Intent.ACTION_SEND)
                .setType("application/octet-stream")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                startActivity(Intent.createChooser(send, getString(R.string.ovpn_ready)))
            } catch (_: Exception) {
                Toast.makeText(this, getString(R.string.no_ovpn_app), Toast.LENGTH_LONG).show()
            }
        }
        status.text = getString(R.string.ovpn_ready)
    }
}
