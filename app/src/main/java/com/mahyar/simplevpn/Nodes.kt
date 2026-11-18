package com.mahyar.simplevpn

import androidx.annotation.DrawableRes

/** A selectable exit-location entry shown on the Nodes screen. */
data class Node(
    val city: String,
    val country: String,
    val code: String,
    /** Real server located in this country, used to measure regional RTT. */
    val pingHost: String,
    @DrawableRes val flag: Int,
    val tagline: String
)

object NodeCatalog {

    val nodes: List<Node> = listOf(
        Node("Frankfurt", "Germany", "DE", "ftp.fau.de", R.drawable.flag_de, "WireGuard Ultra-Q"),
        Node("Zurich", "Switzerland", "CH", "mirror.puzzle.ch", R.drawable.flag_ch, "Secure Bunker"),
        Node("Amsterdam", "Netherlands", "NL", "ftp.nluug.nl", R.drawable.flag_nl, "AMS-IX Fiber"),
        Node("London", "United Kingdom", "GB", "mirrorservice.org", R.drawable.flag_gb, "BBC & 4K"),
        Node("Stockholm", "Sweden", "SE", "mirror.accum.se", R.drawable.flag_se, "Nordic Backbone"),
        Node("Paris", "France", "FR", "mirrors.ircam.fr", R.drawable.flag_fr, "FR-IX Fiber"),
        Node("New York", "United States", "US", "ftp.osuosl.org", R.drawable.flag_us, "US East Core"),
        Node("Tokyo", "Japan", "JP", "ftp.jaist.ac.jp", R.drawable.flag_jp, "APAC Direct")
    )

    fun byCode(code: String): Node =
        nodes.firstOrNull { it.code == code } ?: nodes[0]

    /** Nodes sorted by last measured ping; unmeasured keep catalog order at the end. */
    fun ranked(measured: Map<String, Int?>): List<Node> =
        nodes.sortedBy { n ->
            val p = measured[n.code]
            if (p == null) Int.MAX_VALUE else p
        }
}
