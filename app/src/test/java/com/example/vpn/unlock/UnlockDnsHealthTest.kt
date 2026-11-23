package com.example.vpn.unlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Health checks, hijack detection and ranking for Unlock DNS servers, with fake probes (no network). */
class UnlockDnsHealthTest {

    /** Answers per server and name; [reachable] addresses accept TCP/443. */
    private class FakeProbe(
        val answers: Map<Pair<String, String>, Lookup?>,
        val reachable: Set<String> = setOf("143.20.64.10", "193.233.112.20", "45.13.237.213"),
        val connectMs: Long = 40
    ) : UnlockProbe {
        val asked = mutableListOf<Pair<String, String>>()
        override fun lookup(server: String, name: String, timeoutMs: Int): Lookup? {
            synchronized(asked) { asked += server to name }
            return answers[server to name]
        }
        override fun reach(ip: String, port: Int, timeoutMs: Int): Long? = if (port == 443 && ip in reachable) connectMs else null
    }

    private val control = UnlockDnsHealth.CONTROL_NAME
    private val probeName = UnlockDnsHealth.UNLOCK_PROBE

    private fun good(server: String, ms: Long, relay: String) = mapOf(
        (server to control) to Lookup(listOf("1.1.1.1", "1.0.0.1"), ms),
        (server to probeName) to Lookup(listOf(relay), ms + 5)
    )

    @Test fun aServerThatAnswersHonestlyAndReachesItsRelayIsHealthy() {
        val probe = FakeProbe(good("143.20.64.55", 38, "143.20.64.10"))
        val check = UnlockDnsHealth.check("143.20.64.55", probe, now = 1L)
        assertEquals(ServerHealth.HEALTHY, check.health)
        assertEquals(38L, check.latencyMs)
        assertNull(check.reason)
        assertTrue(check.usable)
    }

    @Test fun aRewrittenControlAnswerMarksTheServerHijacked() {
        val probe = FakeProbe(mapOf(
            ("5.175.161.3" to control) to Lookup(listOf("10.10.34.35"), 20),
            ("5.175.161.3" to probeName) to Lookup(listOf("143.20.64.10"), 20)
        ))
        val check = UnlockDnsHealth.check("5.175.161.3", probe)
        assertEquals(ServerHealth.BLOCKED, check.health)
        assertEquals(BlockReason.HIJACKED, check.reason)
        // A hijacked server is never asked anything else.
        assertEquals(listOf("5.175.161.3" to control), probe.asked)
    }

    @Test fun controlAddressesThatAreNotOneOneOneOneAreHijackedEvenWhenPublic() {
        assertTrue(UnlockDnsHealth.hijacked(listOf("104.16.1.1")))
        assertTrue(UnlockDnsHealth.hijacked(listOf("1.1.1.1", "8.8.8.8")))
        assertTrue(UnlockDnsHealth.hijacked(emptyList()))
        assertFalse(UnlockDnsHealth.hijacked(listOf("1.0.0.1", "1.1.1.1")))
    }

    @Test fun anAnswerFromAnotherAddressIsInterception() {
        val probe = FakeProbe(mapOf(("45.13.237.213" to control) to Lookup(listOf("1.1.1.1"), 15, intercepted = true)))
        assertEquals(BlockReason.INTERCEPTED, UnlockDnsHealth.check("45.13.237.213", probe).reason)
    }

    @Test fun silenceAndUnreachableRelaysAreBlocked() {
        assertEquals(BlockReason.NO_ANSWER, UnlockDnsHealth.check("193.233.112.67", FakeProbe(emptyMap())).reason)
        val noUnlock = FakeProbe(mapOf(("193.233.112.67" to control) to Lookup(listOf("1.1.1.1"), 30)))
        assertEquals(BlockReason.NO_UNLOCK_ANSWER, UnlockDnsHealth.check("193.233.112.67", noUnlock).reason)
        val bogon = FakeProbe(good("193.233.112.67", 30, "10.10.34.36"))
        assertEquals(BlockReason.NO_UNLOCK_ANSWER, UnlockDnsHealth.check("193.233.112.67", bogon).reason)
        val unreachable = FakeProbe(good("193.233.112.67", 30, "159.194.200.33"))
        val check = UnlockDnsHealth.check("193.233.112.67", unreachable)
        assertEquals(BlockReason.UNLOCK_UNREACHABLE, check.reason)
        assertFalse(check.usable)
    }

