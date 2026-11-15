package com.example.vpn.connections

/**
 * Per-network hold after WARP's data plane proved unreachable: the account API answered (registration works) but
 * Aether's gateway scan found no serving MASQUE tunnel while Cloudflare's TLS is filtered here (cf=0,
 * CLOUDFLARE_TLS_PATH_FAILURE).
 *
 * Build 151 on Irancell (cell, metered, cf=0): the OOB API lane passed three times and each run still ended with
 * "Aether gateway scan did not reach a serving tunnel within 19s"; an identical ladder ran again 23 s later on the same
 * network, and the background wireguard-relay started on the metered link two minutes after. For [HOLD_MS] after such
 * a failure, automatic Aether ladders (Smart Connect, the LAB recovery's Irancell WARP attempt, the deferred Aether run)
 * and the background relay are skipped on that network. A user's own test on the Connections page still runs.
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
        cloudflareBlocked && !relayed && reason?.contains(GATEWAY_SCAN_FAILURE, ignoreCase = true) == true

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
