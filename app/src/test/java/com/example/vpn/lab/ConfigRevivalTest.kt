package com.example.vpn.lab

import com.example.data.model.VlessProfile
import com.example.panels.BpbFix
import com.example.vpn.connectivity.BpbRecoveryEngine
import com.example.vpn.connectivity.DerivedRecoveryCandidate.TestResult
import com.example.vpn.connectivity.RecoveryLedger
import com.example.vpn.connectivity.RecoveryProfiles
import com.example.vpn.connectivity.RecoverySecurityGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** LAB "Revive configs": dead Cloudflare configs come back through a recorded recipe; saved configs never change. */
class ConfigRevivalTest {
    private fun cf(id: String, host: String, fp: String = "fp-$id") = VlessProfile(
        id = id, name = "CF $id", address = host, port = 443,
        uuid = "11111111-1111-1111-1111-111111111111", transport = "ws", security = "tls",
        sni = host, host = host, path = "/vl?ed=2560", fingerprint = "chrome", canonicalFingerprint = fp
    )

    private var now = RecoveryProfiles.REVIEWED_AT + 1_000
    private var saved: String? = null
    private val ledger = RecoveryLedger({ saved }, { saved = it })
    private val engine = BpbRecoveryEngine(ledger = ledger, clock = { now }, maxCandidates = ConfigRevival.RECIPES_PER_CONFIG)
    private val net = "cell:43235"
    private val batches = mutableListOf<List<VlessProfile>>()

    /** A network where only [works] gets a request through; [alive] configs work as they are. */
    private fun revival(alive: Set<String> = emptySet(), works: (VlessProfile) -> Boolean = { false }) =
        ConfigRevival(engine, probe = { ps ->
            batches += ps
            assertTrue("a probe batch holds at most ${ConfigRevival.BATCH}", ps.size <= ConfigRevival.BATCH)
            ps.map { p ->
                val plain = p.echConfigList.isBlank() && p.finalMask.isBlank() && p.fingerprint == "chrome" && p.alpn.isBlank()
                when {
                    plain && p.id in alive -> TestResult.Passed(120)
                    !plain && works(p) -> TestResult.Passed(300)
                    else -> TestResult.Failed("timeout")
                }
            }
        })

    @Test fun cloudflareEchRevivesADeadWorkerAndTheVpnUsesItNextTime() {
        val dead = cf("w1", "edge.example.workers.dev")
        val r = revival(works = { it.echConfigList.startsWith("cloudflare-ech.com+https://") && it.fingerprint == "chrome" })
        val results = r.run(listOf(dead), net)
        val only = results.single()
        assertEquals(ConfigRevival.Outcome.REVIVED, only.outcome)
        assertTrue(only.recipe!!.startsWith("ech-cloudflare"))
        assertTrue(only.detail.startsWith("ECH via Cloudflare"))
        // The recorded copy is what the VPN tries first on this network; the saved config is unchanged.
        val active = engine.active(dead, net)
        assertNotNull(active)
        assertEquals("cloudflare-ech.com+https://1.1.1.1/dns-query", active!!.profile.echConfigList)
        assertEquals(dead.sni, active.profile.sni)
        assertEquals(dead.uuid, active.profile.uuid)
        assertFalse(active.profile.allowInsecure)
        assertEquals("", dead.echConfigList)
        assertNull("a different network has nothing recorded", engine.active(dead, "wifi:home"))
    }

    @Test fun fragmentV1RevivesWhenOnlyItGetsThrough() {
        val dead = cf("w2", "pages.example.pages.dev")
        val results = revival(works = { it.finalMask == BpbFix.FINAL_MASK_V1 }).run(listOf(dead), net)
        assertEquals(ConfigRevival.Outcome.REVIVED, results.single().outcome)
        assertEquals("bpb-fragment-v1@v1", results.single().recipe)
        assertEquals("Fragment v1 + Go TLS", results.single().detail.substringBefore(" ·"))
    }

