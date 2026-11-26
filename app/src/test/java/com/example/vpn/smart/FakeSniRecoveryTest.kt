package com.example.vpn.smart

import com.example.data.model.VlessProfile
import com.example.vpn.connectivity.NetworkFirewalls.Firewall
import com.example.vpn.engine.ProfileExtras
import com.example.vpn.lab.AdaptivePlanner
import com.example.vpn.lab.ExperimentPlanner
import com.example.vpn.lab.PathFamily
import com.example.vpn.sidecar.ByedpiSidecar
import com.example.vpn.sidecar.Md5SigSupport
import org.junit.Assert.*
import org.junit.Test

class FakeSniRecoveryTest {
    private fun source() = VlessProfile(name = "saved", address = "origin.example", port = 443,
        uuid = "existing-credential", security = "tls", sni = "origin.example", transport = "ws", host = "origin.example")

    @Test fun resolvesEndpointButPreservesTlsIdentityAndCredentials() {
        val p = source()
        val variants = FakeSniRecovery.candidates(listOf(p), { it.copy(address = "1.1.1.1") })
        assertEquals(3, variants.size)
        assertEquals(variants.size, variants.map { it.id }.distinct().size)
        variants.forEach {
            assertEquals(p.uuid, it.uuid); assertEquals(p.sni, it.sni); assertEquals(p.host, it.host)
            assertEquals(p.port, it.port); assertTrue(ByedpiSidecar.handles(it))
            assertNull(it.lastTestedTimestamp); assertNull(ByedpiSidecar.problem(it))
            assertTrue(FakeSniRecovery.isCandidate(it))
            assertFalse(it.allowInsecure)
        }
        assertFalse(ByedpiSidecar.handles(p))
        assertFalse(FakeSniRecovery.isCandidate(p))
    }

    @Test fun insecurePrivateAndAlreadyWrappedProfilesAreExcluded() {
        assertTrue(FakeSniRecovery.candidates(listOf(source().copy(allowInsecure = true)), { error("must not resolve") }).isEmpty())
        assertTrue(FakeSniRecovery.candidates(listOf(source()), { it.copy(address = "127.0.0.1") }).isEmpty())
        val wrapped = ByedpiSidecar.enable(source().copy(address = "1.1.1.1"), "www.microsoft.com", 4)
        assertTrue(FakeSniRecovery.candidates(listOf(wrapped), { error("must not resolve") }).isEmpty())
    }

    @Test fun originsAndVariantsAreBoundedByFirewall() {
        val inputs = (1..8).map { source().copy(address = "origin$it.example") }
        fun count(fw: Firewall): Pair<Int, Int> {
            var resolved = 0
            val n = FakeSniRecovery.candidates(inputs, { resolved++; it.copy(address = "1.1.1.1") }, fw).size
            return n to resolved
        }
        assertEquals(6 to 2, count(Firewall.OTHER))
        assertEquals(12 to 3, count(Firewall.IRANCELL))
        assertEquals(1 to 1, count(Firewall.MCI))
        assertEquals(6 to 2, count(Firewall.RIGHTEL))
    }

    @Test fun firstSearchUsesWhitelistDecoysAndTheLowestRungs() {
        val plan = FakeSniRecovery.plan(FakeSniMemory.State(), md5sigSupported = false, count = 4)
        assertEquals(listOf(2, 3, 4, 5), plan.map { it.ttl })
        assertEquals(FakeSniRecovery.DECOYS, plan.map { it.decoy })
        assertEquals("auth.vercel.com", plan.first().decoy)
        assertTrue(plan.none { it.md5sig })
        val decoys = FakeSniRecovery.candidates(listOf(source()), { it.copy(address = "1.1.1.1") }, Firewall.IRANCELL)
            .map { ProfileExtras.read(it).getString("fakeSni") }
        assertEquals(listOf("auth.vercel.com", "security.vercel.com", "www.hcaptcha.com", "www.microsoft.com"), decoys)
    }

