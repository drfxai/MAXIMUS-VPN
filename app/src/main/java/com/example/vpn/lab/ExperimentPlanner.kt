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
        /**
         * No ordinary international path: a diverse set of recovery families (DNS tunnel, Psiphon, Tor
         * bridges, Mihomo transports, AmneziaWG/WireGuard) goes first; ordinary configs are still tried last
         * with a low prior (some carriers allowlist a few endpoints), and no config copies are made.
         */
        EMERGENCY_RECOVERY("Emergency recovery")
    }

    data class Plan(
        val mode: Mode,
        /** Families tried first, best first. */
        val prefer: List<PathFamily>,
        /** Families skipped on this network, with the reason. */
        val skip: Map<PathFamily, String>,
        /** Real-request tests this run may spend. */
        val budget: Int,
        val reasons: List<String>,
        /** Families with a low prior on this network: tried only after the preferred ones. */
        val lowPrior: Set<PathFamily> = emptySet()
    )

    data class Budget(val userStarted: Boolean, val batteryPercent: Int?, val charging: Boolean?, val metered: Boolean?)

    /** Tests a run may spend: generous for a user-started foreground run, small in the background. */
    fun budget(b: Budget): Int {
        var n = if (b.userStarted) 15 else 4
        if (b.metered == true) n = minOf(n, if (b.userStarted) 10 else 3)
        if (b.charging != true && (b.batteryPercent ?: 100) < 20) n = minOf(n, 4)
        return n
    }

    /** Ordinary (Xray, non-engine) families: the ones a filtered network usually blocks first. */
    val ORDINARY_FAMILIES: Set<PathFamily> = PathFamily.entries.filter { !it.engine }.toSet() - setOf(PathFamily.OTHER)

    /**
     * Emergency recovery order, from the evidence. A DNS tunnel goes first when DNS or UDP still answers
     * (or recursive DNS egress was verified); otherwise it follows Psiphon and the Tor bridges. UDP
     * families (AmneziaWG, WireGuard, Hysteria2, TUIC) are left out only when UDP was measured dead.
     */
    fun emergencyOrder(net: NetworkCapabilityProfile?): List<PathFamily> = buildList {
        val dnsAlive = net?.recursiveDnsEgress == true || net?.dnsWorking == true || net?.udpAvailable == true
        if (dnsAlive) add(PathFamily.DNS_TUNNEL)
        add(PathFamily.PSIPHON)
        addAll(PathFamily.TOR_FAMILIES)
        if (!dnsAlive) add(PathFamily.DNS_TUNNEL)
        add(PathFamily.MIHOMO)
        if (!udpDead(net)) addAll(listOf(PathFamily.AMNEZIAWG, PathFamily.WIREGUARD, PathFamily.HYSTERIA2, PathFamily.TUIC))
    }

    /** UDP abroad measured dead: direct UDP DNS failed and no QUIC endpoint answered either. */
    fun udpDead(net: NetworkCapabilityProfile?): Boolean =
        net?.udpAvailable == false && net?.quicStatus != "QUIC_AVAILABLE" && net?.quicStatus != "QUIC_DEGRADED"

    fun plan(reading: NetworkStateReading?, net: NetworkCapabilityProfile?, budget: Int): Plan {
        val state = reading?.primary ?: NetworkState.UNKNOWN
        val restrictions = reading?.restrictions.orEmpty() + state
        val prefer = mutableListOf<PathFamily>()
        val skip = linkedMapOf<PathFamily, String>()
        val reasons = mutableListOf<String>()
        val lowPrior = mutableSetOf<PathFamily>()

        val mode = if (state in NetworkStateClassifier.EMERGENCY) Mode.EMERGENCY_RECOVERY else Mode.ORDINARY
        when (state) {
            NetworkState.NIN_WITH_DNS_EGRESS -> reasons += "Only domestic sites answer, but recursive DNS reaches abroad: DNS tunnels first."
            NetworkState.DOMESTIC_ONLY -> reasons += "Only domestic sites answer. Recovery methods of different kinds are tried; ordinary config changes cannot help."
            NetworkState.SEVERE_FILTERING -> reasons += "No international site answers, but some traffic still gets a reply: recovery methods of different kinds are tried."
            NetworkState.NO_VERIFIED_EGRESS -> reasons += "Nothing answered yet. This is not proof of isolation: recovery methods are tried before any conclusion."
            else -> {}
        }
        if (mode == Mode.EMERGENCY_RECOVERY) {
            val order = emergencyOrder(net)
            prefer += order
            if (udpDead(net)) PathFamily.entries.filter { it.udp }.forEach { skip[it] = "UDP abroad gets no answer here" }
            lowPrior += ORDINARY_FAMILIES
            reasons += "Ordinary configs are still tried, last, with a low prior; no config copies are made."
        }

        if (mode == Mode.ORDINARY) {
            if (NetworkState.UDP_BLOCKED_SUSPECTED in restrictions && udpDead(net)) {
                PathFamily.entries.filter { it.udp }.forEach { skip[it] = "UDP abroad gets no answer here" }
                reasons += "UDP is blocked: UDP-based methods are skipped."
            } else if (NetworkState.UDP_BLOCKED_SUSPECTED in restrictions) {
                reasons += "Direct UDP DNS abroad failed, but QUIC answered: UDP methods are still tried."
            }
            if (NetworkState.QUIC_BLOCKED_SUSPECTED in restrictions || NetworkState.UDP_BLOCKED_SUSPECTED in restrictions) {
                prefer += listOf(PathFamily.VLESS_REALITY, PathFamily.XHTTP, PathFamily.WEBSOCKET, PathFamily.HTTP2, PathFamily.VLESS_TLS)
                reasons += "QUIC/UDP is unreliable: TCP-based transports go first."
            }
            if (NetworkState.SNI_INTERFERENCE_SUSPECTED in restrictions || state == NetworkState.TLS_PATH_FAILURE) {
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
            prefer += listOf(PathFamily.PSIPHON) + PathFamily.TOR_FAMILIES
        }
        if (reasons.isEmpty()) reasons += "No restriction measured: every family is tried, one config each first."
        return Plan(mode, prefer.distinct(), skip, budget, reasons, lowPrior)
    }

    /** Null when an automatic (background) experiment may run; otherwise the reason it was not started. */
    fun automaticRefusal(reading: NetworkStateReading?): String? = when (reading?.primary) {
        in NetworkStateClassifier.EMERGENCY, NetworkState.NO_VERIFIED_EGRESS_AFTER_RECOVERY ->
            "${reading!!.primary.title}: config mutations cannot help here. Run a Full Analysis to try recovery methods."
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
