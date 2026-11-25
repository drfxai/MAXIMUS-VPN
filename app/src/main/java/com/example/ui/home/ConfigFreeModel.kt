package com.example.ui.home

import com.example.R
import com.example.data.model.ConnectionState
import com.example.data.model.ConnectionStatus
import com.example.vpn.connections.BuiltinConnections.ConfigFreeMethod

/** What the "Connect without a config" button says (HomeScreen.ConfigFreeConnectButton). */
internal enum class ConfigFreeStatus(val detail: Int?) {
    READY(null),
    CHECKING(R.string.config_free_checking),
    /** The tunnel is up but no request went through it yet; the connect deadline is still running. */
    NO_TRAFFIC(R.string.config_free_no_traffic),
    RECONNECTING(R.string.config_free_reconnecting),
    VERIFIED(R.string.config_free_verified),
    LIMITED(R.string.config_free_limited),
    INTERRUPTED(R.string.config_free_interrupted),
    STOPPING(R.string.config_free_stopping)
}

/**
 * The button's state from the connection state. Pure, so the mapping is unit-tested.
 *
 * 2026-11-25 engine test: manual picks from Advanced options stayed on "Finding a path" / "Checking connection and
 * device traffic" while traffic flowed. The main cause was the state of every Connect pressed a few seconds after a
 * Disconnect being dropped (fixed in #285); here, a tunnel that is up without traffic now says so ([NO_TRAFFIC]) instead
 * of "checking", and device traffic seen on the TUN → engine hop ([ConnectionState.deviceTrafficSeen]) counts as
 * verified device traffic for engine programs, whose own TUN check cannot run (the app is outside its own tunnel).
 */
internal object ConfigFreeStatusModel {
    fun deviceVerified(state: ConnectionState): Boolean {
        val evidence = state.trafficEvidence?.takeIf { it.appliesTo(state) }
        return evidence != null && evidence.path == com.example.vpn.verify.ProbePath.TUN && evidence.bidirectional ||
            state.deviceTrafficSeen
    }

    fun of(requested: Boolean, state: ConnectionState): ConfigFreeStatus = when {
        !requested -> ConfigFreeStatus.READY
        state.status in setOf(ConnectionStatus.FAILED, ConnectionStatus.DEGRADED) -> ConfigFreeStatus.INTERRUPTED
        state.status in setOf(ConnectionStatus.RECONNECTING, ConnectionStatus.SWITCHING,
            ConnectionStatus.NETWORK_CHANGED) -> ConfigFreeStatus.RECONNECTING
        state.status == ConnectionStatus.DISCONNECTING -> ConfigFreeStatus.STOPPING
        state.status == ConnectionStatus.CONNECTED && deviceVerified(state) -> ConfigFreeStatus.VERIFIED
        state.status == ConnectionStatus.CONNECTED -> ConfigFreeStatus.LIMITED
        state.status == ConnectionStatus.TUNNEL_STARTED_CONNECTIVITY_UNVERIFIED -> ConfigFreeStatus.NO_TRAFFIC
        state.isTunnelUp || state.isBusy -> ConfigFreeStatus.CHECKING
        else -> ConfigFreeStatus.READY
    }
}

/**
 * The counts on the picker's two tabs: the connections each tab lists. 2026-11-25 engine test: the "Aether & chains"
 * tab read "1" while it listed 8 connections. The count was 10 (8 rows plus the two setup cards) and the label took
 * the whole tab, so the second digit was cut off. The tab now counts only the connections it lists (setup cards are not
 * connections) and the label gives way to the count.
 */
internal object ConfigFreePickerCounts {
    fun methods(): Int = ConfigFreeMethod.entries.count { it.aetherTransport == null }
    fun chains(): Int = ConfigFreeMethod.entries.count { it.aetherTransport != null }
}
