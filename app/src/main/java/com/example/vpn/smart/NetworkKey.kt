package com.example.vpn.smart

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager

/**
 * Names the network the phone uses outside the VPN, for [NetworkMemory]: the mobile carrier by its
 * MCC+MNC code (no permission needed), or Wi-Fi / Ethernet. Wi-Fi networks are not told apart, since
 * that needs the location permission.
 */
object NetworkKey {
    @Suppress("DEPRECATION")
    fun current(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "other"
        val physical = NetworkCapabilityDetector.physical(cm) ?: return "other"
        val caps = cm.getNetworkCapabilities(physical) ?: return "other"
        // Exactly the same physical link the capability detector just measured:
        // do not classify a validated cellular session as Wi-Fi merely because
        // a disconnected Wi-Fi interface remains listed in allNetworks.
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                val operator = runCatching {
                    (context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)?.networkOperator
                }.getOrNull().orEmpty()
                if (operator.isBlank()) "cell" else "cell:$operator"
            }
            else -> "other"
        }
    }

    /** A readable name for logs. */
    fun describe(key: String): String = when {
        key.startsWith("cell:") -> NetworkNames.carrier(key.removePrefix("cell:"))?.let { "$it ($key)" } ?: key
        else -> key
    }
}
