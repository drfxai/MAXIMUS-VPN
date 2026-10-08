package com.example.vpn.connectivity

import com.example.data.model.VlessProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 11: versioned, expiring, bounded intelligence rules that only nudge eligible candidates. */
class IranIntelligenceTest {
    private val now = 1_800_000_000_000L
    private val cdn = VlessProfile(id = "c", name = "c", address = "104.16.0.1", port = 443, uuid = "u", security = "tls",
        transport = "ws", sni = "a.example.com")
    private val reality = VlessProfile(id = "r", name = "r", address = "5.6.7.8", port = 443, uuid = "u", security = "reality",
        transport = "tcp", sni = "www.example.com")

    private fun rules(vararg r: String) = IranIntelligence.parse("""{"rules":[${r.joinToString(",")}]}""", now)
    private fun rule(id: String, adj: Double, conf: Double = 1.0, extra: String = "", version: Int = 1, issued: Long = now - 1000, ttlHours: Int = 24) =
        """{"id":"$id","version":$version,"issued":$issued,"ttlHours":$ttlHours,"confidence":$conf,"adjustment":$adj$extra}"""

    @Test fun aRuleNudgesOnlyWhatItMatchesScaledByConfidence() {
        val intel = rules(rule("cdn-mci", -4.0, 0.5, ""","network":"cell:*","kind":"CDN""""))
        assertEquals(-2.0, intel.adjustmentFor(cdn, "cell:43211", now).value, 1e-9)
        assertEquals(0.0, intel.adjustmentFor(cdn, "wifi", now).value, 1e-9)
        assertEquals(0.0, intel.adjustmentFor(reality, "cell:43211", now).value, 1e-9)
        assertEquals(listOf("cdn-mci@v1"), intel.adjustmentFor(cdn, "cell:43211", now).ruleRefs)
    }

    @Test fun theTotalIsBoundedAndIneligibleCandidatesGetNothing() {
        val intel = rules(rule("a", 50.0), rule("b", 5.0), rule("c", 5.0))
        assertEquals(ConnectionScore.MAX_ADJUSTMENT, intel.adjustmentFor(reality, "wifi", now).value, 1e-9)
        assertEquals(0.0, intel.adjustmentFor(reality, "wifi", now, eligible = false).value, 1e-9)
        // Intelligence cannot rescue a rejected candidate in the score either.
        val rejected = ConnectionScore.of(ConnectionScore.Inputs(securityProblem = "allowInsecure", boundedAdjustment = 5.0), now)
        assertTrue(!rejected.eligible && rejected.score == 0.0)
    }

    @Test fun rulesExpireAndTheNewestVersionWins() {
        val intel = rules(rule("x", 2.0, version = 1), rule("x", -3.0, version = 2), rule("old", 4.0, issued = now - 50L * 3600_000, ttlHours = 24))
        assertEquals(-3.0, intel.adjustmentFor(reality, "wifi", now).value, 1e-9)
        assertEquals(0.0, intel.adjustmentFor(reality, "wifi", now + 25L * 3600_000).value, 1e-9)
    }

    @Test fun malformedFutureOrUnreadableConditionsAreDropped() {
        val intel = rules(
            rule("future", 3.0, issued = now + 3L * 24 * 3600_000),
            rule("bad id!", 3.0),
            rule("badfamily", 3.0, extra = ""","family":"ipv5""""),
            """{"id":"noconf","issued":${now - 1},"ttlHours":1,"adjustment":3}""",
            rule("ok", 1.0)
        )
        assertEquals(listOf("ok"), intel.rules.map { it.id })
        assertEquals(0, IranIntelligence.parse("not json", now).rules.size)
        // TTL is capped at 30 days.
        val long = rules(rule("long", 1.0, ttlHours = 24 * 365))
        assertEquals(IranIntelligence.MAX_TTL_MS, long.rules.single().ttlMs)
    }

    @Test fun theStoreKeepsTheLastVerifiedRules() {
        var saved: String? = null
        val store = IranIntelligence.Store({ saved }, { saved = it }, { now })
        assertEquals(0, store.current().rules.size)
        store.replace("""{"rules":[${rule("keep", 1.0)}]}""")
        assertEquals(listOf("keep"), IranIntelligence.Store({ saved }, { saved = it }, { now }).current().rules.map { it.id })
    }
}
