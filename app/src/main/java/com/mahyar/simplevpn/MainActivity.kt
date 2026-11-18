package com.mahyar.simplevpn

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Hosts the four VAULT screens and the frosted bottom navigation bar.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusPillText: TextView
    private lateinit var statusPillDot: android.view.View

    private var currentTab = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VpnController.init(applicationContext)
        setContentView(R.layout.activity_main)

        statusPillText = findViewById(R.id.header_pill_text)
        statusPillDot = findViewById(R.id.header_pill_dot)

        if (savedInstanceState == null) {
            val fm = supportFragmentManager.beginTransaction()
            for (i in 0 until TAB_COUNT) fm.add(R.id.fragment_container, newFragment(i), tagFor(i))
            fm.commitNow()
        }
        showTab(currentTab)

        findViewById<android.view.View>(R.id.nav_vault).setOnClickListener { showTab(0) }
        findViewById<android.view.View>(R.id.nav_nodes).setOnClickListener { showTab(1) }
        findViewById<android.view.View>(R.id.nav_stats).setOnClickListener { showTab(2) }
        findViewById<android.view.View>(R.id.nav_config).setOnClickListener { showTab(3) }
    }

    private fun newFragment(i: Int): androidx.fragment.app.Fragment = when (i) {
        0 -> VaultFragment()
        1 -> NodesFragment()
        2 -> StatsFragment()
        else -> ConfigFragment()
    }

    private fun tagFor(i: Int) = "tab_$i"

    fun showTab(index: Int) {
        currentTab = index
        val tx = supportFragmentManager.beginTransaction()
        for (i in 0 until TAB_COUNT) {
            val f = supportFragmentManager.findFragmentByTag(tagFor(i))
            if (f != null) {
                if (i == index) tx.show(f) else tx.hide(f)
            }
        }
        tx.commitAllowingStateLoss()

        // nav highlight
        val ids = intArrayOf(R.id.nav_vault, R.id.nav_nodes, R.id.nav_stats, R.id.nav_config)
        val activeColor = ContextCompat.getColor(this, R.color.cyan_glow)
        val idleColor = ContextCompat.getColor(this, R.color.text_dim)
        ids.forEachIndexed { i, id ->
            val v = findViewById<android.view.View>(id)
            val icon = v.findViewById<android.widget.ImageView>(android.R.id.icon)
            val label = v.findViewById<TextView>(android.R.id.text1)
            val color = if (i == index) activeColor else idleColor
            icon.setColorFilter(color)
            label.setTextColor(color)
            icon.alpha = if (i == index) 1f else 0.75f
        }

        // header pill
        if (index == 0) {
            statusPillText.text = getString(R.string.pill_protected)
            statusPillDot.setBackgroundResource(R.drawable.dot_cyan)
        } else {
            statusPillText.text = getString(R.string.pill_secure)
            statusPillDot.setBackgroundResource(R.drawable.dot_green)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VpnController.REQ_VPN) {
            VpnController.onVpnPermissionResult(resultCode == RESULT_OK, this)
        }
    }

    companion object {
        private const val TAB_COUNT = 4
    }
}
