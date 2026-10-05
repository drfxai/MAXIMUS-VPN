package com.example.vpn.safety

import java.net.URI

/**
 * The tunnel's DNS resolver must be reachable without a DNS lookup: looking up the resolver's own name
 * would go to the ISP in plaintext or loop into the tunnel. Well-known DNS-over-HTTPS names are
 * replaced by the addresses their certificates also cover; any other name is refused.
 */
object DnsResolvers {
    private val KNOWN = mapOf(
        "dns.google" to "8.8.8.8",
        "dns.quad9.net" to "9.9.9.9",
        "cloudflare-dns.com" to "1.1.1.1",
        "one.one.one.one" to "1.1.1.1",
        "dns.adguard-dns.com" to "94.140.14.14"
    )

    /** [resolver] with a known DoH host name replaced by its address; anything else unchanged. */
    fun literal(resolver: String): String {
        val trimmed = resolver.trim()
        if (!trimmed.startsWith("https://")) return trimmed
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return trimmed
        val ip = KNOWN[uri.host?.lowercase()] ?: return trimmed
        return URI(uri.scheme, uri.userInfo, ip, uri.port, uri.path, uri.query, uri.fragment).toString()
    }

    /** True when [resolver] (after [literal]) is an IP address or a DoH URL whose host is one. */
    fun isUsable(resolver: String): Boolean {
        val value = literal(resolver)
        if (value.isBlank()) return false
        val host = if (value.startsWith("https://")) runCatching { URI(value).host }.getOrNull() ?: return false else value
        return isIpLiteral(host)
    }

    private fun isIpLiteral(host: String): Boolean {
        val h = host.removePrefix("[").removeSuffix("]")
        val v4 = h.split('.')
        if (v4.size == 4 && v4.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() <= 255 }) return true
        return h.contains(':') && h.all { it.isLetterOrDigit() || it == ':' || it == '.' } && h.none { it in 'g'..'z' || it in 'G'..'Z' }
    }
}
