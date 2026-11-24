package com.example.vpn.verify

import com.example.vpn.sidecar.PsiphonSidecar
import com.example.vpn.sidecar.ShirokhorshidSidecar
import com.example.vpn.sidecar.TorSidecar

/**
 * The per-step limit (connect, TLS, response) of the checks that keep a running session alive (the ping loop and the
 * failover watchdog's real requests through the route). Pure.
 *
 * Build 174 review: every engine program outside DNS tunnels was checked with 4 s steps. Tor (three relays), Psiphon and
 * Shir o Khorshid (meek / CDN fronting) and engine chains (Psiphon over Lantern) routinely need longer for one TLS
 * handshake; a few slow answers count as watchdog strikes, and two windows of them restart a power-button session
 * (PinnedRecovery) or switch away from a working one — the mid-session drop pattern of the Aether report.
 */
object RouteProbeSteps {
    const val DEFAULT_MS = 4_000
    const val MULTI_HOP_MS = 10_000
    /** Every round trip of a DNS tunnel is a DNS query through a public resolver (about 10 s per request). */
    const val DNS_TUNNEL_MS = 20_000

    private val MULTI_HOP_ENGINES = setOf(TorSidecar.ID, PsiphonSidecar.ID, ShirokhorshidSidecar.ID)

    /** [engines]: the session's engine programs, outermost first (one for a sidecar, each hop for a chain). */
    fun stepMs(engines: List<String>, dnsTunnel: Boolean): Int = when {
        dnsTunnel -> DNS_TUNNEL_MS
        engines.size > 1 || engines.any { it in MULTI_HOP_ENGINES } -> MULTI_HOP_MS
        else -> DEFAULT_MS
    }
}
