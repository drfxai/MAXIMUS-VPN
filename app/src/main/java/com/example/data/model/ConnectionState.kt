package com.example.data.model

import com.example.vpn.diagnostics.FailureStage

/**
 * Each step of a connection, in order. CONNECTED is reported only after real traffic went through the
 * tunnel; a tunnel that exists but carries nothing is TUNNEL_STARTED_CONNECTIVITY_UNVERIFIED.
 *
 * Plan names: STARTING = PREPARING/CONNECTING, TUN_ESTABLISHED = VPN_INTERFACE_ESTABLISHED.
 */
enum class ConnectionStatus {
    DISCONNECTED,
    PREPARING,
    CONNECTING,
    /** The Android TUN interface exists; nothing runs on it yet. */
    VPN_INTERFACE_ESTABLISHED,
    /** The proxy engine reported a successful start. */
    ENGINE_STARTED,
    PROXY_CONNECTING,
    /** The packet loop runs; a real request through the tunnel is being made. */
    VERIFYING,
    /** The tunnel is up but no request has gone through it. Never shown as connected. */
    TUNNEL_STARTED_CONNECTIVITY_UNVERIFIED,
    /** A request went through the tunnel; [ConnectionState.verifiedAt] says when. */
    CONNECTED,
    /** It was verified, but the latest checks through the tunnel failed. */
    DEGRADED,
    RECONNECTING,
    DISCONNECTING,
    FAILED,
    /** Failover is moving the session to a backup; the old tunnel's PASS no longer counts. */
    SWITCHING,
    /**
     * The phone's own network changed under a running tunnel. Traffic stays inside the tunnel, but the
     * earlier PASS was measured on the old network, so the tunnel is re-verified before it counts again.
     */
    NETWORK_CHANGED
}

data class ConnectionState(
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    /** The profile the session belongs to (what the screen shows as the server in use). */
    val activeProfile: VlessProfile? = null,
    val activeEngineName: String? = null,
    val connectedDurationSeconds: Long = 0,
    val uploadBytes: Long = 0,
    val downloadBytes: Long = 0,
    val uploadSpeedBps: Long = 0,
    val downloadSpeedBps: Long = 0,
    val pingMs: Long? = null,
    val exitCountryCode: String? = null,
    val pingCheckedAt: Long? = null,
    val vpnIp: String? = null,
    val errorMessage: String? = null,
    val lastConnectedTime: Long? = null,
    /** From the user's Connect until Disconnect; failover and reconnects keep it. */
    val sessionId: String? = null,
    /** One per connection attempt (each failover or reconnect starts a new one). */
    val attemptId: String? = null,
    /** The profile the user chose for this session. */
    val selectedProfileId: String? = null,
    /** The profile this attempt actually tried (failover or Smart Connect can pick another). */
    val attemptedProfileId: String? = null,
    /** When traffic last went through the tunnel in this attempt; null while unverified. */
    val verifiedAt: Long? = null,
    /** Where the last failure happened, when the status is FAILED, unverified or degraded. */
    val failureStage: FailureStage? = null,
    /** Checks through the tunnel that failed in a row (two in a row turn CONNECTED into DEGRADED). */
    val probeFailures: Int = 0,
    /** Bumped when the phone's own network changes: results measured before it no longer hold. */
    val networkGeneration: Int = 0,
    /** The last connect stopped because the server failed its real test; "Connect anyway" may override. */
    val canConnectAnyway: Boolean = false
) {
    val isConnected: Boolean get() = status == ConnectionStatus.CONNECTED

    /** A tunnel exists (verified or not); the user is protected and can disconnect. */
    val isTunnelUp: Boolean get() = status in TUNNEL_UP

    val isVpnInterfaceActive: Boolean get() = isTunnelUp

    val isBusy: Boolean get() = status in BUSY

    /** The key a measured PASS belongs to: it stops counting when any part changes. */
    val passKey: String get() = "$attemptId|$networkGeneration|${activeProfile?.id}|${status == ConnectionStatus.CONNECTED || status == ConnectionStatus.DEGRADED}"

    /** Short words for the status, never "connected" before traffic was verified. */
    val statusLabel: String get() = when (status) {
        ConnectionStatus.CONNECTED -> "VPN CONNECTED"
        ConnectionStatus.DEGRADED -> "CONNECTED · CHECKS FAILING"
        ConnectionStatus.TUNNEL_STARTED_CONNECTIVITY_UNVERIFIED -> "TUNNEL UP · NO TRAFFIC YET"
        ConnectionStatus.VERIFYING -> "VERIFYING TRAFFIC"
        ConnectionStatus.ENGINE_STARTED, ConnectionStatus.PROXY_CONNECTING -> "STARTING PROXY"
        ConnectionStatus.VPN_INTERFACE_ESTABLISHED -> "TUNNEL CREATED"
        ConnectionStatus.PREPARING, ConnectionStatus.CONNECTING -> "CONNECTING"
        ConnectionStatus.RECONNECTING -> "RECONNECTING"
        ConnectionStatus.SWITCHING -> "SWITCHING SERVER"
        ConnectionStatus.NETWORK_CHANGED -> "NETWORK CHANGED · RE-CHECKING"
        ConnectionStatus.DISCONNECTING -> "DISCONNECTING"
        ConnectionStatus.FAILED -> "FAILED"
        ConnectionStatus.DISCONNECTED -> "DISCONNECTED"
    }

    companion object {
        val TUNNEL_UP = setOf(
            ConnectionStatus.VPN_INTERFACE_ESTABLISHED, ConnectionStatus.ENGINE_STARTED, ConnectionStatus.PROXY_CONNECTING,
            ConnectionStatus.VERIFYING, ConnectionStatus.TUNNEL_STARTED_CONNECTIVITY_UNVERIFIED, ConnectionStatus.CONNECTED,
            ConnectionStatus.DEGRADED, ConnectionStatus.RECONNECTING, ConnectionStatus.SWITCHING, ConnectionStatus.NETWORK_CHANGED
        )
        val BUSY = setOf(
            ConnectionStatus.CONNECTING, ConnectionStatus.PREPARING, ConnectionStatus.VPN_INTERFACE_ESTABLISHED,
            ConnectionStatus.ENGINE_STARTED, ConnectionStatus.PROXY_CONNECTING, ConnectionStatus.VERIFYING,
            ConnectionStatus.RECONNECTING, ConnectionStatus.DISCONNECTING, ConnectionStatus.SWITCHING
        )
    }
}

