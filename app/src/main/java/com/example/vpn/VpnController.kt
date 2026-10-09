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
    fun startVpn(context: Context, profile: VlessProfile, smart: Boolean = false, force: Boolean = false) {
        val intent = Intent(context, RayVpnService::class.java).apply {
            action = RayVpnService.ACTION_CONNECT
            putExtra(RayVpnService.EXTRA_PROFILE_ID, profile.id)
            // Smart Connect: race the saved servers with real requests, [profile] first.
            putExtra(RayVpnService.EXTRA_SMART, smart)
            // "Connect anyway": start [profile] even though its real pre-flight failed.
            putExtra(RayVpnService.EXTRA_FORCE, force)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    /**
     * Stops the running VPN connection and tears down the tunnel cleanly.
     */
    fun stopVpn(context: Context) {
        val intent = Intent(context, RayVpnService::class.java).apply {
            action = RayVpnService.ACTION_DISCONNECT
        }
        try {
            context.startService(intent)
        } catch (_: Exception) {
            ContextCompat.startForegroundService(context, intent)
        }
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
