package com.example.vpn.safety

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Prevents pre-tunnel DNS bootstrapping from silently going through an ISP resolver.
 * A known, pinned DoH name is converted to its verified address; arbitrary DNS names
 * are never accepted as bootstrap targets. IPv4/IPv6 parsing is strict and never
 * performs a network lookup for a hostname.
 */
object DnsResolvers {
    private val KNOWN = mapOf(
        "dns.google" to "8.8.8.8",
        "dns.quad9.net" to "9.9.9.9",
        "cloudflare-dns.com" to "1.1.1.1",
        "one.one.one.one" to "1.1.1.1",
        "dns.adguard-dns.com" to "94.140.14.14"
    )

    /** Replace only allowlisted HTTPS hostnames; preserve path and query for real DoH URLs. */
    fun literal(resolver: String): String {
        val input = resolver.trim()
        if (!input.startsWith("https://", ignoreCase = true)) return input
        val uri = runCatching { URI(input) }.getOrNull() ?: return input
        if (uri.userInfo != null || uri.fragment != null) return input
        val ip = KNOWN[uri.host?.lowercase()] ?: return input
        return runCatching {
            URI("https", null, ip, uri.port, uri.path, uri.query, null).toString()
        }.getOrDefault(input)
    }

    /** Reject ambiguous addresses, URI credentials, and bootstrap hostnames that require ISP DNS. */
    fun isUsable(resolver: String): Boolean {
        val value = literal(resolver)
        if (value.isBlank()) return false
        val host = if (value.startsWith("https://", ignoreCase = true)) {
            val url = runCatching { URI(value) }.getOrNull() ?: return false
            if (!url.scheme.equals("https", true) || url.userInfo != null ||
                url.fragment != null || url.port > 65535 || url.rawAuthority.isNullOrBlank()) return false
            url.host ?: return false
        } else {
            // A bare resolver is an IP literal, never a scheme or a host:port pair.
            if (value.contains('/') || value.contains('?') || value.contains('@')) return false
            value
        }
        return isIpLiteral(host)
    }

    internal fun isIpLiteral(host: String): Boolean {
        val literal = host.removePrefix("[").removeSuffix("]")
        if (literal.isBlank() || '%' in literal || literal.any(Char::isWhitespace)) return false
        if ('.' in literal && ':' !in literal) {
            val parts = literal.split('.')
            return parts.size == 4 && parts.all { part ->
                part.isNotEmpty() && part.length <= 3 &&
                    (part.length == 1 || part[0] != '0') &&
                    part.all { it in '0'..'9' } &&
                    (part.toIntOrNull() ?: 256) in 0..255
            }
        }
        if (':' !in literal || literal.any { !it.isDigit() &&
                it.lowercaseChar() !in 'a'..'f' && it != ':' && it != '.' }) return false
        // An IPv6-looking string cannot be resolved through DNS; Java parses it locally.
        return runCatching { InetAddress.getByName(literal) is Inet6Address }.getOrDefault(false)
    }
}
