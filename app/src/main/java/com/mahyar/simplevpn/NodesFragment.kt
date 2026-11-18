package com.mahyar.simplevpn

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment

class NodesFragment : Fragment() {

    private val controller = VpnController
    private lateinit var adapter: NodeAdapter
    private lateinit var listView: android.widget.ListView
    private var filter: String = ""

    private val listener = {
        adapter.notifyDataSetChanged()
        Unit
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_nodes, container, false)

    override fun onViewCreated(v: View, savedInstanceState: Bundle?) {
        adapter = NodeAdapter()
        listView = v.findViewById(R.id.node_list)
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            val node = displayList()[position]
            controller.select(node, requireContext())
            adapter.notifyDataSetChanged()
        }

        val search = v.findViewById<EditText>(R.id.server_search)
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                filter = s?.toString()?.trim() ?: ""
                adapter.notifyDataSetChanged()
            }
        })

        v.findViewById<View>(R.id.test_pings).setOnClickListener {
            v.findViewById<TextView>(R.id.telemetry_status).text = getString(R.string.testing_now)
            controller.measureAllPings {
                if (isAdded) {
                    view?.findViewById<TextView>(R.id.telemetry_status)?.text = getString(R.string.telemetry_synced)
                }
            }
        }

        controller.addListener(listener)

        // measure once if never tested
        if (controller.pingResults.isEmpty()) {
            v.findViewById<View>(R.id.test_pings).callOnClick()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        controller.removeListener(listener)
    }

    private fun displayList(): List<Node> {
        val ranked = NodeCatalog.ranked(controller.pingResults)
        if (filter.isEmpty()) return ranked
        val f = filter.lowercase()
        return ranked.filter {
            it.city.lowercase().contains(f) ||
                it.country.lowercase().contains(f) ||
                it.code.lowercase().contains(f) ||
                it.tagline.lowercase().contains(f)
        }
    }

    private inner class NodeAdapter : BaseAdapter() {
        override fun getCount(): Int = displayList().size
        override fun getItem(position: Int): Node = displayList()[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v: View = convertView
                ?: layoutInflater.inflate(R.layout.item_node, parent, false)
            val node = getItem(position)
            val rank = NodeCatalog.ranked(controller.pingResults).indexOfFirst { it.code == node.code } + 1
            val ping = controller.pingResults[node.code]
            val selected = controller.selectedCode == node.code

            v.findViewById<ImageView>(R.id.item_flag).setImageResource(node.flag)
            v.findViewById<TextView>(R.id.item_city).text = node.city
            v.findViewById<TextView>(R.id.item_sub).text = "${node.country}  •  ${node.tagline}"
            v.findViewById<TextView>(R.id.item_rank).text = "#$rank"

            val pingView = v.findViewById<TextView>(R.id.item_ping)
            if (ping != null) {
                pingView.text = "${ping} ms"
                pingView.setTextColor(resources.getColor(R.color.text_main, requireContext().theme))
            } else {
                pingView.text = getString(R.string.ping_dash)
                pingView.setTextColor(resources.getColor(R.color.text_dim, requireContext().theme))
            }

            val selIcon = v.findViewById<ImageView>(R.id.item_selected)
            val selBg = v.findViewById<View>(R.id.item_select_btn)
            if (selected) {
                selIcon.setImageResource(R.drawable.ic_check)
                selIcon.setColorFilter(resources.getColor(R.color.cyan_glow, requireContext().theme))
                selBg.setBackgroundResource(R.drawable.bg_select_btn_active)
                v.findViewById<TextView>(R.id.item_connected).visibility = View.VISIBLE
            } else {
                selIcon.setImageResource(R.drawable.ic_power)
                selIcon.setColorFilter(resources.getColor(R.color.text_dim, requireContext().theme))
                selBg.setBackgroundResource(R.drawable.bg_select_btn_idle)
                v.findViewById<TextView>(R.id.item_connected).visibility = View.GONE
            }
            return v
        }
    }
}
