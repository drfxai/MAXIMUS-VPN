package com.example.vpn.smart

import com.example.data.model.VlessProfile
import org.json.JSONObject

/**
 * Per-network memory of the ~16 KB freeze, keyed by [NetworkKey] ("cell:43235", "wifi"): which saved servers
 * passed the upload/download check here (last-known-good) and which froze here. Smart Connect tests the
 * last-good ones first and puts the frozen ones at the back while their [FROZEN_COOLDOWN_MS] runs, so a server
 * the censor freezes on this carrier does not win the race again on one quick request.
 *
 * Idea adapted from UAC SNI Spoofer's AdaptiveProfileStore (per-network best/backup routes with a TTL) and
 * MciRouteSelector (cooling-down entries go last); Floxu1/UAC-SNI-Spoofer-Android, see THIRD_PARTY_NOTICES.md.
 *
 * Entries expire after [TTL_MS]; bounded per network and in networks. Only server ids are kept.
 */
class FreezeBook(
    private val load: () -> String?,
    private val save: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) {
    /** [rescue] names the same-server rescue that made the transfer check pass ("deep-fragment", "xhttp"), if any. */
    data class Good(val at: Long, val rescue: String? = null)

    private class Entry(val good: LinkedHashMap<String, Good> = linkedMapOf(), val frozen: LinkedHashMap<String, Long> = linkedMapOf())

    private val entries: LinkedHashMap<String, Entry> by lazy { parse(load()) }

    /** [profileId] passed the upload/download check on [network]: last-known-good, and no longer frozen here. */
    @Synchronized
    fun recordGood(network: String, profileId: String, rescue: String? = null) {
        val e = entries.getOrPut(network) { Entry() }
        e.frozen.remove(profileId)
        e.good.remove(profileId)
        e.good[profileId] = Good(clock(), rescue)
        while (e.good.size > MAX_PER_NETWORK) e.good.remove(e.good.keys.first())
        trimAndPersist()
    }

    /** [profileId] showed the ~16 KB freeze on [network]: deferred here, and not last-good any more. */
    @Synchronized
    fun recordFrozen(network: String, profileId: String) {
        val e = entries.getOrPut(network) { Entry() }
        e.good.remove(profileId)
        e.frozen.remove(profileId)
        e.frozen[profileId] = clock()
        while (e.frozen.size > MAX_PER_NETWORK) e.frozen.remove(e.frozen.keys.first())
        trimAndPersist()
    }

    /**
     * [profileId]'s bulk download stalled on [network] outside the ~16 KB window while small requests passed:
     * kept with the frozen servers, so failover leaves it out here and Smart Connect tests it last while
     * [FROZEN_COOLDOWN_MS] runs.
     */
    @Synchronized
    fun recordStalled(network: String, profileId: String) = recordFrozen(network, profileId)

    /** Last-known-good servers on [network], most recent first. */
    @Synchronized
    fun lastGood(network: String): List<String> {
        val now = clock()
        return entries[network]?.good?.entries.orEmpty()
            .filter { now - it.value.at in 0 until TTL_MS }
            .sortedByDescending { it.value.at }.map { it.key }
    }

    @Synchronized
    fun good(network: String, profileId: String): Good? =
        entries[network]?.good?.get(profileId)?.takeIf { clock() - it.at in 0 until TTL_MS }

    /** Servers that froze on [network] and are still cooling down. */
    @Synchronized
    fun frozen(network: String): Set<String> {
        val now = clock()
        return entries[network]?.frozen?.filterValues { now - it in 0 until FROZEN_COOLDOWN_MS }?.keys.orEmpty().toSet()
    }

    @Synchronized
    fun clear() {
        entries.clear()
        persist()
    }

    private fun trimAndPersist() {
        val now = clock()
        entries.values.forEach { e ->
            e.good.entries.removeAll { now - it.value.at !in 0 until TTL_MS }
            e.frozen.entries.removeAll { now - it.value !in 0 until TTL_MS }
        }
        entries.entries.removeAll { it.value.good.isEmpty() && it.value.frozen.isEmpty() }
        fun latest(e: Entry) = maxOf(e.good.values.maxOfOrNull { it.at } ?: 0L, e.frozen.values.maxOrNull() ?: 0L)
        entries.entries.sortedByDescending { latest(it.value) }.drop(MAX_NETWORKS).forEach { entries.remove(it.key) }
        persist()
    }

    private fun persist() {
        val n = JSONObject()
        entries.forEach { (network, e) ->
            val good = JSONObject()
            e.good.forEach { (id, g) -> good.put(id, JSONObject().put("at", g.at).apply { g.rescue?.let { put("r", it) } }) }
            val frozen = JSONObject()
            e.frozen.forEach { (id, at) -> frozen.put(id, at) }
            n.put(network, JSONObject().put("good", good).put("frozen", frozen))
        }
        save(JSONObject().put("schema", SCHEMA).put("networks", n).toString())
    }

    /** The order Smart Connect tests in, and what moved (for the `smart · order` log line). */
    data class Ordering(val profiles: List<VlessProfile>, val lastGoodFirst: Int, val frozenDeferred: Int) {
        fun describe(): String = "smart · order · last-good first: $lastGoodFirst, frozen deferred: $frozenDeferred"
    }

    companion object {
        /** About three days, like UAC's per-network memory but shorter: carriers change their filters often. */
        const val TTL_MS = 3 * 24 * 60 * 60 * 1000L
        /** A server that froze here stays at the back this long; after it, it competes normally again. */
        const val FROZEN_COOLDOWN_MS = 12 * 60 * 60 * 1000L
        private const val MAX_PER_NETWORK = 40
        private const val MAX_NETWORKS = 8
        private const val SCHEMA = 1

        /**
         * [ranked] (Smart Connect's own order) with the [lastGood] servers that are eligible here moved to the
         * front (in [lastGood] order) and every server in [frozen] moved to the back, keeping relative order.
         * [eligibleLastGood] are the last-good servers that passed the race's own eligibility (they may have
         * been cut from [ranked] by its size limit); a frozen server is never counted as last-good.
         */
        fun order(
            ranked: List<VlessProfile>,
            lastGood: List<String>,
            frozen: Set<String>,
            eligibleLastGood: List<VlessProfile> = emptyList()
        ): Ordering {
            val byId = (ranked + eligibleLastGood).associateBy { it.id }
            val head = lastGood.filter { it !in frozen }.mapNotNull { byId[it] }.distinctBy { it.id }
            val headIds = head.map { it.id }.toSet()
            val middle = ranked.filter { it.id !in headIds && it.id !in frozen }
            val tail = ranked.filter { it.id in frozen && it.id !in headIds }
            return Ordering(head + middle + tail, head.size, tail.size)
        }

        private fun parse(text: String?): LinkedHashMap<String, Entry> {
            val out = linkedMapOf<String, Entry>()
            val root = runCatching { JSONObject(text ?: return out) }.getOrNull() ?: return out
            if (root.optInt("schema") != SCHEMA) return out
            val networks = root.optJSONObject("networks") ?: return out
            networks.keys().forEach { network ->
                val o = networks.optJSONObject(network) ?: return@forEach
                val e = Entry()
                o.optJSONObject("good")?.let { g ->
                    g.keys().forEach { id ->
                        val v = g.optJSONObject(id) ?: return@forEach
                        e.good[id] = Good(v.optLong("at"), v.optString("r").takeIf { it.isNotEmpty() })
                    }
                }
                o.optJSONObject("frozen")?.let { f -> f.keys().forEach { id -> e.frozen[id] = f.optLong(id) } }
                out[network] = e
            }
            return out
        }
    }
}
