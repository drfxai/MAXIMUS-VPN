package com.example.vpn.dnsfirst

import com.example.vpn.dns.DnsTunnelResolverOrder
import com.example.vpn.dnsfirst.DnsTunnelLadder.Rung
import com.example.vpn.dnsfirst.DnsTunnelLadder.Via
import com.example.vpn.sidecar.CottenDnsSidecar
import com.example.vpn.sidecar.DnsttSidecar
import com.example.vpn.sidecar.SlipstreamSidecar
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsTunnelSpecsTest {
    private val key = "a".repeat(64)
    private val dnstt = DnsttSidecar.profile(key, "t.example.com", resolver = "1.1.1.1:53", kind = DnsttSidecar.ResolverKind.UDP)
    private val slip = SlipstreamSidecar.profile("s.example.com", listOf("8.8.8.8"))
    private val cotten = CottenDnsSidecar.profile(CottenDnsSidecar.Settings("c.example.com", "test-only-cotten-key-000000000000", listOf("9.9.9.9")))

    @After fun reset() { DnsLadderHook.lookup = null }

    @Test
    fun specsReadEveryEngine() {
        val d = DnsTunnelSpecs.spec(dnstt)!!
        assertEquals(EngineKind.DNSTT, d.kind); assertEquals("t.example.com", d.domain); assertEquals(listOf("1.1.1.1"), d.configured)
        assertEquals(EngineKind.SLIPSTREAM, DnsTunnelSpecs.spec(slip)!!.kind)
        val c = DnsTunnelSpecs.spec(cotten)!!
        assertEquals(EngineKind.COTTEN, c.kind); assertEquals(listOf("9.9.9.9"), c.configured)
        assertNull(DnsTunnelSpecs.spec(com.example.data.model.VlessProfile(name = "x", address = "a.com", port = 443, uuid = "u")))
        assertEquals(d.settingsHash, DnsTunnelSpecs.spec(dnstt)!!.settingsHash)
        assertNotEquals(d.settingsHash, DnsTunnelSpecs.spec(DnsttSidecar.withSettings(dnstt, DnsttSidecar.settings(dnstt).copy(qps = 2)))!!.settingsHash)
    }

    @Test
    fun dnsttStripeSetsPerResolverRateAndDohWorks() {
        val stripe = DnsTunnelSpecs.apply(dnstt, Rung(dnstt.id, EngineKind.DNSTT, "stripe-udp", Via.UDP, listOf("5.5.5.5", "6.6.6.6"), 2))!!
        val s = DnsttSidecar.settings(stripe)
        assertEquals(listOf("5.5.5.5:53", "6.6.6.6:53"), s.resolvers.map { it.address })
        assertTrue(s.resolvers.all { it.kind == DnsttSidecar.ResolverKind.UDP })
        assertEquals(2, s.qps); assertTrue(s.perResolver); assertEquals(4, s.totalQps)
        assertEquals(dnstt.id, stripe.id)
        val doh = DnsTunnelSpecs.apply(dnstt, Rung(dnstt.id, EngineKind.DNSTT, "doh", Via.DOH, listOf("https://free.shecan.ir/dns-query"), 1))!!
        assertEquals(DnsttSidecar.ResolverKind.DOH, DnsttSidecar.settings(doh).kind)
        assertNull("dnstt has no plain TCP mode", DnsTunnelSpecs.apply(dnstt, Rung(dnstt.id, EngineKind.DNSTT, "tcp", Via.TCP, listOf("5.5.5.5"))))
    }

    @Test
    fun slipstreamAndCottenVariants() {
        val s = DnsTunnelSpecs.apply(slip, Rung(slip.id, EngineKind.SLIPSTREAM, "stripe-udp", Via.UDP, listOf("5.5.5.5", "6.6.6.6")))!!
        assertEquals(listOf("5.5.5.5", "6.6.6.6"), SlipstreamSidecar.settings(s).resolvers)
        val c = DnsTunnelSpecs.apply(cotten, Rung(cotten.id, EngineKind.COTTEN, "tcp", Via.TCP, listOf("5.5.5.5")))!!
        assertEquals("tcp", CottenDnsSidecar.settings(c).transport)
        assertEquals(listOf("5.5.5.5:53"), CottenDnsSidecar.settings(c).resolvers)
        assertEquals("the copy keeps the saved identity", cotten.id, c.id)
        assertSame(cotten, DnsTunnelSpecs.apply(cotten, Rung(cotten.id, EngineKind.COTTEN, "as-configured", Via.AS_CONFIGURED, listOf("9.9.9.9"))))
    }

    @Test
    fun connectUsesTheLadderWinnerForThisNetworkOnly() {
        var now = 1_000L
        val memory = DnsLadderMemory(clock = { now })
        var network = "cell:43235"
        DnsLadderHook.lookup = DnsLadderHook.lookupFor(memory) { network }
        // Nothing learned: the resolver order logic runs as before.
        assertFalse(DnsTunnelResolverOrder.apply(SlipstreamSidecar.ID, slip, emptyList()).changed)
        val spec = DnsTunnelSpecs.spec(slip)!!
        memory.recordWin(network, slip.id, spec.settingsHash, Rung(slip.id, EngineKind.SLIPSTREAM, "single-udp", Via.UDP, listOf("5.5.5.5")), 300)
        val applied = DnsTunnelResolverOrder.apply(SlipstreamSidecar.ID, slip, emptyList())
        assertTrue(applied.changed)
        assertEquals(listOf("5.5.5.5"), SlipstreamSidecar.settings(applied.profile).resolvers)
        network = "wifi:other"
        assertFalse(DnsTunnelResolverOrder.apply(SlipstreamSidecar.ID, slip, emptyList()).changed)
        // An edited profile is not overridden.
        network = "cell:43235"
        val edited = SlipstreamSidecar.withSettings(slip, SlipstreamSidecar.settings(slip).copy(resolvers = listOf("7.7.7.7")))
        assertFalse(DnsTunnelResolverOrder.apply(SlipstreamSidecar.ID, edited, emptyList()).changed)
        // A broken lookup never breaks a connect.
        DnsLadderHook.lookup = { error("boom") }
        assertNotNull(DnsTunnelResolverOrder.apply(SlipstreamSidecar.ID, slip, emptyList()).profile)
    }
}
