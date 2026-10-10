package com.example.vpn.smart

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * How hostile the network outside the VPN looks, from what the phone and earlier connects observed.
 * Used to log the conditions with every connect and to suggest GOD MODE when filtering is heavy.
 */
object NetworkEnvironment {
    /** NO_VERIFIED_EGRESS: nothing tried here carried traffic; it says nothing about methods not tried. */
    enum class Level { OPEN, FILTERED, HEAVY, NO_VERIFIED_EGRESS }

    enum class Signal {
        /** Android could not reach its own check server (captive portal or a cut-off network). */
        NOT_VALIDATED,
        METERED,
        ROAMING,
        /** The network's DNS gave a blocked answer for a server name. */
        DNS_POISONED,
        /**
         * Plain TLS or REALITY failed recently. A TLS path failure only: by itself it never says why (the
         * address, the port, the handshake or the name could be the cause).
         */
        TLS_PATH_FAILURE,
        /** A controlled comparison (the same address, a filtered and a neutral server name) cut the filtered name. */
        SNI_INTERFERENCE_SUSPECTED,
        /** QUIC (Hysteria2) or WireGuard failed recently. */
        UDP_FILTERED,
        /** Every kind tried recently failed. */
        NOTHING_WORKED
    }

    data class Report(val network: String, val level: Level, val signals: Set<Signal>) {
        fun describe(): String = "${NetworkKey.describe(network)}: ${level.name.lowercase()}" +
            if (signals.isEmpty()) "" else " (${signals.joinToString { it.name.lowercase().replace('_', ' ') }})"
    }

    /**
     * [failedKinds] are the ConnectionKind names that recently carried no traffic on [network] and
     * [workingKinds] the ones that worked there (NetworkMemory).
     */
    fun classify(
        network: String,
        validated: Boolean,
        metered: Boolean,
        roaming: Boolean,
        dnsPoisoned: Boolean,
        failedKinds: Set<String>,
        workingKinds: List<String>,
        /** True only when a controlled SNI comparison on this network cut the filtered name. */
        sniComparisonCut: Boolean = false
    ): Report {
        val signals = mutableSetOf<Signal>()
        if (!validated) signals += Signal.NOT_VALIDATED
        if (metered) signals += Signal.METERED
        if (roaming) signals += Signal.ROAMING
        if (dnsPoisoned) signals += Signal.DNS_POISONED
        if (failedKinds.any { it in setOf("REALITY", "TLS") }) signals += Signal.TLS_PATH_FAILURE
        if (sniComparisonCut) signals += Signal.SNI_INTERFERENCE_SUSPECTED
        if (failedKinds.any { it in setOf("QUIC", "WireGuard") }) signals += Signal.UDP_FILTERED
        val stillWorking = workingKinds.filter { it !in failedKinds }
        if (failedKinds.isNotEmpty() && stillWorking.isEmpty()) signals += Signal.NOTHING_WORKED
        val filters = listOf(Signal.DNS_POISONED, Signal.TLS_PATH_FAILURE, Signal.UDP_FILTERED).count { it in signals }
        val level = when {
            Signal.NOTHING_WORKED in signals && (Signal.NOT_VALIDATED in signals || filters >= 2) -> Level.NO_VERIFIED_EGRESS
            Signal.NOTHING_WORKED in signals || filters >= 2 -> Level.HEAVY
            filters == 1 -> Level.FILTERED
            else -> Level.OPEN
        }
        return Report(network, level, signals)
    }

    /** GOD MODE is worth suggesting when filtering is heavy and the user is in DAILY mode. */
    fun suggestsGodMode(report: Report): Boolean = report.level == Level.HEAVY || report.level == Level.NO_VERIFIED_EGRESS

    @Suppress("DEPRECATION")
    fun observe(context: Context, memory: NetworkMemory, dnsPoisoned: Boolean): Report {
        val network = NetworkKey.current(context)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.allNetworks.orEmpty().mapNotNull { cm?.getNetworkCapabilities(it) }.filter {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        val validated = caps.any { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
        val metered = caps.isNotEmpty() && caps.none { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) }
        val roaming = android.os.Build.VERSION.SDK_INT >= 28 && caps.isNotEmpty() &&
            caps.none { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING) }
        // SNI is named only from the LAB's controlled comparison in the current network session.
        val book = com.example.vpn.connectivity.ConnectivityBrain.book
        val sid = book.session()?.id
        val sniCut = book.all().lastOrNull { it.sessionId == sid && it.type == com.example.vpn.connectivity.MeasurementType.SNI_COMPARISON }?.success == false
        return classify(network, validated, metered, roaming, dnsPoisoned,
            memory.recentFailures(network), memory.workingKinds(network), sniCut)
    }
}
