package com.example.vpn.dnsfirst

import com.example.vpn.smart.VerifiedWinnerBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DnsLadderWinnersTest {
    private val net = "cell:43235"
    private fun rung(id: String) = DnsTunnelLadder.Rung(id, EngineKind.DNSTT, "stripe-udp", DnsTunnelLadder.Via.UDP, listOf("1.1.1.1"))
    private fun step(id: String, ok: Boolean) = DnsLadderRunner.Step(rung(id), DnsLadderRunner.Outcome(ok, latencyMs = 300))

    @Test fun onlyWinsReachTheVerifiedPathMemory() {
        val now = 10_000L
        val book = VerifiedWinnerBook({ null }, {}, { now })
        val report = DnsLadderRunner.Report(listOf(step("dead", false), step("dnstt-1", true), step("dnstt-1", true)))
        val n = DnsLadderWinners.publish(report, net, nameOf = { "DNS $it" }, record = { a, b, c -> book.recordVerified(a, b, c) })
        assertEquals(1, n)
        assertEquals("dnstt-1", book.best(net)?.profileId)
        assertNull(book.best(net, exclude = setOf("dnstt-1")))
    }

    @Test fun aMeasuredDownloadRateStillRanksFirst() {
        val book = VerifiedWinnerBook({ null }, {}, { 10_000L })
        book.recordVerified(net, "serverless", "Serverless", 40_000L)
        DnsLadderWinners.publish(DnsLadderRunner.Report(listOf(step("dnstt-1", true))), net,
            nameOf = { it }, record = { a, b, c -> book.recordVerified(a, b, c) })
        assertEquals("serverless", book.best(net)?.profileId)
        assertEquals("dnstt-1", book.best(net, exclude = setOf("serverless"))?.profileId)
    }

    @Test fun removedProfilesAndBlankNetworksAreSkipped() {
        val book = VerifiedWinnerBook({ null }, {}, { 10_000L })
        val report = DnsLadderRunner.Report(listOf(step("gone", true)))
        assertEquals(0, DnsLadderWinners.publish(report, net, nameOf = { null }, record = { a, b, c -> book.recordVerified(a, b, c) }))
        assertEquals(0, DnsLadderWinners.publish(report, "", nameOf = { it }, record = { a, b, c -> book.recordVerified(a, b, c) }))
        assertNull(book.best(net))
    }
}