/**
 * How a check through the tunnel changes the state. Pure, so the rules are tested without a phone.
 * A result for another attempt is ignored: a stale background check never overwrites a new session.
 */
object ConnectionVerification {
    const val DEGRADE_AFTER = 2

    fun afterCheck(
        state: ConnectionState,
        attemptId: String?,
        success: Boolean,
        latencyMs: Long?,
        now: Long,
        stage: FailureStage? = null
    ): ConnectionState {
        if (attemptId == null || attemptId != state.attemptId) return state
        val live = state.status == ConnectionStatus.VERIFYING || state.status == ConnectionStatus.CONNECTED ||
            state.status == ConnectionStatus.DEGRADED || state.status == ConnectionStatus.TUNNEL_STARTED_CONNECTIVITY_UNVERIFIED ||
            state.status == ConnectionStatus.NETWORK_CHANGED
        if (!live) return state
        if (success) {
            return state.copy(
                status = ConnectionStatus.CONNECTED,
                verifiedAt = now,
                lastConnectedTime = state.lastConnectedTime ?: now,
                pingMs = latencyMs ?: state.pingMs,
                pingCheckedAt = now,
                probeFailures = 0,
                failureStage = null,
                errorMessage = null
            )
        }
        val failures = state.probeFailures + 1
        return when (state.status) {
            ConnectionStatus.VERIFYING, ConnectionStatus.NETWORK_CHANGED -> state.copy(
                status = ConnectionStatus.TUNNEL_STARTED_CONNECTIVITY_UNVERIFIED,
                probeFailures = failures, failureStage = stage ?: FailureStage.UNKNOWN,
                errorMessage = "The tunnel is up but no request went through it (${(stage ?: FailureStage.UNKNOWN).name})."
            )
            ConnectionStatus.CONNECTED -> if (failures >= DEGRADE_AFTER) state.copy(
                status = ConnectionStatus.DEGRADED, probeFailures = failures, failureStage = stage, pingMs = null, pingCheckedAt = now
            ) else state.copy(probeFailures = failures, pingCheckedAt = now)
            else -> state.copy(probeFailures = failures, failureStage = stage ?: state.failureStage, pingMs = null, pingCheckedAt = now)
        }
    }

    /**
     * The phone's network changed. Measurements from the old network stop counting: a CONNECTED or
     * DEGRADED tunnel becomes NETWORK_CHANGED until a request through it passes again. A tunnel that is
     * still starting keeps its status; its own verification runs next.
     */
    fun onNetworkChanged(state: ConnectionState): ConnectionState {
        val next = state.copy(networkGeneration = state.networkGeneration + 1)
        return if (state.status == ConnectionStatus.CONNECTED || state.status == ConnectionStatus.DEGRADED) next.copy(
            status = ConnectionStatus.NETWORK_CHANGED, verifiedAt = null, pingMs = null, pingCheckedAt = null,
            probeFailures = 0, failureStage = null
        ) else next
    }

    /** Failover begins moving a running session to a backup. The old PASS is cleared at once. */
    fun onSwitching(state: ConnectionState): ConnectionState =
        if (state.isTunnelUp) state.copy(status = ConnectionStatus.SWITCHING, verifiedAt = null, pingMs = null, pingCheckedAt = null)
        else state
}
