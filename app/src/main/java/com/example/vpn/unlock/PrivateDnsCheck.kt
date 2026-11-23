package com.example.vpn.unlock

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings

/**
 * Android's Private DNS in strict mode ("Private DNS provider hostname") sends every lookup over DNS-over-TLS
 * to that provider, on the VPN network too, so the Unlock DNS server is never asked. Automatic mode falls
 * back to the VPN's DNS server and is fine.
 */
object PrivateDnsCheck {
    /** Settings.Global.PRIVATE_DNS_MODE and its strict value; hidden constants, stable since Android 9. */
    private const val MODE_KEY = "private_dns_mode"
    private const val MODE_STRICT = "hostname"

    fun strict(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val mode = runCatching { Settings.Global.getString(context.contentResolver, MODE_KEY) }.getOrNull()
        if (mode == MODE_STRICT) return true
        return strictOnActiveNetwork(context)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private fun strictOnActiveNetwork(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return runCatching {
            val network = cm.activeNetwork ?: return@runCatching false
            // Strict mode names its provider on every network, the VPN's included.
            cm.getLinkProperties(network)?.privateDnsServerName != null
        }.getOrDefault(false)
    }
}
