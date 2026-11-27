package com.example.vpn.smart

import com.example.data.model.VlessProfile
import com.example.vpn.engine.registry.EngineLedger

/**
 * Smart Connect's order on a network where connections already carried verified traffic.
 *
 * Build 179 device test (Irancell, cf=0): Lantern, Psiphon over Lantern, Shir o Khorshid over Lantern and Aether over
 * Lantern carried traffic, yet Smart Connect led with Aether (55 s) and then the recovery pool in its default order.
 * What the engine ledger's per-path history ([EngineLedger.pathResults], 24 h) says verified here now goes first:
 * - every recovery candidate whose path verified here gets a stronger planner nudge ([NUDGE]) and goes first in its
 *   family;
 * - the explicit chain Shir o Khorshid over Lantern joins the recovery pool, but only with that evidence (it is never
 *   started on a network where it has not worked);
 * - on Irancell with cf=0, the capped WARP attempt that used to run before every other engine is deferred while another
 *   path verified here ([warpFirst]); Aether stays at the end of the search.
 * History orders candidates only; it never proves current connectivity. Smart Connect only: a manual pick runs as is.
 * Pure, so the rules are unit-tested.
 */
object VerifiedHereOrder {
    const val NUDGE = 0.25

    /** Paths whose last attempt on the network within the ledger's 24 h carried verified traffic. */
    fun verifiedPaths(results: List<EngineLedger.PathResult>): Set<String> =
        results.filter { it.verified }.map { it.path }.toSet()

    /** [candidates] whose path ([pathOf]) is in [verified], keeping their order. */
    fun evidenced(candidates: List<VlessProfile>, verified: Set<String>, pathOf: (VlessProfile) -> String): List<VlessProfile> =
        candidates.filter { runCatching { pathOf(it) }.getOrNull() in verified }

    /**
     * The capped Irancell WARP attempt before the other engines ([RecoveryEngineCaps.irancellWarpFirst]): deferred while a
     * path other than plain Aether verified on this network in the last 24 h.
     */
    fun warpFirst(irancellWarpFirst: Boolean, verified: Set<String>): Boolean =
        irancellWarpFirst && verified.none { !it.startsWith("aether/") || "→" in it }

    /** The log line when [warpFirst] deferred WARP. */
    fun deferredWarpText(verified: Set<String>): String =
        "Recovery: Cloudflare is filtered here (cf=0) and " +
            verified.filter { !it.startsWith("aether/") || "→" in it }.take(3)
                .joinToString(", ") { com.example.vpn.engine.registry.NetworkResults.title(it) } +
            " carried verified traffic on this network in the last 24 h: those go first, WARP (Aether) waits for the end."
}
