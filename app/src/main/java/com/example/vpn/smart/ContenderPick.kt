package com.example.vpn.smart

/**
 * How Smart Connect chooses among the paths that carried a request, the way the LAB's Full Analysis
 * ranks them (FullAnalysis.rank): a path that passed again on a later recheck beats one that passed
 * once and then failed (the Irancell pattern: a connection that works for a few seconds and is then
 * cut), a path the LAB proved on this network beats one it did not, and then the lower score wins.
 *
 * The score is the median request time plus [KIND_STEP_MS] for each step down ConnectionKind.ORDER
 * (REALITY, CDN and TLS look like ordinary HTTPS and survive a filter that escalates; see ServerRace)
 * plus a penalty from what this network measured before (a bulk transfer that stalled through it, a
 * slow download). Pure, so every rule is tested without a phone.
 */
object ContenderPick {
    data class Contender<T>(
        val item: T,
        /** Position in ConnectionKind.ORDER (0 is REALITY). */
        val kindRank: Int,
        /** Request time of each test in order; null for a recheck that carried nothing. The first is always a pass. */
        val samples: List<Long?>,
        /** The LAB's Full Analysis found it carrying traffic on this network. */
        val preferred: Boolean = false,
        /** Added to the score, from earlier measurements on this network (see RouteSpeedBook.penaltyMs). */
        val penaltyMs: Long = 0
    ) {
        /** Every recheck passed too. */
        val stable: Boolean get() = samples.none { it == null }
        val medianMs: Long get() = median(samples.filterNotNull())
        val score: Long get() = medianMs + kindRank * KIND_STEP_MS + penaltyMs
    }

    /** Best first. */
    fun <T> order(contenders: List<Contender<T>>): List<Contender<T>> =
        contenders.sortedWith(
            compareByDescending<Contender<T>> { it.stable }
                .thenByDescending { it.preferred }
                .thenBy { it.score }
        )

    /** How long to wait so the next recheck is at least [RECHECK_GAP_MS] after [lastAt]. */
    fun waitBeforeRecheck(lastAt: Long, now: Long): Long = (lastAt + RECHECK_GAP_MS - now).coerceIn(0, RECHECK_GAP_MS)

    internal fun median(values: List<Long>): Long {
        if (values.isEmpty()) return Long.MAX_VALUE / 4
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /** One step down the kinds of connection costs as much as this much more request time. */
    const val KIND_STEP_MS = 150L

    /** Smart Connect gathers this many working paths before it chooses (the selected server counts). */
    const val CONTENDERS = 3

    /** Rechecks of the working paths before choosing; with the first test that is three requests each. */
    const val RECHECKS = 2

    /** The LAB calls two passes stable only when they are at least this far apart (FullAnalysis.STABLE_WINDOW_MS). */
    const val RECHECK_GAP_MS = 3_000L
}
