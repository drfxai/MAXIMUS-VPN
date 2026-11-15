package com.example.vpn.connections

/**
 * Per-network memory of a failed WARP account registration (the API timed out, TLS failed, rate limited: see
 * [com.example.vpn.sidecar.AetherApiBridge.bootstrapBlocked]).
 *
 * Build 150 on Irancell (cell:43235, metered): registration timed out after ~14 s at 17:25:32, again at 17:26:11
 * on the same network, and the background relay started 6 s after connect and failed the same registration at
 * 17:27:36. Each attempt cost mobile data and time while nothing about the network had changed. For [HOLD_MS]
 * after a failure here, the Aether ladder skips every carrier that would need a new registration (a carrier with
 * its own saved identity still runs) and the background relay is not started.
 *
 * Kept in memory and in [BuiltinMethodMemory]'s store (survives a service restart); cleared as soon as a WARP
 * carrier carries HTTPS on that network.
 */
object WarpRegistrationMemory {
    const val HOLD_MS = 30 * 60_000L

    private val inMemory = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun key(network: String) = "warpRegistrationFailed#$network"

    fun record(network: String?, now: Long = System.currentTimeMillis()) {
        if (network.isNullOrBlank()) return
        inMemory[network] = now
        runCatching { BuiltinMethodMemory.write(key(network), now.toString()) }
    }

    /** When registration last failed on [network] while the hold still runs; null otherwise. */
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

    /** "registration failed here 3 min ago" for a skip line. */
    fun describe(network: String?, now: Long = System.currentTimeMillis()): String =
        failedAt(network, now)?.let { "WARP API registration failed on this network ${(now - it) / 60_000} min ago; " +
            "held for ${HOLD_MS / 60_000} min" } ?: "no registration failure remembered"

    internal fun resetForTest() = inMemory.clear()
}
