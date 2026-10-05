package com.example.vpn.stealth

import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile

/**
 * The kind of traffic a censor sees: QUIC (Hysteria2), WireGuard, REALITY, a CDN path (WebSocket,
 * gRPC, XHTTP or HTTPUpgrade over TLS), direct TLS, or unencrypted.
 */
object ConnectionKind {
    /** Kinds in the order they are tried on the same server when one is blocked. */
    val ORDER = listOf("REALITY", "CDN", "TLS", "QUIC", "WireGuard", "plain")

    private val CDN_TRANSPORTS = setOf("ws", "grpc", "xhttp", "splithttp", "httpupgrade", "http", "h2")

    fun of(profile: VlessProfile): String {
        val security = profile.security.lowercase()
        return when {
            profile.protocolType == ProtocolType.HYSTERIA2 -> "QUIC"
            profile.protocolType == ProtocolType.WIREGUARD -> "WireGuard"
            security == "reality" -> "REALITY"
            security == "tls" && profile.transport.lowercase() in CDN_TRANSPORTS -> "CDN"
            security == "tls" -> "TLS"
            else -> "plain"
        }
    }
}
