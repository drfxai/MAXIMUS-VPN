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
    TLS_INTERFERED("TLS interfered", "International TCP connects, but TLS handshakes are cut"),
    UDP_DEGRADED("UDP degraded", "UDP abroad answers only some of the time"),
    UDP_BLOCKED("UDP blocked", "UDP to international addresses gets no answer"),
    QUIC_DEGRADED("QUIC degraded", "Some QUIC endpoints answer and some do not"),
    QUIC_BLOCKED("QUIC blocked (suspected)", "No QUIC endpoint answered while UDP DNS and TLS abroad worked"),
    IPV4_DEGRADED("IPv4 degraded", "International TLS fails over IPv4 but works over IPv6"),
    IPV6_DEGRADED("IPv6 degraded", "The network offers IPv6 but international TLS over it fails"),
    CDN_PATH_DEGRADED("CDN path degraded", "A major CDN fails while other international sites answer"),
    INTERNATIONAL_DEGRADED("International degraded", "International sites answer slowly or unreliably"),
    PARTIAL_INTERNATIONAL_CONNECTIVITY("Partial international", "Some international references answer and some do not"),
    NIN_WITH_DNS_EGRESS("National network, DNS egress", "Only domestic sites answer, but recursive DNS was verified to reach abroad"),
    DOMESTIC_ONLY_NO_VERIFIED_EGRESS("Domestic only", "Only domestic sites answer and no way out was verified"),
    FULL_ISOLATION("No connectivity", "Neither domestic nor international references answer")
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
            if (p.tcpAvailable == true) return reading(NetworkState.TLS_INTERFERED, 0.7)
            return when (p.domesticReachable) {
                // DNS egress means a recursive resolver reached a foreign authoritative server; a direct
                // UDP answer from a foreign resolver is a different property and never counts here.
                true -> if (p.recursiveDnsEgress == true) reading(NetworkState.NIN_WITH_DNS_EGRESS, 0.75)
                else reading(NetworkState.DOMESTIC_ONLY_NO_VERIFIED_EGRESS, 0.7)
                false -> reading(NetworkState.FULL_ISOLATION, 0.8)
                // Domestic names did not resolve: no evidence either way about domestic reach.
                null -> if (p.dnsWorking == false) reading(NetworkState.FULL_ISOLATION, 0.5) else reading(NetworkState.UNKNOWN, 0.3)
            }
        }

        val restrictions = linkedSetOf<NetworkState>()
        if (p.dnsManipulated == true) restrictions += NetworkState.DNS_MANIPULATED
        if (p.sniFiltered == true) restrictions += NetworkState.SNI_INTERFERENCE_SUSPECTED
        if (p.udpAvailable == false) restrictions += NetworkState.UDP_BLOCKED
        when (p.quicStatus) {
            "QUIC_BLOCKED_SUSPECTED" -> restrictions += NetworkState.QUIC_BLOCKED
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
