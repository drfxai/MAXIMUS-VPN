package com.example.vpn.connections

import com.example.vpn.sidecar.SidecarProcess

/**
 * Per-network hold after WARP's data plane proved unreachable: the account API answered (registration works) but
 * Aether's gateway scan found no serving MASQUE tunnel while Cloudflare's TLS is filtered here (cf=0,
 * CLOUDFLARE_TLS_PATH_FAILURE).
 *
 * Build 151 on Irancell (cell, metered, cf=0): the OOB API lane passed three times and each run still ended with
 * "Aether gateway scan did not reach a serving tunnel within 19s"; an identical ladder ran again 23 s later on the same
 * network, and the background wireguard-relay started on the metered link two minutes after. For [HOLD_MS] after such
 * a failure, automatic Aether ladders (Smart Connect, the LAB recovery's Irancell WARP attempt, the deferred Aether run)
 * and the background relay are skipped on that network. A user's own run still starts, but while Cloudflare is still
 * filtered it skips the direct gateway scans: WARP through Lantern when it can, otherwise it stops at once with
 * [fastFailMessage] (ConnectionEngineRunner.start).
 *
 * This is not [WarpRegistrationMemory]: registration succeeded, so that memory is cleared and the saved identity kept.
 * Kept in memory and in [BuiltinMethodMemory]'s store (survives a service restart); cleared when a WARP carrier carries
 * HTTPS on that network.
 */
object WarpDataPlaneHold {
    const val HOLD_MS = 30 * 60_000L

    private val inMemory = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun key(network: String) = "warpDataPlaneBlocked#$network"

    /**
     * Whether a foreground carrier's startup failure [reason] starts the hold: the gateway scan did not reach a serving
     * tunnel while Cloudflare is filtered here ([cloudflareBlocked]). A relayed carrier (through a saved server) says
     * nothing about this network's own path to WARP.
     */
    fun startsHold(reason: String?, cloudflareBlocked: Boolean, relayed: Boolean): Boolean =
        cloudflareBlocked && !relayed && (reason == SidecarProcess.AETHER_SCAN_EXHAUSTED ||
            reason?.contains(GATEWAY_SCAN_FAILURE, ignoreCase = true) == true)

    const val GATEWAY_SCAN_FAILURE = "gateway scan did not reach a serving tunnel"

    fun record(network: String?, now: Long = System.currentTimeMillis()) {
        if (network.isNullOrBlank()) return
        inMemory[network] = now
        runCatching { BuiltinMethodMemory.write(key(network), now.toString()) }
    }

    fun heldAt(network: String?, now: Long = System.currentTimeMillis()): Long? {
        if (network.isNullOrBlank()) return null
        val at = inMemory[network] ?: runCatching { BuiltinMethodMemory.read(key(network))?.toLongOrNull() }.getOrNull()
            ?.also { inMemory[network] = it }
        return at?.takeIf { now - it in 0 until HOLD_MS }
    }

    fun blocked(network: String?, now: Long = System.currentTimeMillis()): Boolean = heldAt(network, now) != null

    fun clear(network: String?) {
        if (network.isNullOrBlank()) return
        inMemory.remove(network)
        runCatching { BuiltinMethodMemory.write(key(network), null) }
    }

    fun describe(network: String?, now: Long = System.currentTimeMillis()): String =
        heldAt(network, now)?.let { "WARP data plane blocked on this network (gateway scan found no serving tunnel with " +
            "Cloudflare filtered) ${(now - it) / 60_000} min ago; automatic Aether held for ${HOLD_MS / 60_000} min" }
            ?: "no WARP data-plane block remembered"

    /**
     * What the user's own Aether run says when it stops at once inside the hold with Cloudflare still filtered and no
     * Lantern hop to carry WARP (build 170: three manual runs each spent two 45 s scans and ended TLS_HANDSHAKE_FAILED
     * while Lantern, Serverless and Tor carried traffic on the same network).
     */
    fun fastFailMessage(network: String?, now: Long = System.currentTimeMillis(), lanternFailedAt: Long? = null): String {
        val at = heldAt(network, now)
        val ago = at?.let { " ${(now - it) / 60_000} min ago" }.orEmpty()
        val left = at?.let { " for about ${minutesLeft(HOLD_MS, now - it)} more min" }.orEmpty()
        // WARP through the Lantern hop also failed here (LanternRelayMemory): say so, so the user does not expect the
        // next press to try it again. Lantern on its own is a different path and still the first suggestion.
        val lantern = lanternFailedAt?.let { "; WARP through Lantern also failed here ${(now - it) / 60_000} min ago " +
            "and is not retried for about ${minutesLeft(LanternRelayMemory.HOLD_MS, now - it)} more min" }.orEmpty()
        return "Aether skipped: Cloudflare is filtered on this network and WARP's gateway scan found no serving tunnel " +
            "here$ago, so Aether's direct scans are paused$left$lantern. Connect with Lantern or Serverless instead; " +
            "they do not depend on Cloudflare's TLS"
    }

    private fun minutesLeft(holdMs: Long, elapsedMs: Long): Long = maxOf(1L, (holdMs - elapsedMs + 59_999) / 60_000)

    internal fun resetForTest() = inMemory.clear()
}

/**
 * The same Aether ladder never runs twice within [MIN_GAP_MS] on one network. Build 151: the Irancell ladder ran at
 * 16:33:52 and, identical, again at 16:34:35 on the same network, 23 s after the first failed. In memory only.
 * [kind] separates the foreground ladder from the background relay run.
 */
object AetherLadderGuard {
    const val MIN_GAP_MS = 60_000L

    private val last = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * A ladder of [kind] is about to start on [network]. Returns the ms since the same ladder last started there when
     * that is under [MIN_GAP_MS] and [enforce] is set (the caller skips it; nothing is recorded); otherwise records
     * this start and returns null. A manual run ([enforce] false) is recorded but never refused.
     */
    fun begin(network: String?, kind: String, now: Long = System.currentTimeMillis(), enforce: Boolean = true): Long? {
        if (network.isNullOrBlank()) return null
        val k = "$network|$kind"
        val prev = last[k]
        if (enforce && prev != null && now - prev in 0 until MIN_GAP_MS) return now - prev
        last[k] = now
        return null
    }

    /** The ladder of [kind] on [network] ended (any outcome): the gap is counted from its end. */
    fun finish(network: String?, kind: String, now: Long = System.currentTimeMillis()) {
        if (network.isNullOrBlank()) return
        last["$network|$kind"] = now
    }

    internal fun resetForTest() = last.clear()
}
