package com.example.vpn.smart

import org.json.JSONObject

/**
 * Per network ([NetworkKey]: "cell:43235", "wifi"), whether the Uplink Shield
 * ([com.example.vpn.stealth.recordslice.UplinkShield]) cleared the 16 KB upload stall there.
 *
 * - Helped: every connect on that network runs with the shield while [TTL_MS] lasts ([active]).
 * - Did not help: the shield is not tried again there while [NO_HELP_COOLDOWN_MS] runs ([mayTry]).
 * - Inconclusive ([recordInconclusive]): the shield's session ended before its transfer check gave a verdict. Never
 *   counted as help; after [MAX_INCONCLUSIVE] of them in a row (each within [NO_HELP_COOLDOWN_MS] of the last) the
 *   shield is not tried there for [NO_HELP_COOLDOWN_MS] either. Build 151: the shield retry's bulk test was cancelled
 *   when the session ended and nothing was recorded, so the next server on the same network got the shield again.
 *
 * Carriers change their filters often, so both expire. Only the outcome and its time are kept.
 */
class UplinkShieldBook(
    private val load: () -> String?,
    private val save: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) {
    /** [inconclusive] > 0: no verdict yet, that many shield runs in a row ended without one. */
    private data class Result(val helped: Boolean, val at: Long, val inconclusive: Int = 0)

    private val entries: LinkedHashMap<String, Result> by lazy { parse(load()) }

    @Synchronized
    fun record(network: String, helped: Boolean) {
        entries.remove(network)
        entries[network] = Result(helped, clock())
        while (entries.size > MAX_NETWORKS) entries.remove(entries.keys.first())
        persist()
    }

    /**
     * The shield's session on [network] ended before its transfer check finished: not a success. A shield that helped
     * here, or a "did not help" still cooling down, is left as it is (returns 0); otherwise returns the inconclusive
     * runs now counted in a row.
     */
    @Synchronized
    fun recordInconclusive(network: String): Int {
        val now = clock()
        val prev = entries[network]
        if (prev != null && prev.helped && now - prev.at in 0 until TTL_MS) return 0
        // A real "did not help" still cooling down is stronger evidence than a run with no verdict: kept.
        if (prev != null && !prev.helped && prev.inconclusive == 0 && now - prev.at in 0 until NO_HELP_COOLDOWN_MS) return 0
        val n = if (prev != null && !prev.helped && prev.inconclusive > 0 && now - prev.at in 0 until NO_HELP_COOLDOWN_MS)
            prev.inconclusive + 1 else 1
        entries.remove(network)
        entries[network] = Result(helped = false, at = now, inconclusive = n)
        while (entries.size > MAX_NETWORKS) entries.remove(entries.keys.first())
        persist()
        return n
    }

    /** The shield cleared the stall on [network] recently: use it on every connect there. */
    @Synchronized
    fun active(network: String): Boolean =
        entries[network]?.let { it.helped && clock() - it.at in 0 until TTL_MS } == true

    /** The shield may be tried on [network]: it has not just failed to help there. */
    @Synchronized
    fun mayTry(network: String): Boolean = entries[network]?.let {
        !it.helped && clock() - it.at in 0 until NO_HELP_COOLDOWN_MS &&
            (it.inconclusive == 0 || it.inconclusive >= MAX_INCONCLUSIVE)
    } != true

    /** "helped", "no-help", "inconclusive" or "unknown" on [network], for the log. */
    @Synchronized
    fun describe(network: String): String = when {
        active(network) -> "helped"
        !mayTry(network) -> if ((entries[network]?.inconclusive ?: 0) > 0) "inconclusive" else "no-help"
        (entries[network]?.inconclusive ?: 0) > 0 -> "inconclusive"
        else -> "unknown"
    }

    @Synchronized
    fun clear() {
        entries.clear()
        persist()
    }

    private fun persist() {
        val o = JSONObject()
        entries.forEach { (network, r) -> o.put(network, JSONObject().put("h", r.helped).put("at", r.at)
            .apply { if (r.inconclusive > 0) put("i", r.inconclusive) }) }
        save(JSONObject().put("schema", SCHEMA).put("networks", o).toString())
    }

    companion object {
        const val TTL_MS = 3 * 24 * 60 * 60 * 1000L
        const val NO_HELP_COOLDOWN_MS = 12 * 60 * 60 * 1000L
        /** Shield runs in a row that ended with no verdict before the shield is skipped on that network. */
        const val MAX_INCONCLUSIVE = 2
        private const val MAX_NETWORKS = 8
        private const val SCHEMA = 1

        private fun parse(text: String?): LinkedHashMap<String, Result> {
            val out = linkedMapOf<String, Result>()
            val root = runCatching { JSONObject(text ?: return out) }.getOrNull() ?: return out
            if (root.optInt("schema") != SCHEMA) return out
            val networks = root.optJSONObject("networks") ?: return out
            networks.keys().forEach { n ->
                val r = networks.optJSONObject(n) ?: return@forEach
                out[n] = Result(r.optBoolean("h"), r.optLong("at"), r.optInt("i", 0))
            }
            return out
        }
    }
}
