package com.example.panels.cloudflare

import com.example.panels.cloudflare.SmartDomains.Use
import com.example.panels.cloudflare.SmartDomains.Zone
import java.security.SecureRandom

/**
 * What Smart domains fills in once the token works: the zone, the server, the use (and so the proxy
 * setting) and a hard-to-guess label. Pure, so every decision is tested on the JVM; the screen only
 * shows the result and creates it with the usual create, which never overwrites a record.
 */
object SmartFill {
    /** The parts of a saved config the fill needs. [protocol] is the protocol name ("VLESS", "Hysteria2"). */
    data class Server(
        val name: String,
        val protocol: String,
        val transport: String,
        val security: String,
        val address: String,
        val port: Int,
        val country: String? = null
    )

    /** A ready-to-create name: [label] in the zone, an A/AAAA record to [address], for [use]. */
    data class Fill(
        val server: Server,
        val title: String,
        val use: Use,
        val label: String,
        val recordType: String,
        val reason: String
    )

    private val CDN_TRANSPORTS = setOf("ws", "websocket", "xhttp", "splithttp", "grpc", "gun", "httpupgrade")
    private val CDN_PROTOCOLS = setOf("vless", "vmess", "trojan")

    /**
     * The zone to use: the only active zone when there is exactly one, else the one picked before, else the
     * first active one, else the first. With more than one zone the screen keeps its picker.
     */
    fun zone(zones: List<Zone>, savedId: String): Zone? {
        val active = zones.filter { it.status == "active" }
        if (active.size == 1) return active.single()
        return zones.firstOrNull { it.id == savedId } ?: active.firstOrNull() ?: zones.firstOrNull()
    }

    /**
     * CDN (proxied) for WebSocket, XHTTP, gRPC or HTTPUpgrade over TLS on one of Cloudflare's HTTPS ports;
     * DIRECT (DNS only) for everything else: REALITY, Hysteria2, TUIC, WireGuard, SSH, plain TCP, other ports.
     */
    fun useFor(s: Server): Use {
        val cdn = s.protocol.trim().lowercase() in CDN_PROTOCOLS &&
            s.transport.trim().lowercase() in CDN_TRANSPORTS &&
            s.security.trim().lowercase() == "tls" &&
            SmartDomains.portProblem(Use.CDN, s.port) == null
        return if (cdn) Use.CDN else Use.DIRECT
    }

    /** "WebSocket", "gRPC": the transport as people say it, or "" for plain TCP. */
    fun transportName(transport: String): String = when (transport.trim().lowercase()) {
        "ws", "websocket" -> "WebSocket"
        "xhttp", "splithttp" -> "XHTTP"
        "grpc", "gun" -> "gRPC"
        "httpupgrade" -> "HTTPUpgrade"
        "h2", "http" -> "HTTP/2"
        "quic" -> "QUIC"
        else -> ""
    }

    /** "VLESS · WebSocket", "Hysteria2", "VLESS REALITY": the protocol and its transport. */
    fun kind(s: Server): String {
        val reality = s.security.equals("reality", ignoreCase = true)
        return listOfNotNull(
            s.protocol.trim().ifEmpty { "Server" } + if (reality) " REALITY" else "",
            transportName(s.transport).takeIf { it.isNotEmpty() && !reality }
        ).joinToString(" · ")
    }

    /** "VLESS · WebSocket · US", "Hysteria2 · US": [kind] and the country code when the config has one. */
    fun title(s: Server): String {
        val cc = s.country?.trim()?.uppercase()?.takeIf { it.length == 2 && it.all(Char::isLetter) }
        return if (cc != null) "${kind(s)} · $cc" else kind(s)
    }

    /** Why the use was picked, in plain words, for the line under the summary. */
    fun reason(s: Server, use: Use): String {
        val what = kind(s)
        val tail = " Existing records are never overwritten."
        if (use == Use.CDN) {
            return "Picked from the server's protocol: $what over TLS on port ${s.port} can go through Cloudflare's proxy, " +
                "so the name is proxied (orange cloud) and the server's address stays hidden.$tail"
        }
        val cdnShaped = s.protocol.trim().lowercase() in CDN_PROTOCOLS && s.transport.trim().lowercase() in CDN_TRANSPORTS &&
            s.security.trim().lowercase() == "tls"
        val why = if (cdnShaped) "port ${s.port} is not one Cloudflare's proxy carries"
        else "$what can't go through Cloudflare's proxy"
        return "Picked from the server's protocol: $why, so the name is DNS only. " +
            "A WebSocket or gRPC server over TLS would get a proxied (orange cloud) name on port 443.$tail"
    }

    /**
     * The fill for [server], or null when there is nothing to fill: no config selected, or its address is a
     * host name rather than an IP address (the record needs the IP). The screen then shows the form.
     */
    fun fill(server: Server?, random: SecureRandom = SecureRandom()): Fill? {
        if (server == null) return null
        val address = server.address.trim().removePrefix("[").removeSuffix("]")
        if (SmartNames.addressProblem(address) != null) return null
        if (server.port !in 1..65535) return null
        val s = server.copy(address = address)
        val use = useFor(s)
        val type = SmartDomains.desired("x.invalid", address.lowercase(), use).type
        return Fill(s, title(s), use, SmartDomains.randomLabel(SmartNames.prefix(use), random), type, reason(s, use))
    }

    /** "A → 203.0.113.24". */
    fun recordLine(f: Fill): String = "${f.recordType} → ${f.server.address}"

    /** "DNS record (the config keeps its port 40419)". */
    fun recordCaption(f: Fill): String = "DNS record (the config keeps its port ${f.server.port})"
}
