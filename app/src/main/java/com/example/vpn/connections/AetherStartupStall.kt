package com.example.vpn.connections

import com.example.vpn.sidecar.AetherSidecar

/**
 * Build 167 (an unmeasured, non-metered network, nothing remembered there): automatic Aether picked a gateway for
 * MASQUE over HTTP/2, then sat in its "transport" stage for the whole 180 s readiness window ("did not start serving
 * within 180s"), and H3 then did the same until the user gave up after ~6 minutes.
 *
 * On a network where Aether has nothing to go on (no clean HTTP/2 gateway remembered, no method verified here, and no
 * measurement saying Cloudflare is filtered, which has its own fixed budgets) a MASQUE carrier gets a short leash
 * once it stops making progress:
 * - **transport stall**: a gateway was picked ([AetherSidecar] reports the native stage "transport") and the tunnel
 *   has not started serving [TRANSPORT_STALL_MS] later (Aether's own `--startup-secs`), so the carrier is given up;
 * - **no stage at all**: the engine reported no startup stage in [NO_STAGE_MS].
 * While it is still registering, loading its identity or scanning, it is making progress and the normal budget holds.
 *
 * Never for the Irancell ladder (its own budget), a remembered gateway, a relay, a carrier the user chose (manual
 * H2/H3 keeps [AetherSidecar.manualMasqueReadinessMs]) or WireGuard. Pure.
 */
object AetherStartupStall {
    /** Matches Aether's own MASQUE `--startup-secs` (AetherSidecar.MASQUE_STARTUP_SECONDS). */
    const val TRANSPORT_STALL_MS = 45_000L
    /** No native stage line at all within this long: nothing is happening. */
    const val NO_STAGE_MS = 45_000L

    /** Carriers the cap applies to: MASQUE over HTTP/2 and HTTP/3. */
    val CARRIERS = setOf("h2", "h3")

    fun applies(engineId: String, automatic: Boolean, carrier: String, method: String, irancellLadder: Boolean,
                cloudflareFiltered: Boolean, rememberedGateway: Boolean, verifiedHere: Boolean, relay: Boolean): Boolean =
        engineId == AetherSidecar.ID && automatic && carrier in CARRIERS &&
            method != ConnectionEngineRunner.CLEAN_H2 && !irancellLadder && !cloudflareFiltered &&
            !rememberedGateway && !verifiedHere && !relay

    /** Whether to stop waiting now: [startedAtMs] is when the engine was launched. */
    fun shouldGiveUp(nowMs: Long, startedAtMs: Long, stage: String?, transportSinceMs: Long?): Boolean = when {
        transportSinceMs != null -> nowMs - transportSinceMs >= TRANSPORT_STALL_MS
        stage == null -> nowMs - startedAtMs >= NO_STAGE_MS
        else -> false
    }

    /** The startup failure recorded when the wait was cut short. */
    fun reason(stage: String?): String = if (stage == "transport")
        "Aether picked a gateway but the tunnel did not start serving within ${TRANSPORT_STALL_MS / 1000}s on a network " +
            "with nothing remembered; given up early instead of waiting the full window; traffic is unverified."
    else "Aether reported no startup progress within ${NO_STAGE_MS / 1000}s on a network with nothing remembered; " +
        "given up early; traffic is unverified."

    /** One line for the ladder's queue event. */
    const val SUMMARY = "nothing remembered for Aether on this network: a MASQUE carrier that picks a gateway and does not " +
        "start serving within 45s is given up (no full 180s wait)"
}
