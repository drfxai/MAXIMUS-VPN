package com.example.vpn.dnsfirst

import com.example.vpn.dnsfirst.DnsTunnelLadder.Via
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsTunnelLadderTest {
    private val net = "cell:43235"
    private var now = 1_000_000L
    private fun memory(store: Array<String?> = arrayOf(null)) = DnsLadderMemory({ store[0] }, { store[0] = it }, { now })

    private fun caps(ip: String, domain: String, ms: Long = 50, txt: Boolean = true, tcp: Boolean = true, burst: Int = 10, transport: String = "udp") =
        TunnelCaps(ip, domain, transport, reach = true, rttMs = ms, longName = true, txt = txt, tcp = tcp,
            ednsPayload = 1232, burstSent = 10, burstAnswered = burst)

    private val dnstt = DnsTunnelLadder.Spec("p-dnstt", EngineKind.DNSTT, "t.a.com", listOf("1.1.1.1"), "h1")
    private val slip = DnsTunnelLadder.Spec("p-slip", EngineKind.SLIPSTREAM, "s.b.com", listOf("8.8.8.8"), "h2")
    private val cotten = DnsTunnelLadder.Spec("p-cot", EngineKind.COTTEN, "c.d.com", listOf("9.9.9.9"), "h3")

    @Test
    fun dnsttRungsStripeThenSingleThenDohThenAsConfigured() {
        val c = listOf(caps("5.5.5.5", "t.a.com", 40), caps("6.6.6.6", "t.a.com", 80, burst = 7), caps("7.7.7.7", "t.a.com", 120),
            caps("8.8.4.4", "t.a.com", 900), caps("https://free.shecan.ir/dns-query", "t.a.com", 300, transport = "doh"))
        val rungs = DnsTunnelLadder.rungsFor(dnstt, c, memory(), net)
        assertEquals(listOf("stripe-udp", "single-udp", "doh", "as-configured"), rungs.map { it.variant })
        assertEquals(listOf("5.5.5.5", "6.6.6.6", "7.7.7.7"), rungs[0].resolvers)
        assertEquals("the stripe runs at its slowest member's rate", 2, rungs[0].qpsPerResolver)
        assertEquals(listOf("5.5.5.5"), rungs[1].resolvers)
        assertEquals(4, rungs[1].qpsPerResolver)
        assertEquals(Via.DOH, rungs[2].via)
        assertEquals(listOf("1.1.1.1"), rungs[3].resolvers)
    }

    @Test
    fun resolversThatDropTxtAreNotGivenToDnsttButAreToCotten() {
        val c = listOf(caps("5.5.5.5", "t.a.com", txt = false), caps("6.6.6.6", "t.a.com", tcp = false))
        assertEquals(listOf(listOf("6.6.6.6")), DnsTunnelLadder.rungsFor(dnstt, c, memory(), net).filter { it.via == Via.UDP }.map { it.resolvers })
        val cot = DnsTunnelLadder.rungsFor(cotten.copy(domain = "t.a.com"), c, memory(), net)
        assertEquals(listOf("stripe-udp", "single-udp", "tcp", "as-configured"), cot.map { it.variant })
        assertEquals(listOf("5.5.5.5"), cot.first { it.via == Via.TCP }.resolvers)
        assertNull("only dnstt gets a rate", cot.first().qpsPerResolver)
    }

    @Test
    fun rememberedWinnerLeadsAndRepeatedFailuresAreSkipped() {
        val m = memory()
        val c = listOf(caps("5.5.5.5", "t.a.com"), caps("6.6.6.6", "t.a.com"))
        val win = DnsTunnelLadder.Rung("p-dnstt", EngineKind.DNSTT, "single-udp", Via.UDP, listOf("6.6.6.6"), 4)
        m.recordWin(net, "p-dnstt", "h1", win, 300)
        val stripeKey = DnsTunnelLadder.rungsFor(dnstt, c, memory(), net).first().key
        m.recordFailure(net, "p-dnstt", stripeKey); m.recordFailure(net, "p-dnstt", stripeKey)
        val rungs = DnsTunnelLadder.rungsFor(dnstt, c, m, net)
        assertEquals("remembered", rungs.first().variant)
        assertEquals(listOf("6.6.6.6"), rungs.first().resolvers)
        assertTrue("twice-failed stripe skipped", rungs.none { it.key == stripeKey })
        assertTrue("the single variant is the winner's key, kept once", rungs.count { it.key == win.key } == 1)
        // Another network knows nothing of this.
        assertEquals("stripe-udp", DnsTunnelLadder.rungsFor(dnstt, c, m, "wifi:x").first().variant)
        // A changed profile does not reuse the winner.
        assertTrue(DnsTunnelLadder.rungsFor(dnstt.copy(settingsHash = "edited"), c, m, net).none { it.variant == "remembered" })
    }

    @Test
    fun planInterleavesTunnelsAndRespectsTheCap() {
        val caps = mapOf(
            "t.a.com" to listOf(caps("5.5.5.5", "t.a.com"), caps("6.6.6.6", "t.a.com")),
            "s.b.com" to listOf(caps("5.5.5.5", "s.b.com", 30), caps("6.6.6.6", "s.b.com", 30)),
            "c.d.com" to emptyList()
        )
        val plan = DnsTunnelLadder.plan(listOf(dnstt, slip, cotten), caps, memory(), net)
        // Slipstream's best resolver measured faster, so it goes first in each round.
        assertEquals(listOf("p-slip", "p-dnstt", "p-cot"), plan.take(3).map { it.profileId })
        assertEquals("as-configured", plan[2].variant)
        assertEquals(DnsTunnelLadder.plan(listOf(dnstt, slip, cotten), caps, memory(), net, maxRungs = 4).size, 4)
    }

    @Test
    fun runnerStopsAtTheFirstWinPerTunnelAndRemembersIt() {
        val store = arrayOf<String?>(null)
        val m = memory(store)
        val caps = mapOf("t.a.com" to listOf(caps("5.5.5.5", "t.a.com"), caps("6.6.6.6", "t.a.com")),
            "s.b.com" to listOf(caps("5.5.5.5", "s.b.com")))
        val plan = DnsTunnelLadder.plan(listOf(dnstt, slip), caps, m, net)
        val tested = mutableListOf<String>()
        val report = DnsLadderRunner.run(plan, listOf(dnstt, slip), m, net, budgetMs = 60_000, clock = { now }) { rung ->
            tested += "${rung.profileId}/${rung.variant}"
            when {
                rung.profileId == "p-dnstt" && rung.variant == "single-udp" -> DnsLadderRunner.Outcome(true, 420)
                rung.variant == "as-configured" -> DnsLadderRunner.Outcome(false, notTested = true, failure = "engine missing")
                else -> DnsLadderRunner.Outcome(false, failure = "timeout")
            }
        }
        assertEquals(listOf("p-dnstt/stripe-udp", "p-slip/stripe-udp", "p-dnstt/single-udp", "p-slip/as-configured"), tested)
        assertEquals(1, report.wins.size)
        assertEquals(3, report.tested)
        val w = DnsLadderMemory({ store[0] }, { }, { now }).winner(net, "p-dnstt", "h1")
        assertNotNull("persisted", w)
        assertEquals(listOf("5.5.5.5"), w!!.resolvers)
        assertEquals(420L, w.latencyMs)
        assertEquals(1, m.failures(net, "p-slip", plan.first { it.profileId == "p-slip" }.key))
        assertEquals("not tested is no failure", 0, m.failures(net, "p-slip", plan.last { it.profileId == "p-slip" }.key))
    }

    @Test
    fun aFailingRememberedWinnerIsForgottenAndTheBudgetIsHonoured() {
        val m = memory()
        m.recordWin(net, "p-dnstt", "h1", DnsTunnelLadder.Rung("p-dnstt", EngineKind.DNSTT, "x", Via.UDP, listOf("6.6.6.6"), 4), 100)
        val plan = DnsTunnelLadder.plan(listOf(dnstt), mapOf("t.a.com" to listOf(caps("5.5.5.5", "t.a.com"))), m, net)
        var t = now
        val report = DnsLadderRunner.run(plan, listOf(dnstt), m, net, budgetMs = 15_000, clock = { t }) {
            t += 10_000; DnsLadderRunner.Outcome(false, failure = "no answer")
        }
        assertEquals("budget stops after two tries", 2, report.steps.size)
        assertNull(m.winner(net, "p-dnstt", "h1"))
    }

    @Test
    fun memoryExpiresAndSurvivesReload() {
        val store = arrayOf<String?>(null)
        val m = memory(store)
        val rung = DnsTunnelLadder.Rung("p", EngineKind.COTTEN, "tcp", Via.TCP, listOf("1.2.3.4"))
        m.recordWin(net, "p", "h", rung, null)
        val reloaded = DnsLadderMemory({ store[0] }, {}, { now })
        assertEquals(Via.TCP, reloaded.winner(net, "p", "h")!!.via)
        assertNull(reloaded.winner(net, "p", "other-hash"))
        assertEquals(1, reloaded.winners(net))
        now += DnsLadderMemory.TTL_MS
        assertNull(reloaded.winner(net, "p", "h"))
        m.recordFailure(net, "p", "k"); m.recordFailure(net, "p", "k")
        assertEquals(2, m.failures(net, "p", "k"))
        now += DnsLadderMemory.FAIL_TTL_MS
        assertEquals(0, m.failures(net, "p", "k"))
        assertNull(DnsLadderMemory({ "{not json" }, {}, { now }).winner(net, "p", "h"))
    }
}
