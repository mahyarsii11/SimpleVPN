package com.mahyar.simplevpn

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment

class ConfigFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_config, container, false)

    override fun onViewCreated(v: View, savedInstanceState: Bundle?) {
        v.findViewById<TextView>(R.id.cfg_version).text =
            getString(R.string.cfg_version_value, BuildConfig.VERSION_NAME)

        v.findViewById<View>(R.id.cfg_reset).setOnClickListener {
            val act = activity ?: return@setOnClickListener
            VpnController.disconnect()
            WarpManager.reset(act.applicationContext)
            Toast.makeText(act, R.string.cfg_reset_done, Toast.LENGTH_LONG).show()
        }

        v.findViewById<View>(R.id.cfg_alwayson).setOnClickListener {
            try {
                startActivity(
                    android.content.Intent("android.settings.VPN_SETTINGS")
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
                Toast.makeText(act0(), R.string.cfg_settings_missing, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun act0(): android.content.Context = requireContext().applicationContext
}
