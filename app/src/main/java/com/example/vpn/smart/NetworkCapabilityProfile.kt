package com.example.vpn.smart

import org.json.JSONObject

/**
 * What the phone's own network (outside the VPN) was measured to allow. Each field is a measurement:
 * true, false, or null when it was not measured. Nothing here names the user, the phone or the SIM;
 * routing decisions use these capabilities, not the operator's name.
 */
data class NetworkCapabilityProfile(
    /** wifi / cellular / ethernet / other: the kind of link, never its name. */
    val transport: String = "other",
    val ipv4Available: Boolean? = null,
    val ipv6Available: Boolean? = null,
    val udpAvailable: Boolean? = null,
    val tcpAvailable: Boolean? = null,
    val tlsAvailable: Boolean? = null,
    val http2Available: Boolean? = null,
    val quicAvailable: Boolean? = null,
    val cloudflareReachable: Boolean? = null,
    val echCapable: Boolean? = null,
    val uploadConstrained: Boolean? = null,
    val dnsWorking: Boolean? = null,
    val meteredNetwork: Boolean? = null,
    /** Diagnostic only (MCC+MNC of the carrier, no name, no number); never used to choose a route. */
    val carrierCode: String? = null,
    val measuredAt: Long = 0L,
    /** The network's DNS answered a foreign name with a block-page or private address. */
    val dnsManipulated: Boolean? = null,
    /** A DNS-over-HTTPS resolver at an IP literal answered over this network. */
    val dohReachable: Boolean? = null,
    /** How many of the international TLS references completed a handshake, out of [internationalTried]. */
    val internationalOk: Int? = null,
    val internationalTried: Int? = null,
    /** A domestic reference host accepted a TCP connection (it says nothing about leaving the country). */
    val domesticReachable: Boolean? = null,
    /**
     * TLS to the same address passed with a neutral SNI and was reset or silenced with a commonly filtered
     * one. Null when either handshake could not be compared (for example the neutral one failed too).
     */
    val sniFiltered: Boolean? = null
) {
    /** True when at least one international reference completed TLS; false only when all were tried and failed. */
    val internationalReachable: Boolean? get() = when {
        internationalTried == null || internationalTried == 0 -> null
        (internationalOk ?: 0) > 0 -> true
        else -> false
    }

    /**
     * An anonymous bucket for grouping measurements: the transport plus the capabilities that change
     * which servers can work. Two phones on the same kind of network share it.
     */
    fun key(): String = buildString {
        append(transport)
        append(":v4=").append(flag(ipv4Available))
        append(",v6=").append(flag(ipv6Available))
        append(",udp=").append(flag(udpAvailable))
        append(",tls=").append(flag(tlsAvailable))
        append(",cf=").append(flag(cloudflareReachable))
        append(",dns=").append(flag(dnsWorking))
    }

    /** Readable observations, one per measured capability; unmeasured ones are said to be unmeasured. */
    fun observations(): List<String> = listOf(
        "IPv4" to ipv4Available, "IPv6" to ipv6Available, "UDP" to udpAvailable, "TCP" to tcpAvailable,
        "TLS" to tlsAvailable, "HTTP/2" to http2Available, "QUIC" to quicAvailable,
        "Cloudflare reachable" to cloudflareReachable, "ECH" to echCapable, "DNS" to dnsWorking,
        "Upload constrained" to uploadConstrained, "Metered" to meteredNetwork,
        "DNS answers tampered" to dnsManipulated, "Encrypted DNS (DoH) reachable" to dohReachable,
        "International TLS" to internationalReachable, "Domestic sites reachable" to domesticReachable,
        "SNI filtering" to sniFiltered
    ).map { (name, value) ->
        when (value) {
            true -> "$name: yes"
            false -> "$name: no"
            null -> "$name: not measured"
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("transport", transport)
        fun opt(name: String, v: Boolean?) = put(name, v ?: JSONObject.NULL)
        opt("ipv4Available", ipv4Available)
        opt("ipv6Available", ipv6Available)
        opt("udpAvailable", udpAvailable)
        opt("tcpAvailable", tcpAvailable)
        opt("tlsAvailable", tlsAvailable)
        opt("http2Available", http2Available)
        opt("quicAvailable", quicAvailable)
        opt("cloudflareReachable", cloudflareReachable)
        opt("echCapable", echCapable)
        opt("uploadConstrained", uploadConstrained)
        opt("dnsWorking", dnsWorking)
        opt("meteredNetwork", meteredNetwork)
        put("carrierCode", carrierCode ?: JSONObject.NULL)
        put("measuredAt", measuredAt)
        opt("dnsManipulated", dnsManipulated)
        opt("dohReachable", dohReachable)
        put("internationalOk", internationalOk ?: JSONObject.NULL)
        put("internationalTried", internationalTried ?: JSONObject.NULL)
        opt("domesticReachable", domesticReachable)
        opt("sniFiltered", sniFiltered)
    }

    companion object {
        private fun flag(v: Boolean?) = when (v) { true -> "1"; false -> "0"; null -> "?" }

        fun fromJson(o: JSONObject): NetworkCapabilityProfile {
            fun b(name: String): Boolean? = if (!o.has(name) || o.isNull(name)) null else o.optBoolean(name)
            return NetworkCapabilityProfile(
                transport = o.optString("transport", "other"),
                ipv4Available = b("ipv4Available"), ipv6Available = b("ipv6Available"),
                udpAvailable = b("udpAvailable"), tcpAvailable = b("tcpAvailable"), tlsAvailable = b("tlsAvailable"),
                http2Available = b("http2Available"), quicAvailable = b("quicAvailable"),
                cloudflareReachable = b("cloudflareReachable"), echCapable = b("echCapable"),
                uploadConstrained = b("uploadConstrained"), dnsWorking = b("dnsWorking"),
                meteredNetwork = b("meteredNetwork"),
                carrierCode = if (o.isNull("carrierCode")) null else o.optString("carrierCode").ifBlank { null },
                measuredAt = o.optLong("measuredAt"),
                dnsManipulated = b("dnsManipulated"), dohReachable = b("dohReachable"),
                internationalOk = if (!o.has("internationalOk") || o.isNull("internationalOk")) null else o.optInt("internationalOk"),
                internationalTried = if (!o.has("internationalTried") || o.isNull("internationalTried")) null else o.optInt("internationalTried"),
                domesticReachable = b("domesticReachable"), sniFiltered = b("sniFiltered")
            )
        }
    }
}
