package com.example.vpn.connections

import com.example.data.model.VlessProfile
import com.example.vpn.sidecar.PsiphonSidecar

/**
 * Per-network memory of direct Psiphon failing to start, so the next connect there goes straight to Psiphon
 * chained over Lantern (Radiance), [ConfigFreeMethod.PSIPHON_RADIANCE].
 *
 * Build 170 on Irancell (metered, Samsung Android 15): direct Psiphon timed out after 120 s twice per connect
 * (UDP+TCP, then TCP-only) at 20:44 and 21:13 UTC, while the chain over Lantern connected at 21:12 (probe 616 ms,
 * 61 KB/s down). Each failed direct start cost four minutes. For [HOLD_MS] after such a failure on a network an
 * automatic connect to direct Psiphon there starts with the chain instead; an exact (pinned) connect still runs
 * direct Psiphon, and a direct start that brings a tunnel up clears the memory.
 *
 * Kept in memory and in [BuiltinMethodMemory]'s store (survives a service restart).
 */
object PsiphonChainMemory {
    const val HOLD_MS = 6 * 60 * 60_000L

    private val inMemory = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun key(network: String) = "psiphonDirectFailed#$network"

    /** The public direct Psiphon engine profile (not a private server, not a chain hop). Pure. */
    fun isDirect(profile: VlessProfile): Boolean =
        PsiphonSidecar.ID == com.example.vpn.engine.ProfileExtras.read(profile).optString(PsiphonSidecar.KEY_ENGINE) &&
            PsiphonSidecar.privateId(profile).isEmpty()

    /** The chain this falls back to: Psiphon whose dials ride Lantern / Radiance. */
    fun chainProfile(): VlessProfile = BuiltinConnections.ConfigFreeMethod.PSIPHON_RADIANCE.profile()

    /**
     * Whether an automatic hand-over from direct Psiphon to the chain is allowed: the same boundaries as Aether's
     * recovery ([ConnectionEngineRunner.mayRecoverAether]) — never for a pinned (exact), exit-locked or forced
     * session, never twice in one connect. Pure.
     */
    fun mayUseChain(
        direct: Boolean, smart: Boolean, autoFailover: Boolean, fallback: Boolean,
        exact: Boolean, exitLocked: Boolean, force: Boolean, chainAlreadyTried: Boolean, chainAvailable: Boolean
    ): Boolean = direct && chainAvailable && (smart || autoFailover || fallback) && !exact && !exitLocked &&
        !force && !chainAlreadyTried

    fun recordDirectFailure(network: String?, now: Long = System.currentTimeMillis()) {
        if (network.isNullOrBlank()) return
        inMemory[network] = now
        runCatching { BuiltinMethodMemory.write(key(network), now.toString()) }
    }

    /** When direct Psiphon last failed to start on [network] while the hold still runs; null otherwise. */
    fun failedAt(network: String?, now: Long = System.currentTimeMillis()): Long? {
        if (network.isNullOrBlank()) return null
        val at = inMemory[network] ?: runCatching { BuiltinMethodMemory.read(key(network))?.toLongOrNull() }.getOrNull()
            ?.also { inMemory[network] = it }
        return at?.takeIf { now - it in 0 until HOLD_MS }
    }

    fun preferChain(network: String?, now: Long = System.currentTimeMillis()): Boolean = failedAt(network, now) != null

    fun clear(network: String?) {
        if (network.isNullOrBlank()) return
        inMemory.remove(network)
        runCatching { BuiltinMethodMemory.write(key(network), null) }
    }

    fun describe(network: String?, now: Long = System.currentTimeMillis()): String =
        failedAt(network, now)?.let { "direct Psiphon failed to start on this network ${(now - it) / 60_000} min ago; " +
            "the chain over Lantern goes first for ${HOLD_MS / 3_600_000} h" } ?: "no direct Psiphon failure remembered"

    internal fun resetForTest() = inMemory.clear()
}
