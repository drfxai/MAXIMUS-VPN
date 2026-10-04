package com.example.vpn.engine

import com.example.data.model.ProtocolType
import com.example.data.model.ProfileType
import com.example.data.model.VlessProfile

/** Import compatibility is broader than the Xray-core and Kotlin forwarding runtimes. */
object RuntimeCapabilities {
    fun unsupportedReason(profile: VlessProfile): String? = when {
        profile.profileType == ProfileType.MIHOMO_YAML ->
            "A bundled Mihomo configuration needs the native Mihomo core, which is not included. Select an Xray-compatible node instead."
        profile.profileType == ProfileType.XRAY_JSON -> null
        profile.protocolType !in setOf(ProtocolType.VLESS, ProtocolType.TROJAN, ProtocolType.VMESS, ProtocolType.SHADOWSOCKS, ProtocolType.HTTP, ProtocolType.SOCKS5) ->
            "${profile.protocolType.displayName} is not supported by the bundled Xray core or Kotlin compatibility tunnel."
        profile.transport.lowercase() !in setOf("tcp", "ws", "grpc", "http", "h2", "xhttp", "splithttp") ->
            "Transport ${profile.transport} is not supported by the current Xray configuration adapter."
        profile.security.lowercase() !in setOf("", "none", "tls", "reality") ->
            "Security ${profile.security} is not supported by the bundled Xray core."
        profile.security.equals("reality", ignoreCase = true) && (profile.publicKey.isBlank() || profile.sni.isBlank()) ->
            "REALITY requires both the server public key and SNI."
        profile.finalMask.isNotBlank() && runCatching { org.json.JSONObject(profile.finalMask) }.isFailure ->
            "The finalMask setting is not valid JSON."
        profile.echSockopt.isNotBlank() && runCatching { org.json.JSONObject(profile.echSockopt) }.isFailure ->
            "The echSockopt setting is not valid JSON."
        profile.pinnedPeerCertSha256.isNotBlank() && !isValidCertPin(profile.pinnedPeerCertSha256) ->
            "The certificate fingerprint must be one or more 64-character SHA-256 hex values."
        profile.allowInsecure && profile.pinnedPeerCertSha256.isBlank() ->
            "allowInsecure needs the server's certificate fingerprint. Fetch it in Edit Configuration."
        profile.targetStrategy.isNotBlank() && TARGET_STRATEGIES.none { it.equals(profile.targetStrategy, ignoreCase = true) } ->
            "Unknown targetStrategy ${profile.targetStrategy}."
        profile.headerType.lowercase() !in setOf("", "none", "http") -> "TCP header ${profile.headerType} is not supported by the Xray configuration adapter."
        profile.protocolType == ProtocolType.VLESS && profile.encryption.lowercase() !in setOf("", "none") ->
            "This VLESS encryption mode requires a native Xray core."
        profile.protocolType == ProtocolType.TROJAN && !profile.security.equals("tls", ignoreCase = true) ->
            "Trojan requires TLS; refusing to send credentials over an unencrypted transport."
        profile.flow.isNotBlank() && (profile.protocolType != ProtocolType.VLESS || profile.transport.lowercase() !in setOf("", "tcp") || profile.security.lowercase() !in setOf("tls", "reality")) ->
            "VLESS flow ${profile.flow} requires VLESS over TCP with TLS or REALITY."
        profile.protocolType in setOf(ProtocolType.HTTP, ProtocolType.SOCKS5) && profile.transport.lowercase() !in setOf("", "tcp") ->
            "HTTP and SOCKS proxies require TCP transport."
        profile.protocolType in setOf(ProtocolType.HTTP, ProtocolType.SOCKS5) && (profile.uuid.isNotBlank() || profile.security.lowercase() !in setOf("", "none")) ->
            "Authenticated HTTP/SOCKS proxies are not supported by the Kotlin compatibility tunnel."
        else -> null
    }
    val TARGET_STRATEGIES = listOf(
        "AsIs", "UseIP", "UseIPv4", "UseIPv6", "UseIPv4v6", "UseIPv6v4",
        "ForceIP", "ForceIPv4", "ForceIPv6", "ForceIPv4v6", "ForceIPv6v4"
    )

    /** Xray's pinnedPeerCertSha256: comma separated SHA-256 hashes, hex with optional colons. */
    fun isValidCertPin(value: String): Boolean {
        val pins = value.split(',').map { it.trim().replace(":", "") }.filter { it.isNotEmpty() }
        return pins.isNotEmpty() && pins.all { it.length == 64 && it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }
    }

    fun requireSupported(profile: VlessProfile) {
        unsupportedReason(profile)?.let { throw IllegalArgumentException(it) }
    }
}
