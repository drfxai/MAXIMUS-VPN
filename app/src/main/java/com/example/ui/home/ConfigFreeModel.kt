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
    /** The attempt ended FAILED (build 177 said "Connection interrupted" for a pick that never connected). */
    FAILED(R.string.config_free_failed),
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
        state.status == ConnectionStatus.FAILED -> ConfigFreeStatus.FAILED
        state.status == ConnectionStatus.DEGRADED -> ConfigFreeStatus.INTERRUPTED
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

/**
 * Home's headline after a failed Connect: "Connection failed" and the specific reason, shortened to its first clause,
 * instead of "Not connected / Tap the button to connect" with the reason far below the fold (build 177, B7). Pure.
 */
internal object HomeFailure {
    const val MAX_REASON = 160

    /**
     * The reason to show under the headline, or null when the state is not a failure with a message. When the message
     * ends with one of [blockNotices] (traffic blocked by the Kill Switch or the mode, or the block released), that
     * sentence follows on its own line, so Home always says whether apps have internet (build 177, B2 and B20).
     */
    fun reason(state: ConnectionState, blockNotices: List<String> = emptyList()): String? {
        if (state.status != ConnectionStatus.FAILED) return null
        val message = state.errorMessage ?: return null
        val notice = blockNotices.filter { it.isNotBlank() && message.contains(it) }.maxByOrNull { it.length }
        val rest = if (notice == null) message else message.replace(notice, "").trim()
        val first = short(rest)
        return when {
            notice == null -> first
            first == null -> notice
            else -> first + "\n" + notice
        }
    }

    /**
     * Build 179 device test (N1): the tunnel is up and carries nothing (the VPN holds every app's traffic). Home shows a
     * busy "No traffic yet" with the Stop hint, never "Not connected".
     */
    fun holdsTraffic(state: ConnectionState): Boolean = state.status == ConnectionStatus.TUNNEL_STARTED_CONNECTIVITY_UNVERIFIED

    /** Build 179 device test (N10): Unlock mode's failure, shown in the header instead of only below the fold. */
    fun unlockReason(state: ConnectionState): String? =
        if (state.status != ConnectionStatus.FAILED) null else short(state.errorMessage)

    /** The message up to its first sentence end (or [MAX_REASON] characters). */
    fun short(message: String?): String? {
        val text = message?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val end = Regex("[.!?。](\\s|$)").find(text)?.range?.first?.plus(1) ?: text.length
        val first = text.substring(0, end).trim()
        return if (first.length <= MAX_REASON) first else first.take(MAX_REASON - 1).trimEnd() + "…"
    }
}

/**
 * The picker's line under each built-in connection, as a string resource (build 177, B19: the Russian picker showed the
 * catalogue's English descriptions). [ConfigFreeMethod.description] stays English for logs. Pure.
 */
internal object ConfigFreeMethodText {
    fun description(method: ConfigFreeMethod): Int = when (method) {
        ConfigFreeMethod.AETHER_WARP -> R.string.picker_desc_aether_warp
        ConfigFreeMethod.TOR_SNOWFLAKE -> R.string.picker_desc_tor
        ConfigFreeMethod.SERVERLESS -> R.string.picker_desc_serverless
        ConfigFreeMethod.PSIPHON -> R.string.picker_desc_psiphon
        ConfigFreeMethod.SHIROKHORSHID -> R.string.picker_desc_shirokhorshid
        ConfigFreeMethod.PSIPHON_RADIANCE -> R.string.picker_desc_psiphon_radiance
        ConfigFreeMethod.RADIANCE -> R.string.picker_desc_radiance
        ConfigFreeMethod.AETHER_H2 -> R.string.picker_desc_aether_h2
        ConfigFreeMethod.AETHER_H3 -> R.string.picker_desc_aether_h3
        ConfigFreeMethod.AETHER_WG -> R.string.picker_desc_aether_wg
        ConfigFreeMethod.AETHER_GOOL -> R.string.picker_desc_aether_gool
        ConfigFreeMethod.AETHER_GOOL_CLASSIC -> R.string.picker_desc_aether_wiw
        ConfigFreeMethod.AETHER_MIM -> R.string.picker_desc_aether_mim
        ConfigFreeMethod.AETHER_PSIPHON -> R.string.picker_desc_aether_psiphon
        ConfigFreeMethod.AETHER_TOR -> R.string.picker_desc_aether_tor
        ConfigFreeMethod.SHIROKHORSHID_RADIANCE -> R.string.picker_desc_shirokhorshid_radiance
        ConfigFreeMethod.AETHER_LANTERN -> R.string.picker_desc_aether_lantern
    }
}
