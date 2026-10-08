package com.example.vpn.connectivity

import com.example.data.model.VlessProfile
import com.example.vpn.stealth.ConnectionKind
import org.json.JSONObject

/**
 * Hints about what currently works on Iranian networks ("CDN paths fail on cell:43211 this week"), as
 * versioned rules with an expiry and a confidence. A rule can only nudge the order candidates are tried
 * in, by at most ±[ConnectionScore.MAX_ADJUSTMENT] in total; it never makes a candidate eligible or
 * ineligible, never changes a config, and carries no commands.
 *
 * Rules are accepted only from `intel.json` named in the free list's signed manifest
 * (FreeConfigList.downloadIntel); nothing reads them from Telegram, a feed or the AI agent.
 */
class IranIntelligence(rules: List<Rule> = emptyList()) {
    data class Rule(
        val id: String,
        val version: Int,
        val issuedAt: Long,
        val ttlMs: Long,
        /** 0..1: how sure the evidence is. The adjustment is scaled by it. */
        val confidence: Double,
        /** -5..+5 before scaling. */
        val adjustment: Double,
        /** A network key ("wifi", "cell:43211"), "cell:*" for any mobile network, or null for any network. */
        val network: String? = null,
        /** ConnectionKind ("CDN", "REALITY", "QUIC", ...), or null for any. */
        val kind: String? = null,
        val transport: String? = null,
        /** "ipv4" / "ipv6", or null for any. */
        val family: String? = null,
        /** true: only CDN-fronted candidates; false: only direct ones; null: both. */
        val cdn: Boolean? = null,
        /** Short human-readable evidence, shown in reasons. */
        val evidence: String = ""
    ) {
        val expiresAt: Long get() = issuedAt + ttlMs
        fun expired(now: Long) = now >= expiresAt

        fun matches(p: VlessProfile, networkKey: String?, traits: DiversitySelector.Traits): Boolean {
            if (network != null) {
                val ok = if (network.endsWith(":*")) networkKey?.startsWith(network.dropLast(1)) == true else network == networkKey
                if (!ok) return false
            }
            if (kind != null && !kind.equals(ConnectionKind.of(p), ignoreCase = true)) return false
            if (transport != null && !transport.equals(p.transport, ignoreCase = true)) return false
            if (family != null && family != traits.family) return false
            if (cdn != null && cdn != traits.cdn) return false
            return true
        }
    }

    data class Adjustment(val value: Double, val ruleRefs: List<String>)

    /** Newest version of each rule id. */
    val rules: List<Rule> = rules.groupBy { it.id }.map { (_, rs) -> rs.maxBy { it.version } }

    /**
     * The bounded adjustment for [p] on [networkKey]: the sum of matching, unexpired rules' adjustment ×
     * confidence, clamped to ±[ConnectionScore.MAX_ADJUSTMENT]. Zero for ineligible candidates
     * ([eligible] false): intelligence never rescues a rejected config.
     */
    fun adjustmentFor(p: VlessProfile, networkKey: String?, now: Long = System.currentTimeMillis(), eligible: Boolean = true,
                      traits: DiversitySelector.Traits = DiversitySelector.traitsOf(p)): Adjustment {
        if (!eligible) return Adjustment(0.0, emptyList())
        val hits = rules.filter { !it.expired(now) && it.matches(p, networkKey, traits) }
        val sum = hits.sumOf { it.adjustment * it.confidence }
        return Adjustment(sum.coerceIn(-ConnectionScore.MAX_ADJUSTMENT, ConnectionScore.MAX_ADJUSTMENT), hits.map { "${it.id}@v${it.version}" })
    }

    /** Keeps the last verified rule file on the phone, so rules survive a restart until they expire. */
    class Store(private val load: () -> String?, private val save: (String) -> Unit, private val clock: () -> Long = System::currentTimeMillis) {
        @Volatile private var cached: IranIntelligence? = null

        fun current(): IranIntelligence = cached ?: parse(runCatching { load() }.getOrNull() ?: "{}", clock()).also { cached = it }

        /** [verifiedJson] must come from FreeConfigList.downloadIntel (signature and hash checked). */
        fun replace(verifiedJson: String) {
            cached = parse(verifiedJson, clock())
            runCatching { save(verifiedJson) }
        }
    }

    companion object {
        const val MAX_RULES = 100
        const val MAX_TTL_MS = 30L * 24 * 60 * 60 * 1000
        /** Clock skew tolerated for a rule's issue time. */
        const val FUTURE_SKEW_MS = 24L * 60 * 60 * 1000

        /**
         * Rules from verified `intel.json` text. Malformed rules are dropped one by one; values are bounded
         * (confidence 0..1, adjustment ±5, TTL at most 30 days); rules issued in the future are dropped.
         */
        fun parse(json: String, now: Long = System.currentTimeMillis()): IranIntelligence {
            val root = runCatching { JSONObject(json) }.getOrNull() ?: return IranIntelligence()
            val arr = root.optJSONArray("rules") ?: return IranIntelligence()
            val out = mutableListOf<Rule>()
            for (i in 0 until minOf(arr.length(), MAX_RULES)) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,64}")) } ?: continue
                val issued = o.optLong("issued", -1).takeIf { it > 0 && it <= now + FUTURE_SKEW_MS } ?: continue
                val ttl = (o.optLong("ttlHours", 0) * 60 * 60 * 1000).takeIf { it > 0 }?.coerceAtMost(MAX_TTL_MS) ?: continue
                val confidence = o.optDouble("confidence", Double.NaN).takeIf { !it.isNaN() }?.coerceIn(0.0, 1.0) ?: continue
                val adjustment = o.optDouble("adjustment", Double.NaN).takeIf { !it.isNaN() }
                    ?.coerceIn(-ConnectionScore.MAX_ADJUSTMENT, ConnectionScore.MAX_ADJUSTMENT) ?: continue
                fun str(k: String) = o.optString(k).trim().takeIf { it.isNotEmpty() && it.length <= 64 }
                // A condition that cannot be read would widen the rule to everything: drop the rule instead.
                if (listOf("network", "kind", "transport", "family").any { o.has(it) && str(it) == null }) continue
                if (o.has("family") && str("family")?.lowercase() !in setOf("ipv4", "ipv6")) continue
                out += Rule(
                    id, o.optInt("version", 1).coerceAtLeast(1), issued, ttl, confidence, adjustment,
                    network = str("network"), kind = str("kind"), transport = str("transport"),
                    family = str("family")?.lowercase(),
                    cdn = if (o.has("cdn")) o.optBoolean("cdn") else null,
                    evidence = o.optString("evidence").take(160)
                )
            }
            return IranIntelligence(out)
        }
    }
}
