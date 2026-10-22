package com.example

import com.example.vpn.EndpointResolver
import com.example.vpn.connectivity.DnsOutcome
import com.example.vpn.connectivity.DnsResilienceEngine
import com.example.vpn.lab.DnsWire
import com.example.vpn.lab.ResolverIntelligence
import org.junit.Assert.*
import org.junit.Test

class DnsRecoveryUpgradeTest {
    @Test fun unrelatedJsonAddressCannotBecomeTheServerEndpoint() {
        val body = """{"Status":0,"Answer":[{"name":"other.example","type":1,"data":"1.1.1.1"}]}"""
        assertNull(EndpointResolver.parseDohAnswer(body, "wanted.example"))
    }

    @Test fun jsonCnameChainIsFollowedRegardlessOfRecordOrderAndCase() {
        val body = """{"Status":0,"Answer":[{"name":"edge.example.","type":1,"data":"1.1.1.1"},{"name":"WANTED.example.","type":5,"data":"edge.example."}]}"""
        assertEquals("1.1.1.1", EndpointResolver.parseDohAnswer(body, "wanted.EXAMPLE"))
    }

    @Test fun jsonCnameCyclesAndConflictingTargetsAreRejected() {
        val loop = """{"Status":0,"Answer":[{"name":"a.example","type":5,"data":"a.example"},{"name":"other.example","type":1,"data":"1.1.1.1"}]}"""
        val conflict = """{"Status":0,"Answer":[{"name":"a.example","type":5,"data":"b.example"},{"name":"a.example","type":5,"data":"c.example"},{"name":"b.example","type":1,"data":"1.1.1.1"}]}"""
        assertNull(EndpointResolver.parseDohAnswer(loop, "a.example"))
        assertNull(EndpointResolver.parseDohAnswer(conflict, "a.example"))
    }

    @Test fun previousSuccessDoesNotKeepADeadResolverInTheLiveSet() {
        var now = 100_000L
        val e = DnsResilienceEngine({ null }, {}, { now })
        val all = listOf("a", "b", "c", "d")
        repeat(12) { e.record("wifi", "a", "host", DnsOutcome.ANSWER, 10) }
        repeat(3) { e.record("wifi", "a", "host", DnsOutcome.TIMEOUT, null) }
        assertFalse("a" in e.profile("wifi", all).resolverOrder)
        now += DnsResilienceEngine.RETRY_AFTER_MS
        assertTrue("a" in e.profile("wifi", all).resolverOrder)
        e.record("wifi", "a", "host", DnsOutcome.ANSWER, 20)
        assertTrue("a" in e.profile("wifi", all).resolverOrder)
        assertEquals(all, e.profile("mobile", all).resolverOrder)
    }

    @Test fun statisticsUseRecentOutcomesRatherThanYesterdaySuccesses() {
        val e = DnsResilienceEngine({ null }, {})
        repeat(20) { e.record("wifi", "a", "host", DnsOutcome.ANSWER, 10) }
        repeat(DnsResilienceEngine.STATS_WINDOW) { e.record("wifi", "a", "host", DnsOutcome.TIMEOUT, null) }
        assertEquals(0, e.stats("wifi", "a").answers)
        assertEquals(DnsResilienceEngine.STATS_WINDOW, e.stats("wifi", "a").queries)
    }

    private fun answer(truncated: Boolean = false) = DnsWire.Answer(1, 0, truncated, 1, emptyList(), 30)

    @Test fun truncatedUdpRetriesTheSameQuestionOnTcp() {
        val tcp = answer()
        var calls = 0
        assertSame(tcp, ResolverIntelligence.completeAnswer({ answer(true) }, { calls++; tcp }))
        assertEquals(1, calls)
    }

    @Test fun droppedUdpCanRecoverOverTcp() {
        val tcp = answer()
        assertSame(tcp, ResolverIntelligence.completeAnswer({ null }, { tcp }))
    }

    @Test fun completeUdpDoesNotSendAnExtraTcpQuery() {
        val udp = answer()
        assertSame(udp, ResolverIntelligence.completeAnswer({ udp }, { error("Unexpected TCP query") }))
    }

    @Test fun truncatedTcpNeverBecomesACompleteAnswer() {
        assertNull(ResolverIntelligence.completeAnswer({ null }, { answer(true) }))
    }

    @Test fun sixHourHistoryIsNotReusedAsCurrentLabEvidence() {
        val now = 1_000_000L
        val r = ResolverIntelligence.Result("r", "1.1.1.1", "test", "wifi", true, true, 20L,
            false, false, 1232, now, now + ResolverIntelligence.TTL_MS)
        assertTrue(ResolverIntelligence.reusable(listOf(r), now))
        assertFalse(ResolverIntelligence.reusable(listOf(r), now + ResolverIntelligence.RECHECK_MS + 1))
        assertFalse(ResolverIntelligence.reusable(listOf(r), now - 1))
        assertFalse(ResolverIntelligence.reusable(emptyList(), now))
    }
}
