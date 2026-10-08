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
    val sourceType: SourceType = SourceType.BUILT_IN
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
    }

    val key: String get() = "$profileId@v$version"

    fun isExpired(now: Long): Boolean = now >= expiresAt

    fun needsEndpoint(): Boolean = parameters.values.any { it == ENDPOINT }

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
            val value = when {
                raw == ENDPOINT -> endpoint!!
                SNI in raw -> {
                    // ECH looks up the site's own key by its name; an IP or missing SNI cannot use it.
                    val sni = parent.sni.ifBlank { parent.host }
                    if (sni.isBlank() || sni.all { it.isDigit() || it == '.' || it == ':' } || parent.echConfigList.isNotBlank()) return null
                    raw.replace(SNI, sni)
                }
                else -> raw
            }
            p = when (field) {
                "finalMask" -> p.copy(finalMask = value)
                "fingerprint" -> p.copy(fingerprint = value)
                "alpn" -> p.copy(alpn = value)
                "cipherSuites" -> p.copy(cipherSuites = value)
                "echConfigList" -> p.copy(echConfigList = value)
                "address" -> p.copy(address = value)
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
         * security mode and allowInsecure are never among them.
         */
        val ALLOWED_FIELDS = setOf("finalMask", "fingerprint", "alpn", "cipherSuites", "echConfigList", "address")

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
        family: String = "any", transports: Set<String> = RecoveryProfile.CDN_TRANSPORTS
    ) = RecoveryProfile(
        profileId = id, version = 1, createdAt = REVIEWED_AT, expiresAt = REVIEWED_AT + REVIEW_PERIOD_MS,
        addressFamily = family, networkConditions = setOf(strategy), parameters = params, confidence = confidence,
        supportedTransports = transports
    )

    /** FIX BPB's tested masks; the cipher list only takes effect with Go's TLS stack ("unsafe" fingerprint). */
    private fun bpb(mask: String) = mapOf(
        "finalMask" to mask, "fingerprint" to com.example.panels.BpbFix.FINGERPRINT,
        "alpn" to com.example.panels.BpbFix.ALPN, "cipherSuites" to com.example.panels.BpbFix.CIPHER_SUITES
    )

    val BUILT_IN: List<RecoveryProfile> = listOf(
        p("bpb-fragment-original", RecoveryProfile.Strategy.FRAGMENT, bpb(com.example.panels.BpbFix.FINAL_MASK_ORIGINAL), 0.6),
        p("bpb-fragment", RecoveryProfile.Strategy.FRAGMENT, bpb(com.example.panels.BpbFix.FINAL_MASK), 0.6),
        p("fragment-tlshello", RecoveryProfile.Strategy.FRAGMENT, mapOf("finalMask" to com.example.panels.BpbFix.FINAL_MASK_TLSHELLO), 0.5),
        p("fragment-tlshello-small", RecoveryProfile.Strategy.FRAGMENT, mapOf("finalMask" to com.example.panels.BpbFix.FINAL_MASK_TLSHELLO_SMALL), 0.45),
        p("fingerprint-firefox", RecoveryProfile.Strategy.FINGERPRINT, mapOf("fingerprint" to "firefox"), 0.35),
        p("fingerprint-chrome", RecoveryProfile.Strategy.FINGERPRINT, mapOf("fingerprint" to "chrome"), 0.35),
        p("ech-doh", RecoveryProfile.Strategy.ECH, mapOf("echConfigList" to RecoveryProfile.SNI + "+" + com.example.vpn.stealth.StealthVariants.ECH_DNS), 0.3),
        p("alpn-http11", RecoveryProfile.Strategy.ALPN, mapOf("alpn" to "http/1.1"), 0.25,
            transports = setOf("ws", "httpupgrade")),
        p("endpoint-ipv4", RecoveryProfile.Strategy.ALT_ENDPOINT_V4, mapOf("address" to RecoveryProfile.ENDPOINT), 0.55, family = "ipv4"),
        p("endpoint-ipv6", RecoveryProfile.Strategy.ALT_ENDPOINT_V6, mapOf("address" to RecoveryProfile.ENDPOINT), 0.5, family = "ipv6")
    )

    fun byKey(key: String): RecoveryProfile? = BUILT_IN.firstOrNull { it.key == key }
}
