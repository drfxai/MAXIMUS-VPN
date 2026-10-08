package com.example.vpn.lab

/**
 * What Maximus actually supports, per layer (spec section 34), so nothing parse-only is shown or tested as
 * working. Each entry was checked against the code named in [evidence] for Xray-core v26.9.9
 * (scripts/fetch-libxray-android.sh). "Parser" means share links; Xray JSON imports pass every Xray field
 * through and are not counted here. Updating the core means re-checking this table (CoreUpdatePolicy).
 */
object CoreCapabilityRegistry {
    enum class Support { YES, PARTIAL, NO, UNKNOWN }

    data class Capability(
        val id: String,
        val title: String,
        val parser: Support,
        val model: Support,
        val builder: Support,
        val runtime: Support,
        val ui: Support,
        val evidence: String,
        val note: String = ""
    ) {
        /** Working end to end: LAB may use it in candidates and the UI may show it as available. */
        val usable: Boolean get() = model == Support.YES && builder == Support.YES && runtime == Support.YES
    }

    const val CORE = "Xray-core v26.9.9"

    val ALL: List<Capability> = listOf(
        Capability("ech", "ECH", Support.YES, Support.YES, Support.YES, Support.YES, Support.YES,
            "vless/VlessParser.kt (ech), VlessProfile.echConfigList/echSockopt, XrayConfigBuilder, EditConfigScreen",
            "ECH reachability of a network is not probed yet (NetworkCapabilityProfile.echCapable stays null)."),
        Capability("fragment", "Fragment / finalmask", Support.PARTIAL, Support.YES, Support.YES, Support.YES, Support.YES,
            "VlessProfile.finalMask, XrayConfigBuilder finalmask, FragmentProfileEngine",
            "Share links carry it for Hysteria2 only (ProtocolLinks); other links need Edit Configuration or JSON."),
        Capability("cipher-suites", "Cipher-suite override", Support.NO, Support.YES, Support.YES, Support.YES, Support.YES,
            "VlessProfile.cipherSuites, XrayConfigBuilder", "Takes effect only with the Go TLS stack (fingerprint \"unsafe\")."),
        Capability("tls-fingerprint", "TLS fingerprints", Support.YES, Support.YES, Support.YES, Support.YES, Support.YES,
            "VlessParser (fp), RecoverySecurityGate.TLS_FINGERPRINTS"),
        Capability("xhttp", "XHTTP", Support.YES, Support.YES, Support.YES, Support.YES, Support.YES, "VlessParser, XrayConfigBuilder xhttpSettings"),
        Capability("h2", "HTTP/2 transport", Support.YES, Support.YES, Support.YES, Support.YES, Support.YES, "VlessParser, XrayConfigBuilder"),
        Capability("h3", "HTTP/3 (XHTTP over QUIC)", Support.PARTIAL, Support.YES, Support.YES, Support.YES, Support.PARTIAL,
            "VlessProfile.alpn=h3, TransportCapabilityEngine.h2Fallback", "Needs UDP; QUIC reachability is not probed yet."),
        Capability("masque", "MASQUE", Support.NO, Support.NO, Support.NO, Support.NO, Support.NO, "none",
            "Not implemented; never offered."),
        Capability("warp", "WARP", Support.NO, Support.PARTIAL, Support.YES, Support.YES, Support.YES,
            "vpn/warp/WarpProvider.kt (WireGuard registration)", "WireGuard WARP only; WARP over MASQUE (Aether) is not supported."),
        Capability("ipv6", "IPv6 endpoints", Support.YES, Support.YES, Support.YES, Support.YES, Support.YES,
            "TransportCapabilityEngine.familyOf, VpnRoutePolicy (TUN captures IPv6)"),
        Capability("chain", "Proxy chains", Support.NO, Support.PARTIAL, Support.PARTIAL, Support.PARTIAL, Support.NO,
            "vpn/sidecar/SidecarChain.kt", "Only Xray in front of a bundled sidecar engine; no user-defined chains."),
        Capability("hysteria2", "Hysteria2", Support.YES, Support.YES, Support.YES, Support.YES, Support.YES, "ProtocolLinks, XrayConfigBuilder"),
        Capability("wireguard", "WireGuard", Support.YES, Support.YES, Support.YES, Support.YES, Support.YES, "WireGuardConf, XrayConfigBuilder"),
        Capability("tuic", "TUIC", Support.YES, Support.YES, Support.NO, Support.NO, Support.NO, "RuntimeCapabilities",
            "Parsed but refused at connect: the bundled Xray core has no TUIC client."),
        Capability("vless-encryption", "VLESS Encryption (ML-KEM)", Support.YES, Support.YES, Support.YES, Support.YES, Support.PARTIAL,
            "RuntimeCapabilities.isVlessEncryption")
    )

    fun byId(id: String): Capability? = ALL.firstOrNull { it.id == id }

    /** The capability a LAB mutation needs; a mutation whose capability is not usable is never generated. */
    fun forMutation(mutationProfileId: String): Capability? = when {
        mutationProfileId.startsWith("ech") -> byId("ech")
        mutationProfileId.contains("fragment") -> byId("fragment")
        mutationProfileId.startsWith("fingerprint") -> byId("tls-fingerprint")
        mutationProfileId.startsWith("endpoint-ipv6") || mutationProfileId == CandidateGenerator.FAMILY_V6 -> byId("ipv6")
        mutationProfileId == CandidateGenerator.TRANSPORT_H2 -> byId("h2")
        else -> null
    }
}

/**
 * The rules for replacing the bundled core (spec section 35). Not automated: the checklist is enforced by the
 * pinned SHA-256 in scripts/fetch-libxray-android.sh and by CI, and this list is what a reviewer signs off.
 */
object CoreUpdatePolicy {
    val CHECKLIST = listOf(
        "Read the release's source changes and confirm it is an upstream tag",
        "Build or fetch it reproducibly and pin its SHA-256 in scripts/fetch-libxray-android.sh",
        "Confirm the Android ABIs and minSdk still load it",
        "Check API and config-field changes against XrayConfigBuilder and RecoverySecurityGate.TLS_FINGERPRINTS",
        "Re-check every row of CoreCapabilityRegistry against the new core",
        "Run unit, regression and VPN security tests (DNS leak, IPv6 leak, kill switch, certificate checks)"
    )
}
