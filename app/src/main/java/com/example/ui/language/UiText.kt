package com.example.ui.language

import com.example.R
import com.example.data.model.ConnectionState
import com.example.data.model.ConnectionStatus
import com.example.data.model.OperationalMode

/**
 * String resources for labels that the model layer keeps in English for logs and tests (build 177, B19: the Russian
 * Home showed "Daily Mode", "High Speed • Your routing choices…", and Analytics "DISCONNECTED"). Pure: each function
 * maps a value to its resource id, and the UI reads it with stringResource.
 */
object UiText {
    fun modeTitle(mode: OperationalMode): Int = when (mode) {
        OperationalMode.DAILY -> R.string.mode_daily_title
        OperationalMode.UNLOCK -> R.string.mode_unlock_title
        OperationalMode.GOD_MODE -> R.string.mode_god_title
        OperationalMode.TRADE -> R.string.mode_trade_title
        OperationalMode.GHOST_RIDER -> R.string.mode_ghost_title
    }

    fun modeSubtitle(mode: OperationalMode): Int = when (mode) {
        OperationalMode.DAILY -> R.string.mode_daily_subtitle
        OperationalMode.UNLOCK -> R.string.unlock_mode_subtitle
        OperationalMode.GOD_MODE -> R.string.mode_god_subtitle
        OperationalMode.TRADE -> R.string.mode_trade_subtitle
        OperationalMode.GHOST_RIDER -> R.string.mode_ghost_subtitle
    }

    /** [ConnectionState.statusLabel], localized. */
    fun status(state: ConnectionState): Int = when (state.status) {
        ConnectionStatus.CONNECTED -> if (state.verification == com.example.vpn.verify.RouteVerification.ENGINE_PROXY_VERIFIED)
            R.string.status_engine_proxy_verified else R.string.status_vpn_connected
        ConnectionStatus.DEGRADED -> R.string.status_degraded
        ConnectionStatus.TUNNEL_STARTED_CONNECTIVITY_UNVERIFIED -> R.string.status_no_traffic_yet
        ConnectionStatus.VERIFYING -> R.string.status_verifying
        ConnectionStatus.ENGINE_STARTED, ConnectionStatus.PROXY_CONNECTING -> R.string.status_starting_proxy
        ConnectionStatus.VPN_INTERFACE_ESTABLISHED -> R.string.status_tunnel_created
        ConnectionStatus.PREPARING, ConnectionStatus.CONNECTING -> R.string.status_connecting
        ConnectionStatus.RECONNECTING -> R.string.status_reconnecting
        ConnectionStatus.SWITCHING -> R.string.status_switching
        ConnectionStatus.NETWORK_CHANGED -> R.string.status_network_changed
        ConnectionStatus.DISCONNECTING -> R.string.status_disconnecting
        ConnectionStatus.FAILED -> R.string.status_failed
        ConnectionStatus.DISCONNECTED -> R.string.status_disconnected
    }
}
