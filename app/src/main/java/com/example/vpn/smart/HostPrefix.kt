package com.example.vpn.smart

/**
 * A stand-in for "the same network / ASN" when no ASN data is at hand: the IPv4 /24 or IPv6 /48 a server's
 * address (or the address its name last resolved to) sits in. A per-connection cap is applied per flagged
 * address range, so after a bulk stall a server in the same range is likely capped too.
 *
 * Keys are compared as sets: two servers are on the same network when any key matches. A name that has not
 * been resolved keys on the name itself ("host:<name>"), so the same host is still caught. Pure.
 */
object HostPrefix {
    private val V4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    /** "a.b.c.0/24" for an IPv4 literal, "x:y:z::/48" for an IPv6 literal, null for a name. */
    fun of(address: String): String? {
        val a = address.trim().removePrefix("[").removeSuffix("]")
        V4.matchEntire(a)?.let { m ->
            val o = m.groupValues.drop(1).map { it.toInt() }
            if (o.any { it > 255 }) return null
            return "${o[0]}.${o[1]}.${o[2]}.0/24"
        }
        if (':' !in a) return null
        val groups = expandV6(a.substringBefore('%')) ?: return null
        return groups.take(3).joinToString(":") { it.toString(16) } + "::/48"
    }

    /** Keys for a server at [address] whose name (if any) last resolved to [resolved]. */
    fun keys(address: String, resolved: String? = null): Set<String> {
        val a = address.trim().removePrefix("[").removeSuffix("]").lowercase()
        if (a.isBlank()) return emptySet()
        of(a)?.let { return setOf(it) }
        return setOfNotNull("host:$a", resolved?.let(::of))
    }

    /** The short form for a log line: the IP prefix when known, otherwise "unresolved". Never the host name. */
    fun label(keys: Set<String>): String = keys.firstOrNull { !it.startsWith("host:") } ?: "unresolved"

    private fun expandV6(a: String): List<Int>? {
        if (a.count { it == ':' } < 2 || a.split("::").size > 2) return null
        val parts = a.split("::")
        fun parse(s: String): List<Int>? = if (s.isEmpty()) emptyList() else s.split(':').map {
            if (it.isEmpty() || it.length > 4) return null
            it.toIntOrNull(16) ?: return null
        }
        val head = parse(parts[0]) ?: return null
        val tail = if (parts.size == 2) parse(parts[1]) ?: return null else emptyList()
        if (parts.size == 1 && head.size != 8) return null
        val fill = 8 - head.size - tail.size
        if (fill < 0 || (parts.size == 2 && fill < 1)) return null
        return head + List(fill) { 0 } + tail
    }
}
