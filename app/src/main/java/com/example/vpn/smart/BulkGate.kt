package com.example.vpn.smart

import com.example.vpn.verify.FREEZE_MAX_BYTES
import com.example.vpn.verify.FREEZE_MIN_BYTES

/**
 * A cheap bulk download before Smart Connect calls a server its winner, on networks where one small request proves
 * too little.
 *
 * Build 151 (Irancell, metered, cf=0): stage D "verified 25/2" picked p:b9917628cc on one 638 ms request; the
 * background check a minute later saw the download stop at 16050 B and the upload at 16384 B. The next session's
 * stage D picked p:e63b7946e8 (same /16) the same way and it froze at the same byte. Here, on a metered network or
 * one with a ~16 KB freeze on record, the server that just passed its small request must also carry
 * [MIN_BYTES] of a [DOWNLOAD_BYTES] download within [BUDGET_MS] before it counts as verified; one that stops inside the
 * freeze window and then goes quiet is marked frozen (FreezeBook) and the search goes on without it. At most
 * [MAX_CANDIDATES] candidates are gated per Connect, so the extra time is bounded (≤ 6 s each, ≤ 18 s in all).
 *
 * Pure: the caller runs the download ([com.example.vpn.verify.TrafficCheck.gateDownload]) and acts on [verdict].
 */
object BulkGate {
    /** Bytes that make the gate pass. */
    const val MIN_BYTES = 32 * 1024
    /** Size of the gate download: enough past [MIN_BYTES] that a short read is not mistaken for a pass. */
    const val DOWNLOAD_BYTES = 48 * 1024
    /** The whole gate per candidate: connect, TLS and the download. */
    const val BUDGET_MS = 6_000L
    /** One exchange's own deadline, inside [BUDGET_MS]. */
    const val STEP_MS = 5_500
    /** A read that stopped inside the freeze window counts as frozen only after this long with no new byte. */
    const val MIN_IDLE_MS = 2_000L
    /** Candidates gated per Connect; past this the next pick is taken on its small request as before. */
    const val MAX_CANDIDATES = 3
    /** The last-good mark (FreezeBook) that spares a server the gate: it passed the full check here this recently. */
    const val RECENT_GOOD_MS = 60 * 60_000L

    /** Carried in the connect's exclude set, one per gated candidate that froze. */
    const val MARKER_PREFIX = "__bulk-gate-"

    fun marker(n: Int): String = "${MARKER_PREFIX}${n}__"

    /** How many candidates this Connect already gated out (markers in [exclude]). */
    fun gatedSoFar(exclude: Set<String>): Int = exclude.count { it.startsWith(MARKER_PREFIX) }

    /**
     * Whether the gate runs for a winner: a Smart pick ([smartPick]: raced, not pinned, forced or the power button's
     * exact config) carried by the Xray core through the TUN ([tunPath]), on a [metered] network or one with a freeze
     * on record ([freezeHistory]), not a server that passed the full transfer check here within [RECENT_GOOD_MS]
     * ([recentlyGood]), and fewer than [MAX_CANDIDATES] gated so far.
     */
    fun applies(smartPick: Boolean, tunPath: Boolean, metered: Boolean, freezeHistory: Boolean,
                recentlyGood: Boolean, gatedSoFar: Int): Boolean =
        smartPick && tunPath && (metered || freezeHistory) && !recentlyGood && gatedSoFar < MAX_CANDIDATES

    /** What one gate download saw. */
    sealed class Read {
        /** The exchange ended: [bytes] of body arrived (all of it unless the server closed early). */
        data class Done(val bytes: Int, val elapsedMs: Long) : Read()
        /** The exchange was cut (deadline, reset): [bytes] arrived, the last of them [idleMs] before the end. */
        data class Cut(val bytes: Int, val idleMs: Long, val timedOut: Boolean, val elapsedMs: Long) : Read()
        /** Could not be measured (the reference refused, answered an error status, name did not resolve). */
        data class NotTested(val reason: String) : Read()
    }

    enum class Verdict { PASS, FROZEN, INCONCLUSIVE }

    fun verdict(read: Read): Verdict = when (read) {
        is Read.Done -> if (read.bytes >= MIN_BYTES) Verdict.PASS else Verdict.INCONCLUSIVE
        is Read.Cut -> when {
            read.bytes >= MIN_BYTES -> Verdict.PASS
            read.timedOut && read.bytes in FREEZE_MIN_BYTES..FREEZE_MAX_BYTES && read.idleMs >= MIN_IDLE_MS -> Verdict.FROZEN
            else -> Verdict.INCONCLUSIVE
        }
        is Read.NotTested -> Verdict.INCONCLUSIVE
    }

    fun describe(read: Read): String = when (read) {
        is Read.Done -> "${read.bytes} B in ${read.elapsedMs} ms"
        is Read.Cut -> "${read.bytes} B then nothing for ${read.idleMs} ms (${if (read.timedOut) "timeout" else "closed"}, ${read.elapsedMs} ms)"
        is Read.NotTested -> "not tested: ${read.reason}"
    }
}
