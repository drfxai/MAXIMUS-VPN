package com.example.vpn.safety

import com.example.data.model.OperationalMode

/**
 * Whether a connect that failed before any tunnel existed may remove the traffic block and let apps
 * use the network directly.
 *
 * A server name that cannot be looked up is a network problem, usually the filter itself: the block
 * stays, in every mode, and the user ends it with Disconnect. Releasing it there sent phones online
 * without the VPN during exactly the failovers, reconnects and Always-on restarts that happen on a
 * hostile network.
 *
 * A profile that can never run (malformed, unsupported) is not an outage. In DAILY mode, when the user
 * pressed Connect, the block is released so the phone keeps working; an automatic connect (failover,
 * reconnect, Always-on) and GOD MODE keep it.
 */
object FailClosedPolicy {
    enum class Failure { UNRESOLVABLE_SERVER, INVALID_PROFILE }

    fun mayReleaseBlock(
        failure: Failure,
        startedByUser: Boolean,
        mode: OperationalMode,
        /** Explicit user Kill Switch overrides DAILY's invalid-profile convenience exception. */
        killSwitchEnabled: Boolean = false
    ): Boolean =
        !killSwitchEnabled && failure == Failure.INVALID_PROFILE &&
            startedByUser && mode == OperationalMode.DAILY

    /**
     * A Connect the user pressed ended FAILED with nothing more to try (its deadline gave up, or every allowed step
     * failed). Build 176 device test: after such a failure the log said "other apps stay blocked", so the phone had no
     * internet until the user tapped the button again. In DAILY mode with the Kill Switch off the block is released and
     * apps use the network directly until the next Connect; the user's explicit Kill Switch, GOD MODE ("traffic stays
     * blocked when nothing works") and Trade mode keep it, and so does an automatic session (Always-on, failover).
     */
    fun mayReleaseAfterFailedConnect(startedByUser: Boolean, mode: OperationalMode, killSwitchEnabled: Boolean): Boolean =
        startedByUser && !killSwitchEnabled && mode == OperationalMode.DAILY

    /**
     * Whether traffic stays blocked after a failed Connect: only while the protection (block) was requested and
     * [mayReleaseAfterFailedConnect] does not release it. The failure text on Home follows this (build 177 said
     * "Traffic stays blocked" while Daily mode with the Kill Switch off had released the block).
     */
    fun keepsBlockAfterFailedConnect(protectionRequested: Boolean, startedByUser: Boolean, mode: OperationalMode,
                                     killSwitchEnabled: Boolean): Boolean =
        protectionRequested && !mayReleaseAfterFailedConnect(startedByUser, mode, killSwitchEnabled)

    /** What a failed Connect leaves behind, and so what its text says (build 177, B2 and B20). */
    enum class AfterFailure {
        /** No traffic block was requested (nothing to say). */
        NO_BLOCK,
        /** Daily mode, the user's Connect, Kill Switch off: the block is released. */
        RELEASED,
        /** Daily mode, the user's Connect, Kill Switch on: the block stays because of the Kill Switch. */
        KILL_SWITCH,
        /** GOD / Trade / Ghost Rider or an automatic session (Always-on, failover): the block stays. */
        MODE_OR_AUTOMATIC
    }

    fun afterFailedConnect(protectionRequested: Boolean, startedByUser: Boolean, mode: OperationalMode,
                           killSwitchEnabled: Boolean): AfterFailure = when {
        !protectionRequested -> AfterFailure.NO_BLOCK
        mayReleaseAfterFailedConnect(startedByUser, mode, killSwitchEnabled) -> AfterFailure.RELEASED
        startedByUser && mode == OperationalMode.DAILY && killSwitchEnabled -> AfterFailure.KILL_SWITCH
        else -> AfterFailure.MODE_OR_AUTOMATIC
    }

    /**
     * Build 177 (B21): the Kill Switch was turned off while a failed Connect still held the traffic block, and the block
     * stayed. The block a failed Connect holds ([holdingFailedBlock]) is released when the Kill Switch goes from on to
     * off and the same rule as at the failure ([mayReleaseAfterFailedConnect]) now releases it: Daily mode and the
     * user's own Connect only. GOD, Trade, Ghost Rider and automatic (Always-on) sessions keep the block.
     */
    fun releaseOnKillSwitchOff(holdingFailedBlock: Boolean, startedByUser: Boolean, mode: OperationalMode,
                               killSwitchWas: Boolean, killSwitchNow: Boolean): Boolean =
        holdingFailedBlock && killSwitchWas && !killSwitchNow && mayReleaseAfterFailedConnect(startedByUser, mode, killSwitchNow)

    /**
     * The failure message with exactly one sentence about the traffic block: [notice] replaces any of [knownNotices]
     * already in [message] (a deadline message carries one; a later release changes it), or is appended. Pure.
     */
    fun withNotice(message: String?, notice: String, knownNotices: Collection<String>): String {
        var base = message?.trim().orEmpty()
        knownNotices.filter { it.isNotBlank() }.sortedByDescending { it.length }.forEach { base = base.replace(it, "") }
        base = base.trim().replace(Regex("\\s{2,}"), " ")
        if (notice.isBlank()) return base
        if (base.isEmpty()) return notice
        return if (base.last() in ".!?:") "$base $notice" else "$base. $notice"
    }
}
