package com.example.panels.cloudflare

import java.security.SecureRandom

/**
 * Subdomains on the user's own Cloudflare domain for their own servers: one name per use, with the
 * proxy (orange cloud) set from what the connection needs. Pure, so every rule is tested without the API.
 *
 * - [Use.CDN]: WebSocket, XHTTP, gRPC or HTTPUpgrade over TLS. Proxied through Cloudflare, so the server's
 *   address is not published and the client connects to Cloudflare's edge. Only Cloudflare's proxy ports work.
 * - [Use.DIRECT]: REALITY, Hysteria2, TUIC, WireGuard, plain TLS on the server, SSH. Cloudflare cannot carry
 *   these, so the record is DNS only (grey cloud) and publishes the server's address.
 * - [Use.DNS_TUNNEL]: the name server record a DNS tunnel delegates to. Always DNS only.
 *
 * A record is never overwritten when it already points somewhere else or has another type: that is
 * reported as a conflict and left for the user, so a name in use by something else is never taken over.
 */
object SmartDomains {
    enum class Use(val title: String, val proxied: Boolean) {
        CDN("Through Cloudflare (WebSocket, XHTTP, gRPC)", proxied = true),
        DIRECT("Direct to the server (REALITY, Hysteria2, TUIC, WireGuard)", proxied = false),
        DNS_TUNNEL("DNS tunnel name server", proxied = false)
    }

    /** Ports Cloudflare's proxy accepts for HTTPS; anything else only works DNS only. */
    val CDN_HTTPS_PORTS = setOf(443, 2053, 2083, 2087, 2096, 8443)
    /** Ports Cloudflare's proxy accepts for plain HTTP (no TLS at the edge). */
    val CDN_HTTP_PORTS = setOf(80, 8080, 8880, 2052, 2082, 2086, 2095)

    private val LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
    private val HOST = Regex("^(?=.{4,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")
    private val IPV4 = Regex("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$")

    fun normalize(input: String): String = input.trim().lowercase()
        .removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore(':').trimEnd('.')

    /** A user-facing problem with a subdomain label, or null. */
    fun labelProblem(label: String): String? = when {
        label.isEmpty() -> "Enter a name, like edge"
        label.contains('.') -> "One label only, without dots"
        label == "*" || label.contains('*') -> "Wildcard names are not created"
        !LABEL.matches(label) -> "Use a-z, 0-9 and - (not at the start or end)"
        else -> null
    }

    /**
     * Problems with putting [use] on [port], or null. TLS on a proxied name ends at Cloudflare, so a CDN
     * name needs a Cloudflare HTTPS port; a DIRECT name must not be proxied (Cloudflare would cut it).
     */
    fun portProblem(use: Use, port: Int?): String? = when {
        use == Use.CDN && port != null && port !in CDN_HTTPS_PORTS ->
            "Cloudflare's proxy only carries TLS on ports ${CDN_HTTPS_PORTS.sorted().joinToString()}; use one of them or a direct name"
        else -> null
    }

    /** A label that is hard to guess (so the name is not found by trying common ones) and reads as plain text. */
    fun randomLabel(prefix: String = "", random: SecureRandom = SecureRandom()): String {
        val chars = "abcdefghijkmnpqrstuvwxyz23456789"
        val tail = buildString { repeat(6) { append(chars[random.nextInt(chars.length)]) } }
        val p = prefix.lowercase().filter { it.isLetterOrDigit() }.take(12)
        return if (p.isEmpty()) tail else "$p-$tail"
    }

    data class Zone(val id: String, val name: String, val status: String)

    /** The user's zone that [host] belongs to: the longest matching suffix, active zones first. */
    fun zoneFor(host: String, zones: List<Zone>): Zone? {
        val h = normalize(host)
        return zones.filter { h == it.name || h.endsWith("." + it.name) }
            .sortedWith(compareByDescending<Zone> { it.status == "active" }.thenByDescending { it.name.length })
            .firstOrNull()
    }

    data class Record(val id: String?, val name: String, val type: String, val content: String, val proxied: Boolean)

    /** The record [use] needs for [name] pointing at [address] (an IPv4 or IPv6 address of the user's server). */
    fun desired(name: String, address: String, use: Use): Record {
        val type = if (IPV4.matches(address)) "A" else if (address.contains(':')) "AAAA" else error("Not an IP address")
        return Record(null, normalize(name), type, address, use.proxied)
    }

    fun isHost(host: String): Boolean = HOST.matches(normalize(host))

    sealed class Action {
        data class Create(val record: Record) : Action()
        data class Update(val id: String, val record: Record, val change: String) : Action()
        data class Unchanged(val record: Record) : Action()
        /** The name is used by something else; nothing is changed. */
        data class Conflict(val existing: Record, val why: String) : Action()
    }

    /** What to do so [want] exists, given the records already at that name: create, update in place, or leave. */
    fun plan(want: Record, existing: List<Record>): Action {
        val same = existing.filter { normalize(it.name) == want.name }
        if (same.isEmpty()) return Action.Create(want)
        same.firstOrNull { it.type == "CNAME" }?.let { return Action.Conflict(it, "${want.name} is a CNAME to ${it.content}; it was left as it is") }
        val sameType = same.filter { it.type == want.type }
        if (sameType.isEmpty()) {
            // An A record next to the AAAA we want (or the reverse) is fine: both can point at the same server.
            return Action.Create(want)
        }
        if (sameType.size > 1) return Action.Conflict(sameType.first(), "${want.name} has ${sameType.size} ${want.type} records; it was left as it is")
        val old = sameType.single()
        if (old.content != want.content) return Action.Conflict(old, "${want.name} already points at ${old.content}; it was left as it is")
        if (old.proxied == want.proxied) return Action.Unchanged(old)
        return Action.Update(old.id ?: return Action.Conflict(old, "no record id"), want,
            if (want.proxied) "proxy turned on" else "proxy turned off (DNS only)")
    }
}
