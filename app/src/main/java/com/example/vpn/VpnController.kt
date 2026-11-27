package com.example.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.example.data.model.ConnectionState
import com.example.data.model.ConnectionStatus
import com.example.data.model.VlessProfile
import kotlinx.coroutines.flow.StateFlow

object VpnController {

    val connectionState: StateFlow<ConnectionState> = RayVpnService.vpnState

    /**
     * Checks if Android VPN permission has been granted by the user.
     * Returns an Intent if permission needs to be requested via ActivityResultLauncher, or null if already granted.
     */
    fun prepareVpn(context: Context): Intent? {
        return VpnService.prepare(context)
    }

    /**
     * Helper to verify if VPN permission is already granted.
     */
    fun isPermissionGranted(context: Context): Boolean {
        return VpnService.prepare(context) == null
    }

    /**
     * Initiates VPN connection with a specific VLESS profile.
     */
    fun startVpn(context: Context, profile: VlessProfile, smart: Boolean = false, force: Boolean = false, exact: Boolean = false,
                 powerButton: Boolean = false) {
        // A second tap right after this one is read as part of it, not as a Stop (StopGuard).
        StopGuard.markConnectTap()
        context.stopService(Intent(context, com.example.vpn.share.LocalProxyService::class.java))
        val intent = Intent(context, RayVpnService::class.java).apply {
            action = RayVpnService.ACTION_CONNECT
            putExtra(RayVpnService.EXTRA_PROFILE_ID, profile.id)
            // Smart Connect: race the saved servers with real requests, [profile] first.
            putExtra(RayVpnService.EXTRA_SMART, smart)
            // "Connect anyway": start [profile] even though its real pre-flight failed.
            putExtra(RayVpnService.EXTRA_FORCE, force)
            // The power button: only [profile], as saved (see RayVpnService.EXTRA_EXACT).
            putExtra(RayVpnService.EXTRA_EXACT, exact)
            // Who started it: the power button, or a pick in a list (build 179, N2).
            putExtra(RayVpnService.EXTRA_POWER_BUTTON, powerButton)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    /**
     * Unlock mode: starts the DNS-only session. No profile is involved; the service reads the mode and the
     * Unlock DNS choices itself (see RayVpnService.connectUnlockLocked).
     */
    fun startUnlock(context: Context) {
        StopGuard.markConnectTap()
        context.stopService(Intent(context, com.example.vpn.share.LocalProxyService::class.java))
        val intent = Intent(context, RayVpnService::class.java).apply { action = RayVpnService.ACTION_CONNECT }
        ContextCompat.startForegroundService(context, intent)
    }

    /**
     * Stops the running VPN connection and tears down the tunnel cleanly. [origin] says who asked (the service records
     * it with the session, build 178 field report). Returns false when the tap was read as the tail of the Connect tap
     * that just started the session ([StopGuard]) and nothing was stopped.
     */
    fun stopVpn(context: Context, origin: StopOrigin = StopOrigin.UNKNOWN,
                /** The state the user stopped from, before the UI showed "Stopping" (build 179, N8). */
                fromStatus: ConnectionStatus = connectionState.value.status): Boolean {
        val since = StopGuard.sinceConnectTap()
        // A failed session holds nothing worth protecting from a quick second tap: its Stop always goes through.
        if (connectionState.value.status != com.example.data.model.ConnectionStatus.FAILED && StopGuard.swallow(origin)) {
            runCatching {
                com.example.xray.XrayLogManager.i("VPN", "Stop from ${origin.label} ${since} ms after the Connect tap: read as " +
                    "the same tap (double tap), the connection keeps going.")
                com.example.vpn.diagnostics.events.EventLog.event("session.stop_ignored", attributes = mapOf(
                    "origin" to origin.key, "since.connect.ms" to (since ?: -1L).toString(),
                    "status" to connectionState.value.status.name, "why" to "double tap within ${StopGuard.DOUBLE_TAP_MS} ms"))
            }
            return false
        }
        val intent = Intent(context, RayVpnService::class.java).apply {
            action = RayVpnService.ACTION_DISCONNECT
            putExtra(RayVpnService.EXTRA_STOP_ORIGIN, origin.key)
            putExtra(RayVpnService.EXTRA_STOP_FROM, fromStatus.name)
        }
        try {
            context.startService(intent)
        } catch (_: Exception) {
            ContextCompat.startForegroundService(context, intent)
        }
        return true
    }

    /**
     * The connection carries small requests but large downloads stall: move to another server the way the
     * stall failover does (never in a pinned session).
     */
    fun tryAnotherServer(context: Context) {
        val intent = Intent(context, RayVpnService::class.java).apply {
            action = RayVpnService.ACTION_TRY_ANOTHER_SERVER
        }
        // Only offered while connected, so the service is already in the foreground; never start it from here.
        runCatching { context.startService(intent) }
    }

    /**
     * Reconnects the active VPN tunnel.
     */
    fun reconnectVpn(context: Context) {
        val intent = Intent(context, RayVpnService::class.java).apply {
            action = RayVpnService.ACTION_RECONNECT
        }
        ContextCompat.startForegroundService(context, intent)
    }
}
