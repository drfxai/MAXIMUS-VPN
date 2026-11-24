package com.example.vpn.smart

import org.json.JSONObject

/**
 * The connections (saved servers and built-in engines alike) that carried verified traffic on each network
 * ([NetworkIdentity] key), with the download rate the check after connect measured through them.
 *
 * Build 171 report: on an Iranian cellular network with Cloudflare's TLS filtered, a chain profile (~51 KB/s),
 * Serverless (~41 KB/s), Lantern (~19 KB/s) and Tor (~10 KB/s) were all verified within ten minutes, yet the next
 * automatic Aether connect spent three gateway scans (≈190 s) before failing. Automatic Aether on a filtered link now
 * falls back to [best] here at once instead.
 *
 * [best] prefers the highest verified rate, then the most recent verification. A path whose download stalled is
 * dropped. Only profile ids and a redacted display name are kept, for [TTL_MS]; bounded per network and in networks.
 */
class VerifiedWinnerBook(
    private val load: () -> String?,
    private val save: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) {
    data class Winner(val profileId: String, val name: String, val bytesPerSecond: Long?, val at: Long) {
        /** "Serverless (41 KB/s)" for messages. */
        fun label(): String = name + (bytesPerSecond?.let { " (${it / 1024} KB/s)" }.orEmpty())
    }

    private val entries: MutableMap<String, MutableMap<String, Winner>> by lazy { parse(load()) }

    /**
     * [profileId] carried verified traffic on [network] now. A rate measured earlier is kept when none is given
     * (the verification comes before the download check).
     */
    @Synchronized
    fun recordVerified(network: String, profileId: String, name: String, bytesPerSecond: Long? = null) {
        if (network.isBlank() || profileId.isBlank()) return
        val n = entries.getOrPut(network) { linkedMapOf() }
        val previous = n.remove(profileId)
        n[profileId] = Winner(profileId, name.take(MAX_NAME), bytesPerSecond ?: previous?.bytesPerSecond, clock())
        while (n.size > MAX_PER_NETWORK) n.remove(n.keys.first())
        trim()
        persist()
    }

    /** The download check through [profileId] on [network] measured [bytesPerSecond], or found it [stalled]. */
    @Synchronized
    fun recordRate(network: String, profileId: String, bytesPerSecond: Long?, stalled: Boolean) {
        val n = entries[network] ?: return
        val w = n[profileId] ?: return
        if (stalled) n.remove(profileId)
        else if (bytesPerSecond != null) n[profileId] = w.copy(bytesPerSecond = bytesPerSecond)
        else return
        persist()
    }

    /** The path failed on [network]: it is no longer a fallback there. */
    @Synchronized
    fun forget(network: String, profileId: String) {
        if (entries[network]?.remove(profileId) != null) persist()
    }

    /** Best verified path on [network] other than [exclude], within [maxAgeMs]: highest rate, then most recent. */
    @Synchronized
    fun best(network: String, exclude: Set<String> = emptySet(), maxAgeMs: Long = TTL_MS,
             eligible: (String) -> Boolean = { true }): Winner? {
        val now = clock()
        return entries[network]?.values
            ?.filter { it.profileId !in exclude && now - it.at in 0 until minOf(maxAgeMs, TTL_MS) && eligible(it.profileId) }
            ?.sortedWith(compareByDescending<Winner> { it.bytesPerSecond ?: -1L }.thenByDescending { it.at })
            ?.firstOrNull()
    }

    @Synchronized
    fun clear() {
        entries.clear()
        persist()
    }

    private fun trim() {
        val now = clock()
        entries.values.forEach { n -> n.entries.removeAll { now - it.value.at !in 0 until TTL_MS } }
        entries.entries.removeAll { it.value.isEmpty() }
        entries.entries.sortedByDescending { e -> e.value.values.maxOf { it.at } }.drop(MAX_NETWORKS).forEach { entries.remove(it.key) }
    }

    private fun persist() {
        val n = JSONObject()
        entries.forEach { (network, paths) ->
            val o = JSONObject()
            paths.forEach { (id, w) ->
                o.put(id, JSONObject().put("n", w.name).put("at", w.at).apply { w.bytesPerSecond?.let { put("r", it) } })
            }
            n.put(network, o)
        }
        runCatching { save(JSONObject().put("schema", SCHEMA).put("networks", n).toString()) }
    }

    private fun parse(raw: String?): MutableMap<String, MutableMap<String, Winner>> {
        val out = linkedMapOf<String, MutableMap<String, Winner>>()
        val root = runCatching { JSONObject(raw ?: return out) }.getOrNull() ?: return out
        if (root.optInt("schema") != SCHEMA) return out
        val networks = root.optJSONObject("networks") ?: return out
        networks.keys().forEach { network ->
            val o = networks.optJSONObject(network) ?: return@forEach
            val m = linkedMapOf<String, Winner>()
            o.keys().forEach { id ->
                val w = o.optJSONObject(id) ?: return@forEach
                m[id] = Winner(id, w.optString("n"), if (w.has("r")) w.optLong("r") else null, w.optLong("at"))
            }
            if (m.isNotEmpty()) out[network] = m
        }
        return out
    }

    companion object {
        /** A path verified longer ago than this says little about the network now. */
        const val TTL_MS = 6 * 60 * 60_000L
        const val MAX_PER_NETWORK = 8
        const val MAX_NETWORKS = 8
        private const val MAX_NAME = 64
        private const val SCHEMA = 1
    }
}