    @Test fun ladderCoversEveryTtlAndDecoyPairOnce() {
        val all = (0 until FakeSniRecovery.LADDER_SIZE).map(FakeSniRecovery::ladderStep)
        assertEquals(FakeSniRecovery.LADDER_SIZE, all.toSet().size)
        assertEquals(FakeSniRecovery.TTL_LADDER, all.take(FakeSniRecovery.TTL_LADDER.size).map { it.ttl })
        assertEquals(FakeSniRecovery.ladderStep(0), FakeSniRecovery.ladderStep(FakeSniRecovery.LADDER_SIZE))
        assertTrue(all.all { it.ttl in 2..12 && it.decoy in FakeSniRecovery.DECOYS })
        // Each pass shifts the decoys, so rung 0 gets a different decoy every pass.
        assertEquals(4, (0 until 4).map { FakeSniRecovery.ladderStep(it * FakeSniRecovery.TTL_LADDER.size).decoy }.toSet().size)
    }

    @Test fun md5CandidateOnlyWhenTheKernelSupportsIt() {
        val without = FakeSniRecovery.plan(FakeSniMemory.State(), md5sigSupported = false, count = 3)
        assertTrue(without.none { it.md5sig })
        val with = FakeSniRecovery.plan(FakeSniMemory.State(), md5sigSupported = true, count = 3)
        assertEquals(FakeSniRecovery.Variant("auth.vercel.com", FakeSniRecovery.MD5SIG_TTL, true), with.first())
        val profile = FakeSniRecovery.candidates(listOf(source()), { it.copy(address = "1.1.1.1") }, md5sigSupported = true).first()
        assertTrue(ProfileExtras.read(profile).getBoolean(ByedpiSidecar.KEY_MD5SIG))
        assertEquals(with.first(), FakeSniRecovery.variantOf(profile))
        // A decoy whose MD5 test failed here is skipped; the next one is tried.
        val failed = FakeSniMemory.State(md5Failed = mapOf("auth.vercel.com" to 1L))
        assertEquals("security.vercel.com", FakeSniRecovery.plan(failed, true, 1).single().decoy)
        val allFailed = FakeSniMemory.State(md5Failed = FakeSniRecovery.DECOYS.associateWith { 1L })
        assertTrue(FakeSniRecovery.plan(allFailed, true, 2).none { it.md5sig })
    }

    @Test fun md5ProbeClassification() {
        assertTrue(Md5SigSupport.classify(Md5SigSupport.EINVAL))
        assertTrue(Md5SigSupport.classify(null))
        assertFalse(Md5SigSupport.classify(Md5SigSupport.ENOPROTOOPT))
        assertFalse(Md5SigSupport.classify(13))
        val original = Md5SigSupport.probe
        try {
            Md5SigSupport.resetForTest()
            Md5SigSupport.probe = { throw IllegalStateException("no Os") }
            assertFalse(Md5SigSupport.supported())
            Md5SigSupport.resetForTest()
            Md5SigSupport.probe = { Md5SigSupport.EINVAL }
            assertTrue(Md5SigSupport.supported())
        } finally { Md5SigSupport.probe = original; Md5SigSupport.resetForTest() }
    }

    @Test fun rememberedWinnerGoesFirstAndLadderResumesWhereItStopped() {
        val now = 10_000_000L
        var s = FakeSniMemory.State()
        // First search: rungs 0..2 fail.
        FakeSniRecovery.plan(s, false, 3).forEach { s = FakeSniMemory.after(s, it, passed = false, now = now) }
        assertEquals(3, s.cursor)
        val next = FakeSniRecovery.plan(s, false, 2)
        assertEquals(listOf(FakeSniRecovery.ladderStep(3), FakeSniRecovery.ladderStep(4)), next)
        // Rung 3 (TTL 5) carries traffic: it becomes the remembered winner and goes first next time.
        s = FakeSniMemory.after(s, next[0], passed = true, now = now)
        assertEquals(5, s.ttlWinner!!.ttl)
        assertEquals(next[0], FakeSniRecovery.plan(s, false, 3).first())
        // A higher TTL passing later does not replace the lower winner; a lower one does.
        s = FakeSniMemory.after(s, FakeSniRecovery.Variant("www.hcaptcha.com", 10), passed = true, now = now)
        assertEquals(5, s.ttlWinner!!.ttl)
        s = FakeSniMemory.after(s, FakeSniRecovery.Variant("www.hcaptcha.com", 3), passed = true, now = now)
        assertEquals(3, s.ttlWinner!!.ttl)
        // The winner failing a real request is forgotten.
        s = FakeSniMemory.after(s, FakeSniRecovery.Variant("www.hcaptcha.com", 3), passed = false, now = now)
        assertNull(s.ttlWinner)
    }

