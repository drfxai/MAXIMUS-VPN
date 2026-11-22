package com.example.vpn.dns

import com.example.data.model.VlessProfile
import com.example.vpn.sidecar.CottenDnsSidecar
import com.example.vpn.sidecar.DnsttSidecar
import com.example.vpn.sidecar.SlipstreamSidecar
import org.json.JSONArray
import org.json.JSONObject

/**
 * The resolvers [ResolverScanner] verified on each network, keyed like the other per-network memory
 * (NetworkIdentity / [com.example.vpn.smart.NetworkKey]: "cell:43235", "wifi:…"), best first, for [TTL_MS].
 * Only honest, answering resolvers are kept, and only addresses and what they passed; [load] and [save]
 * hold the JSON (SharedPreferences in the app, memory in tests).
 */
class VerifiedResolverPool(
    private val load: () -> String?,
    private val save: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) {
    data class Entry(val ip: String, val score: Int, val ms: Long, val udp: Boolean, val tcp: Boolean, val dot: Boolean)

    private class Scan(val at: Long, val entries: List<Entry>)

    private val scans: LinkedHashMap<String, Scan> by lazy { parse(load()) }

    /** Replaces [network]'s pool with the top of [ranked] (as [ResolverScanner.rank] returns it). */
    @Synchronized
    fun record(network: String, ranked: List<ResolverScanner.Result>) {
        val entries = ranked.filter { it.usable }.take(MAX_PER_NETWORK).map {
            Entry(it.ip, it.score, it.bestMs ?: 0L, it.udp, it.tcp, it.dot)
        }
        scans.remove(network)
        scans[network] = Scan(clock(), entries)
        val now = clock()
        scans.entries.removeAll { now - it.value.at !in 0 until TTL_MS }
        while (scans.size > MAX_NETWORKS) scans.remove(scans.keys.first())
        persist()
    }

    /** The verified resolvers on [network], best first; empty once the scan is older than [TTL_MS]. */
    @Synchronized
    fun verified(network: String): List<Entry> =
        scans[network]?.takeIf { clock() - it.at in 0 until TTL_MS }?.entries.orEmpty()

    /** No scan on [network], or it is past half its life: time for a background rescan. */
    @Synchronized
    fun needsScan(network: String): Boolean =
        scans[network]?.let { clock() - it.at !in 0 until TTL_MS / 2 } ?: true

    @Synchronized
    fun clear() { scans.clear(); persist() }

    private fun persist() {
        val n = JSONObject()
        scans.forEach { (network, s) ->
            val a = JSONArray()
            s.entries.forEach { e ->
                a.put(JSONObject().put("ip", e.ip).put("s", e.score).put("ms", e.ms)
                    .put("u", e.udp).put("t", e.tcp).put("d", e.dot))
            }
            n.put(network, JSONObject().put("at", s.at).put("r", a))
        }
        save(JSONObject().put("schema", SCHEMA).put("networks", n).toString())
    }

    private fun parse(raw: String?): LinkedHashMap<String, Scan> {
        val out = linkedMapOf<String, Scan>()
        val root = runCatching { JSONObject(raw ?: return out) }.getOrNull() ?: return out
        if (root.optInt("schema") != SCHEMA) return out
        val n = root.optJSONObject("networks") ?: return out
        n.keys().forEach { network ->
            val o = n.optJSONObject(network) ?: return@forEach
            val a = o.optJSONArray("r") ?: JSONArray()
            val entries = (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let {
                    Entry(it.optString("ip"), it.optInt("s"), it.optLong("ms"), it.optBoolean("u"), it.optBoolean("t"), it.optBoolean("d"))
                }
            }.filter { it.ip.isNotBlank() }
            out[network] = Scan(o.optLong("at"), entries)
        }
        return out
    }

    companion object {
        /** Twelve hours: carriers change their DNS filtering often, and a rescan is cheap. */
        const val TTL_MS = 12 * 60 * 60 * 1000L
        const val MAX_PER_NETWORK = 12
        private const val MAX_NETWORKS = 8
        private const val SCHEMA = 1

        /**
         * Config-based path: the profile's own resolvers, with the ones verified on this network moved to the
         * front in pool order. Nothing is added or dropped: a configured resolver may be the only one that
         * forwards to the tunnel's name server, so the user's list stays whole. [host] gives a line's IP.
         */
        fun preferVerified(configured: List<String>, verified: List<String>, host: (String) -> String): List<String> {
            val rank = verified.withIndex().associate { it.value to it.index }
            val head = configured.filter { host(it) in rank }.sortedBy { rank.getValue(host(it)) }
            return head + configured.filter { host(it) !in rank }
        }

        /** "1.2.3.4", "1.2.3.4:53", "[::1]:53" or "tls://1.2.3.4:853" -> the IP. */
        fun hostOf(line: String): String {
            val s = line.trim().removePrefix("tls://").removePrefix("udp://")
            if (s.startsWith('[')) return s.substringAfter('[').substringBefore(']')
            return if (s.count { it == ':' } == 1) s.substringBefore(':') else s
        }
    }
}

