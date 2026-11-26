package com.example.vpn.connectivity

import com.example.data.model.VlessProfile

/**
 * A versioned, expiring set of client-side settings that may help a degraded config through a filter.
 * A profile never edits a saved config: [derive] returns a copy, and only the fields in
 * [ALLOWED_FIELDS] may differ from the original. Every derived copy goes through [RecoverySecurityGate].
 *
 * Profiles hold no assumption about Iranian filtering beyond their own [expiresAt]: when a profile
 * expires it is not offered until a newer version replaces it.
 */
data class RecoveryProfile(
    val profileId: String,
    val version: Int,
    val createdAt: Long,
    val expiresAt: Long,
    val supportedEngines: Set<String> = setOf("xray"),
    val supportedProtocols: Set<String> = setOf("vless", "trojan", "vmess"),
    val supportedTransports: Set<String> = CDN_TRANSPORTS,
    /** "any", "ipv4" or "ipv6": the address family the profile's endpoint must have. */
    val addressFamily: String = "any",
    /** Failure stages this profile is meant for (see [BpbRecoveryEngine.strategiesFor]). */
    val networkConditions: Set<Strategy> = emptySet(),
    /** Field -> value. The value "{endpoint}" is replaced by a validated endpoint at derive time. */
    val parameters: Map<String, String>,
    val securityRequirements: Set<String> = setOf(REQ_TLS, REQ_CERT_VERIFIED, REQ_SAME_IDENTITY),
    val successHistory: Int = 0,
    val failureHistory: Int = 0,
    /** Consecutive failures after a success that withdraw a derived candidate (back to the original). */
    val rollbackPolicy: Int = 2,
    /** 0..1: how sure the maintainers are this profile helps; low values are tried after high ones. */
    val confidence: Double = 0.5,
    val sourceType: SourceType = SourceType.BUILT_IN,
    /** Firewall types this profile is offered on; empty for every network (see [allowedIn]). */
    val firewalls: Set<NetworkFirewalls.Firewall> = emptySet(),
    /** Offered only when the phone's network has IPv6 ([RecoveryContext.ipv6] true). */
    val requiresIpv6: Boolean = false,
    /** Offered only when the patterniha Xray fork can run the candidate ([RecoveryContext.forkAvailable]). */
    val requiresFork: Boolean = false,
    /**
     * Public CDN addresses an endpoint profile falls back to when no address of its family is validated on this
     * network yet. Each one is still tried with a real request, and the certificate is still checked against the
     * config's unchanged SNI, before it is used.
     */
    val defaultEndpoints: List<String> = emptyList()
) {
    enum class Strategy { FRAGMENT, FINGERPRINT, ECH, ALT_ENDPOINT_V4, ALT_ENDPOINT_V6, ALPN }

    enum class SourceType {
        /** Shipped with the app after engineering review. */
        BUILT_IN,
        /** Measured on this phone. */
        LOCAL_MEASUREMENT,
        /** From reviewed external reports (never applied without review; see Stage 11). */
        REVIEWED_INTELLIGENCE
    }

    init {
        require(parameters.keys.all { it in ALLOWED_FIELDS }) { "Recovery profile $profileId changes a field it may not change" }
        require(expiresAt > createdAt) { "Recovery profile $profileId must expire after it is created" }
        require(confidence in 0.0..1.0)
        require(rollbackPolicy in 1..5)
        require(defaultEndpoints.isEmpty() || needsEndpoint()) { "Recovery profile $profileId has default endpoints but no endpoint field" }
        require(defaultEndpoints.all { ip ->
            RecoverySecurityGate.isIpLiteral(ip) && !RecoverySecurityGate.isPrivateOrReserved(ip) &&
                (addressFamily == "any" || (if (ip.contains(':')) "ipv6" else "ipv4") == addressFamily)
        }) { "Recovery profile $profileId has a default endpoint that is not a public address of its family" }
        parameters["dialMode"]?.let { mode ->
            // Only the patterniha fork knows dialMode, and it fails every dial on a value it cannot parse.
            require(requiresFork) { "Recovery profile $profileId sets dialMode but does not require the fork" }
            require(com.example.vpn.engine.PatternihaFields.validDialMode(mode)) { "Recovery profile $profileId has an invalid dialMode" }
        }
    }

    val key: String get() = "$profileId@v$version"

    fun isExpired(now: Long): Boolean = now >= expiresAt

    fun needsEndpoint(): Boolean = parameters.values.any { it == ENDPOINT }

    /** True when the profile is limited to some firewalls, to IPv6 networks or to the fork. */
    val gated: Boolean get() = firewalls.isNotEmpty() || requiresIpv6 || requiresFork

    /** Whether this profile may be offered in [context]; anything [context] does not know counts as not met. */
    fun allowedIn(context: RecoveryContext): Boolean =
        (firewalls.isEmpty() || context.firewall in firewalls) &&
            (!requiresIpv6 || context.ipv6 == true) &&
            (!requiresFork || context.forkAvailable)

    fun appliesTo(profile: VlessProfile): Boolean =
        profile.protocolType.name.lowercase() in supportedProtocols &&
            profile.transport.lowercase().ifBlank { "tcp" } in supportedTransports &&
            profile.security.equals("tls", ignoreCase = true)

    /** The derived copy for [parent], or null when the profile does not apply or needs an endpoint it was not given. */
    fun derive(parent: VlessProfile, endpoint: String? = null): VlessProfile? {
        if (!appliesTo(parent)) return null
        if (needsEndpoint() && endpoint.isNullOrBlank()) return null
        var p = parent
        for ((field, raw) in parameters) {
            if (field == "echConfigList" && raw.isNotEmpty()) {
                // ECH hides a server name, so an IP or missing SNI cannot use it; a config with its own ECH keeps it.
                // An empty value clears ECH instead (recorded in the ledger, see RecoveryLedger.Entry.clearedEch).
                val sni = parent.sni.ifBlank { parent.host }
                if (sni.isBlank() || sni.all { it.isDigit() || it == '.' || it == ':' } || parent.echConfigList.isNotBlank()) return null
            }
            val value = when {
                raw == ENDPOINT -> endpoint!!
                SNI in raw -> raw.replace(SNI, parent.sni.ifBlank { parent.host })
                else -> raw
            }
            p = when (field) {
                "finalMask" -> p.copy(finalMask = value)
                "fingerprint" -> p.copy(fingerprint = value)
                "alpn" -> p.copy(alpn = value)
                "cipherSuites" -> p.copy(cipherSuites = value)
                "echConfigList" -> p.copy(echConfigList = value)
                "address" -> p.copy(address = value)
                // Stored where an imported link keeps it; ForkXraySidecar runs any profile that carries a valid one.
                "dialMode" -> com.example.vpn.engine.ProfileExtras.with(p, com.example.vpn.engine.ProfileExtras.PATT_DIAL_MODE, value.ifEmpty { null })
                else -> error("unreachable: $field")
            }
        }
        return p
    }

    companion object {
        const val ENDPOINT = "{endpoint}"
        const val SNI = "{sni}"
        const val REQ_TLS = "tls-kept"
        const val REQ_CERT_VERIFIED = "certificate-verified"
        const val REQ_SAME_IDENTITY = "same-sni-host-credential"

        /**
         * The only fields a recovery profile may change. SNI, Host, path, credentials, certificate pins,
         * security mode and allowInsecure are never among them. `dialMode` is the patterniha fork's socket option
         * (TCP out-of-band bytes on the first writes); a profile that sets it must require the fork.
         */
        val ALLOWED_FIELDS = setOf("finalMask", "fingerprint", "alpn", "cipherSuites", "echConfigList", "address", "dialMode")

        val CDN_TRANSPORTS = setOf("ws", "httpupgrade", "xhttp", "splithttp", "grpc", "h2", "tcp", "raw")
    }
}

