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
}