/**
 * Applies the verified pool to a DNS-tunnel profile just before its engine starts; the saved profile is
 * never changed. Two separate paths:
 *  - config-based (the profile lists its own resolvers): [VerifiedResolverPool.preferVerified] reorders only;
 *  - config-free (the profile was left on its engine's built-in default resolver): verified resolvers fill
 *    the list, the default kept last. CottenDNS/MasterDNS always carries explicit resolvers, so it is
 *    always config-based.
 */
object DnsTunnelResolverOrder {
    val ENGINES = setOf(CottenDnsSidecar.ID, DnsttSidecar.ID, SlipstreamSidecar.ID)
    /** How many verified resolvers a config-free profile gets in front of its default. */
    const val CONFIG_FREE_FILL = 3

    data class Applied(val profile: VlessProfile, val changed: Boolean, val configFree: Boolean)

    fun apply(engineId: String, profile: VlessProfile, pool: List<VerifiedResolverPool.Entry>): Applied {
        if (pool.isEmpty()) return Applied(profile, false, false)
        return runCatching {
            when (engineId) {
                CottenDnsSidecar.ID -> cotten(profile, pool)
                DnsttSidecar.ID -> dnstt(profile, pool)
                SlipstreamSidecar.ID -> slipstream(profile, pool)
                else -> Applied(profile, false, false)
            }
        }.getOrDefault(Applied(profile, false, false))
    }

    private fun cotten(profile: VlessProfile, pool: List<VerifiedResolverPool.Entry>): Applied {
        val s = CottenDnsSidecar.settings(profile)
        val usable = pool.filter { when (s.transport) { "udp" -> it.udp; "tcp" -> it.tcp; "dot" -> it.dot; else -> true } }
        val ordered = VerifiedResolverPool.preferVerified(s.resolvers, usable.map { it.ip }, { VerifiedResolverPool.hostOf(it) })
        return if (ordered == s.resolvers) Applied(profile, false, false)
            else Applied(CottenDnsSidecar.withSettings(profile, s.copy(resolvers = ordered)), true, false)
    }

    private fun dnstt(profile: VlessProfile, pool: List<VerifiedResolverPool.Entry>): Applied {
        val s = DnsttSidecar.settings(profile)
        val configFree = s.resolvers.size == 1 && s.kind == DnsttSidecar.ResolverKind.DOH && s.resolver == DnsttSidecar.DEFAULT_DOH
        val resolvers = (if (configFree) {
            // Encrypted like the default DoH: only resolvers that passed DoT here.
            pool.filter { it.dot }.take(CONFIG_FREE_FILL)
                .map { DnsttSidecar.Resolver(DnsttSidecar.ResolverKind.DOT, "${it.ip}:853") } + s.resolvers
        } else {
            val verified = pool.map { it.ip }
            fun key(r: DnsttSidecar.Resolver) = if (r.kind == DnsttSidecar.ResolverKind.DOH) "" else VerifiedResolverPool.hostOf(r.address)
            val rank = verified.withIndex().associate { it.value to it.index }
            s.resolvers.filter { key(it) in rank }.sortedBy { rank.getValue(key(it)) } + s.resolvers.filter { key(it) !in rank }
        }).take(DnsttSidecar.MAX_RESOLVERS)
        return if (resolvers == s.resolvers) Applied(profile, false, configFree)
            else Applied(DnsttSidecar.withSettings(profile, s.copy(resolvers = resolvers)), true, configFree)
    }

    private fun slipstream(profile: VlessProfile, pool: List<VerifiedResolverPool.Entry>): Applied {
        val s = SlipstreamSidecar.settings(profile)
        val configFree = s.resolvers == listOf(SlipstreamSidecar.DEFAULT_RESOLVER)
        val resolvers = (if (configFree) {
            (pool.filter { it.udp }.take(CONFIG_FREE_FILL).map { it.ip } + s.resolvers).distinct()
        } else {
            VerifiedResolverPool.preferVerified(s.resolvers, pool.filter { it.udp }.map { it.ip }) { VerifiedResolverPool.hostOf(it) }
        }).take(SlipstreamSidecar.MAX_RESOLVERS)
        return if (resolvers == s.resolvers) Applied(profile, false, configFree)
            else Applied(SlipstreamSidecar.withSettings(profile, s.copy(resolvers = resolvers)), true, configFree)
    }
}
