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
    /** DNS JSON endpoints addressed by IP. */
    val DOH_ENDPOINTS = listOf(
        "https://1.1.1.1/dns-query?name=%s&type=A",
        "https://8.8.8.8/resolve?name=%s&type=A"
    )
    private const val TIMEOUT_MS = 5000

    data class Result(val address: String, val viaDoh: Boolean)

    /**
     * [system] is the network's own resolver; [open] opens a connection outside the VPN. Returns the
     * system answer when it is a public address, otherwise the first public DoH answer.
     */
    fun resolve(
        host: String,
        system: (String) -> List<InetAddress>,
        open: (URL) -> HttpURLConnection
    ): Result {
        var systemError: Exception? = null
        val answers = try { system(host) } catch (e: Exception) { systemError = e; emptyList() }
        val pick = answers.firstOrNull { it is java.net.Inet4Address } ?: answers.firstOrNull()
        if (pick != null && !isBlockedAnswer(pick)) return Result(pick.hostAddress.orEmpty(), viaDoh = false)

        var dohError: Exception? = null
        for (endpoint in DOH_ENDPOINTS) {
            try {
                val ip = queryDoh(String.format(endpoint, URLEncoder.encode(host, "UTF-8")), open)
                if (ip != null) return Result(ip, viaDoh = true)
            } catch (e: Exception) {
                dohError = e
            }
        }
        // A blocked answer is still better than nothing: the error then shows in the proxy's log.
        if (pick != null) return Result(pick.hostAddress.orEmpty(), viaDoh = false)
        throw IllegalStateException(
            "cannot resolve the server address '$host'",
            systemError ?: dohError
        )
    }

    /** True for addresses a public server name cannot have: private, loopback, link-local or unspecified. */
    fun isBlockedAnswer(address: InetAddress): Boolean =
        address.isSiteLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isAnyLocalAddress || address.isMulticastAddress ||
            (address.address.size == 16 && (address.address[0].toInt() and 0xfe) == 0xfc)

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
