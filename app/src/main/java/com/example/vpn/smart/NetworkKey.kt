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
    private val CARRIERS = mapOf(
        "43211" to "Hamrah-e Aval (MCI)",
        "43235" to "Irancell",
        "43220" to "Rightel",
        "43232" to "Taliya",
        "43270" to "TCI mobile"
    )

    @Suppress("DEPRECATION")
    fun current(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "other"
        val caps = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }.filter {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        return when {
            caps.any { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } -> "wifi"
            caps.any { it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } -> "ethernet"
            caps.any { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } -> {
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
        key.startsWith("cell:") -> CARRIERS[key.removePrefix("cell:")]?.let { "$it ($key)" } ?: key
        else -> key
    }
}
