package com.example.vpn.connections

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

    /**
     * Switch to Aether only after the relay carried verified HTTPS, with the same connection still up on the same
     * network, its exit server not frozen there meanwhile, and no relay switch yet in this user Connect.
     */
    fun shouldSwitch(relayVerified: Boolean, stillConnected: Boolean, sameNetwork: Boolean,
                     connectedFrozen: Boolean = false, alreadySwitched: Boolean = false): Boolean =
        relayVerified && stillConnected && sameNetwork && !connectedFrozen && !alreadySwitched

    /** The physical link counts as metered when measured so, when Android says so, or when it is mobile data. */
    fun metered(measuredMetered: Boolean, activeMetered: Boolean, networkKey: String?): Boolean =
        measuredMetered || activeMetered || networkKey?.let { it == "cell" || it.startsWith("cell:") } == true

    /** Total cap of the background run: [METERED_BUDGET_MS] on a metered link, none otherwise. */
    fun budgetMs(metered: Boolean): Long? = if (metered) METERED_BUDGET_MS else null
}
