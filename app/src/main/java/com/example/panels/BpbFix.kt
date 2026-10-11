package com.example.panels

import com.example.data.model.VlessProfile
import com.example.xray.RealDelayProbe

/**
 * "FIX BPB": client settings that let BPB Worker configs connect on filtered networks.
 *
 * - finalMask fragments the TLS ClientHello so it cannot be matched in one packet. Which fragment
 *   settings get past the network's filter differs between networks, so FIX BPB tests each one with
 *   a real request through the worker and keeps the first that works;
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

    /**
     * Firefox's cipher order (TLS 1.3, then ECDHE with AES-GCM, ChaCha20 and AES-CBC), which the community
     * pairs with the empty-record fragment on Irancell. Like [CIPHER_SUITES] it needs the Go TLS stack.
     */
    const val FIREFOX_CIPHER_SUITES =
        "TLS_AES_128_GCM_SHA256:TLS_CHACHA20_POLY1305_SHA256:TLS_AES_256_GCM_SHA384:" +
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256:TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256:" +
            "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256:TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256:" +
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384:TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384:" +
            "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA:TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA:" +
            "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA:TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA"

    /**
     * The original FIX BPB recipe: an empty TLS record, a 104-byte record, then 1-byte records, all in
     * one write, which is then split into TCP segments. The empty record confuses some filters, but
     * TLS forbids empty handshake records (RFC 8446 section 5.1) and strict servers such as Go's abort
     * the handshake on them, so it is only kept when a real request through the worker succeeds.
     */
    const val FINAL_MASK_ORIGINAL =
        """{"tcp": [{"type": "fragment", "settings": {"packets": "tlshello", "lengths": ["0", "104", "1"], """ +
            """"delays": ["0"], "maxSplit": "0"}},{"type": "fragment", "settings": {"packets": "1-1", """ +
            """"lengths": ["114", "1"], "delays": ["1"], "maxSplit": "11"}}]}"""

    /** [FINAL_MASK_ORIGINAL] without the empty record, which every TLS server accepts. */
    const val FINAL_MASK =
        """{"tcp": [{"type": "fragment", "settings": {"packets": "tlshello", "lengths": ["104", "1"], """ +
            """"delays": ["0"], "maxSplit": "0"}},{"type": "fragment", "settings": {"packets": "1-1", """ +
            """"lengths": ["114", "1"], "delays": ["1"], "maxSplit": "11"}}]}"""

    /** Classic ClientHello fragmentation into 100-200 byte TCP segments, 10-20 ms apart. */
    const val FINAL_MASK_TLSHELLO =
        """{"tcp": [{"type": "fragment", "settings": {"packets": "tlshello", "length": "100-200", "delay": "10-20"}}]}"""

    /** Finer ClientHello fragmentation into 10-20 byte TCP segments, 10-20 ms apart. */
    const val FINAL_MASK_TLSHELLO_SMALL =
        """{"tcp": [{"type": "fragment", "settings": {"packets": "tlshello", "length": "10-20", "delay": "10-20"}}]}"""

    /**
     * The older "v1" recipe some Iranian clients still use (PattNG's export format, also Proxy Builder's
     * "v1" preset): 5-, 94- and 1-byte records, then 109- and 1-byte records split into up to 355 TCP
     * segments 1 ms apart. Revive tries it after [FINAL_MASK_ORIGINAL]; FIX BPB does not.
     */
    const val FINAL_MASK_V1 =
        """{"tcp": [{"type": "fragment", "settings": {"packets": "tlshello", "lengths": ["5", "94", "1"], """ +
            """"delays": ["0"], "maxSplit": "0"}},{"type": "fragment", "settings": {"packets": "1-1", """ +
            """"lengths": ["109", "1"], "delays": ["1"], "maxSplit": "355"}}]}"""

    /** Masks FIX BPB tries, most preferred first; with the unfixed config they fill one probe batch. */
    val MASKS = listOf(FINAL_MASK_ORIGINAL, FINAL_MASK, FINAL_MASK_TLSHELLO, FINAL_MASK_TLSHELLO_SMALL)

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

    fun apply(profile: VlessProfile, mask: String = FINAL_MASK): VlessProfile =
        if (!canApply(profile)) profile
        else profile.copy(
            fingerprint = FINGERPRINT,
            alpn = ALPN,
            cipherSuites = CIPHER_SUITES,
            finalMask = mask
        )

    /** True when [profile] carries the current fix with [mask] (any known mask when null). */
    fun isApplied(profile: VlessProfile, mask: String? = null): Boolean =
        profile.fingerprint == FINGERPRINT && profile.alpn == ALPN && profile.cipherSuites == CIPHER_SUITES &&
            (if (mask != null) profile.finalMask == mask else profile.finalMask in MASKS)

    /** True when FIX BPB was turned on for [profile]. */
    fun wasApplied(profile: VlessProfile): Boolean = isApplied(profile)

    sealed class Choice {
        /** [mask] carried a real request through the worker. */
        data class Verified(val mask: String, val latencyMs: Long) : Choice()
        /** The configs work as they are; none of the fixes did. */
        data class NotNeeded(val latencyMs: Long) : Choice()
        /** Nothing carried traffic, with or without the fix: the worker or its proxy IP is the problem. */
        data class NothingWorks(val reason: String) : Choice()
        /** Could not test (for example while the VPN is connected); [FINAL_MASK_ORIGINAL] is used untested. */
        data class Untested(val reason: String) : Choice()
    }

    /**
     * Sends a real request through [sample] with each mask and without the fix, in one batch, and
     * picks the first mask that works.
     */
    fun choose(sample: VlessProfile, timeoutSec: Int = 8, probe: (List<VlessProfile>, Int) -> List<RealDelayProbe.Outcome> = RealDelayProbe::measure): Choice {
        val candidates = MASKS.map { apply(sample, it) } + sample
        val outcomes = probe(candidates, timeoutSec)
        MASKS.forEachIndexed { i, mask ->
            (outcomes[i] as? RealDelayProbe.Outcome.Delay)?.let { return Choice.Verified(mask, it.latencyMs) }
        }
        return when (val plain = outcomes[MASKS.size]) {
            is RealDelayProbe.Outcome.Delay -> Choice.NotNeeded(plain.latencyMs)
            is RealDelayProbe.Outcome.Failed -> Choice.NothingWorks(plain.reason)
            is RealDelayProbe.Outcome.NotRun ->
                (outcomes.firstOrNull { it is RealDelayProbe.Outcome.Failed } as? RealDelayProbe.Outcome.Failed)
                    ?.let { Choice.NothingWorks(it.reason) } ?: Choice.Untested(plain.reason)
        }
    }
}
