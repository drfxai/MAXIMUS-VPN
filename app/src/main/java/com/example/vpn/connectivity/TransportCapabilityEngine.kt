package com.example.vpn.connectivity

import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.vpn.smart.NetworkCapabilityProfile

/**
 * Which transports the phone's own network can carry right now, from measurements
 * ([NetworkCapabilityProfile]) and recent results per transport on this network, and what that means for
 * the order servers are tried in. No transport is assumed better than another:
 *
 * - UDP / QUIC / HTTP/3 measured unavailable: UDP servers are skipped, not hammered. UDP failing again and
 *   again on this network (with no success): they go after TCP ones, and an HTTP/3 config gets its TCP
 *   (HTTP/2) form offered where the config allows it.
 * - IPv6 measured unavailable: IPv6 endpoints are skipped, never forced. IPv4 failing here while IPv6
 *   works: IPv6 endpoints compete first.
 * - Anything not measured counts as possible.
 */
object TransportCapabilityEngine {
    enum class Transport { UDP, TCP_TLS, TCP_PLAIN }
    enum class Family { IPV4, IPV6, UNKNOWN }
    enum class Admission { ALLOW, DEPRIORITIZE, SKIP }

    /** Recent outcomes per transport and address family on this network. */
    data class History(
        val udpSuccesses: Int = 0, val udpFailures: Int = 0,
        val ipv4Successes: Int = 0, val ipv4Failures: Int = 0,
        val ipv6Successes: Int = 0, val ipv6Failures: Int = 0
    )

    data class Capabilities(
        val ipv4: Boolean?, val ipv6: Boolean?, val udp: Boolean?, val tcp: Boolean?, val tls: Boolean?,
        val http2: Boolean?, val http3: Boolean?, val quic: Boolean?, val ech: Boolean?, val dns: Boolean?, val cloudflare: Boolean?,
        /** UDP worked for nothing lately on this network although it was not measured as blocked. */
        val udpImpaired: Boolean,
        /** IPv4 paths fail here while IPv6 ones work. */
        val ipv4Impaired: Boolean
    )

    data class Decision(val admission: Admission, val reason: String)

    /** Failures in a row (with no success) before a transport counts as impaired on a network. */
    const val IMPAIRED_AFTER = 3

    fun capabilities(net: NetworkCapabilityProfile?, history: History = History()): Capabilities {
        val udpImpaired = net?.udpAvailable != false && history.udpSuccesses == 0 && history.udpFailures >= IMPAIRED_AFTER
        val v4Impaired = history.ipv4Successes == 0 && history.ipv4Failures >= IMPAIRED_AFTER && history.ipv6Successes > 0
        return Capabilities(
            ipv4 = net?.ipv4Available, ipv6 = net?.ipv6Available, udp = net?.udpAvailable, tcp = net?.tcpAvailable,
            tls = net?.tlsAvailable, http2 = net?.http2Available,
            // HTTP/3 runs over QUIC over UDP: without UDP it cannot work.
            http3 = if (net?.udpAvailable == false) false else net?.quicAvailable,
            quic = if (net?.udpAvailable == false) false else net?.quicAvailable,
            ech = net?.echCapable, dns = net?.dnsWorking, cloudflare = net?.cloudflareReachable,
            udpImpaired = udpImpaired, ipv4Impaired = v4Impaired
        )
    }

    fun transportOf(p: VlessProfile): Transport = when {
        p.protocolType == ProtocolType.HYSTERIA2 || p.protocolType == ProtocolType.WIREGUARD || p.protocolType == ProtocolType.TUIC -> Transport.UDP
        p.transport.lowercase() == "quic" || usesHttp3(p) -> Transport.UDP
        p.security.equals("tls", true) || p.security.equals("reality", true) -> Transport.TCP_TLS
        else -> Transport.TCP_PLAIN
    }

    fun usesHttp3(p: VlessProfile): Boolean =
        p.alpn.split(',').map { it.trim().lowercase() } .let { it.isNotEmpty() && it.all { a -> a == "h3" } }

    /** The address family of the endpoint, when it is an IP literal. */
    fun familyOf(p: VlessProfile): Family {
        val a = p.address.removePrefix("[").removeSuffix("]")
        return when {
            RecoverySecurityGate.isIpLiteral(a) && a.contains(':') -> Family.IPV6
            RecoverySecurityGate.isIpLiteral(a) -> Family.IPV4
            else -> Family.UNKNOWN
        }
    }

    fun decide(p: VlessProfile, c: Capabilities): Decision {
        val transport = transportOf(p)
        val family = familyOf(p)
        if (family == Family.IPV6 && c.ipv6 == false) return Decision(Admission.SKIP, "IPv6 is not available on this network")
        if (family == Family.IPV4 && c.ipv4 == false) return Decision(Admission.SKIP, "IPv4 is not available on this network")
        if (transport == Transport.UDP && c.udp == false) return Decision(Admission.SKIP, "UDP is blocked on this network")
        if (transport == Transport.UDP && usesHttp3(p) && c.quic == false) return Decision(Admission.SKIP, "QUIC is blocked on this network")
        if (transport == Transport.UDP && c.udpImpaired) return Decision(Admission.DEPRIORITIZE, "UDP failed repeatedly here; TCP first")
        if (family == Family.IPV4 && c.ipv4Impaired) return Decision(Admission.DEPRIORITIZE, "IPv4 paths fail here while IPv6 works")
        if (transport != Transport.UDP && c.tcp == false) return Decision(Admission.DEPRIORITIZE, "TCP looked blocked when measured")
        return Decision(Admission.ALLOW, "allowed")
    }

    /** [candidates] in their given order, deprioritized ones after allowed ones, skipped ones removed. */
    fun order(candidates: List<VlessProfile>, c: Capabilities): List<VlessProfile> {
        val decided = candidates.map { it to decide(it, c).admission }
        return decided.filter { it.second == Admission.ALLOW }.map { it.first } +
            decided.filter { it.second == Admission.DEPRIORITIZE }.map { it.first }
    }

    /**
     * The TCP form of an HTTP/3 config (ALPN h2, then HTTP/1.1) when QUIC is unavailable or impaired;
     * null when the config has no TCP form. The copy passes the same security gate as recovery.
     */
    fun h2Fallback(p: VlessProfile): VlessProfile? {
        if (!usesHttp3(p) || p.transport.lowercase() !in setOf("xhttp", "splithttp") || !p.security.equals("tls", true)) return null
        val copy = p.copy(alpn = "h2,http/1.1")
        return copy.takeIf { RecoverySecurityGate.check(p, it).passed }
    }
}
