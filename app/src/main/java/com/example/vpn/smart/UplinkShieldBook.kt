package com.example.vpn.smart

import org.json.JSONObject

/**
 * Per network ([NetworkKey]: "cell:43235", "wifi"), whether the Uplink Shield
 * ([com.example.vpn.stealth.recordslice.UplinkShield]) cleared the 16 KB upload stall there.
 *
 * - Helped: every connect on that network runs with the shield while [TTL_MS] lasts ([active]).
 * - Did not help: the shield is not tried again there while [NO_HELP_COOLDOWN_MS] runs ([mayTry]).
 *
 * Carriers change their filters often, so both expire. Only the outcome and its time are kept.
 */
class UplinkShieldBook(
    private val load: () -> String?,
    private val save: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private data class Result(val helped: Boolean, val at: Long)

    private val entries: LinkedHashMap<String, Result> by lazy { parse(load()) }

    @Synchronized
    fun record(network: String, helped: Boolean) {
        entries.remove(network)
        entries[network] = Result(helped, clock())
        while (entries.size > MAX_NETWORKS) entries.remove(entries.keys.first())
        persist()
    }

    /** The shield cleared the stall on [network] recently: use it on every connect there. */
    @Synchronized
    fun active(network: String): Boolean =
        entries[network]?.let { it.helped && clock() - it.at in 0 until TTL_MS } == true

    /** The shield may be tried on [network]: it has not just failed to help there. */
    @Synchronized
    fun mayTry(network: String): Boolean =
        entries[network]?.let { !it.helped && clock() - it.at in 0 until NO_HELP_COOLDOWN_MS } != true

    /** "helped", "no-help" or "unknown" on [network], for the log. */
    @Synchronized
    fun describe(network: String): String = when {
        active(network) -> "helped"
        !mayTry(network) -> "no-help"
        else -> "unknown"
    }

    @Synchronized
    fun clear() {
        entries.clear()
        persist()
    }

    private fun persist() {
        val o = JSONObject()
        entries.forEach { (network, r) -> o.put(network, JSONObject().put("h", r.helped).put("at", r.at)) }
        save(JSONObject().put("schema", SCHEMA).put("networks", o).toString())
    }

    companion object {
        const val TTL_MS = 3 * 24 * 60 * 60 * 1000L
        const val NO_HELP_COOLDOWN_MS = 12 * 60 * 60 * 1000L
        private const val MAX_NETWORKS = 8
        private const val SCHEMA = 1

        private fun parse(text: String?): LinkedHashMap<String, Result> {
            val out = linkedMapOf<String, Result>()
            val root = runCatching { JSONObject(text ?: return out) }.getOrNull() ?: return out
            if (root.optInt("schema") != SCHEMA) return out
            val networks = root.optJSONObject("networks") ?: return out
            networks.keys().forEach { n ->
                val r = networks.optJSONObject(n) ?: return@forEach
                out[n] = Result(r.optBoolean("h"), r.optLong("at"))
            }
            return out
        }
    }
}
