package com.example.vpn

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
     */
    fun resolve(
        host: String,
        system: (String) -> List<InetAddress>,
        open: (URL) -> HttpURLConnection
    ): Result {
        val pool = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "endpoint-resolver").apply { isDaemon = true } }
        try {
            val done = java.util.concurrent.ExecutorCompletionService<Pair<String?, Boolean>>(pool)
            var systemError: Exception? = null
            var systemPick: InetAddress? = null
            val systemFuture = done.submit {
                val answers = try { system(host) } catch (e: Exception) { systemError = e; emptyList() }
                systemPick = answers.firstOrNull { it is java.net.Inet4Address } ?: answers.firstOrNull()
                systemPick?.takeIf { !isBlockedAnswer(it) }?.hostAddress to false
            }
            val first = done.poll(SYSTEM_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (first != null) {
                first.get().first?.let { return Result(it, viaDoh = false) }
            }

            var dohError: Exception? = null
            val pending = DOH_ENDPOINTS.size + if (first == null) 1 else 0
            DOH_ENDPOINTS.forEach { endpoint ->
                done.submit {
                    try {
                        queryEndpoint(endpoint, host, open) to true
                    } catch (e: Exception) {
                        dohError = e
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
            // A blocked answer is still better than nothing: the error then shows in the proxy's log.
            if (!systemFuture.isDone) systemFuture.cancel(true)
            systemPick?.let { return Result(it.hostAddress.orEmpty(), viaDoh = false) }
            throw IllegalStateException(
                "cannot resolve the server address '$host'",
                systemError ?: dohError
            )
        } finally {
            pool.shutdownNow()
        }
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
            connection.setRequestProperty("Accept", "application/dns-json")
            if (connection.responseCode != 200) return null
            return parseDohAnswer(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
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
            connection.setRequestProperty("Accept", "application/dns-message")
            if (connection.responseCode != 200) return null
            return parseWireAnswer(query, connection.inputStream.use { it.readBytes() })
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
        out.write(byteArrayOf(0, 0, 1, 0, 1))
        return out.toByteArray()
    }

    /** First public IPv4 address among the answers of a wire-format response to [query]. */
    internal fun parseWireAnswer(query: ByteArray, response: ByteArray): String? {
        if (response.size < query.size || response[0] != query[0] || response[1] != query[1]) return null
        if ((response[2].toInt() and 0x80) == 0 || (response[3].toInt() and 0x0f) != 0) return null
        fun u16(i: Int) = ((response[i].toInt() and 0xff) shl 8) or (response[i + 1].toInt() and 0xff)
        val answers = u16(6)
        var pos = query.size // the question is echoed unchanged
        repeat(answers) {
            // Name: a pointer (2 bytes) or labels ending in zero.
            while (pos < response.size) {
                val len = response[pos].toInt() and 0xff
                if (len and 0xc0 == 0xc0) { pos += 2; break }
                pos += 1 + len
                if (len == 0) break
            }
            if (pos + 10 > response.size) return null
            val type = u16(pos)
            val rdLength = u16(pos + 8)
            pos += 10
            if (pos + rdLength > response.size) return null
            if (type == 1 && rdLength == 4) {
                val address = InetAddress.getByAddress(response.copyOfRange(pos, pos + 4))
                if (!isBlockedAnswer(address)) return address.hostAddress
            }
            pos += rdLength
        }
        return null
    }

    /** First public IPv4 address in a DNS JSON response, following CNAMEs. */
    internal fun parseDohAnswer(body: String): String? {
        val answers = JSONObject(body).optJSONArray("Answer") ?: return null
        return (0 until answers.length()).mapNotNull { answers.optJSONObject(it) }
            .filter { it.optInt("type") == 1 }
            .map { it.optString("data").trim() }
            .firstOrNull { ip ->
                ip.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) && !isBlockedAnswer(InetAddress.getByName(ip))
            }
    }
}
