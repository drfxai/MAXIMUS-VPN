package com.example.vpn.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object LiveTunnelProbe {
    data class Sample(val latencyMs: Long, val country: String?)
    /** Explicit VPN binding prevents a late probe from falling back to the ISP after disconnect. */
    suspend fun measure(context: Context): Sample = withContext(Dispatchers.IO) {
        val manager = requireNotNull(context.getSystemService(ConnectivityManager::class.java))
        val vpn = manager.allNetworks.firstOrNull {
            manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        } ?: error("VPN network unavailable")
        val started = System.nanoTime()
        val connection = vpn.openConnection(URL("https://www.cloudflare.com/cdn-cgi/trace")) as HttpURLConnection
        try {
            connection.connectTimeout = 4000
            connection.readTimeout = 4000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Cache-Control", "no-cache")
            check(connection.responseCode == 200)
            // Read only the small prefix needed for loc=; readText() would buffer an
            // unbounded response before take(8192) could limit it.
            val trace = connection.inputStream.bufferedReader().use { reader ->
                val chars = CharArray(8192)
                val count = reader.read(chars)
                if (count > 0) String(chars, 0, count) else ""
            }
            val country = trace.lineSequence().firstOrNull { it.startsWith("loc=") }?.substringAfter('=')
                ?.takeIf { it.matches(Regex("[A-Z]{2}")) }
            Sample(((System.nanoTime() - started) / 1_000_000).coerceAtLeast(1), country)
        } finally { connection.disconnect() }
    }
}
