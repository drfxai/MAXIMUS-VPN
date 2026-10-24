package com.example.vpn.lab

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class ResolverMixedAnswerTest {
    private fun answer(vararg ips: String) = DnsWire.Answer(
        1, 0, false, ips.size, ips.map { InetAddress.getByName(it) }, 64
    )

    @Test fun mixedBlockedAndPublicAnswerIsRejectedInEitherOrder() {
        assertEquals(true, ResolverIntelligence.judge(answer("8.8.8.8", "127.0.0.1"), null).first)
        assertEquals(true, ResolverIntelligence.judge(answer("127.0.0.1", "8.8.8.8"), null).first)
    }

    @Test fun publicOnlyAnswerIsNotMarkedBlocked() {
        assertEquals(false, ResolverIntelligence.judge(answer("8.8.8.8"), null).first)
    }

    @Test fun cachedOrderDoesNotOutrankHealthyResolver() {
        fun result(name: String, blocked: Boolean, rtt: Long) = ResolverIntelligence.Result(
            name, "8.8.8.8", "test", "network-a", true, true, rtt,
            blocked, false, null, 1_000, 10_000
        )
        val rows = listOf(result("blocked-fast", true, 1), result("healthy", false, 100))
        val restored = rows.map { ResolverIntelligence.Result.fromJson(it.toJson())!! }
        assertEquals("healthy", ResolverIntelligence.rank(restored).first().label)
        assertEquals(false, ResolverIntelligence.reusable(restored, now = 11_000))
        assertEquals(false, ResolverIntelligence.reusable(restored, now = 500))
    }

    @Test fun missingAnswerRemainsUnknown() {
        assertEquals(null, ResolverIntelligence.judge(null, null).first)
    }
}
