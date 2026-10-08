package com.example.vpn.connectivity

import org.json.JSONArray
import org.json.JSONObject

/**
 * Edge addresses (clean CDN IPs and the like) measured on this phone, per network: which ones completed
 * a real exchange with the config's own server (the clean-address scan's TLS + WebSocket upgrade to the
 * real host, or a request through the proxy), how often and how fast. Only addresses that passed here,
 * recently and mostly, are handed to recovery as endpoint alternatives ([validated]); an address that only
 * answered a TCP connect never is.
 */
class EndpointScoringEngine(
    private val load: () -> String?,
    private val save: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) {
    data class Measurement(val ip: String, val network: String?, val at: Long, val success: Boolean, val latencyMs: Long?) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("ip", ip); network?.let { put("n", it) }; put("t", at); put("s", success); latencyMs?.let { put("l", it) }
        }

        companion object {
            fun fromJson(o: JSONObject) = Measurement(o.getString("ip"), o.optString("n").ifBlank { null }, o.optLong("t"),
                o.optBoolean("s"), if (o.has("l")) o.optLong("l") else null)
        }
    }

    data class Score(val ip: String, val trials: Int, val successes: Int, val medianMs: Long?, val lastSuccessAt: Long?) {
        val rate: Double get() = if (trials == 0) 0.0 else successes.toDouble() / trials
    }

    private var items: MutableList<Measurement>? = null

    @Synchronized
    private fun list(): MutableList<Measurement> = items ?: runCatching {
        val a = JSONArray(load() ?: "[]")
        (0 until a.length()).map { Measurement.fromJson(a.getJSONObject(it)) }.toMutableList()
    }.getOrDefault(mutableListOf()).also { items = it }

    /** Records results; addresses that are not public IP literals are ignored. */
    @Synchronized
    fun record(measurements: List<Measurement>) {
        val ok = measurements.filter { RecoverySecurityGate.isIpLiteral(it.ip) && !RecoverySecurityGate.isPrivateOrReserved(it.ip) }
        if (ok.isEmpty()) return
        val all = list()
        val now = clock()
        all.removeAll { now - it.at > TTL_MS }
        all += ok
        while (all.size > MAX_ITEMS) all.removeAt(0)
        val a = JSONArray()
        all.forEach { a.put(it.toJson()) }
        runCatching { save(a.toString()) }
    }

    @Synchronized
    fun scores(network: String?): List<Score> {
        val now = clock()
        return list().filter { it.network == network && now - it.at <= TTL_MS }.groupBy { it.ip }.map { (ip, ms) ->
            val lat = ms.filter { it.success }.mapNotNull { it.latencyMs }.sorted()
            Score(ip, ms.size, ms.count { it.success }, lat.getOrNull(lat.size / 2), ms.filter { it.success }.maxOfOrNull { it.at })
        }
    }

    /**
     * Addresses that carried a request on [network] within [VALID_FOR_MS] and succeed at least [MIN_RATE]
     * of the time, best first (success rate, then median latency, then address for a stable order).
     */
    fun validated(network: String?, limit: Int = 4): List<String> {
        val now = clock()
        return scores(network)
            .filter { s -> s.lastSuccessAt != null && now - s.lastSuccessAt <= VALID_FOR_MS && s.rate >= MIN_RATE }
            .sortedWith(compareBy<Score>({ -it.rate }, { it.medianMs ?: Long.MAX_VALUE }, { it.ip }))
            .take(limit)
            .map { it.ip }
    }

    companion object {
        const val TTL_MS = 3L * 24 * 60 * 60 * 1000
        const val VALID_FOR_MS = 24L * 60 * 60 * 1000
        const val MIN_RATE = 0.5
        const val MAX_ITEMS = 600
    }
}