/**
 * The reviewed, built-in recovery profiles. Small on purpose: each one is a known Xray client setting,
 * bounded, and expires so it is re-reviewed rather than trusted forever.
 */
object RecoveryProfiles {
    /** Built-in profiles are reviewed at least this often. */
    const val REVIEW_PERIOD_MS = 120L * 24 * 60 * 60 * 1000
    /** When this set was reviewed (2026-10-08 UTC). */
    const val REVIEWED_AT = 1_791_417_600_000L

    private fun p(
        id: String, strategy: RecoveryProfile.Strategy, params: Map<String, String>, confidence: Double,
        family: String = "any", transports: Set<String> = RecoveryProfile.CDN_TRANSPORTS,
        also: Set<RecoveryProfile.Strategy> = emptySet(), firewalls: Set<NetworkFirewalls.Firewall> = emptySet(),
        ipv6: Boolean = false, fork: Boolean = false, defaults: List<String> = emptyList()
    ) = RecoveryProfile(
        profileId = id, version = 1, createdAt = REVIEWED_AT, expiresAt = REVIEWED_AT + REVIEW_PERIOD_MS,
        addressFamily = family, networkConditions = setOf(strategy) + also, parameters = params, confidence = confidence,
        supportedTransports = transports, firewalls = firewalls, requiresIpv6 = ipv6, requiresFork = fork, defaultEndpoints = defaults
    )

