package com.example.vpn.lab

import com.example.vpn.smart.NetworkCapabilityProfile

/**
 * What the phone's own network allows right now, as far as the measurements show. The carrier name never
 * decides the state; only measured behaviour does. States that need evidence the phone does not have yet
 * (for example a domestic relay) are never claimed without it.
 */
enum class NetworkState(val title: String, val detail: String) {
    UNKNOWN("Unknown", "Not enough measurements yet"),
    NORMAL("Normal", "International sites answer and nothing was seen being filtered"),
    FILTERED("Filtered", "International sites answer, with several kinds of filtering"),
    DNS_MANIPULATED("DNS tampered", "The network's DNS answers some foreign names with block pages"),
    SNI_INTERFERENCE_SUSPECTED("SNI interference suspected", "TLS was cut for filtered site names on several addresses while neutral names passed"),
    /**
     * TLS to neutral international references fails although TCP connects. A path failure, not a claim about
     * server names: only a controlled comparison (same address, filtered vs neutral name) may say SNI.
     */
    TLS_PATH_FAILURE("TLS path failure", "International TCP connects, but TLS handshakes do not complete"),
    UDP_DEGRADED("UDP degraded", "UDP abroad answers only some of the time"),
    UDP_BLOCKED_SUSPECTED("UDP blocked (suspected)", "UDP to international addresses got no answer in repeated checks"),
    QUIC_DEGRADED("QUIC degraded", "Some QUIC endpoints answer and some do not"),
    QUIC_BLOCKED_SUSPECTED("QUIC blocked (suspected)", "No QUIC endpoint answered while UDP DNS and TLS abroad worked"),
    IPV4_DEGRADED("IPv4 degraded", "International TLS fails over IPv4 but works over IPv6"),
    IPV6_DEGRADED("IPv6 degraded", "The network offers IPv6 but international TLS over it fails"),
    CDN_PATH_DEGRADED("CDN path degraded", "A major CDN fails while other international sites answer"),
    INTERNATIONAL_DEGRADED("International degraded", "International sites answer slowly or unreliably"),
    PARTIAL_INTERNATIONAL_CONNECTIVITY("Partial egress", "Some international references answer and some do not"),
    NIN_WITH_DNS_EGRESS("National network, DNS egress", "Only domestic sites answer, but recursive DNS was verified to reach abroad"),
    DOMESTIC_ONLY("Domestic only", "Only domestic sites answer and no way out was verified"),
    /**
     * Nothing ordinary answers abroad and DNS, UDP, DoH, DoT or QUIC still showed some life. Recovery
     * families (DNS tunnel, Psiphon, Tor bridges, Mihomo transports) are worth trying.
     */
    SEVERE_FILTERING("Severe filtering", "No international site answers, but some kinds of traffic still get a reply"),
    /**
     * No reference answered, at home or abroad. This is not isolation: it only says no egress was verified,
     * and recovery families are still tried.
     */
    NO_VERIFIED_EGRESS("No verified egress", "No reference answered; recovery methods have not ruled out a way out"),
    /**
     * After recovery: every available, configured family was really tested on this network and none carried
     * international traffic. It means only "MAXIMUS did not verify international egress using the currently
     * available, configured and tested methods" and never that no other technology could work.
     */
    NO_VERIFIED_EGRESS_AFTER_RECOVERY("No verified egress after recovery",
        "Maximus did not verify international egress using the methods available, configured and tested here")
}

/**
 * One classification. [evidence] lists the measurements that led to it; [notTested] the ones that were
 * missing, so the screen can say what is unknown instead of guessing. [confidence] is in 0..1.
 */
data class NetworkStateReading(
    val primary: NetworkState,
    val restrictions: Set<NetworkState>,
    val confidence: Double,
    val evidence: List<String>,
    val notTested: List<String>,
    val at: Long
) {
    init { require(confidence in 0.0..1.0) }

    /** Readable one-liner: "Filtered (DNS tampered, SNI filtered) · 70% confidence". */
    fun summary(): String = buildString {
        append(primary.title)
        val extra = restrictions - primary
        if (extra.isNotEmpty()) append(" (").append(extra.joinToString { it.title }).append(")")
        if (primary != NetworkState.UNKNOWN) append(" · ").append((confidence * 100).toInt()).append("% confidence")
    }
}

/**
 * Pure rules from a [NetworkCapabilityProfile] to a [NetworkStateReading]. Observation (the profile) and
 * assessment (the reading) stay separate: the profile is never changed here.
 */
object NetworkStateClassifier {