    @Test fun workingConfigsAreLeftAloneAndDeadServersAreReportedHonestly() {
        val ok = cf("a", "ok.example.workers.dev")
        val gone = cf("b", "gone.example.workers.dev")
        val results = revival(alive = setOf("a")).run(listOf(gone, ok), net)
        assertEquals(listOf(ConfigRevival.Outcome.ALREADY_WORKING, ConfigRevival.Outcome.NOT_REVIVED), results.map { it.outcome })
        assertEquals(0, results.first().tried)
        assertTrue(results.last().tried in 1..ConfigRevival.RECIPES_PER_CONFIG)
        assertTrue(results.last().detail.contains("server"))
        assertNull(engine.active(gone, net))
        // One batch for the plain tests, then at most two batches of recipes for the dead config.
        assertTrue(batches.size <= 3)
    }

    @Test fun onlyCloudflareStyleTlsConfigsAreEligibleAndTheRunIsCapped() {
        val reality = cf("r", "r.example.com").copy(security = "reality", transport = "tcp")
        val plainWs = cf("p", "p.example.com").copy(security = "none")
        val insecure = cf("i", "i.example.com").copy(allowInsecure = true)
        val many = (1..20).map { cf("m$it", "m$it.example.workers.dev") }
        val r = revival()
        assertTrue(r.eligible(listOf(reality, plainWs, insecure)).isEmpty())
        assertEquals(20, r.eligibleCount(many + reality))
        assertEquals(ConfigRevival.MAX_CONFIGS, r.eligible(many).size)
        assertTrue(r.run(listOf(reality), net).isEmpty())
    }

    @Test fun vpnOnMeansNothingIsTestedOrRecorded() {
        val dead = cf("w3", "x.example.workers.dev")
        val r = ConfigRevival(engine, probe = { ps -> ps.map { TestResult.NotRun("the VPN's Xray core is running") } })
        val res = r.run(listOf(dead), net).single()
        assertEquals(ConfigRevival.Outcome.NOT_TESTED, res.outcome)
        assertTrue(ledger.entries().isEmpty())
    }

    @Test fun stoppingSkipsTheRemainingConfigs() {
        var calls = 0
        val r = revival()
        val res = r.run(listOf(cf("s1", "s1.example.workers.dev"), cf("s2", "s2.example.workers.dev")), net, cancelled = { calls++ > 0 })
        assertEquals(ConfigRevival.Outcome.NOT_TESTED, res.last().outcome)
    }

    @Test fun gateAcceptsCloudflareEchAndV1ButNothingThatWeakensTls() {
        val p = cf("g", "g.example.workers.dev")
        assertTrue(RecoverySecurityGate.check(p, p.copy(echConfigList = "cloudflare-ech.com+https://1.1.1.1/dns-query", fingerprint = "chrome")).passed)
        assertTrue(RecoverySecurityGate.check(p, p.copy(finalMask = BpbFix.FINAL_MASK_V1)).passed)
        // Another site's key, a plain-DNS lookup or a host-name resolver stay refused.
        assertFalse(RecoverySecurityGate.check(p, p.copy(echConfigList = "evil.example+https://1.1.1.1/dns-query")).passed)
        assertFalse(RecoverySecurityGate.check(p, p.copy(echConfigList = "cloudflare-ech.com+udp://1.1.1.1")).passed)
        assertFalse(RecoverySecurityGate.check(p, p.copy(echConfigList = "cloudflare-ech.com+https://dns.google/dns-query")).passed)
        assertFalse(RecoverySecurityGate.check(p, p.copy(echConfigList = "cloudflare-ech.com+https://1.1.1.1/dns-query", allowInsecure = true)).passed)
    }

    @Test fun echRecipesSkipConfigsWithoutAServerName() {
        val byIp = cf("ip", "104.16.1.1").copy(sni = "", host = "")
        RecoveryProfiles.BUILT_IN.filter { it.profileId.startsWith("ech") }.forEach { assertNull(it.derive(byIp)) }
        val ownEch = cf("o", "o.example.workers.dev").copy(echConfigList = "o.example.workers.dev+https://1.1.1.1/dns-query")
        RecoveryProfiles.BUILT_IN.filter { it.profileId.startsWith("ech") }.forEach { assertNull(it.derive(ownEch)) }
    }
}
