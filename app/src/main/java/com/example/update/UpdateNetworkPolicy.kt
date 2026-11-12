package com.example.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Explicit fail-closed download policy. When enabled, downloads stop rather than falling
 * back to the unprotected physical network after VPN disconnects.
 */
object UpdateNetworkPolicy {
    private const val PREFS = "maximus_update_network"
    private const val KEY_VPN_ONLY = "vpn_only"

    fun vpnOnly(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_VPN_ONLY, false)

    fun setVpnOnly(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_VPN_ONLY, value).apply()
    }

    fun requireAllowed(context: Context) {
        if (!vpnOnly(context)) return
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: error("Cannot determine network routing. VPN-only update stopped.")
        val network = manager.activeNetwork ?: error("VPN-only update stopped: no active network.")
        val capabilities = manager.getNetworkCapabilities(network)
        check(capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
            "VPN-only update stopped: active VPN not detected; no direct fallback."
        }
    }
}
