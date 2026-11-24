package com.example.vpn.connections

/**
 * What an automatic connect tries after direct Psiphon failed on this network (it did not start, or its tunnel stopped
 * carrying traffic): Shir o Khorshid first (the community Psiphon fork: Conduit volunteers and CDN fronting, which
 * reach Psiphon's network by other ways than direct Psiphon's dials), then Psiphon over Lantern.
 *
 * Shir o Khorshid is skipped when Psiphon over Lantern is already the path verified on this network
 * ([PsiphonChainMemory.chainVerifiedAt]): the chain goes straight next instead of another start of up to two minutes.
 * When Shir o Khorshid was itself the fallback of a failed direct Psiphon and also fails to start, the chain follows.
 * A Shir o Khorshid the user picked on their own never hands over (nothing marks it as a fallback).
 *
 * The same boundaries as the chain ([PsiphonChainMemory.mayUseChain]): never for a pinned (exact), exit-locked or
 * forced session; each step at most once per connect. Pure.
 */
object PsiphonFallbackLadder {
    enum class Step { SHIROKHORSHID, CHAIN }

    fun next(
        /** The connect that failed ran direct Psiphon (public servers). */
        failedDirect: Boolean,
        /** The connect that failed ran Shir o Khorshid as the fallback of a failed direct Psiphon. */
        failedShirokhorshidFallback: Boolean,
        /** Smart Connect, auto failover or the power button's fallback is on, and the session is not pinned/locked/forced. */
        allowed: Boolean,
        shirokhorshidAvailable: Boolean,
        shirokhorshidTried: Boolean,
        chainAvailable: Boolean,
        chainTried: Boolean,
        /** Psiphon over Lantern carried verified traffic on this network recently. */
        chainVerifiedHere: Boolean
    ): Step? {
        if (!allowed) return null
        val chain = if (chainAvailable && !chainTried) Step.CHAIN else null
        return when {
            failedDirect -> if (shirokhorshidAvailable && !shirokhorshidTried && !chainVerifiedHere) Step.SHIROKHORSHID else chain
            failedShirokhorshidFallback -> chain
            else -> null
        }
    }
}
