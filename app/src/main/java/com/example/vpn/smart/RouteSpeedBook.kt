package com.example.vpn.smart

import org.json.JSONObject

/**
 * What the download check after each connect measured through each saved server, per network
 * ([NetworkIdentity] key): the rate, or that the transfer stalled part-way (small requests passed, bulk
 * data stopped). Smart Connect reads it so a server that was slow or stalled here goes behind one that
 * was fast, instead of winning again on one quick request.
 *
 * Only the latest result per server counts, for [TTL_MS]. Bounded per network and in networks.
 */
class RouteSpeedBook(
    private val load: () -> String?,
    private val save: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) {
    data class Sample(val bytesPerSecond: Long?, val stalled: Boolean, val at: Long)

    private val entries: MutableMap<String, MutableMap<String, Sample>> by lazy { parse(load()) }

    /** The download check through [profileId] on [network] ended: its rate, or [stalled]. */
    @Synchronized
    fun record(network: String, profileId: String, bytesPerSecond: Long?, stalled: Boolean) {
        if (bytesPerSecond == null && !stalled) return
        val n = entries.getOrPut(network) { linkedMapOf() }
        n.remove(profileId)
        n[profileId] = Sample(bytesPerSecond, stalled, clock())
        while (n.size > MAX_PER_NETWORK) n.remove(n.keys.first())
        trim()
        persist()
    }

    @Synchronized
    fun sample(network: String, profileId: String): Sample? =
        entries[network]?.get(profileId)?.takeIf { clock() - it.at in 0 until TTL_MS }

    /**
     * Request time added to a server's score on [network]: a stall here weighs like [STALL_PENALTY_MS],
     * a measured rate below [GOOD_BYTES_PER_SECOND] up to [SLOW_PENALTY_MS], a fast one nothing.
     */
    @Synchronized
    fun penaltyMs(network: String, profileId: String): Long {
        val s = sample(network, profileId) ?: return 0
        if (s.stalled) return if (clock() - s.at < STALL_MEMORY_MS) STALL_PENALTY_MS else SLOW_PENALTY_MS
        val rate = s.bytesPerSecond ?: return 0
        if (rate >= GOOD_BYTES_PER_SECOND) return 0
        return SLOW_PENALTY_MS * (GOOD_BYTES_PER_SECOND - rate) / GOOD_BYTES_PER_SECOND
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
        val root = JSONObject().put("schema", SCHEMA)
        val n = JSONObject()
        entries.forEach { (network, servers) ->
            val o = JSONObject()
            servers.forEach { (id, s) ->
                o.put(id, JSONObject().put("at", s.at).put("st", s.stalled).apply { s.bytesPerSecond?.let { put("r", it) } })
            }
            n.put(network, o)
        }
        save(root.put("networks", n).toString())
    }

    companion object {
        const val TTL_MS = 3 * 24 * 60 * 60 * 1000L
        /** A stall this recent puts the server behind every other working one. */
        const val STALL_MEMORY_MS = 6 * 60 * 60 * 1000L
        const val STALL_PENALTY_MS = 5_000L
        const val SLOW_PENALTY_MS = 600L
        /** About 1 Mbit/s over the 64 KB check, which includes the connection set-up. */
        const val GOOD_BYTES_PER_SECOND = 128 * 1024L
        private const val MAX_PER_NETWORK = 60
        private const val MAX_NETWORKS = 8
        private const val SCHEMA = 1

        private fun parse(text: String?): MutableMap<String, MutableMap<String, Sample>> {
            val out = linkedMapOf<String, MutableMap<String, Sample>>()
            val root = runCatching { JSONObject(text ?: return out) }.getOrNull() ?: return out
            if (root.optInt("schema") != SCHEMA) return out
            val networks = root.optJSONObject("networks") ?: return out
            networks.keys().forEach { network ->
                val o = networks.optJSONObject(network) ?: return@forEach
                val servers = linkedMapOf<String, Sample>()
                o.keys().forEach { id ->
                    val s = o.optJSONObject(id) ?: return@forEach
                    servers[id] = Sample(if (s.has("r")) s.optLong("r") else null, s.optBoolean("st"), s.optLong("at"))
                }
                out[network] = servers
            }
            return out
        }
    }
}
