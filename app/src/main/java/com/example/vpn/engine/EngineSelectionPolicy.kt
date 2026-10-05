package com.example.vpn.engine

import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile

/**
 * Picks the runtime that carries a profile's traffic. Only two exist in the app: the bundled native
 * Xray core and the Kotlin packet tunnel. The Mihomo adapter has no native core, so nothing is ever
 * routed to it, whatever the profile's engineType or the Settings engine preference say.
 */
object EngineSelectionPolicy {
    enum class Runtime { XRAY, KOTLIN_TUNNEL }

    fun select(profile: VlessProfile): Runtime =
        if (usesKotlinPacketTunnel(profile)) Runtime.KOTLIN_TUNNEL else Runtime.XRAY

    /** Profiles forwarded by TunnelManager, which currently understands IPv4 packets only. */
    fun usesKotlinPacketTunnel(profile: VlessProfile): Boolean =
        requiresKotlinTunnel(profile) || profile.protocolType in setOf(ProtocolType.HTTP, ProtocolType.SOCKS5)

    fun requiresKotlinTunnel(profile: VlessProfile): Boolean =
        profile.protocolType == ProtocolType.VLESS &&
            profile.transport.equals("tcp", ignoreCase = true) &&
            profile.security.isBlankOrNone() &&
            profile.encryption.isBlankOrNone() &&
            profile.flow.isBlank()

    private fun String.isBlankOrNone(): Boolean = isBlank() || equals("none", ignoreCase = true)
}