    @Test fun memoryExpiresAfterThreeDaysAndSurvivesJson() {
        val t0 = 1_000_000L
        var s = FakeSniMemory.after(FakeSniMemory.State(), FakeSniRecovery.ladderStep(0), passed = true, now = t0)
        s = FakeSniMemory.after(s, FakeSniRecovery.Variant("auth.vercel.com", FakeSniRecovery.MD5SIG_TTL, true), passed = true, now = t0)
        val parsed = FakeSniMemory.State.parse(s.toJson())
        assertEquals(s, parsed)
        assertEquals(2, parsed.ttlWinner!!.ttl)
        assertEquals("auth.vercel.com", parsed.md5Winner)
        val later = parsed.live(t0 + FakeSniMemory.MEMORY_MS + 1)
        assertNull(later.ttlWinner); assertNull(later.md5Winner); assertEquals(0, later.cursor)
        assertEquals(FakeSniMemory.State(), FakeSniMemory.State.parse("not json"))
    }

    @Test fun memoryIsKeptPerNetwork() {
        val store = mutableMapOf<String, String?>()
        val oldRead = FakeSniMemory.read; val oldWrite = FakeSniMemory.write
        try {
            FakeSniMemory.resetForTest()
            FakeSniMemory.read = { store[it] }
            FakeSniMemory.write = { k, v -> store[k] = v }
            FakeSniMemory.record("cell:43235", FakeSniRecovery.Variant("auth.vercel.com", 4), passed = true)
            assertEquals(4, FakeSniMemory.state("cell:43235").ttlWinner!!.ttl)
            assertNull(FakeSniMemory.state("cell:43211").ttlWinner)
            FakeSniMemory.resetForTest()
            assertEquals(4, FakeSniMemory.state("cell:43235").ttlWinner!!.ttl)
            assertNull(FakeSniMemory.state(null).ttlWinner)
        } finally {
            FakeSniMemory.read = oldRead; FakeSniMemory.write = oldWrite; FakeSniMemory.resetForTest()
        }
    }

    @Test fun searchBudgetBoundsFakeSniTime() {
        val b = FakeSniRecovery.Budget()
        assertEquals(FakeSniRecovery.TEST_WINDOW_MS, b.window(90_000L))
        assertEquals(5_000L, b.window(5_000L))
        b.charge(15_000L); b.charge(12_000L)
        assertEquals(3_000L, b.window(90_000L))
        b.charge(2_000L)
        assertNull(b.window(90_000L))
    }

    @Test fun firewallOrdersFakeSniEarlierOnIrancellAndLastOnMci() {
        val plan = ExperimentPlanner.Plan(ExperimentPlanner.Mode.ORDINARY, emptyList(), emptyMap(), 20, emptyList())
        fun first(fw: Firewall): String {
            val p = AdaptivePlanner(plan, null, device = AdaptivePlanner.Device(metered = true), goal = AdaptivePlanner.Goal.CONNECT)
            p.add(listOf(
                AdaptivePlanner.Candidate("psiphon", "psiphon", PathFamily.PSIPHON),
                AdaptivePlanner.Candidate("tor", "tor", PathFamily.TOR_WEBTUNNEL),
                AdaptivePlanner.Candidate("fake", "fake", PathFamily.ENGINE_CHAIN, firewallFactor = FakeSniRecovery.firewallFactor(fw))
            ))
            return (p.decide(0) as AdaptivePlanner.Decision.Test).candidate.id
        }
        assertEquals("fake", first(Firewall.IRANCELL))
        assertEquals("psiphon", first(Firewall.OTHER))
        val mci = AdaptivePlanner(plan, null, device = AdaptivePlanner.Device(metered = true), goal = AdaptivePlanner.Goal.CONNECT)
        mci.add(listOf(
            AdaptivePlanner.Candidate("fake", "fake", PathFamily.ENGINE_CHAIN, firewallFactor = FakeSniRecovery.firewallFactor(Firewall.MCI)),
            AdaptivePlanner.Candidate("tor", "tor", PathFamily.TOR_WEBTUNNEL)
        ))
        val order = mutableListOf<String>()
        repeat(2) {
            val d = mci.decide(it.toLong()) as AdaptivePlanner.Decision.Test
            order += d.candidate.id
            mci.record(d.candidate.id, listOf(false), null, it.toLong())
        }
        assertEquals(listOf("tor", "fake"), order)
    }
}
