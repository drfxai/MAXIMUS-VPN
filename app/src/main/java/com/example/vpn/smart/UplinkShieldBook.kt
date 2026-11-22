package com.example.vpn.smart

import com.example.vpn.stealth.recordslice.UplinkShield
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
 * - MSS ladder: each result is kept with the MSS it ran at. A "did not help" at one step leaves the next, smaller
 *   step of [UplinkShield.LADDER] (1360 → 1220 → 1140) to try ([nextMss]); only the last step's failure starts the
 *   cooldown. A step that helped is the one used on every connect there ([activeMss]).
 *
 * Carriers change their filters often, so both expire. Only the outcome, its MSS and its time are kept.
 */
class UplinkShieldBook(
    private val load: () -> String?,
    private val save: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) {
    /** [inconclusive] > 0: no verdict yet, that many shield runs in a row ended without one. */
    private data class Result(val helped: Boolean, val at: Long, val inconclusive: Int = 0, val mss: Int = UplinkShield.MSS)

    private val entries: LinkedHashMap<String, Result> by lazy { parse(load()) }

    @Synchronized
    fun record(network: String, helped: Boolean, mss: Int = UplinkShield.MSS) {
        entries.remove(network)
        entries[network] = Result(helped, clock(), mss = mss)
        while (entries.size > MAX_NETWORKS) entries.remove(entries.keys.first())
        persist()
    }

    /**
     * The shield's session on [network] ended before its transfer check finished: not a success. A shield that helped
     * here, or a "did not help" still cooling down, is left as it is (returns 0); otherwise returns the inconclusive
     * runs now counted in a row.
     */
    @Synchronized
    fun recordInconclusive(network: String, mss: Int = UplinkShield.MSS): Int {
        val now = clock()
        val prev = entries[network]
        if (prev != null && prev.helped && now - prev.at in 0 until TTL_MS) return 0
        // A real "did not help" still cooling down is stronger evidence than a run with no verdict: kept.
        // A run at a smaller ladder step than that "did not help" is new evidence and is counted.
        if (prev != null && !prev.helped && prev.inconclusive == 0 && mss >= prev.mss && now - prev.at in 0 until NO_HELP_COOLDOWN_MS) return 0
        val n = if (prev != null && !prev.helped && prev.inconclusive > 0 && now - prev.at in 0 until NO_HELP_COOLDOWN_MS)
            prev.inconclusive + 1 else 1
        entries.remove(network)
        entries[network] = Result(helped = false, at = now, inconclusive = n, mss = mss)
        while (entries.size > MAX_NETWORKS) entries.remove(entries.keys.first())
        persist()
        return n
    }

    /** The shield cleared the stall on [network] recently: use it on every connect there. */
    @Synchronized
    fun active(network: String): Boolean = activeMss(network) != null

    /** The MSS step that cleared the stall on [network] recently, or null. */
    @Synchronized
    fun activeMss(network: String): Int? =
        entries[network]?.takeIf { it.helped && clock() - it.at in 0 until TTL_MS }?.mss

    /**
     * The shield may be tried on [network]: it has not just failed there at the last ladder step, and has not
     * ended [MAX_INCONCLUSIVE] runs in a row without a verdict.
     */
    @Synchronized
    fun mayTry(network: String): Boolean = entries[network]?.let {
        !it.helped && clock() - it.at in 0 until NO_HELP_COOLDOWN_MS &&
            (if (it.inconclusive == 0) UplinkShield.nextStep(it.mss) == null else it.inconclusive >= MAX_INCONCLUSIVE)
    } != true

    /**
     * The MSS to try next on [network]: the step that helped; after a "did not help" still cooling down, the next
     * smaller step; after an inconclusive run, the same step again; otherwise the ladder's first step.
     */
    @Synchronized
    fun nextMss(network: String): Int {
        val e = entries[network] ?: return UplinkShield.MSS
        val age = clock() - e.at
        return when {
            e.helped && age in 0 until TTL_MS -> e.mss
            !e.helped && age in 0 until NO_HELP_COOLDOWN_MS && e.inconclusive == 0 -> UplinkShield.nextStep(e.mss) ?: e.mss
            !e.helped && age in 0 until NO_HELP_COOLDOWN_MS -> e.mss
            else -> UplinkShield.MSS
        }
    }

    /** "helped", "no-help", "inconclusive" or "unknown" on [network], for the log. */
    @Synchronized
    fun describe(network: String): String = when {
        active(network) -> "helped"
        !mayTry(network) -> if ((entries[network]?.inconclusive ?: 0) > 0) "inconclusive" else "no-help"
        (entries[network]?.inconclusive ?: 0) > 0 -> "inconclusive"
        entries[network]?.let { !it.helped && clock() - it.at in 0 until NO_HELP_COOLDOWN_MS } == true ->
            "no-help at ${entries[network]?.mss}, next ${nextMss(network)}"
        else -> "unknown"
    }

    @Synchronized
    fun clear() {
        entries.clear()
        persist()
    }

    private fun persist() {
        val o = JSONObject()
        entries.forEach { (network, r) -> o.put(network, JSONObject().put("h", r.helped).put("at", r.at).put("m", r.mss)
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
                out[n] = Result(r.optBoolean("h"), r.optLong("at"), r.optInt("i", 0),
                    r.optInt("m", UplinkShield.MSS).takeIf { it in UplinkShield.MSS_RANGE } ?: UplinkShield.MSS)
            }
            return out
        }
    }
}
