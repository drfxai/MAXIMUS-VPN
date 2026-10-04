package com.example.data.model

import java.util.UUID

/**
 * Strongly typed representation of a normalized proxy node configuration.
 */
data class VlessProfile(
    val id: String = UUID.randomUUID().toString(),
    val canonicalFingerprint: String = "",
    val name: String,
    val address: String,
    val port: Int,
    val uuid: String,
    val encryption: String = "none",
    val transport: String = "tcp", // tcp, ws, grpc, http, h2, quic
    val security: String = "none",  // none, tls, reality
    val sni: String = "",
    val host: String = "",
    val path: String = "",
    val serviceName: String = "",
    val flow: String = "",         // xtls-rprx-vision
    val fingerprint: String = "",  // chrome, firefox, safari, randomized, unsafe
    val cipherSuites: String = "", // custom cipher suites colon or comma separated
    val fakeSniPool: String = "",  // multi-domain fake SNI pool
    val finalMask: String = "",    // raw Xray streamSettings.finalmask JSON (e.g. TLS fragment)
    val desyncEnabled: Boolean = true,
    val desyncProfileName: String = "BALANCED",
    val desyncMethodName: String = "FAKE_SNI",
    val desyncSplitPosition: Int = 2,
    val publicKey: String = "",    // REALITY pbk
    val shortId: String = "",      // REALITY sid
    val spiderX: String = "",      // REALITY spx
    val alpn: String = "",         // h2,http/1.1
    val headerType: String = "",   // http, none
    val allowInsecure: Boolean = false,      // trust the pinned certificate instead of public CAs
    val pinnedPeerCertSha256: String = "",   // TLS pcs: hex SHA-256 of the leaf or CA certificate
    val verifyPeerCertByName: String = "",   // TLS vcn: names the certificate may be verified against
    val echConfigList: String = "",          // TLS ECH config list (base64 or DNS name)
    val echSockopt: String = "",             // raw Xray tlsSettings.echSockopt JSON
    val targetStrategy: String = "",         // outbound targetStrategy, e.g. AsIs, UseIPv4
    val extraSettings: String = "",          // JSON of protocol extras, see ProfileExtras
    val isFavorite: Boolean = false,
    val lastLatencyMs: Long? = null,
    val downloadMbps: Double = 0.0,
    val uploadMbps: Double = 0.0,
    val jitterMs: Long = 0,
    val packetLoss: Double = 0.0,
    val handshakeMs: Long = 0,
    val stability: Double = 100.0,
    val successRate: Double = 100.0,
    val speedScore: Double = 0.0,
    val latencyScore: Double = 0.0,
    val stabilityScore: Double = 0.0,
    val reliabilityScore: Double = 0.0,
    val overallScore: Double = 0.0,
    val category: ServerCategory = ServerCategory.BALANCED,
    val lastTestedTimestamp: Long? = null,
    val countryCode: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val profileType: ProfileType = ProfileType.VLESS,
    val protocolType: ProtocolType = ProtocolType.VLESS,
    val engineType: EngineType = EngineType.XRAY,
    val rawConfig: String = "",
    val subscriptionUrl: String? = null,
    val sourceSubscription: String? = null,
    val sourceFile: String? = null,
    val proxyGroupName: String? = null,
    val nodeCount: Int = 1
) {
    val effectiveFingerprint: String
        get() = if (canonicalFingerprint.isNotBlank()) {
            canonicalFingerprint
        } else {
            CanonicalFingerprint.computeFromProfile(this)
        }

    val displaySubtitle: String
        get() = when (profileType) {
            ProfileType.XRAY_JSON -> "Xray JSON Core • $address:$port"
            ProfileType.MIHOMO_YAML -> "Mihomo Meta • $nodeCount Proxies • $address:$port"
            ProfileType.SUBSCRIPTION -> "Subscription • $nodeCount Nodes"
            else -> "$address:$port • ${transport.uppercase()}${if (security != "none" && security.isNotBlank()) "/${security.uppercase()}" else ""}"
        }

    val securityBadge: String
        get() = when {
            protocolType == ProtocolType.HYSTERIA2 -> "HY2"
            protocolType == ProtocolType.WIREGUARD -> "WG"
            security.equals("reality", ignoreCase = true) -> "REALITY"
            security.equals("tls", ignoreCase = true) || security.equals("ssl", ignoreCase = true) -> "TLS"
            protocolType == ProtocolType.SHADOWSOCKS -> "SS"
            protocolType == ProtocolType.TROJAN -> "TROJAN"
            protocolType == ProtocolType.VMESS -> "VMESS"
            protocolType == ProtocolType.TUIC -> "TUIC"
            protocolType == ProtocolType.SOCKS5 -> "SOCKS5"
            protocolType == ProtocolType.HTTP -> "HTTP"
            else -> if (security.isNotBlank() && security != "none") security.uppercase() else "PLAIN"
        }

    val engineBadge: String
        get() = when (engineType) {
            EngineType.MIHOMO -> "MIHOMO"
            EngineType.XRAY -> "XRAY"
            EngineType.AUTO -> if (profileType == ProfileType.MIHOMO_YAML || protocolType == ProtocolType.TUIC) "MIHOMO" else "XRAY"
        }
}
