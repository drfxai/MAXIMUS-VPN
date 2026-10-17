package com.example.ui.settings

import com.example.vpn.smart.NetworkNames
import com.example.vpn.smart.RouteBook

/**
 * Settings → Smart Connect → "Learned network data": one row per network with remembered routes. Carriers
 * are named; a Wi-Fi network is only ever "Wi-Fi network (name not stored)", numbered when there are
 * several, since its key is a salted fingerprint and no SSID is kept. Pure.
 */
object LearnedNetworks {
    data class Row(val name: String, val routes: Int) {
        val count: String get() = if (routes == 1) "1 route" else "$routes routes"
    }

    /** [counts] is network key → unexpired routes ([RouteBook.countsByNetwork]); most routes first. */
    fun rows(counts: Map<String, Int>): List<Row> {
        val sorted = counts.filterValues { it > 0 }.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        val names = sorted.map { NetworkNames.learned(it.key) }
        val seen = mutableMapOf<String, Int>()
        return sorted.mapIndexed { i, e ->
            val name = names[i]
            val total = names.count { it == name }
            val n = (seen[name] ?: 0) + 1
            seen[name] = n
            Row(if (total > 1) numbered(name, n) else name, e.value)
        }
    }

    private fun numbered(name: String, n: Int): String =
        if (name == NetworkNames.WIFI) "Wi-Fi network $n (name not stored)" else "$name $n"

    /** How long a route is kept, in days, from the book's own limit. */
    val keptDays: Long get() = RouteBook.TTL_MS / (24 * 60 * 60 * 1000L)

    val explanation: String get() =
        "Routes that worked, per carrier or Wi-Fi. Kept for $keptDays ${if (keptDays == 1L) "day" else "days"}, no passwords or keys."

    const val FOOTER = "Clearing forgets every remembered route and what worked on each network. Your saved servers and settings " +
        "are not touched. The next connect on each network searches from the start."
}
