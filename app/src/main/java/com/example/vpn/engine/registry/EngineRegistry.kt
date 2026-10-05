package com.example.vpn.engine.registry

import com.example.vpn.engine.EngineSelectionPolicy

/**
 * Every engine the app has or plans, with what it can carry and where it comes from. New engines
 * (Mihomo, AmneziaWG, Psiphon, Tor, DNS tunnels...) are added here first, as [EngineDescriptor.bundled]
 * false, and become selectable only once bundled, license-reviewed and pinned.
 *
 * [CAPABILITIES] is the protocol matrix from the V1.0.1 audit (docs/V101_AUDIT.md); a test keeps the
 * two from drifting from what RuntimeCapabilities actually accepts.
 */
object EngineRegistry {
    data class EngineDescriptor(
        val id: String,
        val name: String,
        /** Upstream version the build pins, or null when not bundled. */
        val version: String?,
        /** SPDX license of what is shipped. */
        val license: String,
        val bundled: Boolean,
        /** The runtime it maps to when bundled. */
        val runtime: EngineSelectionPolicy.Runtime?
    )

    val XRAY = EngineDescriptor("xray", "Xray-core (libXray)", "libXray v26.9.9", "MPL-2.0 (Xray-core), MIT (libXray)",
        bundled = true, runtime = EngineSelectionPolicy.Runtime.XRAY)
    val KOTLIN_TUNNEL = EngineDescriptor("kotlin-tunnel", "Kotlin packet tunnel", "in app", "Proprietary (this app)",
        bundled = true, runtime = EngineSelectionPolicy.Runtime.KOTLIN_TUNNEL)
    /**
     * Engines the plan calls for that are not bundled. Each needs its own native library, a license
     * decision and a pinned, hash-checked download before it can appear as a runtime.
     */
    /** Separate engine programs (vpn/sidecar): bundled, but carried behind Xray rather than being a runtime. */
    val MIHOMO = EngineDescriptor("mihomo", "Mihomo (separate program)", com.example.vpn.sidecar.MihomoSidecar.VERSION, "GPL-3.0",
        bundled = true, runtime = null)
    val AMNEZIAWG = EngineDescriptor("amneziawg", "amneziawg-go", null, "MIT", bundled = false, runtime = null)
    /** WARP needs no engine of its own: a registered device runs on Xray's WireGuard client. */
    val WARP = EngineDescriptor("warp", "Cloudflare WARP (WireGuard on Xray)", "in app", "Proprietary (this app)",
        bundled = true, runtime = null)
    val PSIPHON = EngineDescriptor("psiphon", "Psiphon tunnel-core", null, "GPL-3.0", bundled = false, runtime = null)
    val TOR = EngineDescriptor("tor", "Tor with pluggable transports", null, "BSD-3-Clause (Tor), MIT (lyrebird)",
        bundled = false, runtime = null)
    val NAIVE = EngineDescriptor("naive", "NaiveProxy", null, "BSD-3-Clause", bundled = false, runtime = null)
    val DNS_TUNNEL = EngineDescriptor("dns-tunnel", "DNS tunnel (dnstt-style)", null, "MIT", bundled = false, runtime = null)

    val ENGINES = listOf(XRAY, KOTLIN_TUNNEL, MIHOMO, AMNEZIAWG, WARP, PSIPHON, TOR, NAIVE, DNS_TUNNEL)

    fun descriptorFor(runtime: EngineSelectionPolicy.Runtime): EngineDescriptor =
        ENGINES.first { it.runtime == runtime }

    /** Protocol or feature -> how far it has come. */
    val CAPABILITIES: Map<String, CapabilityState> = linkedMapOf(
        "VLESS REALITY Vision" to CapabilityState.VERIFIED_WORKING,
        "VLESS WebSocket TLS (CDN)" to CapabilityState.VERIFIED_WORKING,
        "VLESS Encryption" to CapabilityState.VERIFIED_WORKING,
        "Hysteria2" to CapabilityState.VERIFIED_WORKING,
        "WireGuard" to CapabilityState.VERIFIED_WORKING,
        "VLESS plain TCP" to CapabilityState.ENGINE_SUPPORTED,
        "VMess" to CapabilityState.ENGINE_SUPPORTED,
        "Trojan" to CapabilityState.ENGINE_SUPPORTED,
        "Shadowsocks" to CapabilityState.ENGINE_SUPPORTED,
        "XHTTP" to CapabilityState.ENGINE_SUPPORTED,
        "gRPC" to CapabilityState.ENGINE_SUPPORTED,
        "HTTPUpgrade" to CapabilityState.ENGINE_SUPPORTED,
        "SOCKS5 without login" to CapabilityState.ENGINE_SUPPORTED,
        "Xray JSON" to CapabilityState.ENGINE_SUPPORTED,
        "AmneziaWG junk packets" to CapabilityState.ENGINE_SUPPORTED,
        "TUIC (Mihomo)" to CapabilityState.VERIFIED_WORKING,
        "Mihomo / Clash proxies" to CapabilityState.ENGINE_SUPPORTED,
        "AmneziaWG custom headers (Mihomo)" to CapabilityState.ENGINE_SUPPORTED,
        "Cloudflare WARP" to CapabilityState.ENGINE_SUPPORTED,
        "HTTP proxy with login" to CapabilityState.PARSE_SUPPORTED,
        "Psiphon" to CapabilityState.NONE,
        "Tor" to CapabilityState.NONE,
        "NaiveProxy" to CapabilityState.NONE,
        "DNS tunnels" to CapabilityState.NONE
    )

    fun verified(): List<String> = CAPABILITIES.filterValues { it == CapabilityState.VERIFIED_WORKING }.keys.toList()
}
