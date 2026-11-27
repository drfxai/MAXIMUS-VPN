package com.example.vpn.connections

/**
 * Per-network memory that Cloudflare's TLS was measured filtered (cf=0) here.
 *
 * Build 179 device test (Irancell): the cf=0 reading is only "fresh" for 10 minutes
 * ([com.example.vpn.smart.SmartConnectPlan.READING_MAX_AGE_MS]), and an explicit WARP carrier pick does not measure it
 * again. So MASQUE-in-MASQUE (182 s) and Aether → Tor (201 s) ran without the 60 s cf=0 check the picker had promised,
 * and Smart Connect called the network "open" and led with Aether (55 s instead of 19 s, N5). A cf=0 reading (or a WARP
 * data-plane hold) is remembered for [MEMORY_MS] on that network; a reading that Cloudflare answers, or WARP carrying
 * verified traffic there, clears it. Kept in memory and in [BuiltinMethodMemory]'s store.
 */
object CloudflareFilterMemory {
    const val MEMORY_MS = 6 * 60 * 60_000L

    private val inMemory = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun key(network: String) = "cloudflareFiltered#$network"

    fun record(network: String?, now: Long = System.currentTimeMillis()) {
        if (network.isNullOrBlank()) return
        inMemory[network] = now
        runCatching { BuiltinMethodMemory.write(key(network), now.toString()) }
    }

    fun clear(network: String?) {
        if (network.isNullOrBlank()) return
        inMemory.remove(network)
        runCatching { BuiltinMethodMemory.write(key(network), null) }
    }

    fun knownAt(network: String?, now: Long = System.currentTimeMillis()): Long? {
        if (network.isNullOrBlank()) return null
        val at = inMemory[network] ?: runCatching { BuiltinMethodMemory.read(key(network))?.toLongOrNull() }.getOrNull()
            ?.also { inMemory[network] = it }
        return at?.takeIf { now - it in 0 until MEMORY_MS }
    }

    /**
     * Whether cf=0 is known here: a fresh reading says so ([freshFiltered]), or one was remembered, or WARP's data plane
     * is held here. A fresh reading that Cloudflare answers ([freshReachable]) wins over the memory. Pure.
     */
    fun known(freshFiltered: Boolean, freshReachable: Boolean, rememberedAt: Long?, warpHeld: Boolean): Boolean =
        freshFiltered || (!freshReachable && (rememberedAt != null || warpHeld))

    /** [known] for [network] now, recording a fresh cf=0 and clearing on a fresh cf=1. */
    fun knownNow(network: String?, freshFiltered: Boolean, freshReachable: Boolean, now: Long = System.currentTimeMillis()): Boolean {
        if (freshFiltered) record(network, now)
        if (freshReachable) clear(network)
        return known(freshFiltered, freshReachable, knownAt(network, now),
            runCatching { WarpDataPlaneHold.blocked(BuiltinMethodMemory.network(), now) }.getOrDefault(false))
    }

    internal fun resetForTest() = inMemory.clear()
}
