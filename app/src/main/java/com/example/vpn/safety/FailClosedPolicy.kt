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

    fun mayReleaseBlock(failure: Failure, startedByUser: Boolean, mode: OperationalMode): Boolean =
        failure == Failure.INVALID_PROFILE && startedByUser && mode == OperationalMode.DAILY
}
