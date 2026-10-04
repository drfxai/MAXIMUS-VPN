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

    /**
     * Latency of a real HTTPS request through the tunnel. It uses a non-Cloudflare URL: a Cloudflare
     * Worker (BPB) reaches Cloudflare-hosted sites only through its proxy IP, so a Cloudflare URL can
     * fail while the rest of the internet works. Explicit VPN binding prevents a late probe from
     * falling back to the ISP after disconnect.
     */
    suspend fun latency(context: Context): Long = withContext(Dispatchers.IO) {
        val vpn = vpnNetwork(context)
        val started = System.nanoTime()
        val connection = vpn.openConnection(URL(com.example.xray.RealDelayProbe.PROBE_URL)) as HttpURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            check(connection.responseCode in 200..399) { "Probe returned HTTP ${connection.responseCode}" }
            ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(1)
        } finally { connection.disconnect() }
    }

    /** Latency through the tunnel, plus the exit country when Cloudflare's trace page is reachable. */
    suspend fun measure(context: Context): Sample {
        val latency = latency(context)
        val country = try {
            country(context)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return Sample(latency, country)
    }

    private fun vpnNetwork(context: Context): android.net.Network {
        val manager = requireNotNull(context.getSystemService(ConnectivityManager::class.java))
        return manager.allNetworks.firstOrNull {
            manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        } ?: error("VPN network unavailable")
    }

    private suspend fun country(context: Context): String? = withContext(Dispatchers.IO) {
        val vpn = vpnNetwork(context)
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
            trace.lineSequence().firstOrNull { it.startsWith("loc=") }?.substringAfter('=')
                ?.takeIf { it.matches(Regex("[A-Z]{2}")) }
        } finally { connection.disconnect() }
    }
}
