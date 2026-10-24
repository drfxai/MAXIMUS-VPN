package com.example.vpn.sidecar

import com.example.vpn.EndpointResolver
import com.example.xray.XrayLogManager
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The pinned lyrebird client accepts `addr` independently of URL, HTTP Host and TLS identity. */
object WebTunnelEndpoints {
    fun resolve(lines: List<String>, privateDns: Boolean): List<String> = resolveWith(lines) { host ->
        EndpointResolver.resolve(host,
            system = { InetAddress.getAllByName(it).toList() },
            open = { it.openConnection() as HttpURLConnection }, private = privateDns).address
    }

    internal fun resolveWith(lines: List<String>, timeoutMs: Long = 12_000, lookup: (String) -> String): List<String> {
        require(timeoutMs > 0)
        if (lines.none { it.startsWith("webtunnel ") }) return lines
        val pool = Executors.newFixedThreadPool(minOf(lines.size, 4))
        try {
            val tasks = lines.map { line -> Callable {
                try { pin(line, lookup) }
                catch (e: InterruptedException) { throw e }
                catch (_: Exception) {
                    // Do not pass a poisoned name back to lyrebird for endless block-page retries.
                    XrayLogManager.w("TOR", "Skipped a WebTunnel bridge: no public endpoint could be resolved.")
                    null
                }
            } }
            // One shared deadline: a stuck DNS lookup must not block every healthy bridge.
            val results = pool.invokeAll(tasks, timeoutMs, TimeUnit.MILLISECONDS)
            val usable = results.mapNotNull { if (it.isCancelled) null else it.get() }
            check(usable.isNotEmpty()) { "No WebTunnel bridge has a usable public DNS answer; try another bridge or transport" }
            return usable
        } finally { pool.shutdownNow() }
    }

    internal fun pin(line: String, lookup: (String) -> String): String {
        val parts = line.split(' ')
        if (parts.first() != "webtunnel" || parts.any { it.startsWith("addr=") }) return line
        val url = URI(parts.firstOrNull { it.startsWith("url=") }?.removePrefix("url=")
            ?: error("WebTunnel URL is missing"))
        require(url.scheme == "https" && url.host != null && url.userInfo == null) { "WebTunnel requires an HTTPS URL" }
        val ip = lookup(url.host)
        // Reject hostnames before InetAddress can perform another system lookup.
        require(ip.matches(Regex("[0-9.]+")) || (':' in ip && ip.matches(Regex("[0-9a-fA-F:]+"))))
        require(!EndpointResolver.isBlockedAnswer(InetAddress.getByName(ip))) { "Blocked WebTunnel DNS answer" }
        val host = if (':' in ip) "[$ip]" else ip
        val port = if (url.port == -1) 443 else url.port
        require(port in 1..65535)
        return "$line addr=$host:$port"
    }
}