    fun classify(p: NetworkCapabilityProfile?, now: Long = System.currentTimeMillis()): NetworkStateReading {
        if (p == null) return NetworkStateReading(NetworkState.UNKNOWN, emptySet(), 0.0, emptyList(), listOf("No measurement yet"), now)

        val evidence = mutableListOf<String>()
        val missing = mutableListOf<String>()
        fun note(name: String, value: Boolean?) {
            when (value) {
                true -> evidence += "$name: yes"
                false -> evidence += "$name: no"
                null -> missing += name
            }
        }
        p.internationalTried?.let { evidence += "International TLS: ${p.internationalOk ?: 0}/$it references answered" } ?: run { missing += "International TLS" }
        note("International TCP", p.tcpAvailable)
        note("Domestic sites", p.domesticReachable)
        note("System DNS", p.dnsWorking)
        note("DNS tampering", p.dnsManipulated)
        note("Direct DNS to a foreign resolver (UDP)", p.udpAvailable)
        note("Recursive DNS egress (nonce)", p.recursiveDnsEgress)
        note("Encrypted DNS (DoH)", p.dohReachable)
        note("DNS over TLS", p.dotReachable)
        p.sniPairs?.let { evidence += "SNI comparisons: ${p.sniCut ?: 0}/$it cut" } ?: run { missing += "SNI comparison" }
        if (p.quicStatus != null && p.quicStatus != "QUIC_NOT_MEASURED") evidence += "QUIC: ${p.quicStatus}" else missing += "QUIC"
        if (p.ipv6Available == true) note("International TLS over IPv6", p.ipv6TlsOk)
        val measuredShare = evidence.size.toDouble() / (evidence.size + missing.size)

        val intl = p.internationalReachable
        fun reading(primary: NetworkState, base: Double, restrictions: Set<NetworkState> = emptySet()) =
            NetworkStateReading(primary, restrictions, (base * (0.5 + 0.5 * measuredShare)).coerceIn(0.0, 1.0), evidence, missing, now)

        if (intl == null && p.tcpAvailable == null && p.tlsAvailable == null) return reading(NetworkState.UNKNOWN, 0.0)

        // No international reference completed TLS over IPv4.
        if (intl == false || (intl == null && p.tlsAvailable == false && p.tcpAvailable == false)) {
            if (p.ipv6TlsOk == true) return reading(NetworkState.IPV4_DEGRADED, 0.7, setOf(NetworkState.IPV4_DEGRADED))
            if (p.tcpAvailable == true) return reading(NetworkState.TLS_PATH_FAILURE, 0.7)
            return when (p.domesticReachable) {
                // DNS egress means a recursive resolver reached a foreign authoritative server; a direct
                // UDP answer from a foreign resolver is a different property and never counts here.
                true -> if (p.recursiveDnsEgress == true) reading(NetworkState.NIN_WITH_DNS_EGRESS, 0.75)
                else reading(NetworkState.DOMESTIC_ONLY, 0.7)
                // Nothing ordinary answers. Any sign of life (a DNS answer, a UDP reply, DoH, DoT, a QUIC
                // endpoint) means traffic is filtered rather than absent. Neither case is isolation: that
                // needs every recovery family to fail too (see EmergencyRecovery.conclude).
                false -> if (anyLife(p)) reading(NetworkState.SEVERE_FILTERING, 0.6)
                else reading(NetworkState.NO_VERIFIED_EGRESS, 0.7)
                // Domestic names did not resolve: no evidence either way about domestic reach.
                null -> if (anyLife(p)) reading(NetworkState.SEVERE_FILTERING, 0.4)
                else if (p.dnsWorking == false) reading(NetworkState.NO_VERIFIED_EGRESS, 0.4) else reading(NetworkState.UNKNOWN, 0.3)
            }
        }

        val restrictions = linkedSetOf<NetworkState>()
        if (p.dnsManipulated == true) restrictions += NetworkState.DNS_MANIPULATED
        if (p.sniFiltered == true) restrictions += NetworkState.SNI_INTERFERENCE_SUSPECTED
        if (p.udpAvailable == false) restrictions += NetworkState.UDP_BLOCKED_SUSPECTED
        when (p.quicStatus) {
            "QUIC_BLOCKED_SUSPECTED" -> restrictions += NetworkState.QUIC_BLOCKED_SUSPECTED
            "QUIC_DEGRADED" -> restrictions += NetworkState.QUIC_DEGRADED
        }
        if (p.ipv6Available == true && p.ipv6TlsOk == false) restrictions += NetworkState.IPV6_DEGRADED
        // The CDN reference is reached by name, so a tampered DNS alone explains its failure.
        if (p.cloudflareReachable == false && p.dnsManipulated != true && intl == true) restrictions += NetworkState.CDN_PATH_DEGRADED

        val partial = intl == true && p.internationalTried != null && (p.internationalOk ?: 0) < p.internationalTried
        if (partial) return reading(NetworkState.PARTIAL_INTERNATIONAL_CONNECTIVITY, 0.6, restrictions + NetworkState.PARTIAL_INTERNATIONAL_CONNECTIVITY)
        if (intl == null) {
            // Only the older TCP/TLS-by-name probes ran.
            return if (p.tlsAvailable == true && restrictions.isEmpty()) reading(NetworkState.NORMAL, 0.4) else reading(NetworkState.UNKNOWN, 0.3, restrictions)
        }
        return when (restrictions.size) {
            0 -> reading(NetworkState.NORMAL, 0.8)
            1 -> reading(restrictions.first(), if (restrictions.first() == NetworkState.SNI_INTERFERENCE_SUSPECTED) 0.6 else 0.75, restrictions)
            else -> reading(NetworkState.FILTERED, 0.75, restrictions)
        }
    }

