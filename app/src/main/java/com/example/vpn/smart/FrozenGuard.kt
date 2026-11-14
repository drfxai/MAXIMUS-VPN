package com.example.vpn.smart

/**
 * Keeps a server that froze (or stalled) on this network ([FreezeBook.frozen]) out of Smart Connect's choices.
 *
 * The ~16 KB freeze is a per-connection byte cap on the server's address: a stealth alternate (another TLS
 * fingerprint, a split handshake, ECH) still answers a small request through it, then freezes at the same byte
 * again. Build 145 on Irancell: a server frozen at 16050 B was chosen again on the next Connect through
 * "an alternate path: Firefox TLS fingerprint" and froze at 16050 B a minute later.
 *
 * A frozen server is only used when nothing else is left: no other saved server that has not frozen here.
 * Pure.
 */
object FrozenGuard {
    /**
     * Whether Smart Connect may look for a stealth alternate path to [requestedId] (the selected server).
     * [otherUsable] are the other saved servers Smart Connect could use. False while [requestedId] is frozen
     * here and any of [otherUsable] is not: the search moves on to them instead.
     */
    fun stealthAllowed(requestedId: String, frozen: Set<String>, otherUsable: Collection<String>): Boolean =
        requestedId !in frozen || otherUsable.none { it != requestedId && it !in frozen }

    /** [items] without the frozen ones, unless every item is frozen (the last resort keeps them all). */
    fun <T> preferUnfrozen(items: List<T>, frozen: Set<String>, id: (T) -> String): List<T> {
        if (frozen.isEmpty()) return items
        val live = items.filter { id(it) !in frozen }
        return if (live.isEmpty()) items else live
    }

    /**
     * The comparison's seed (the selected server that just answered) and candidates, frozen servers moved out:
     * [Split.seed] and [Split.candidates] compete; [Split.lastResortSeed] is returned only when none of them works.
     */
    fun <T, S> split(seed: List<S>, candidates: List<T>, frozen: Set<String>, seedId: (S) -> String, id: (T) -> String): Split<T, S> {
        if (frozen.isEmpty()) return Split(seed, candidates, emptyList(), 0)
        val liveSeed = seed.filter { seedId(it) !in frozen }
        val frozenSeed = seed.filter { seedId(it) in frozen }
        val liveCandidates = candidates.filter { id(it) !in frozen }
        // Nothing else to compare against: the frozen candidates still race, as the last resort.
        val raced = if (liveSeed.isEmpty() && liveCandidates.isEmpty()) candidates else liveCandidates
        return Split(liveSeed, raced, frozenSeed, (candidates.size - raced.size) + frozenSeed.size)
    }

    data class Split<T, S>(val seed: List<S>, val candidates: List<T>, val lastResortSeed: List<S>, val leftOut: Int)

    fun skipLine(name: String): String =
        "Selected server froze on this network (~16 KB cap): stealth alternates are not tried for it while other servers remain ($name)."
}
