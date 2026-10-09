package com.example.vpn.lab

import com.example.vpn.smart.NetworkCapabilityProfile

/**
 * Turns the classified network state into a bounded plan: which recovery mode applies, which method
 * families go first or last, and how many real tests the run may spend. It only orders and gates;
 * every candidate still passes the mutation policy and the security gate, and nothing here can add a
 * strategy the generator would not produce.
 */
object ExperimentPlanner {

    enum class Mode(val title: String) {
        /** Test saved configs and safe derived copies. */
        ORDINARY("Test saved configs and safe variants"),
        /** No international path: skip ordinary config mutations; DNS tunnel configs are the only hope and are tested as the measurement. */
        DNS_TUNNEL_RECOVERY("DNS tunnel recovery"),
        /** Nothing answers at all: no software path can work; report it and stop. */
        STOP_NO_EGRESS("Stop: no path out")
    }

    data class Plan(
        val mode: Mode,
        /** Families tried first, best first. */
        val prefer: List<PathFamily>,
        /** Families skipped on this network, with the reason. */
        val skip: Map<PathFamily, String>,
        /** Real-request tests this run may spend. */
        val budget: Int,
        val reasons: List<String>
    )

    data class Budget(val userStarted: Boolean, val batteryPercent: Int?, val charging: Boolean?, val metered: Boolean?)

    /** Tests a run may spend: generous for a user-started foreground run, small in the background. */
    fun budget(b: Budget): Int {
        var n = if (b.userStarted) 15 else 4
        if (b.metered == true) n = minOf(n, if (b.userStarted) 10 else 3)
        if (b.charging != true && (b.batteryPercent ?: 100) < 20) n = minOf(n, 4)
        return n
    }

    fun plan(reading: NetworkStateReading?, net: NetworkCapabilityProfile?, budget: Int): Plan {
        val state = reading?.primary ?: NetworkState.UNKNOWN
        val restrictions = reading?.restrictions.orEmpty() + state
        val prefer = mutableListOf<PathFamily>()
        val skip = linkedMapOf<PathFamily, String>()
        val reasons = mutableListOf<String>()

        val mode = when (state) {
            NetworkState.FULL_ISOLATION -> {
                reasons += "Neither domestic nor international references answered: no software path can work here."
                Mode.STOP_NO_EGRESS
            }
            NetworkState.NIN_WITH_DNS_EGRESS -> {
                reasons += "Only domestic sites answer, but recursive DNS reaches abroad: testing DNS tunnels."
                Mode.DNS_TUNNEL_RECOVERY
            }
            NetworkState.DOMESTIC_ONLY_NO_VERIFIED_EGRESS -> {
                reasons += "Only domestic sites answer. Ordinary config changes cannot help; DNS tunnel configs are tried as the measurement of DNS egress."
                Mode.DNS_TUNNEL_RECOVERY
            }
            else -> Mode.ORDINARY
        }
        if (mode == Mode.DNS_TUNNEL_RECOVERY) {
            prefer += PathFamily.DNS_TUNNEL
            PathFamily.entries.filter { it != PathFamily.DNS_TUNNEL }.forEach { skip[it] = "no international path on this network" }
        }
        if (mode == Mode.STOP_NO_EGRESS) PathFamily.entries.forEach { skip[it] = "nothing answers on this network" }

        if (mode == Mode.ORDINARY) {
            if (NetworkState.UDP_BLOCKED in restrictions) {
                PathFamily.entries.filter { it.udp }.forEach { skip[it] = "UDP abroad gets no answer here" }
                reasons += "UDP is blocked: UDP-based methods are skipped."
            }
            if (NetworkState.QUIC_BLOCKED in restrictions || NetworkState.UDP_BLOCKED in restrictions) {
                prefer += listOf(PathFamily.VLESS_REALITY, PathFamily.XHTTP, PathFamily.WEBSOCKET, PathFamily.HTTP2, PathFamily.VLESS_TLS)
                reasons += "QUIC/UDP is unreliable: TCP-based transports go first."
            }
            if (NetworkState.SNI_INTERFERENCE_SUSPECTED in restrictions || state == NetworkState.TLS_INTERFERED) {
                prefer += listOf(PathFamily.VLESS_REALITY, PathFamily.XHTTP)
                reasons += "TLS names seem to be inspected: REALITY first, then fragment and ECH variants of TLS configs."
            }
            if (state == NetworkState.PARTIAL_INTERNATIONAL_CONNECTIVITY) {
                reasons += "Only some international paths work: one config per family first, for diversity."
            }
            if (NetworkState.DNS_MANIPULATED in restrictions) {
                reasons += "DNS is tampered: server names are resolved over encrypted DNS, and clean edge addresses are tried."
            }
            if (NetworkState.IPV6_DEGRADED in restrictions) reasons += "IPv6 fails abroad: IPv6 endpoints are avoided."
            if (state == NetworkState.IPV4_DEGRADED) reasons += "IPv4 fails abroad while IPv6 works: IPv6 endpoints go first."
            // Engine families cost the most (start-up, battery); they go after the Xray families.
            prefer += listOf(PathFamily.PSIPHON, PathFamily.TOR)
        }
        if (reasons.isEmpty()) reasons += "No restriction measured: every family is tried, one config each first."
        return Plan(mode, prefer.distinct(), skip, if (mode == Mode.STOP_NO_EGRESS) 0 else budget, reasons)
    }

    /** Null when an automatic (background) experiment may run; otherwise the reason it was not started. */
    fun automaticRefusal(reading: NetworkStateReading?): String? = when (reading?.primary) {
        NetworkState.FULL_ISOLATION, NetworkState.DOMESTIC_ONLY_NO_VERIFIED_EGRESS, NetworkState.NIN_WITH_DNS_EGRESS ->
            "${reading.primary.title}: config mutations cannot help here. Run a Full Analysis to try DNS tunnels."
        else -> null
    }

    /**
     * Puts the strategies the measurements point at first. SNI interference or cut TLS: fragment and ECH
     * before the rest. Tampered DNS: a validated edge address first. IPv6 candidates are dropped where IPv6
     * is absent or measured broken.
     */
    fun order(hypotheses: List<Hypothesis>, net: NetworkCapabilityProfile?): List<Hypothesis> {
        if (net == null) return hypotheses
        val tlsCut = net.sniFiltered == true || (net.internationalReachable == false && net.tcpAvailable == true)
        val first = buildList {
            if (tlsCut) addAll(listOf("fragment", "ech"))
            if (net.dnsManipulated == true) add("endpoint")
            if (net.internationalReachable == false && net.ipv6TlsOk == true) add("address-family")
        }
        val kept = hypotheses.filterNot { it.strategy == "address-family" && (net.ipv6Available == false || net.ipv6TlsOk == false) }
        return kept.sortedBy { h -> first.indexOf(h.strategy).let { if (it < 0) Int.MAX_VALUE else it } }
    }
}