    /** FIX BPB's tested masks; the cipher list only takes effect with Go's TLS stack ("unsafe" fingerprint). */
    private fun bpb(mask: String) = mapOf(
        "finalMask" to mask, "fingerprint" to com.example.panels.BpbFix.FINGERPRINT,
        "alpn" to com.example.panels.BpbFix.ALPN, "cipherSuites" to com.example.panels.BpbFix.CIPHER_SUITES
    )

    /** DoH resolvers the Cloudflare ECH recipes look the shared key up through, in order. */
    val CLOUDFLARE_ECH_DOH = listOf("https://1.1.1.1/dns-query", "https://8.8.8.8/dns-query", "https://9.9.9.9/dns-query")

    /** The echConfigList values the Cloudflare ECH recipes set. */
    val CLOUDFLARE_ECH_VALUES: Set<String> = CLOUDFLARE_ECH_DOH.map { RecoverySecurityGate.CLOUDFLARE_ECH_NAME + "+" + it }.toSet()

    private fun cloudflareEch(doh: String) = mapOf(
        "echConfigList" to RecoverySecurityGate.CLOUDFLARE_ECH_NAME + "+" + doh, "fingerprint" to "chrome"
    )

    val BUILT_IN: List<RecoveryProfile> = listOf(
        p("bpb-fragment-original", RecoveryProfile.Strategy.FRAGMENT, bpb(com.example.panels.BpbFix.FINAL_MASK_ORIGINAL), 0.6),
        p("bpb-fragment", RecoveryProfile.Strategy.FRAGMENT, bpb(com.example.panels.BpbFix.FINAL_MASK), 0.6),
        p("fragment-tlshello", RecoveryProfile.Strategy.FRAGMENT, mapOf("finalMask" to com.example.panels.BpbFix.FINAL_MASK_TLSHELLO), 0.5),
        p("fragment-tlshello-small", RecoveryProfile.Strategy.FRAGMENT, mapOf("finalMask" to com.example.panels.BpbFix.FINAL_MASK_TLSHELLO_SMALL), 0.45),
        p("fingerprint-firefox", RecoveryProfile.Strategy.FINGERPRINT, mapOf("fingerprint" to "firefox"), 0.35),
        p("fingerprint-chrome", RecoveryProfile.Strategy.FINGERPRINT, mapOf("fingerprint" to "chrome"), 0.35),
        p("ech-doh", RecoveryProfile.Strategy.ECH, mapOf("echConfigList" to RecoveryProfile.SNI + "+" + com.example.vpn.stealth.StealthVariants.ECH_DNS), 0.3),
        // Cloudflare's shared ECH key (Proxy Builder's ECH recipe), fetched over DoH from three resolvers in
        // turn, with a Chrome fingerprint. Works for Cloudflare-fronted configs whose own name has no ECH record.
        p("ech-cloudflare", RecoveryProfile.Strategy.ECH, cloudflareEch(CLOUDFLARE_ECH_DOH[0]), 0.55),
        p("ech-cloudflare-google", RecoveryProfile.Strategy.ECH, cloudflareEch(CLOUDFLARE_ECH_DOH[1]), 0.45),
        p("ech-cloudflare-quad9", RecoveryProfile.Strategy.ECH, cloudflareEch(CLOUDFLARE_ECH_DOH[2]), 0.4),
        p("bpb-fragment-v1", RecoveryProfile.Strategy.FRAGMENT, bpb(com.example.panels.BpbFix.FINAL_MASK_V1), 0.5),
        // The Irancell recipe the community reports: the empty-record fragment with Firefox's cipher list.
        p(NetworkFirewalls.IRANCELL_PROFILE, RecoveryProfile.Strategy.FRAGMENT,
            bpb(com.example.panels.BpbFix.FINAL_MASK_ORIGINAL) + ("cipherSuites" to com.example.panels.BpbFix.FIREFOX_CIPHER_SUITES), 0.45),
        // UAC SNI Spoofer's Iran ClientHello split: the first record ends right before the server name (Go TLS
        // stack + fixed cipher list). No ALPN pin, so it also fits xhttp/gRPC/h2 configs. See [IranHelloSplit].
        p(IranHelloSplit.PROFILE_ID, RecoveryProfile.Strategy.FRAGMENT, mapOf(
            "finalMask" to IranHelloSplit.MASK, "fingerprint" to IranHelloSplit.FINGERPRINT,
            "cipherSuites" to IranHelloSplit.CIPHER_SUITES), 0.5),
        // UAC's Irancell "deep" fragment: 5-byte ClientHello pieces, up to 100 splits.
        p(IranHelloSplit.DEEP_PROFILE_ID, RecoveryProfile.Strategy.FRAGMENT, mapOf("finalMask" to IranHelloSplit.DEEP_MASK), 0.4),
        p("alpn-http11", RecoveryProfile.Strategy.ALPN, mapOf("alpn" to "http/1.1"), 0.25,
            transports = setOf("ws", "httpupgrade")),
        p("endpoint-ipv4", RecoveryProfile.Strategy.ALT_ENDPOINT_V4, mapOf("address" to RecoveryProfile.ENDPOINT), 0.55, family = "ipv4"),
        p("endpoint-ipv6", RecoveryProfile.Strategy.ALT_ENDPOINT_V6, mapOf("address" to RecoveryProfile.ENDPOINT), 0.5, family = "ipv6"),
        // patterniha's oob for Hamrah-e-Aval: the fork appends one TCP out-of-band byte to the first write
        // (dialMode "oob6.0": only when the dialled address is IPv6, so it is a no-op on IPv4), Chrome fingerprint,
        // no fragment/cipher list/ECH, to a Cloudflare IPv6 edge (validated, else 2a06:98c1:3120::7). It makes
        // pages.dev/workers.dev reachable without an own domain where Hamrah-e-Aval filters IPv6 by blacklist; on its
        // IPv4 whitelist or on Irancell it is useless, so it is offered only on Hamrah-e-Aval-type networks with IPv6,
        // and only when the fork can run it (the candidate is tested and connected through ForkXraySidecar).
        p(NetworkFirewalls.MCI_V6_OOB_PROFILE, RecoveryProfile.Strategy.ALT_ENDPOINT_V6, mapOf(
            "dialMode" to MCI_V6_OOB_DIAL_MODE, "fingerprint" to "chrome", "finalMask" to "", "cipherSuites" to "", "echConfigList" to "",
            "address" to RecoveryProfile.ENDPOINT), 0.45, family = "ipv6",
            firewalls = setOf(NetworkFirewalls.Firewall.MCI), ipv6 = true, fork = true,
            defaults = listOf(MCI_V6_OOB_IPV6))
    )

    /** The fork's IPv6-only oob form: one 0x00 out-of-band byte on the first write to an IPv6 address. */
    const val MCI_V6_OOB_DIAL_MODE = "oob6.0"

    /** The Cloudflare IPv6 edge patterniha's oob recipe uses when none is validated on the network. */
    const val MCI_V6_OOB_IPV6 = "2a06:98c1:3120::7"

    fun byKey(key: String): RecoveryProfile? = BUILT_IN.firstOrNull { it.key == key }
}
