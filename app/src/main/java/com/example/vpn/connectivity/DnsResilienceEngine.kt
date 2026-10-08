package com.example.vpn.connectivity

import org.json.JSONArray
import org.json.JSONObject

/** What one real DNS query returned. ICMP is never used to judge a resolver. */
enum class DnsOutcome {
    /** A public address. */
    ANSWER,
    /** A private or reserved address: the block-page answer filtering networks give for blocked names. */
    BLOCKED_ANSWER,
    /** No usable answer (empty, NXDOMAIN, or an HTTP error from a DoH resolver). */
    NO_ANSWER,
    TIMEOUT,
    ERROR
}

/**
 * How server names are looked up on one network, from real queries made on it:
 * which DoH resolvers to ask (best first, ones that never worked here last or left out), and for which
 * names the network's own DNS is known to give block-page answers, so those go straight to DoH without the
 * grace wait. Never a downgrade: names that go privately (GOD MODE) never reach the network's DNS.
 */
data class DnsResilienceProfile(
    val network: String?,
    val resolverOrder: List<String>,
    /** Names the network's DNS answered with a block page here and never with a public address. */
    val tamperedNames: Set<String>,
    val measuredAt: Long
) {
    fun skipSystemFor(host: String): Boolean = host.lowercase() in tamperedNames
}

class DnsResilienceEngine(
    private val load: () -> String?,
    private val save: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) {
    data class Sample(val network: String?, val resolver: String, val host: String, val at: Long, val outcome: DnsOutcome, val ms: Long?) {
        fun toJson(): JSONObject = JSONObject().apply {
            network?.let { put("n", it) }; put("r", resolver); put("h", host); put("t", at); put("o", outcome.name); ms?.let { put("m", it) }
        }

        companion object {
            fun fromJson(o: JSONObject) = Sample(
                o.optString("n").ifBlank { null }, o.getString("r"), o.optString("h"), o.optLong("t"),
                runCatching { DnsOutcome.valueOf(o.optString("o")) }.getOrDefault(DnsOutcome.ERROR),
                if (o.has("m")) o.optLong("m") else null
            )
        }
    }

    data class ResolverStats(val resolver: String, val queries: Int, val answers: Int, val timeouts: Int, val medianMs: Long?) {
        val rate: Double get() = if (queries == 0) 0.0 else answers.toDouble() / queries
    }

    private var samples: MutableList<Sample>? = null

    @Synchronized
    private fun list(): MutableList<Sample> = samples ?: runCatching {
        val a = JSONArray(load() ?: "[]")
        (0 until a.length()).map { Sample.fromJson(a.getJSONObject(it)) }.toMutableList()
    }.getOrDefault(mutableListOf()).also { samples = it }

    @Synchronized
    fun record(network: String?, resolver: String, host: String, outcome: DnsOutcome, ms: Long?) {
        val all = list()
        val now = clock()
        all.removeAll { now - it.at > TTL_MS }
        all += Sample(network, resolver, host.lowercase(), now, outcome, ms)
        while (all.size > MAX_SAMPLES) all.removeAt(0)
        val a = JSONArray()
        all.forEach { a.put(it.toJson()) }
        runCatching { save(a.toString()) }
    }

    @Synchronized
    fun stats(network: String?, resolver: String): ResolverStats {
        val s = fresh(network).filter { it.resolver == resolver }
        val lat = s.filter { it.outcome == DnsOutcome.ANSWER }.mapNotNull { it.ms }.sorted()
        return ResolverStats(resolver, s.size, s.count { it.outcome == DnsOutcome.ANSWER }, s.count { it.outcome == DnsOutcome.TIMEOUT },
            lat.getOrNull(lat.size / 2))
    }

    /** The profile for [network] over the [available] DoH resolvers. Unmeasured resolvers keep their place. */
    @Synchronized
    fun profile(network: String?, available: List<String>): DnsResilienceProfile {
        val stats = available.associateWith { stats(network, it) }
        fun dead(r: String) = stats.getValue(r).let { it.queries >= DEAD_AFTER && it.answers == 0 }
        val ranked = available.withIndex().sortedWith(compareBy<IndexedValue<String>>(
            { dead(it.value) },
            // Measured and working first, by success rate then speed; unmeasured keep their given order.
            { stats.getValue(it.value).let { s -> if (s.queries == 0) 0.5 else 1.0 - s.rate } },
            { stats.getValue(it.value).medianMs ?: Long.MAX_VALUE },
            { it.index }
        )).map { it.value }
        // Ones that never answered here are left out, but [MIN_RESOLVERS] are always asked.
        val live = ranked.filter { !dead(it) }
        val order = if (live.size >= MIN_RESOLVERS) live else ranked.take(maxOf(MIN_RESOLVERS, live.size))
        val system = fresh(network).filter { it.resolver == SYSTEM }
        val tampered = system.groupBy { it.host }.filter { (_, s) ->
            s.count { it.outcome == DnsOutcome.BLOCKED_ANSWER } >= TAMPERED_AFTER && s.none { it.outcome == DnsOutcome.ANSWER }
        }.keys
        return DnsResilienceProfile(network, order, tampered, clock())
    }

    private fun fresh(network: String?): List<Sample> = clock().let { now -> list().filter { it.network == network && now - it.at <= TTL_MS } }

    companion object {
        /** The network's own resolver, as named in samples. */
        const val SYSTEM = "system"
        const val TTL_MS = 24L * 60 * 60 * 1000
        const val MAX_SAMPLES = 500
        const val DEAD_AFTER = 3
        const val MIN_RESOLVERS = 3
        const val TAMPERED_AFTER = 2
    }
}
