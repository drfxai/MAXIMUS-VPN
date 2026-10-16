package com.example.vpn.safety

import com.example.vpn.sidecar.EngineChain

/**
 * The connect path Ghost Rider prefers: a multi-hop chain that exits through Tor, chosen from the
 * engines that can actually run on the device, and always ending in a safe fallback so maximum
 * privacy is never bought at the cost of being left offline. Connectivity comes first even here, so
 * the preference degrades gracefully rather than failing closed into no connection at all.
 *
 * This is a pure planner: it names the ordered preference and builds the chain, but does not start
 * anything. The connect path reads it only when [OperatingModePolicy.torMultiHop] is set.
 */
object GhostRiderPath {
    const val PSIPHON = "psiphon"
    const val TOR = "tor"

    /** What Ghost Rider should try, strongest privacy first, each one falling back to the next. */
    enum class Step {
        /** Psiphon → Tor: two bundled relays in series, exiting through Tor. The strongest path. */
        CHAIN_PSIPHON_TOR,
        /** Tor alone, itself three relays. Used when Psiphon cannot run. */
        TOR_ONLY,
        /** The mode's normal hardened server racing. The always-present floor so a connect never dead-ends. */
        HARDENED_SERVERS
    }

    /**
     * The ordered preference for this connect, given the engine ids that can run now. The list always
     * ends in [Step.HARDENED_SERVERS], so privacy preferences degrade to a working connection rather
     * than to nothing.
     */
    fun preference(availableEngineIds: Set<String>): List<Step> {
        val steps = mutableListOf<Step>()
        if (PSIPHON in availableEngineIds && TOR in availableEngineIds) steps += Step.CHAIN_PSIPHON_TOR
        if (TOR in availableEngineIds) steps += Step.TOR_ONLY
        steps += Step.HARDENED_SERVERS
        return steps
    }

    /**
     * The engine chain for [Step.CHAIN_PSIPHON_TOR]: Psiphon is the entry hop (closest to the phone)
     * and Tor is the exit, so the site sees a Tor exit. Valid by [EngineChain.problem].
     */
    fun chain(): EngineChain.Chain =
        EngineChain.Chain(listOf(EngineChain.Hop(PSIPHON), EngineChain.Hop(TOR)))
}
