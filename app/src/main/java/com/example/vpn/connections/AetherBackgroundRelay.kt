package com.example.vpn.connections

import com.example.data.model.ConnectionState
import com.example.data.model.ConnectionStatus

/**
 * WARP through a saved server, retried in the background after Aether stopped because the carrier drops WARP data
 * (WireGuard handshake passed, no HTTPS) while no saved server had been verified on this network yet. The session
 * falls back to saved servers first; once one carries verified traffic, wireguard-relay (then h2-relay) is tried
 * through it on a separate port, and the session switches to Aether only if that relay carries verified HTTPS.
 */
object AetherBackgroundRelay {
    /** A pending retry older than this (or from another network) is dropped. */
    const val MAX_AGE_MS = 10 * 60_000L
    /** Let the new connection settle before a second engine starts beside it. */
    const val SETTLE_MS = 5_000L

    /** Whole background run on a metered link (mobile data): wireguard-relay and h2-relay together, never longer. */
    const val METERED_BUDGET_MS = 90_000L

    /** Leave the second carrier time to start and pass HTTPS verification on mobile data. */
    internal const val FIRST_STARTUP_BUDGET_MS = 30_000L

    internal fun startupWindowMs(windowMs: Long, elapsedMs: Long, metered: Boolean, hasNext: Boolean): Long =
        if (metered && hasNext) minOf(windowMs, (FIRST_STARTUP_BUDGET_MS - elapsedMs).coerceAtLeast(0))
        else windowMs

    /** Order of the relayed carriers tried in the background. */
    val ORDER = listOf(ConnectionEngineRunner.WIREGUARD_RELAY, ConnectionEngineRunner.H2_RELAY)

    /**
     * Start the background relay: a retry is pending for this same network and still fresh, the connection that
     * just verified is a saved server Aether can use as its exit hop, that server is not frozen (~16 KB freeze) on
     * this network, and this user Connect has not switched to a relay yet (at most one relay switch per Connect).
     */
    fun shouldStart(pendingNetwork: String?, pendingAt: Long, network: String?, savedServerEligible: Boolean,
                    connectedVerified: Boolean, now: Long, connectedFrozen: Boolean = false,
                    alreadySwitched: Boolean = false): Boolean =
        pendingNetwork != null && !network.isNullOrBlank() && pendingNetwork == network && savedServerEligible &&
            connectedVerified && now - pendingAt in 0..MAX_AGE_MS && !connectedFrozen && !alreadySwitched

    internal fun isSameRunningAttempt(state: ConnectionState, attemptId: String, networkGeneration: Int): Boolean =
        state.attemptId == attemptId && state.networkGeneration == networkGeneration &&
            (state.status == ConnectionStatus.CONNECTED || state.status == ConnectionStatus.DEGRADED)

    /**
     * Switch to Aether only after the relay carried verified HTTPS, with the same connection still up on the same
     * network, its exit server not frozen there meanwhile, and no relay switch yet in this user Connect.
     */
    fun shouldSwitch(relayVerified: Boolean, stillConnected: Boolean, sameNetwork: Boolean,
                     connectedFrozen: Boolean = false, alreadySwitched: Boolean = false): Boolean =
        relayVerified && stillConnected && sameNetwork && !connectedFrozen && !alreadySwitched

    /**
     * After connect the main tunnel must pass at least one real traffic check of the failover watchdog (not only the
     * connect-time request), and then [GRACE_AFTER_REAL_CHECK_MS] more, before a second engine starts beside it.
     * Build 150: the relay started 6 s after connect on Irancell while the connected server was already failing its
     * checks, and spent the metered link on a registration that had failed twice there minutes before.
     */
    const val GRACE_AFTER_REAL_CHECK_MS = 20_000L
    /** Longest wait for that check; past it the relay is not started for this connection. */
    const val MAX_GATE_WAIT_MS = 3 * 60_000L
    const val GATE_POLL_MS = 2_000L

    enum class Gate { WAIT, START, SKIP_REGISTRATION, SKIP_TIMEOUT, SKIP_WARP_DATA }

    /**
     * Whether the background relay may start now. [firstRealPassAt]: when the watchdog's first real check after
     * connect passed (0: none yet). [strikes]: failover strikes inside its window. [registrationBlocked]: WARP
     * registration failed on this network recently ([WarpRegistrationMemory]). [waitedMs]: time spent waiting.
     * [warpDataHeld]: WARP's data plane is held on this network ([WarpDataPlaneHold]): never started.
     * [bulkRunning]: the main tunnel's upload/download check (bulk.tun) is still running: wait for its verdict, so the
     * relay neither competes with it for the link nor starts beside a server that is about to be called frozen
     * (build 151: the relay started at 16:36:21 while the main tunnel's bulk test was still running on mobile data).
     */
    fun gate(firstRealPassAt: Long, now: Long, metered: Boolean, strikes: Int, registrationBlocked: Boolean, waitedMs: Long,
             bulkRunning: Boolean = false, warpDataHeld: Boolean = false): Gate = when {
        warpDataHeld -> Gate.SKIP_WARP_DATA
        registrationBlocked -> Gate.SKIP_REGISTRATION
        bulkRunning -> if (waitedMs >= MAX_GATE_WAIT_MS) Gate.SKIP_TIMEOUT else Gate.WAIT
        firstRealPassAt > 0 && now - firstRealPassAt >= GRACE_AFTER_REAL_CHECK_MS && !(metered && strikes > 0) -> Gate.START
        waitedMs >= MAX_GATE_WAIT_MS -> Gate.SKIP_TIMEOUT
        else -> Gate.WAIT
    }

    /** A running relay on a metered link stops as soon as the main tunnel has a failover strike: it needs the link. */
    fun mustStop(metered: Boolean, strikes: Int): Boolean = metered && strikes > 0

    /** The physical link counts as metered when measured so, when Android says so, or when it is mobile data. */
    fun metered(measuredMetered: Boolean, activeMetered: Boolean, networkKey: String?): Boolean =
        measuredMetered || activeMetered || networkKey?.let { it == "cell" || it.startsWith("cell:") } == true

    /** Total cap of the background run: [METERED_BUDGET_MS] on a metered link, none otherwise. */
    fun budgetMs(metered: Boolean): Long? = if (metered) METERED_BUDGET_MS else null
}
