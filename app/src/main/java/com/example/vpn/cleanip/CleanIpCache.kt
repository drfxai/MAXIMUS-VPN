package com.example.vpn.cleanip

import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-network memory for the clean-IP path: the best edges found for each (CDN, TLS name) on a network, and
 * whether the network proxies Cloudflare TLS by name ([SniProxyDetector]). Clean addresses "leak and get
 * filtered quickly" in Iran, so entries expire after [ttlMs] and a rescan replaces them.
 */
class CleanIpCache(
    private val load: () -> String?,
    private val save: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    val ttlMs: Long = DEFAULT_TTL_MS,
    val proxyTtlMs: Long = DEFAULT_TTL_MS,
    val maxKeys: Int = 96
) {
    private data class Entry(val ips: List<String>, val at: Long)
    private data class Proxy(val proxied: Boolean, val reasons: List<String>, val at: Long)

    private var edges: MutableMap<String, Entry>? = null
    private var proxies: MutableMap<String, Proxy>? = null

    /** Best edges for [sni] behind [cdn] on [network], best first; empty when none are fresh. */
    @Synchronized
    fun best(network: String, cdn: Cdn, sni: String): List<String> {
        val e = edges()[key(network, cdn, sni)] ?: return emptyList()
        return if (clock() - e.at < ttlMs) e.ips else emptyList()
    }

    @Synchronized
    fun put(network: String, cdn: Cdn, sni: String, ips: List<String>) {
        val clean = ips.filter { CdnRanges.ipv4(it) != null }.distinct().take(MAX_IPS)
        val map = edges()
        if (clean.isEmpty()) map.remove(key(network, cdn, sni)) else map[key(network, cdn, sni)] = Entry(clean, clock())
        persist()
    }

    /** True or false while a recent verdict for [network] holds; null when the network was not checked lately. */
    @Synchronized
    fun sniProxied(network: String): Boolean? = proxies()[network]?.takeIf { clock() - it.at < proxyTtlMs }?.proxied

    @Synchronized
    fun proxyReasons(network: String): List<String> = proxies()[network]?.takeIf { clock() - it.at < proxyTtlMs }?.reasons.orEmpty()

    /** Remembers a conclusive [verdict] for [network]; an inconclusive one changes nothing. */
    @Synchronized
    fun record(network: String, verdict: SniProxyDetector.Verdict) {
        if (!verdict.conclusive) return
        proxies()[network] = Proxy(verdict.proxied, verdict.reasons.take(4), clock())
        persist()
    }

    private fun key(network: String, cdn: Cdn, sni: String) = "$network|${cdn.name}|${sni.lowercase()}"

    private fun edges(): MutableMap<String, Entry> { read(); return edges!! }
    private fun proxies(): MutableMap<String, Proxy> { read(); return proxies!! }

    private fun read() {
        if (edges != null && proxies != null) return
        val e = LinkedHashMap<String, Entry>()
        val p = LinkedHashMap<String, Proxy>()
        runCatching {
            val root = JSONObject(load() ?: "{}")
            root.optJSONObject("edges")?.let { o ->
                o.keys().forEach { k ->
                    val v = o.getJSONObject(k)
                    val a = v.getJSONArray("ips")
                    e[k] = Entry((0 until a.length()).map { a.getString(it) }, v.getLong("at"))
                }
            }
            root.optJSONObject("proxy")?.let { o ->
                o.keys().forEach { k ->
                    val v = o.getJSONObject(k)
                    val a = v.optJSONArray("why") ?: JSONArray()
                    p[k] = Proxy(v.getBoolean("on"), (0 until a.length()).map { a.getString(it) }, v.getLong("at"))
                }
            }
        }
        edges = e
        proxies = p
    }

    private fun persist() {
        val now = clock()
        val e = edges!!
        val p = proxies!!
        e.entries.removeAll { now - it.value.at >= ttlMs }
        p.entries.removeAll { now - it.value.at >= proxyTtlMs }
        while (e.size > maxKeys) e.remove(e.minByOrNull { it.value.at }!!.key)
        while (p.size > maxKeys) p.remove(p.minByOrNull { it.value.at }!!.key)
        val root = JSONObject()
        root.put("edges", JSONObject().also { o -> e.forEach { (k, v) -> o.put(k, JSONObject().put("ips", JSONArray(v.ips)).put("at", v.at)) } })
        root.put("proxy", JSONObject().also { o -> p.forEach { (k, v) -> o.put(k, JSONObject().put("on", v.proxied).put("why", JSONArray(v.reasons)).put("at", v.at)) } })
        runCatching { save(root.toString()) }
    }

    companion object {
        /** Six hours: long enough to make reconnects instant, short enough that a filtered address ages out. */
        const val DEFAULT_TTL_MS = 6L * 60 * 60 * 1000
        const val MAX_IPS = 8
    }
}
