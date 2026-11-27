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

    /** Build 181 (3.5): the whole WebTunnel pre-check; every bridge is tried at once. */
    const val PRECHECK_MS = 8_000L

    /** Installed by the VPN service: the pre-check measures the phone's network, never this app's own tunnel. */
    @Volatile var socketProtector: ((java.net.Socket) -> Boolean)? = null

    /**
     * Build 181 (3.5): before Tor starts, each pinned WebTunnel bridge gets one verified TLS handshake (system CAs and
     * the URL's host name, as lyrebird checks it) to its `addr`. Bridges that answer go first, in answer order; the rest
     * follow, so a slow bridge is still tried. Nothing is sent after the handshake. Returns the reordered lines and how
     * many answered. Non-WebTunnel lines are kept as they are.
     */
    fun precheck(lines: List<String>, timeoutMs: Long = PRECHECK_MS,
                 handshake: (host: String, ip: String, port: Int, timeoutMs: Int) -> Boolean = ::tlsHandshake): Pair<List<String>, Int> {
        val web = lines.filter { it.startsWith("webtunnel ") && " addr=" in it }
        if (web.isEmpty()) return lines to 0
        val pool = Executors.newFixedThreadPool(minOf(web.size, 8))
        val answered = java.util.concurrent.ConcurrentLinkedQueue<String>()
        try {
            val tasks = web.map { line -> Callable {
                val target = target(line) ?: return@Callable false
                val ok = runCatching { handshake(target.first, target.second, target.third, timeoutMs.toInt()) }.getOrDefault(false)
                if (ok) answered += line
                ok
            } }
            pool.invokeAll(tasks, timeoutMs, TimeUnit.MILLISECONDS)
        } finally { pool.shutdownNow() }
        val first = answered.toList()
        return (first + lines.filter { it !in first }) to first.size
    }

    /** (URL host, addr IP, addr port) of a pinned WebTunnel line, or null. Pure. */
    internal fun target(line: String): Triple<String, String, Int>? = runCatching {
        val parts = line.split(' ')
        val host = URI(parts.first { it.startsWith("url=") }.removePrefix("url=")).host ?: return null
        val addr = parts.first { it.startsWith("addr=") }.removePrefix("addr=")
        val ip = if (addr.startsWith("[")) addr.substringAfter('[').substringBefore(']') else addr.substringBeforeLast(':')
        val port = addr.substringAfterLast(':').toInt().takeIf { it in 1..65535 } ?: return null
        Triple(host, ip, port)
    }.getOrNull()

    private fun tlsHandshake(host: String, ip: String, port: Int, timeoutMs: Int): Boolean {
        val raw = java.net.Socket()
        return try {
            socketProtector?.let { protect -> if (!com.example.vpn.protectTcpSocket(raw, protect)) return false }
            raw.connect(java.net.InetSocketAddress(InetAddress.getByName(ip), port), timeoutMs)
            val ssl = (javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory)
                .createSocket(raw, host, port, true) as javax.net.ssl.SSLSocket
            ssl.soTimeout = timeoutMs
            ssl.sslParameters = ssl.sslParameters.apply {
                serverNames = listOf(javax.net.ssl.SNIHostName(host))
                endpointIdentificationAlgorithm = "HTTPS"
            }
            ssl.use { it.startHandshake() }
            true
        } catch (_: Exception) {
            false
        } finally {
            runCatching { raw.close() }
        }
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
