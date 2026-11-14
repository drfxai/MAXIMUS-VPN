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

    /** Order of the relayed carriers tried in the background. */
    val ORDER = listOf(ConnectionEngineRunner.WIREGUARD_RELAY, ConnectionEngineRunner.H2_RELAY)

    /**
     * Start the background relay: a retry is pending for this same network and still fresh, and the connection
     * that just verified is a saved server Aether can use as its exit hop.
     */
    fun shouldStart(pendingNetwork: String?, pendingAt: Long, network: String?, savedServerEligible: Boolean,
                    connectedVerified: Boolean, now: Long): Boolean =
        pendingNetwork != null && !network.isNullOrBlank() && pendingNetwork == network && savedServerEligible &&
            connectedVerified && now - pendingAt in 0..MAX_AGE_MS

    /** Switch to Aether only after the relay carried verified HTTPS, with the same connection still up on the same network. */
    fun shouldSwitch(relayVerified: Boolean, stillConnected: Boolean, sameNetwork: Boolean): Boolean =
        relayVerified && stillConnected && sameNetwork
}
