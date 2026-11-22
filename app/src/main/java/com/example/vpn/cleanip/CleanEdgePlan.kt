package com.example.vpn.cleanip

import com.example.data.model.VlessProfile

/**
 * Which clean-edge search a fronted config gets on this network, shared by Smart Connect's stage E and the
 * Panels clean-IP optimizer (config revival makes the same choice in RayApplication). Pure.
 *
 * - Not a CDN-fronted TLS config (REALITY, raw TCP, insecure TLS, raw JSON): nothing to scan.
 * - Cloudflare on a network that proxies Cloudflare TLS by name ([SniProxyDetector]): nothing to scan; every
 *   Cloudflare address reaches the same operator proxy, so a "clean" one cannot help.
 * - Cloudflare WebSocket/HTTPUpgrade: the origin-checked WebSocket scan ([com.example.panels.CleanIpOptimizer]),
 *   topped up by the handshake scan when it finds too few.
 * - Everything else fronted (XHTTP, gRPC, H2, and Fastly/CloudFront/Gcore fronts of any transport): the
 *   handshake scan of the config's own CDN ([CdnCleanIpFinder]); a real request through the config decides.
 */
object CleanEdgePlan {
    data class Decision(
        /** The CDN the config sits behind, or null when it is not fronted. */
        val cdn: Cdn?,
        /** Run the Cloudflare WebSocket scan (TLS + the config's own WebSocket/HTTPUpgrade exchange). */
        val websocketScan: Boolean,
        /** Run (or top up with) the handshake scan of [cdn]'s ranges with the config's own TLS name. */
        val cdnScan: Boolean,
        /** Why nothing is scanned, or null when something is. */
        val skipped: String? = null
    ) {
        val runs: Boolean get() = skipped == null
    }

    const val NOT_FRONTED = "not a CDN-fronted TLS config (WS, HTTPUpgrade, XHTTP, gRPC or H2 over verified TLS)"
    const val SNI_PROXIED = "this network proxies Cloudflare TLS by name; a clean Cloudflare address cannot help here"

    /**
     * [cdn]: the CDN found for the config's address (null: none of the bundled lists holds it, taken as
     * Cloudflare, the usual front). [cloudflareProxied]: the network's SNI-proxy verdict, null when unknown.
     */
    fun decide(profile: VlessProfile, cdn: Cdn?, cloudflareProxied: Boolean?): Decision {
        if (!CdnEdgeScanner.fronted(profile)) return Decision(null, websocketScan = false, cdnScan = false, skipped = NOT_FRONTED)
        val front = cdn ?: Cdn.CLOUDFLARE
        if (front == Cdn.CLOUDFLARE && cloudflareProxied == true) {
            return Decision(front, websocketScan = false, cdnScan = false, skipped = SNI_PROXIED)
        }
        val ws = front == Cdn.CLOUDFLARE && com.example.panels.CleanIpOptimizer.canRevive(profile)
        return Decision(front, websocketScan = ws, cdnScan = true)
    }

    /** The WebSocket scan found too few edges and there is time left: top up with the handshake scan. */
    fun topUp(decision: Decision, websocketFound: Int, remainingMs: Long): Boolean =
        decision.cdnScan && (!decision.websocketScan || websocketFound < MIN_WEBSOCKET_EDGES) && remainingMs >= MIN_TOP_UP_MS

    /** Addresses for the real-request race: remembered validated ones first, then WebSocket-checked, then handshake-ranked. */
    fun candidates(remembered: List<String>, websocket: List<String>, handshake: List<String>, limit: Int = MAX_CANDIDATES): List<String> =
        (remembered + websocket + handshake).map { it.trim() }.filter { CdnRanges.ipv4(it) != null }.distinct().take(limit)

    const val MIN_WEBSOCKET_EDGES = 3
    const val MIN_TOP_UP_MS = 4_000L
    const val MAX_CANDIDATES = 12
}
