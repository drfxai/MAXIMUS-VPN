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
            "VlessProfile.alpn=h3, TransportCapabilityEngine.h2Fallback", "Needs UDP. The LAB probes QUIC reachability (Version Negotiation) but not an HTTP/3 request."),
        Capability("masque", "MASQUE", Support.NO, Support.NO, Support.NO, Support.NO, Support.NO, "none",
            "Not implemented; never offered."),
        Capability("warp", "WARP", Support.NO, Support.PARTIAL, Support.YES, Support.YES, Support.YES,
            "vpn/warp/WarpProvider.kt (WireGuard registration)", "WireGuard WARP only; WARP over MASQUE (Aether) is not supported."),
        Capability("ipv6", "IPv6 endpoints", Support.YES, Support.YES, Support.YES, Support.YES, Support.YES,
            "TransportCapabilityEngine.familyOf, VpnRoutePolicy (TUN captures IPv6)"),
        Capability("chain", "Proxy chains", Support.NO, Support.PARTIAL, Support.PARTIAL, Support.PARTIAL, Support.NO,
            "vpn/sidecar/SidecarChain.kt, EngineChain.kt", "Xray in front of a bundled engine, or a two-hop Psiphon/Tor chain (EngineChain)."),
        Capability("hysteria2", "Hysteria2", Support.YES, Support.YES, Support.YES, Support.YES, Support.YES, "ProtocolLinks, XrayConfigBuilder"),
        Capability("wireguard", "WireGuard", Support.YES, Support.YES, Support.YES, Support.YES, Support.YES, "WireGuardConf, XrayConfigBuilder"),
        Capability("tuic", "TUIC", Support.YES, Support.YES, Support.NO, Support.NO, Support.NO, "RuntimeCapabilities",
            "Share links are refused at connect: the bundled Xray core has no TUIC client. A TUIC proxy imported from a Clash file runs on Mihomo instead (see CapabilityMatrix)."),
        Capability("amneziawg", "AmneziaWG", Support.PARTIAL, Support.YES, Support.YES, Support.PARTIAL, Support.PARTIAL,
            "Mihomo v1.19.32 AmneziaWGOption (amnezia-wg-option); MihomoSidecar passes the proxy through",
            "Only from Clash files on the bundled Mihomo. The Linux build of the same tag accepted AWG configs with -t; traffic is not verified."),
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

/**
 * Capability Registry V2: one row per engine or feature, one value per layer, so "parsed" is never read as
 * "works". The layers go from code (PARSER, MODEL, BUILDER) through the runtime (present, accepts the config,
 * carried traffic) to where it was exercised (UNIT tests, the censor SIMULATOR, an EMULATOR, a DEVICE, the
 * FIELD). UNKNOWN means nobody has recorded it; it is never shown as working.
 */
object CapabilityMatrix {
    enum class Layer(val title: String) {
        PARSER("Parser"), MODEL("Model"), BUILDER("Builder"), RUNTIME_PRESENT("Runtime present"),
        RUNTIME_CONFIG_ACCEPTED("Runtime accepts config"), RUNTIME_TRAFFIC_VERIFIED("Traffic verified"),
        UNIT("Unit tests"), SIMULATOR("Simulator"), EMULATOR("Emulator"), DEVICE("Device"), FIELD("Field")
    }

    enum class Value { YES, NO, PARTIAL, UNKNOWN }

    data class Row(val id: String, val title: String, val values: Map<Layer, Value>, val evidence: String) {
        fun at(layer: Layer): Value = values[layer] ?: Value.UNKNOWN
        /** Shown as working only with traffic verified at runtime on a device or in the field. */
        val provenWorking: Boolean get() = at(Layer.RUNTIME_TRAFFIC_VERIFIED) == Value.YES && (at(Layer.DEVICE) == Value.YES || at(Layer.FIELD) == Value.YES)
    }

    private val Y = Value.YES
    private val N = Value.NO
    private val P = Value.PARTIAL
    private val U = Value.UNKNOWN

    private fun row(id: String, title: String, evidence: String, vararg v: Value): Row {
        require(v.size == Layer.entries.size) { "one value per layer" }
        return Row(id, title, Layer.entries.zip(v.toList()).toMap(), evidence)
    }

    // Order of values: PARSER, MODEL, BUILDER, RUNTIME_PRESENT, RUNTIME_CONFIG_ACCEPTED, RUNTIME_TRAFFIC_VERIFIED,
    // UNIT, SIMULATOR, EMULATOR, DEVICE, FIELD. Device and field stay UNKNOWN until a run is recorded.
    val ROWS: List<Row> = listOf(
        row("xray", "Xray-core v26.9.9", "libXray (scripts/fetch-libxray-android.sh), XrayConfigBuilder, RealDelayProbe; config tests in CI",
            Y, Y, Y, Y, P, U, Y, P, N, U, U),
        row("mihomo", "Mihomo v1.19.32", "scripts/engines/mihomo.sh (source tag), MihomoSidecar; Linux build of the same tag accepted configs with -t",
            P, Y, Y, Y, P, U, Y, N, N, U, U),
        row("amneziawg", "AmneziaWG (on Mihomo)", "Mihomo AmneziaWGOption; jc/jmin/jmax/s1-s4/h1-h4 accepted and a bad jc rejected by -t (Linux build)",
            P, Y, Y, Y, P, N, P, N, N, U, U),
        row("psiphon", "Psiphon", "scripts/engines psiphon build; needs the PSIPHON_CONFIG secret from Psiphon Inc. at release time",
            N, Y, Y, P, U, U, P, N, N, U, U),
        row("tor", "Tor + lyrebird (obfs4, WebTunnel, Snowflake, meek)", "TorSidecar (bridges, torrc), lyrebird",
            Y, Y, Y, Y, U, U, Y, N, N, U, U),
        row("dnstt", "DNS tunnel (dnstt)", "DnsttSidecar; lifecycle in EngineProbe; needs the user's own dnstt server",
            Y, Y, Y, Y, U, U, Y, P, N, U, U),
        row("ech", "ECH", "VlessParser (ech), echConfigList/echSockopt, XrayConfigBuilder; no ECH handshake measured by the LAB",
            Y, Y, Y, Y, U, U, Y, N, N, U, U),
        row("warp", "WARP (WireGuard)", "vpn/warp/WarpProvider.kt; WARP over MASQUE is not supported",
            N, P, Y, Y, U, U, P, N, N, U, U),
        row("tuic", "TUIC", "Xray has no TUIC client (refused at connect); Mihomo runs TUIC from Clash files",
            P, Y, P, P, U, U, P, N, N, U, U),
        row("http3", "HTTP/3 (XHTTP over QUIC)", "alpn=h3 in XrayConfigBuilder; the LAB probes QUIC reachability only",
            P, Y, Y, Y, U, U, P, P, N, U, U),
        row("fragment", "Fragment / finalMask", "VlessProfile.finalMask, XrayConfigBuilder finalmask, curated FragmentProfileEngine profiles",
            P, Y, Y, Y, U, U, Y, P, N, U, U)
    )

    fun byId(id: String): Row? = ROWS.firstOrNull { it.id == id }
}