    @Test fun aSlowServerStaysUsableButRanksAfterHealthyOnes() {
        val probe = FakeProbe(good("45.13.237.213", UnlockDnsHealth.SLOW_MS + 100, "45.13.237.213") +
            good("143.20.64.55", 61, "143.20.64.10") + good("193.233.112.67", 38, "193.233.112.20"))
        val ranked = UnlockDnsHealth.checkAll(listOf("45.13.237.213", "143.20.64.55", "193.233.112.67", "5.175.161.3"), probe)
        assertEquals(listOf("193.233.112.67", "143.20.64.55", "45.13.237.213", "5.175.161.3"), ranked.map { it.ip })
        assertEquals(listOf(ServerHealth.HEALTHY, ServerHealth.HEALTHY, ServerHealth.SLOW, ServerHealth.BLOCKED), ranked.map { it.health })
    }

    @Test fun autoPickDropsDeadServersAndManualChoiceIsHonoured() {
        val checks = listOf(
            ServerCheck("5.175.161.3", ServerHealth.BLOCKED, reason = BlockReason.NO_ANSWER),
            ServerCheck("143.20.64.55", ServerHealth.HEALTHY, 52),
            ServerCheck("193.233.112.67", ServerHealth.HEALTHY, 38)
        )
        assertEquals("193.233.112.67", UnlockDnsHealth.pick(checks, autoPick = true, manual = "5.175.161.3"))
        assertEquals("143.20.64.55", UnlockDnsHealth.pick(checks, autoPick = false, manual = "143.20.64.55"))
        // The user's pick holds even when its last check failed; a server no longer listed falls back to auto.
        assertEquals("5.175.161.3", UnlockDnsHealth.pick(checks, autoPick = false, manual = "5.175.161.3"))
        assertEquals("193.233.112.67", UnlockDnsHealth.pick(checks, autoPick = false, manual = "9.9.9.9"))
        assertNull(UnlockDnsHealth.pick(checks.take(1), autoPick = true, manual = null))
        assertEquals("143.20.64.55", UnlockDnsHealth.next(checks, "193.233.112.67"))
        assertNull(UnlockDnsHealth.next(checks.take(1), "5.175.161.3"))
    }

    @Test fun aServerThatOutlivesTheDeadlineCountsAsBlocked() {
        val slowProbe = object : UnlockProbe {
            override fun lookup(server: String, name: String, timeoutMs: Int): Lookup? {
                if (server == "5.175.161.3") Thread.sleep(2_000)
                return if (name == UnlockDnsHealth.CONTROL_NAME) Lookup(listOf("1.1.1.1"), 20) else Lookup(listOf("143.20.64.10"), 20)
            }
            override fun reach(ip: String, port: Int, timeoutMs: Int): Long? = 30
        }
        val ranked = UnlockDnsHealth.checkAll(listOf("5.175.161.3", "143.20.64.55"), slowProbe, deadlineMs = 300)
        assertEquals(ServerHealth.HEALTHY, ranked.first { it.ip == "143.20.64.55" }.health)
        assertEquals(ServerHealth.BLOCKED, ranked.first { it.ip == "5.175.161.3" }.health)
    }

    @Test fun serviceBadgesNeedPublicAnswersThatAcceptTls() {
        val probe = FakeProbe(mapOf(
            ("143.20.64.55" to "gemini.google.com") to Lookup(listOf("143.20.64.10"), 20),
            ("143.20.64.55" to "chatgpt.com") to Lookup(listOf("10.10.34.35"), 20),
            ("143.20.64.55" to "aistudio.google.com") to Lookup(listOf("31.25.239.132"), 20)
        ))
        assertTrue(UnlockDnsHealth.verify("143.20.64.55", UnlockDnsHealth.Service.GEMINI, probe))
        assertFalse(UnlockDnsHealth.verify("143.20.64.55", UnlockDnsHealth.Service.CHATGPT, probe))
        assertFalse(UnlockDnsHealth.verify("143.20.64.55", UnlockDnsHealth.Service.AI_STUDIO, probe))
        assertFalse(UnlockDnsHealth.verify("143.20.64.55", UnlockDnsHealth.Service.ANDROID_DEV, probe))
    }
}
