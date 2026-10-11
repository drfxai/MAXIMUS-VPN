package com.example.vpn.safety

import com.example.data.model.AppSettings
import com.example.data.model.OperationalMode
import com.example.data.model.RoutingMode

/**
 * What each mode changes at runtime.
 *
 * DAILY is for speed on an ordinary day: the user's routing choices (LAN and custom bypass) apply,
 * Smart Connect runs when asked, and a profile that can never run may release the traffic block.
 *
 * GOD MODE is for survival on a hostile network, and fails closed: everything goes through the proxy
 * (no LAN, carrier or custom bypass, and direct rules in imported Xray configs are dropped), IPv6 is
 * blocked rather than proxied, server names are looked up only over DNS-over-HTTPS, every connect races the saved servers with real requests, failover and
 * reconnect are on, and nothing releases the block except Disconnect.
 *
 * TRADE is for a constant identity: the one chosen config is pinned and the exit never moves, because
 * trading and similar uses need a fixed IP. Smart racing, auto-failover, stealth switching and LAB
 * recovery are all off ([lockExit]); the app keeps the exact same server, IP, exit location and config
 * until the user changes it by hand. (An Android app VPN cannot read or change the device MAC, so that
 * is unaffected either way.)
 */
data class OperatingModePolicy(
    val mode: OperationalMode,
    /** The user's routing mode (LAN bypass, custom list) applies; otherwise everything is proxied. */
    val userRouting: Boolean,
    /** Direct rules in an imported Xray config are kept. */
    val importedDirectRules: Boolean,
    /** Every connect tests the saved servers with real requests first. */
    val alwaysSmartConnect: Boolean,
    /** IPv6 follows the user's setting; otherwise it is blocked. */
    val userIpv6: Boolean,
    /** Server names are looked up only over DNS-over-HTTPS, never with the network's own DNS. */
    val privateServerLookup: Boolean,
    /**
     * The exit is pinned to the chosen config and never changes on its own: no smart racing, no
     * failover, no stealth alternates, no LAB recovery. The connect path uses only the selected server.
     */
    val lockExit: Boolean = false
) {
    /** [settings] as this mode runs them. */
    fun apply(settings: AppSettings): AppSettings = when (mode) {
        OperationalMode.DAILY -> settings
        OperationalMode.TRADE -> settings.copy(
            // A fixed exit means nothing may switch the node out from under the user.
            autoFailoverEnabled = false,
            // Reconnecting to the same server keeps the same IP, so it stays allowed.
            autoReconnect = settings.autoReconnect
        )
        OperationalMode.GOD_MODE -> settings.copy(
            routingMode = if (userRouting) settings.routingMode else RoutingMode.GLOBAL,
            ipv6Enabled = userIpv6 && settings.ipv6Enabled,
            autoFailoverEnabled = true,
            autoReconnect = true
        )
    }

    companion object {
        val DAILY = OperatingModePolicy(OperationalMode.DAILY, userRouting = true, importedDirectRules = true,
            alwaysSmartConnect = false, userIpv6 = true, privateServerLookup = false)
        val GOD_MODE = OperatingModePolicy(OperationalMode.GOD_MODE, userRouting = false, importedDirectRules = false,
            alwaysSmartConnect = true, userIpv6 = false, privateServerLookup = true)
        val TRADE = OperatingModePolicy(OperationalMode.TRADE, userRouting = true, importedDirectRules = true,
            alwaysSmartConnect = false, userIpv6 = true, privateServerLookup = false, lockExit = true)

        fun of(mode: OperationalMode): OperatingModePolicy = when (mode) {
            OperationalMode.GOD_MODE -> GOD_MODE
            OperationalMode.TRADE -> TRADE
            OperationalMode.DAILY -> DAILY
        }
    }
}
