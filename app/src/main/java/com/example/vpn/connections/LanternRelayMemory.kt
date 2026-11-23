package com.example.vpn.connections

/**
 * Per-network memory of WARP through the Lantern hop ([com.example.vpn.sidecar.LanternRelayHop], method
 * [ConnectionEngineRunner.H2_LANTERN]) failing: Radiance did not become ready, could not reach Cloudflare through its
 * exit, or Aether found no serving tunnel through it.
 *
 * Each such attempt costs up to 60 s for Radiance plus 90 s for Aether. Inside [WarpDataPlaneHold] the user's own run
 * goes straight to the Lantern hop; without this memory every press of Connect on the same network would repeat the
 * same 2.5 min failure. For [HOLD_MS] after a failure here the hop is not queued again: inside the data-plane hold the
 * run stops at once with [WarpDataPlaneHold.fastFailMessage] (which then says the Lantern hop failed too), outside it
 * only the direct carriers run. A hop that carries HTTPS clears the memory.
 *
 * Kept in memory and in [BuiltinMethodMemory]'s store (survives a service restart).
 */
object LanternRelayMemory {
    const val HOLD_MS = 30 * 60_000L

    private val inMemory = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun key(network: String) = "lanternHopFailed#$network"

    fun record(network: String?, now: Long = System.currentTimeMillis()) {
        if (network.isNullOrBlank()) return
        inMemory[network] = now
        runCatching { BuiltinMethodMemory.write(key(network), now.toString()) }
    }

    /** When the Lantern hop last failed on [network] while the hold still runs; null otherwise. */
    fun failedAt(network: String?, now: Long = System.currentTimeMillis()): Long? {
        if (network.isNullOrBlank()) return null
        val at = inMemory[network] ?: runCatching { BuiltinMethodMemory.read(key(network))?.toLongOrNull() }.getOrNull()
            ?.also { inMemory[network] = it }
        return at?.takeIf { now - it in 0 until HOLD_MS }
    }

    fun blocked(network: String?, now: Long = System.currentTimeMillis()): Boolean = failedAt(network, now) != null

    fun clear(network: String?) {
        if (network.isNullOrBlank()) return
        inMemory.remove(network)
        runCatching { BuiltinMethodMemory.write(key(network), null) }
    }

    fun describe(network: String?, now: Long = System.currentTimeMillis()): String =
        failedAt(network, now)?.let { "WARP through Lantern failed on this network ${(now - it) / 60_000} min ago; " +
            "not retried for ${HOLD_MS / 60_000} min" } ?: "no Lantern hop failure remembered"

    /**
     * Whether a failed Lantern-hop attempt says something about this network (and is remembered). The build not
     * carrying Radiance is not about the network; everything else (hop not ready, no Cloudflare through Lantern,
     * Aether not ready or no HTTPS through the hop) is.
     */
    fun counts(reason: String?): Boolean = reason != com.example.vpn.sidecar.LanternRelayHop.RUNTIME_MISSING

    internal fun resetForTest() = inMemory.clear()
}
