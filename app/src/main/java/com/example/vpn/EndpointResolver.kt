package com.example.vpn

import com.example.vpn.connectivity.DnsOutcome
import com.example.vpn.connectivity.DnsResilienceEngine
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder

/**
 * Resolves a proxy server's host name before the tunnel starts.
 *
 * Filtering networks answer DNS for blocked names (for example *.workers.dev, which BPB uses) with a
 * private address of their block page, such as 10.10.34.36. The proxy then dials that address and
 * every request times out, while the VPN shows as connected. Such an answer is detected and the name
 * is looked up again over DNS-over-HTTPS at a resolver's IP address, which needs no DNS itself.
 */
object EndpointResolver {
    /**
     * DNS-over-HTTPS endpoints addressed by IP (so they need no DNS themselves), from four operators so
     * one blocked provider does not stop the fallback. JSON API where the operator offers one, RFC 8484
     * wire format otherwise.
     */
    val DOH_ENDPOINTS = listOf(
        "https://1.1.1.1/dns-query?name=%s&type=A",
        "https://8.8.8.8/resolve?name=%s&type=A",
        "https://1.0.0.1/dns-query?name=%s&type=A",
        "https://8.8.4.4/resolve?name=%s&type=A",
        "wire:https://9.9.9.9/dns-query",
        "wire:https://94.140.14.14/dns-query"
    )
    private const val TIMEOUT_MS = 5000

    /** How long the network's own DNS gets before the DoH resolvers are asked as well. */
    internal const val SYSTEM_GRACE_MS = 1500L

    data class Result(val address: String, val viaDoh: Boolean)

