package com.example.vpn.connectivity

import com.example.data.model.VlessProfile
import com.example.vpn.stealth.ConnectionKind

/**
 * The phone's side of the one Top-30 free list: the same constrained ranking the list builder uses
 * (tools/aggregator/scripts/pipeline.py `select_diverse`), applied to this phone's scores, so the list,
 * its test order and the failover backups never pile onto one CDN, network, source or kind of connection.
 *
 * One list, at most [MAX] entries. No per-operator, per-protocol or per-family lists and no equal
 * quotas: best score first, with caps, relaxed in steps when they would leave the list short.
 */
object DiversitySelector {
    const val MAX = 30
    const val MAX_PER_DOMAIN = 3
    const val MAX_PER_KIND = 10
    const val MAX_PER_SOURCE = 12
    const val MAX_CDN_SHARE = 0.7

    data class Traits(val failureDomain: String, val kind: String, val source: String, val family: String, val cdn: Boolean)

    private val CLOUDFLARE_V4 = listOf(
        "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22", "141.101.64.0/18", "108.162.192.0/18",
        "190.93.240.0/20", "188.114.96.0/20", "197.234.240.0/22", "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13",
        "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22"
    ).map { cidr -> cidr.substringBefore('/').let(::v4) to cidr.substringAfter('/').toInt() }
    private val CDN_SUFFIXES = listOf(".workers.dev", ".pages.dev", ".cloudfront.net", ".fastly.net", ".azureedge.net",
        ".vercel.app", ".netlify.app", ".gcore.com")

    private fun v4(ip: String): Long = ip.split('.').fold(0L) { acc, p -> (acc shl 8) or p.toLong() }

    private fun inCloudflare(ip: String): Boolean {
        val x = v4(ip)
        return CLOUDFLARE_V4.any { (net, bits) -> val mask = (-1L shl (32 - bits)) and 0xFFFFFFFFL; (x and mask) == (net and mask) }
    }

    /** Failure domain, kind, source and family of a profile, by the builder's rules. */
    fun traitsOf(p: VlessProfile): Traits {
        val host = p.address.removePrefix("[").removeSuffix("]").lowercase()
        val isIp = RecoverySecurityGate.isIpLiteral(host)
        val v6 = isIp && host.contains(':')
        val cloudflare = (isIp && !v6 && inCloudflare(host)) || host.endsWith(".workers.dev") || host.endsWith(".pages.dev")
        val cdnByName = CDN_SUFFIXES.any { host.endsWith(it) }
        val fronted = p.security.equals("tls", true) && p.transport.lowercase() in setOf("ws", "grpc", "httpupgrade", "xhttp", "splithttp", "h2")
        val sni = p.sni.lowercase()
        val cdn = cloudflare || cdnByName || (fronted && sni.isNotBlank() && sni != host)
        val domain = when {
            cloudflare -> "cdn:cloudflare"
            cdnByName -> "cdn:" + host.split('.').let { it.getOrElse(it.size - 2) { host } }
            isIp && !v6 -> "net:" + host.split('.').take(2).joinToString(".") + ".0.0/16"
            v6 -> "net:" + expand6(host).take(2).joinToString(":") + "::/32"
            else -> "dom:" + host.split('.').takeLast(2).joinToString(".")
        }
        val source = p.sourceSubscription ?: p.subscriptionUrl ?: "local"
        return Traits(domain, "${p.protocolType.name.lowercase()}/${p.transport.lowercase().ifBlank { "tcp" }}/${p.security.lowercase()}" +
            "|" + ConnectionKind.of(p), source, if (!isIp) "unknown" else if (v6) "ipv6" else "ipv4", cdn)
    }

    private fun expand6(h: String): List<String> {
        val parts = h.split("::")
        val head = parts[0].split(':').filter { it.isNotEmpty() }
        val tail = parts.getOrNull(1)?.split(':')?.filter { it.isNotEmpty() }.orEmpty()
        return (head + List((8 - head.size - tail.size).coerceAtLeast(0)) { "0" } + tail).map { it.trimStart('0').ifEmpty { "0" } }
    }

    /**
     * Picks at most [limit] of [items] (already eligible), best [score] first, under the diversity caps.
     * Deterministic: ties break on [tieBreak].
     */
    fun <T> select(items: List<T>, score: (T) -> Double, traits: (T) -> Traits, tieBreak: (T) -> String, limit: Int = MAX): List<T> {
        val cap = limit.coerceIn(0, MAX)
        val ranked = items.sortedWith(compareBy<T>({ -score(it) }, { tieBreak(it) }))
        val t = ranked.associateWith(traits)
        val hasCdn = t.values.any { it.cdn }
        val hasDirect = t.values.any { !it.cdn }
        val picked = mutableListOf<T>()
        val domains = HashMap<String, Int>(); val kinds = HashMap<String, Int>(); val sources = HashMap<String, Int>()

        fun fits(c: T, level: Int): Boolean {
            val x = t.getValue(c)
            if ((domains[x.failureDomain] ?: 0) >= MAX_PER_DOMAIN) return false
            if (level < 1 && (kinds[x.kind] ?: 0) >= MAX_PER_KIND) return false
            if (level < 1 && (sources[x.source] ?: 0) >= MAX_PER_SOURCE) return false
            if (level < 2) {
                val same = picked.count { t.getValue(it).cdn == x.cdn }
                val otherExists = if (x.cdn) hasDirect else hasCdn
                if (otherExists && same + 1 > MAX_CDN_SHARE * cap) return false
            }
            return true
        }
        fun take(c: T) {
            val x = t.getValue(c)
            picked += c
            domains.merge(x.failureDomain, 1, Int::plus); kinds.merge(x.kind, 1, Int::plus); sources.merge(x.source, 1, Int::plus)
        }
        // One of each address family first when both exist, so one family failing cannot empty the list.
        for (family in listOf("ipv6", "ipv4")) {
            ranked.firstOrNull { t.getValue(it).family == family && it !in picked }?.let { if (picked.size < cap && fits(it, 0)) take(it) }
        }
        for (level in 0..2) for (c in ranked) {
            if (picked.size >= cap) break
            if (c !in picked && fits(c, level)) take(c)
        }
        for (c in ranked) {
            if (picked.size >= cap) break
            if (c !in picked) take(c)
        }
        return picked.sortedWith(compareBy<T>({ -score(it) }, { tieBreak(it) }))
    }

    /** Largest share any one failure domain has in [picked]; the list builder's report shows the same. */
    fun <T> largestDomain(picked: List<T>, traits: (T) -> Traits): Int =
        picked.groupingBy { traits(it).failureDomain }.eachCount().values.maxOrNull() ?: 0
}
