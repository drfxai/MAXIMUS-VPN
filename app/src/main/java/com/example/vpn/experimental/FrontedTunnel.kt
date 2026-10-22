package com.example.vpn.experimental

import com.example.data.model.ProfileType
import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.vpn.EndpointResolver
import java.net.IDN
import java.net.InetAddress
import java.util.UUID

/** Opt-in SNI/Host separation for a user-configured TLS front and compatible WS origin.
 * No arbitrary decoy bytes, forged source address, insecure certificate mode or inferred CDN support.
 */
object FrontedTunnel {
    fun domain(raw: String): String {
        val name = IDN.toASCII(raw.trim().trimEnd('.')).lowercase()
        require(name.length in 3..253 && name.contains('.') && !name.all { it.isDigit() || it == '.' }) { "Enter a DNS host name" }
        require(name.split('.').all { it.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) }) { "Invalid DNS host name" }
        return name
    }

    fun publicAddress(raw: String): String {
        val literal = raw.trim().removePrefix("[").removeSuffix("]")
        require(literal.matches(Regex("[0-9.]+")) ||
            (literal.contains(':') && literal.matches(Regex("[0-9a-fA-F:.]+")))) { "Use a literal public IP address" }
        if (!literal.contains(':')) require(literal.split('.').size == 4 && literal.split('.').all { it.toIntOrNull() in 0..255 })
        val address = InetAddress.getByName(literal)
        require(!EndpointResolver.isBlockedAnswer(address)) { "Private or reserved address is not a public front" }
        return address.hostAddress ?: error("Invalid IP address")
    }

    fun build(base: VlessProfile, endpoint: String, tlsName: String, originHost: String): VlessProfile {
        require(base.profileType == ProfileType.VLESS && base.protocolType == ProtocolType.VLESS &&
            base.security.equals("tls", true) && base.transport.equals("ws", true) && base.rawConfig.isBlank()) {
            "Choose a VLESS WebSocket TLS profile"
        }
        require(!base.allowInsecure && base.verifyPeerCertByName.isBlank() && base.pinnedPeerCertSha256.isBlank()) {
            "Use a profile with normal CA and host-name verification"
        }
        return base.copy(id = UUID.randomUUID().toString(), canonicalFingerprint = "", name = "Fronted · ${base.name}",
            address = publicAddress(endpoint), sni = domain(tlsName), host = domain(originHost),
            echConfigList = "", echSockopt = "", allowInsecure = false,
            desyncEnabled = false, desyncMethodName = "NONE", lastLatencyMs = null,
            lastTestedTimestamp = null, subscriptionUrl = null, sourceSubscription = null, sourceFile = null,
            isFavorite = false, createdAt = System.currentTimeMillis(), downloadMbps = 0.0, uploadMbps = 0.0,
            jitterMs = 0, packetLoss = 0.0, handshakeMs = 0, overallScore = 0.0,
            speedScore = 0.0, latencyScore = 0.0, stabilityScore = 0.0, reliabilityScore = 0.0)
    }
}