    /**
     * [system] is the network's own resolver; [open] opens a connection outside the VPN. Returns the
     * system answer when it is a public address. When it is a block-page address, fails, or does not
     * arrive within [SYSTEM_GRACE_MS] (DNS dropped), every DoH resolver is asked in parallel and the
     * first public answer wins; a late public system answer is still accepted.
     *
     * [private] (GOD MODE): the network's DNS is never asked, so the server's name is not sent to the
     * ISP in plaintext; only the DoH resolvers answer.
     *
     * [doh] is the resolver order for this network and [skipSystem] skips the grace wait for a name this
     * network's DNS is known to block (DnsResilienceProfile). Every query's [DnsOutcome] goes to [onOutcome]
     * (resolver "system" for the network's own DNS), so the next lookup on this network is better informed.
     */
    fun resolve(
        host: String,
        system: (String) -> List<InetAddress>,
        open: (URL) -> HttpURLConnection,
        private: Boolean = false,
        doh: List<String> = DOH_ENDPOINTS,
        skipSystem: Boolean = false,
        onOutcome: (resolver: String, outcome: DnsOutcome, ms: Long?) -> Unit = { _, _, _ -> }
    ): Result {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(9) { r -> Thread(r, "endpoint-resolver").apply { isDaemon = true } }
        val active = mutableSetOf<HttpURLConnection>()
        var closed = false
        val trackedOpen: (URL) -> HttpURLConnection = { url ->
            open(url).also { connection ->
                synchronized(active) {
                    if (closed) {
                        connection.disconnect()
                        throw java.io.InterruptedIOException("DNS lookup completed")
                    }
                    active += connection
                }
            }
        }
        try {
            val done = java.util.concurrent.ExecutorCompletionService<Pair<String?, Boolean>>(pool)
            var systemError: Exception? = null
            var systemPick: InetAddress? = null
            val systemFuture = if (private) null else done.submit {
                val started = System.nanoTime()
                val answers = try { system(host) } catch (e: Exception) { systemError = e; emptyList() }
                val usable = answers.filterNot { isBlockedAnswer(it) }
                systemPick = usable.firstOrNull { it is java.net.Inet4Address } ?: usable.firstOrNull() ?: answers.firstOrNull()
                val ms = (System.nanoTime() - started) / 1_000_000
                report(onOutcome, DnsResilienceEngine.SYSTEM, when {
                    systemError is java.net.SocketTimeoutException -> DnsOutcome.TIMEOUT
                    systemError != null && systemError !is java.net.UnknownHostException -> DnsOutcome.ERROR
                    systemPick == null -> DnsOutcome.NO_ANSWER
                    isBlockedAnswer(systemPick!!) -> DnsOutcome.BLOCKED_ANSWER
                    else -> DnsOutcome.ANSWER
                }, ms)
                systemPick?.takeIf { !isBlockedAnswer(it) }?.hostAddress to false
            }
            val first = if (private || skipSystem) null else done.poll(SYSTEM_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (first != null) {
                first.get().first?.let { return Result(it, viaDoh = false) }
            }

            var dohError: Exception? = null
            val resolvers = doh.ifEmpty { DOH_ENDPOINTS }.distinct().take(8)
            val pending = resolvers.size + if (first == null && !private) 1 else 0
            resolvers.forEach { endpoint ->
                done.submit {
                    val started = System.nanoTime()
                    try {
                        queryEndpoint(endpoint, host, trackedOpen).also {
                            report(onOutcome, endpoint, if (it != null) DnsOutcome.ANSWER else DnsOutcome.NO_ANSWER,
                                (System.nanoTime() - started) / 1_000_000)
                        } to true
                    } catch (e: Exception) {
                        dohError = e
                        report(onOutcome, endpoint, if (e is java.net.SocketTimeoutException) DnsOutcome.TIMEOUT else DnsOutcome.ERROR, null)
                        null to true
                    }
                }
            }
            val deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000L
            repeat(pending) {
                val left = deadline - System.nanoTime()
                val next = if (left > 0) done.poll(left, java.util.concurrent.TimeUnit.NANOSECONDS) else null
                val (ip, viaDoh) = next?.get() ?: return@repeat
                if (ip != null) return Result(ip, viaDoh)
            }
            // Never dial a known block page when every trustworthy lookup failed.
            if (systemFuture != null && !systemFuture.isDone) systemFuture.cancel(true)
            systemPick?.takeIf { !isBlockedAnswer(it) }?.let { return Result(it.hostAddress.orEmpty(), viaDoh = false) }
            throw IllegalStateException(
                "cannot resolve the server address '$host'",
                systemError ?: dohError
            )
        } finally {
            pool.shutdownNow()
            val connections = synchronized(active) { closed = true; active.toList() }
            connections.forEach { runCatching { it.disconnect() } }
        }
    }

    private fun report(onOutcome: (String, DnsOutcome, Long?) -> Unit, resolver: String, outcome: DnsOutcome, ms: Long?) {
        // Interrupted when the lookup finished first: not a measurement of the resolver.
        if (Thread.currentThread().isInterrupted) return
        runCatching { onOutcome(resolver, outcome, ms) }
    }

    private fun queryEndpoint(endpoint: String, host: String, open: (URL) -> HttpURLConnection): String? =
        if (endpoint.startsWith("wire:")) queryWire(endpoint.removePrefix("wire:"), host, open)
        else queryDoh(String.format(endpoint, URLEncoder.encode(host, "UTF-8")), open)

    /**
     * True for addresses a public server name cannot have: private, loopback, link-local, unspecified,
     * carrier-grade NAT (100.64.0.0/10), benchmarking (198.18.0.0/15) or reserved (240.0.0.0/4).
     */
    fun isBlockedAnswer(address: InetAddress): Boolean {
        val b = address.address.map { it.toInt() and 0xff }
        return address.isSiteLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isAnyLocalAddress || address.isMulticastAddress ||
            (b.size == 16 && (b[0] and 0xfe) == 0xfc) ||
            (b.size == 4 && ((b[0] == 100 && b[1] and 0xc0 == 64) || (b[0] == 198 && b[1] and 0xfe == 18) || b[0] >= 240))
    }

    internal fun queryDoh(url: String, open: (URL) -> HttpURLConnection): String? {
        val connection = open(URL(url))
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/dns-json")
            if (connection.responseCode != 200) return null
            val host = URL(url).query.orEmpty().split('&').firstOrNull { it.startsWith("name=") }
                ?.substringAfter('=')?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                ?: throw java.io.IOException("DNS JSON query has no name")
            return parseDohAnswer(connection.inputStream.use { readBounded(it).toString(Charsets.UTF_8) }, host)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > 65535) throw java.io.IOException("Oversized DNS response")
            out.write(buffer, 0, n)
        }
    }

    /** RFC 8484 GET with a wire-format A query. */
    internal fun queryWire(url: String, host: String, open: (URL) -> HttpURLConnection): String? {
        val query = wireQuery(host)
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(query)
        val connection = open(URL("$url?dns=$encoded"))
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/dns-message")
            if (connection.responseCode != 200) return null
            return parseWireAnswer(query, connection.inputStream.use { readBounded(it) })
        } finally {
            connection.disconnect()
        }
    }

    internal fun wireQuery(host: String, id: Int = java.security.SecureRandom().nextInt(65536)): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf((id shr 8).toByte(), id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        host.trimEnd('.').split('.').forEach { label ->
            val bytes = java.net.IDN.toASCII(label).toByteArray()
            require(bytes.size in 1..63) { "Invalid host name" }
            out.write(bytes.size)
            out.write(bytes)
        }
        require(out.size() - 12 <= 254) { "Invalid host name length" }
        out.write(byteArrayOf(0, 0, 1, 0, 1))
        return out.toByteArray()
    }

    /** First public IPv4 address among the answers of a wire-format response to [query]. */
    internal fun parseWireAnswer(query: ByteArray, response: ByteArray): String? {
        return com.example.vpn.dns.DnsMessageValidator.addressAnswers(query, response, response.size)
            .map { InetAddress.getByAddress(it) }
            .firstOrNull { !isBlockedAnswer(it) }?.hostAddress
    }

    /** First public IPv4 address in a DNS JSON response, following CNAMEs. */
    internal fun parseDohAnswer(body: String, host: String? = null): String? {
        val root = JSONObject(body)
        if (root.optInt("Status", -1) != 0 || root.optBoolean("TC", false)) return null
        val answers = root.optJSONArray("Answer") ?: return null
        val records = (0 until answers.length()).mapNotNull { answers.optJSONObject(it) }
        fun canonical(value: String): String = java.net.IDN.toASCII(value.trim().trimEnd('.')).lowercase(java.util.Locale.ROOT)
        // Real lookups must bind every address to the requested name or its CNAME chain.
        // The host-less form is retained only for parser-level validation callers.
        val owner = host?.let { requested ->
            var current = canonical(requested)
            val visited = mutableSetOf<String>()
            var terminal: String? = null
            for (hops in 0..16) {
                if (!visited.add(current)) return null
                val here = records.filter { canonical(it.optString("name")) == current }
                val aliases = here.filter { it.optInt("type") == 5 }.map { canonical(it.optString("data")) }.distinct()
                if (aliases.isEmpty()) { terminal = current; break }
                if (aliases.size != 1 || here.any { it.optInt("type") == 1 }) return null
                current = aliases.single()
            }
            terminal ?: return null
        }
        return records.filter { it.optInt("type") == 1 && (owner == null || canonical(it.optString("name")) == owner) }
            .map { it.optString("data").trim() }
            .firstOrNull { ip ->
                ip.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) && ip.split('.').all { it.toInt() in 0..255 } &&
                    !isBlockedAnswer(InetAddress.getByName(ip))
            }
    }
}
