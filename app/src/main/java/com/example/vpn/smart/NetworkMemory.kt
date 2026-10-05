package com.example.vpn.smart

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * What worked on each network the phone has used: every carrier (Irancell, Hamrah-e Aval, Rightel...)
 * and Wi-Fi filters differently, so what one network taught is not applied to another.
 *
 * Per network it keeps the stealth alternate that last carried traffic for each profile, the kinds of
 * connection that worked (most recent first, used to order a race), and a running average of real
 * request latency, which sets the probe time limits: a fast network gives up on a dead path sooner,
 * a slow one is not cut off before a working path answers.
 *
 * [load] and [save] hold the JSON (SharedPreferences in the app, memory in tests).
 */
class NetworkMemory(private val load: () -> String?, private val save: (String) -> Unit) {
    private class Entry(
        val variants: ConcurrentHashMap<String, String> = ConcurrentHashMap(),
        val kinds: MutableList<String> = mutableListOf(),
        var latencyMs: Long? = null,
        /** Kind -> when it last carried no traffic. */
        val failures: MutableMap<String, Long> = mutableMapOf()
    )

    private val entries: LinkedHashMap<String, Entry> by lazy { parse(load()) }

    /** Profile id -> stealth alternate key that last worked on [network]; changes are kept by [persist]. */
    @Synchronized
    fun variants(network: String): MutableMap<String, String> = entry(network).variants

    /** Kinds of connection (see ConnectionKind) that worked on [network], most recent first. */
    @Synchronized
    fun workingKinds(network: String): List<String> = entries[network]?.kinds?.toList().orEmpty()

    /** Average real-request latency on [network], or null before the first success. */
    @Synchronized
    fun latencyMs(network: String): Long? = entries[network]?.latencyMs

    /** Probe time limits for [network]: first try, then each later round. */
    @Synchronized
    fun timeouts(network: String): Timeouts = timeoutsFor(entries[network]?.latencyMs)

    /**
     * Kinds that carried no traffic on [network] in the last [FAILURE_MEMORY_MS] and have not worked
     * since. A filter that just started rarely lifts within minutes, so a race puts them last.
     */
    @Synchronized
    fun recentFailures(network: String, now: Long = System.currentTimeMillis()): Set<String> =
        entries[network]?.failures?.filterValues { now - it < FAILURE_MEMORY_MS }?.keys.orEmpty()

    @Synchronized
    fun recordFailure(network: String, kind: String, now: Long = System.currentTimeMillis()) {
        entry(network).failures[kind] = now
        persist()
    }

    @Synchronized
    fun recordSuccess(network: String, kind: String, latencyMs: Long) {
        val e = entry(network)
        e.failures.remove(kind)
        e.kinds.remove(kind)
        e.kinds.add(0, kind)
        while (e.kinds.size > MAX_KINDS) e.kinds.removeAt(e.kinds.size - 1)
        e.latencyMs = e.latencyMs?.let { (it * (1 - ALPHA) + latencyMs * ALPHA).toLong() } ?: latencyMs
        persist()
    }

    @Synchronized
    fun persist() {
        val root = JSONObject()
        entries.forEach { (network, e) ->
            root.put(network, JSONObject()
                .put("v", JSONObject(e.variants as Map<*, *>))
                .put("k", JSONArray(e.kinds))
                .put("f", JSONObject(e.failures as Map<*, *>))
                .apply { e.latencyMs?.let { put("l", it) } })
        }
        save(root.toString())
    }

    private fun entry(network: String): Entry {
        entries[network]?.let { e ->
            // Most recently used last, so the oldest network is the one dropped.
            entries.remove(network)
            entries[network] = e
            return e
        }
        val e = Entry()
        entries[network] = e
        while (entries.size > MAX_NETWORKS) entries.remove(entries.keys.first())
        return e
    }

    data class Timeouts(val firstSec: Int, val alternateSec: Int)

    companion object {
        const val MAX_NETWORKS = 8
        const val MAX_KINDS = 6
        private const val ALPHA = 0.3
        const val FAILURE_MEMORY_MS = 30 * 60 * 1000L

        /** Before anything is known: the limits the simulator was tuned with. */
        val DEFAULT = Timeouts(firstSec = 4, alternateSec = 5)

        /**
         * About three times the usual latency plus a second, never under 3 s or over 6 s; later rounds
         * get one second more, since a disguised path (split handshake, junk packets) is slower.
         */
        fun timeoutsFor(latencyMs: Long?): Timeouts {
            latencyMs ?: return DEFAULT
            val first = ceil((latencyMs * 3 + 1000) / 1000.0).toInt().coerceIn(3, 6)
            return Timeouts(first, first + 1)
        }

        private fun parse(text: String?): LinkedHashMap<String, Entry> {
            val result = LinkedHashMap<String, Entry>()
            val root = runCatching { JSONObject(text ?: return result) }.getOrNull() ?: return result
            root.keys().forEach { network ->
                val o = root.optJSONObject(network) ?: return@forEach
                val e = Entry(latencyMs = if (o.has("l")) o.optLong("l") else null)
                o.optJSONObject("v")?.let { v -> v.keys().forEach { id -> e.variants[id] = v.optString(id) } }
                o.optJSONArray("k")?.let { k -> for (i in 0 until k.length()) e.kinds += k.optString(i) }
                o.optJSONObject("f")?.let { f -> f.keys().forEach { kind -> e.failures[kind] = f.optLong(kind) } }
                result[network] = e
            }
            return result
        }
    }
}
