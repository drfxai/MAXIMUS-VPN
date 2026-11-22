package com.example.vpn.lab

import org.json.JSONArray
import org.json.JSONObject

/**
 * Which config and which revival recipe won [ConfigRevival] on each network ([com.example.vpn.smart.NetworkKey]),
 * for [TTL_MS]. The next run on that network tries them first: the configs that came back here lead the pool,
 * and for each config the recipe that won for it (or, failing that, the recipe that won for any config here)
 * leads its recipe order. Nothing is skipped because of it; the gate and the real request still decide.
 *
 * Bounded per network ([MAX_PER_NETWORK]) and in networks ([MAX_NETWORKS]). [load]/[save] hold the JSON
 * (SharedPreferences in the app, memory in tests).
 */
class RevivalWinnerMemory(
    private val load: () -> String? = { null },
    private val save: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis
) {
    data class Win(val config: String, val recipe: String, val at: Long)

    private val entries: MutableMap<String, MutableList<Win>> by lazy { parse(load()) }

    /** [recipe] (a recovery profile key) revived [config] (its effective fingerprint) on [network]. */
    @Synchronized
    fun record(network: String?, config: String, recipe: String) {
        if (network.isNullOrBlank() || config.isBlank() || recipe.isBlank()) return
        val list = entries.getOrPut(network) { mutableListOf() }
        list.removeAll { it.config == config }
        list.add(0, Win(config, recipe, clock()))
        while (list.size > MAX_PER_NETWORK) list.removeAt(list.lastIndex)
        trim()
        persist()
    }

    /** The fresh wins on [network], newest first. */
    @Synchronized
    fun wins(network: String?): List<Win> {
        if (network.isNullOrBlank()) return emptyList()
        val now = clock()
        return entries[network].orEmpty().filter { now - it.at in 0 until TTL_MS }
    }

    /** The recipe that last revived [config] on [network], when fresh. */
    fun recipeFor(network: String?, config: String): String? = wins(network).firstOrNull { it.config == config }?.recipe

    /** Order boost for recipe [key] of [config] on [network]: its own win first, then a win on another config here. */
    fun boost(network: String?, config: String, key: String): Double {
        val base = key.substringBefore('@')
        val ws = wins(network)
        return when {
            ws.any { it.config == config && it.recipe.substringBefore('@') == base } -> OWN_BOOST
            ws.any { it.recipe.substringBefore('@') == base } -> NETWORK_BOOST
            else -> 0.0
        }
    }

    /** [items] with the configs that won on [network] first (newest win first), the rest in their order. */
    fun <T> winnersFirst(network: String?, items: List<T>, config: (T) -> String): List<T> {
        val rank = wins(network).mapIndexed { i, w -> w.config to i }.toMap()
        if (rank.isEmpty()) return items
        return items.withIndex().sortedWith(compareBy({ rank[config(it.value)] ?: Int.MAX_VALUE }, { it.index })).map { it.value }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        persist()
    }

    private fun trim() {
        val now = clock()
        entries.values.forEach { l -> l.removeAll { now - it.at !in 0 until TTL_MS } }
        entries.entries.removeAll { it.value.isEmpty() }
        entries.entries.sortedByDescending { e -> e.value.maxOf { it.at } }.drop(MAX_NETWORKS).forEach { entries.remove(it.key) }
    }

    private fun persist() {
        val n = JSONObject()
        entries.forEach { (network, list) ->
            n.put(network, JSONArray().apply {
                list.forEach { put(JSONObject().put("c", it.config).put("r", it.recipe).put("at", it.at)) }
            })
        }
        save(JSONObject().put("schema", SCHEMA).put("networks", n).toString())
    }

    companion object {
        /** A win is tried first for about a day; networks and filters change. */
        const val TTL_MS = 24 * 60 * 60 * 1000L
        /** Added to a recipe's order score: well above any success rate (0..1) and firewall bias. */
        const val OWN_BOOST = 100.0
        const val NETWORK_BOOST = 50.0
        private const val MAX_PER_NETWORK = 24
        private const val MAX_NETWORKS = 8
        private const val SCHEMA = 1

        private fun parse(text: String?): MutableMap<String, MutableList<Win>> {
            val out = linkedMapOf<String, MutableList<Win>>()
            val root = runCatching { JSONObject(text ?: return out) }.getOrNull() ?: return out
            if (root.optInt("schema") != SCHEMA) return out
            val networks = root.optJSONObject("networks") ?: return out
            networks.keys().forEach { network ->
                val arr = networks.optJSONArray(network) ?: return@forEach
                out[network] = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    Win(o.optString("c"), o.optString("r"), o.optLong("at")).takeIf { it.config.isNotBlank() && it.recipe.isNotBlank() }
                }.toMutableList()
            }
            return out
        }
    }
}
