package com.example.vpn.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object LiveTunnelProbe {
    /** [ip] is the exit address websites see, from the same trace page as [country]. */
    data class Sample(val latencyMs: Long, val country: String?, val ip: String? = null)

    /**
     * Latency of a real HTTPS request through the tunnel. It uses a non-Cloudflare URL: a Cloudflare
     * Worker (BPB) reaches Cloudflare-hosted sites only through its proxy IP, so a Cloudflare URL can
     * fail while the rest of the internet works. Explicit VPN binding prevents a late probe from
     * falling back to the ISP after disconnect.
     */
    suspend fun latency(context: Context, stepTimeoutMs: Int = 4_000): Long = withContext(Dispatchers.IO) {
        com.example.vpn.verify.TrafficCheck(
            com.example.vpn.verify.RouteDialers.vpn(context), stepTimeoutMs = stepTimeoutMs
        ).smallRequest()
    }

    /** Latency through the tunnel, plus the exit country and address when Cloudflare's trace page is reachable. */
    suspend fun measure(context: Context, stepTimeoutMs: Int = 4_000): Sample {
        val latency = latency(context, stepTimeoutMs)
        val exit = try {
            trace(context)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return Sample(latency, exit?.country, exit?.ip)
    }

    data class Exit(val country: String?, val ip: String?)

    /** The `loc=` and `ip=` lines of a trace page; anything that is not a country code or an IP address is dropped. */
    internal fun parseTrace(trace: String): Exit {
        fun field(key: String) = trace.lineSequence().firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.trim()
        val country = field("loc")?.takeIf { it.matches(Regex("[A-Z]{2}")) }
        val ip = field("ip")?.takeIf { it.length <= 45 && (it.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")) || it.matches(Regex("[0-9a-fA-F:]{2,45}")) && ':' in it) }
        return Exit(country, ip)
    }

    internal fun vpnNetwork(context: Context): android.net.Network {
        val manager = requireNotNull(context.getSystemService(ConnectivityManager::class.java))
        return manager.allNetworks.firstOrNull {
            manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        } ?: error("VPN network unavailable")
    }

    private suspend fun trace(context: Context): Exit = withContext(Dispatchers.IO) {
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
            parseTrace(trace)
        } finally { connection.disconnect() }
    }
}
