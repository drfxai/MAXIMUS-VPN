package com.example.panels

import com.example.data.model.VlessProfile

/**
 * "FIX BPB": client settings that let BPB Worker configs connect on filtered networks.
 *
 * - finalMask fragments the TLS ClientHello so it cannot be matched in one packet;
 * - the "unsafe" fingerprint makes Xray use Go's standard TLS stack (instead of a browser
 *   imitation), which is required for the custom cipher-suite list to take effect. Certificate
 *   verification stays enabled;
 * - ALPN is pinned to HTTP/1.1, which is what the Worker's WebSocket endpoint speaks.
 */
object BpbFix {
    const val FINGERPRINT = "unsafe"
    const val ALPN = "http/1.1"
    const val CIPHER_SUITES =
        "TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA256:TLS_AES_128_GCM_SHA256:" +
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384:TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384:" +
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256:TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256:" +
            "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256:TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256:" +
            "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA:TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA:" +
            "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256:TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256"
    const val FINAL_MASK =
        """{"tcp": [{"type": "fragment", "settings": {"packets": "tlshello", "lengths": ["0", "104", "1"], """ +
            """"delays": ["0"], "maxSplit": "0"}},{"type": "fragment", "settings": {"packets": "1-1", """ +
            """"lengths": ["114", "1"], "delays": ["1"], "maxSplit": "11"}}]}"""

    /** True when [profile] is one of the configs served by the BPB worker at [workerHost]. */
    fun belongsTo(profile: VlessProfile, workerHost: String): Boolean {
        val host = workerHost.trim().lowercase()
        if (host.isBlank()) return false
        return profile.address.equals(host, ignoreCase = true) ||
            profile.host.equals(host, ignoreCase = true) ||
            profile.sni.equals(host, ignoreCase = true) ||
            profile.sourceSubscription.orEmpty().lowercase().contains(host) ||
            profile.subscriptionUrl.orEmpty().lowercase().contains(host)
    }

    /** Only TLS configs take these settings; plain (port 80 family) configs are returned unchanged. */
    fun canApply(profile: VlessProfile): Boolean = profile.security.equals("tls", ignoreCase = true)

    fun apply(profile: VlessProfile): VlessProfile =
        if (!canApply(profile)) profile
        else profile.copy(
            fingerprint = FINGERPRINT,
            alpn = ALPN,
            cipherSuites = CIPHER_SUITES,
            finalMask = FINAL_MASK
        )

    fun isApplied(profile: VlessProfile): Boolean =
        profile.fingerprint == FINGERPRINT && profile.alpn == ALPN &&
            profile.cipherSuites == CIPHER_SUITES && profile.finalMask == FINAL_MASK
}