    /** True when some traffic still got an answer, so filtering (not a dead link) is the better reading. */
    private fun anyLife(p: NetworkCapabilityProfile): Boolean =
        p.dnsWorking == true || p.udpAvailable == true || p.dohReachable == true || p.dotReachable == true ||
            p.quicStatus == "QUIC_AVAILABLE" || p.quicStatus == "QUIC_DEGRADED"

    /** States where no ordinary international path exists and recovery families are the plan. */
    val EMERGENCY = setOf(
        NetworkState.NIN_WITH_DNS_EGRESS, NetworkState.DOMESTIC_ONLY,
        NetworkState.SEVERE_FILTERING, NetworkState.NO_VERIFIED_EGRESS
    )
}

/**
 * Hysteresis over [NetworkStateClassifier] readings for one network session: the shown state changes only
 * after two consistent readings, or at once for a reading with confidence of at least [IMMEDIATE]. A new
 * network session starts fresh, so one network's state never carries over to another.
 */
class NetworkStateTracker {
    private var session: String? = null
    private var shown: NetworkStateReading? = null
    private var pending: NetworkStateReading? = null

    @Synchronized
    fun update(sessionId: String, reading: NetworkStateReading): NetworkStateReading {
        if (sessionId != session) {
            session = sessionId
            shown = reading
            pending = null
            return reading
        }
        val current = shown
        if (current == null || current.primary == NetworkState.UNKNOWN || reading.primary == current.primary) {
            shown = reading
            pending = null
            return reading
        }
        if (reading.confidence >= IMMEDIATE || pending?.primary == reading.primary) {
            shown = reading
            pending = null
            return reading
        }
        pending = reading
        return current
    }

    @Synchronized
    fun current(): NetworkStateReading? = shown

    companion object { const val IMMEDIATE = 0.9 }
}

/**
 * The only way to NO_VERIFIED_EGRESS_AFTER_RECOVERY: after a recovery run, every family with a saved, supported
 * config was really tested (not merely skipped) and none carried a real request. It is a statement about the
 * methods MAXIMUS had and tested here, never that the network is physically isolated or that no other
 * technology could work. Anything less stays NO_VERIFIED_EGRESS (or what the classifier said). Pure.
 */
object EmergencyRecovery {

    fun conclude(net: NetworkCapabilityProfile?, tracks: List<AdaptivePlanner.Track>, available: Set<PathFamily>): NetworkState? {
        if (available.isEmpty()) return null
        val tested = tracks.filter { it.outcomes.isNotEmpty() }
        if (tested.isEmpty() || tested.any { it.passes > 0 }) return null
        val testedFamilies = tested.map { it.candidate.family }.toSet()
        if (!testedFamilies.containsAll(available)) return null
        return NetworkState.NO_VERIFIED_EGRESS_AFTER_RECOVERY
    }

    /** What the conclusion leaves open, in words, so the report never implies more than was shown. */
    fun whyNot(net: NetworkCapabilityProfile?, tracks: List<AdaptivePlanner.Track>, available: Set<PathFamily>): String? {
        val untested = available - tracks.filter { it.outcomes.isNotEmpty() }.map { it.candidate.family }.toSet()
        return when {
            tracks.any { it.passes > 0 } -> null
            available.isEmpty() -> "No usable config is saved, so nothing could be tested."
            untested.isNotEmpty() -> "Not every family was tested (${untested.joinToString { it.title }}), so recovery is not finished."
            available.none { it.engine } -> "Only ordinary configs were tested. Recovery engines (DNS tunnel, Psiphon, Tor bridges) were not available to try."
            else -> "Every available method was tested here. Other methods or servers may still work."
        }
    }
}
