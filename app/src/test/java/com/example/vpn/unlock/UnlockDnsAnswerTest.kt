package com.example.vpn.unlock

import com.example.vpn.dns.DnsMessageValidator
import com.example.vpn.safety.UnlockDnsRoutePolicy
import com.example.vpn.sidecar.ResolverReach
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build 177 B4: Unlock mode, 4/4 servers healthy (all with query shape tc_z), yet gemini.google.com and
 * chatgpt.com failed to resolve in apps. An answer with TC set (copied from the shaped question, or a real
 * truncation whose TCP retry failed) sends the app to TCP on the TUN, which nothing answers.
 */
class UnlockDnsAnswerTest {
    private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val query = ResolverReach.query(0x1234, "a.bc")
    private val name = "01 61 02 62 63 00"

    /** An answer with [count] A records (each a pointer to the question name), flags [flags]. */
    private fun answer(count: Int, flags: String = "8180", extra: String = "", arcount: Int = 0): ByteArray {
        val records = (1..count).joinToString(" ") { "c00c 0001 0001 0000003c 0004 01020304".replace("04 01020304", "04 010203%02x".format(it)) }
        return hex("1234 $flags 0001 %04x 0000 %04x $name 0001 0001 $records $extra".format(count, arcount))
    }

    private val opt4096 = "00 0029 1000 00000000 0000"

    @Test fun aTruncatedAnswerWithRecordsReachesTheAppWithoutTc() {
        val echoed = answer(2, flags = "8382") // QR RD TC | RA: the resolver copied TC from a tc_z question
        assertTrue(UnlockDnsWire.truncated(echoed))
        val result = UnlockDnsAnswer.prepare(query, echoed, UnlockDnsRoutePolicy.MTU)
        assertTrue(result is UnlockDnsAnswer.Result.Ok)
        val out = (result as UnlockDnsAnswer.Result.Ok).response
        assertFalse(UnlockDnsWire.truncated(out))
        assertNotNull(result.note)
        assertEquals(listOf("1.2.3.1", "1.2.3.2"), com.example.vpn.dns.ResolverScanner.aRecords(query, out, out.size))
        assertTrue(DnsMessageValidator.isResponseTo(query, out, out.size))
        // Once TC is gone the validator also hands the addresses out (it ignores a truncated answer).
        assertEquals(2, DnsMessageValidator.addressAnswers(query, out, out.size).size)
    }

    @Test fun aTruncatedAnswerWithoutRecordsIsUnusable() {
        val empty = answer(0, flags = "8382")
        val result = UnlockDnsAnswer.prepare(query, empty, UnlockDnsRoutePolicy.MTU)
        assertEquals(UnlockDnsAnswer.Result.Unusable(UnlockDnsAnswer.Problem.TRUNCATED_EMPTY), result)
    }

    @Test fun aNormalAnswerIsPassedOnUnchanged() {
        val normal = answer(3)
        val result = UnlockDnsAnswer.prepare(query, normal, UnlockDnsRoutePolicy.MTU) as UnlockDnsAnswer.Result.Ok
        assertArrayEquals(normal, result.response)
        assertNull(result.note)
    }

    @Test fun theAppsUdpSizeComesFromItsEdnsRecord() {
        assertEquals(512, UnlockDnsAnswer.appUdpSize(query))
        val withOpt = hex("1234 0100 0001 0000 0000 0001 $name 0001 0001 $opt4096")
        assertEquals(4096, UnlockDnsAnswer.appUdpSize(withOpt))
        val tinyOpt = hex("1234 0100 0001 0000 0000 0001 $name 0001 0001 00 0029 0100 00000000 0000")
        assertEquals(512, UnlockDnsAnswer.appUdpSize(tinyOpt))
    }

    @Test fun anAnswerLargerThanTheAppTakesIsCutAtARecordBoundary() {
        // 40 A records = 12 + 10 + 40 * 16 = 662 bytes > 512 (no EDNS in the app's question).
        val big = answer(40, extra = opt4096, arcount = 1)
        assertTrue(big.size > 512)
        val result = UnlockDnsAnswer.prepare(query, big, UnlockDnsRoutePolicy.MTU) as UnlockDnsAnswer.Result.Ok
        val out = result.response
        assertTrue(out.size <= 512)
        assertFalse(UnlockDnsWire.truncated(out))
        assertTrue(DnsMessageValidator.wellFormed(out, out.size))
        assertTrue(DnsMessageValidator.isResponseTo(query, out, out.size))
        assertEquals((512 - 22) / 16, com.example.vpn.dns.ResolverScanner.aRecords(query, out, out.size)!!.size)
        assertEquals(0, out[11].toInt()) // additional (EDNS) dropped
    }

    @Test fun anAppWithEdnsGetsTheWholeLargeAnswer() {
        val q = hex("1234 0100 0001 0000 0000 0001 $name 0001 0001 $opt4096")
        val big = answer(40)
        val result = UnlockDnsAnswer.prepare(q, big, UnlockDnsRoutePolicy.MTU) as UnlockDnsAnswer.Result.Ok
        assertArrayEquals(big, result.response)
    }

    @Test fun theQuestionIsDescribedForTheLog() {
        assertEquals("a.bc A", UnlockDnsAnswer.describe(query))
        assertEquals("chatgpt.com A", UnlockDnsAnswer.describe(ResolverReach.query(7, "chatgpt.com")))
    }

    // ---- other servers ----

    private fun ex(r: ByteArray) = UnlockDnsWire.Exchange(r, 50, intercepted = false, tcp = false)
    private val checks = listOf(
        ServerCheck("193.233.112.67", ServerHealth.HEALTHY, 109),
        ServerCheck("143.20.64.55", ServerHealth.HEALTHY, 257),
        ServerCheck("5.175.161.3", ServerHealth.HEALTHY, 315),
        ServerCheck("45.13.237.213", ServerHealth.BLOCKED, null, BlockReason.NO_ANSWER)
    )

    @Test fun anUnusableAnswerIsAskedOfTheNextHealthyServers() {
        val order = UnlockDnsAnswerPlan.order("193.233.112.67", checks, 2)
        assertEquals(listOf("193.233.112.67", "143.20.64.55", "5.175.161.3"), order)
        val asked = mutableListOf<String>()
        val outcome = UnlockDnsAnswerPlan.run(order, 4_500, { 0 }, { ip ->
            asked += ip
            when (ip) { "193.233.112.67" -> null; "143.20.64.55" -> ex(answer(0, flags = "8382")); else -> ex(answer(1)) }
        }, { UnlockDnsAnswer.prepare(query, it.response, UnlockDnsRoutePolicy.MTU) })
        assertEquals(order, asked)
        assertEquals("5.175.161.3", outcome.server)
        assertNotNull(outcome.answer)
        assertEquals(listOf("193.233.112.67" to UnlockDnsAnswer.Problem.NO_ANSWER,
            "143.20.64.55" to UnlockDnsAnswer.Problem.TRUNCATED_EMPTY), outcome.problems)
        val line = UnlockDnsAnswerPlan.describe("chatgpt.com A", outcome)
        assertTrue(line, line.contains("answered by 5.175.161.3") && line.contains("193.233.112.67: no answer"))
    }

    @Test fun whenNoServerAnswersTheReasonIsLogged() {
        val order = UnlockDnsAnswerPlan.order("193.233.112.67", checks, 2)
        val outcome = UnlockDnsAnswerPlan.run(order, 4_500, { 0 }, { null }, { error("not reached") })
        assertNull(outcome.answer)
        assertEquals(3, outcome.problems.size)
        val line = UnlockDnsAnswerPlan.describe("gemini.google.com A", outcome)
        assertTrue(line, line.startsWith("Unlock DNS: gemini.google.com A failed (") && line.endsWith("the app got SERVFAIL."))
    }

    @Test fun noFurtherServerIsAskedAfterTheBudget() {
        var now = 0L
        val asked = mutableListOf<String>()
        UnlockDnsAnswerPlan.run(UnlockDnsAnswerPlan.order("193.233.112.67", checks, 2), 4_500, { now },
            { ip -> asked += ip; now += 5_000; null }, { error("not reached") })
        assertEquals(listOf("193.233.112.67"), asked)
    }

    // ---- a server that only works shaped ----

    @Test fun aShapedOnlyServerRetriesItsShapesWhenPlainIsSilent() {
        val book = ShapeBook().apply { put("cell:43235", "193.233.112.67", ShapeBook.Entry(QueryShape.TC_Z, always = true)) }
        var tcz = 0
        val real = ex(answer(1))
        val calls = mutableListOf<String>()
        val t = object : ShapedForwarder.Transport {
            override fun plain(server: String, query: ByteArray, timeoutMs: Int): UnlockDnsWire.Exchange? {
                calls += "plain"; assertTrue(timeoutMs <= UnlockDnsShaping.ATTEMPT_TIMEOUT_MS); return null
            }
            override fun shaped(server: String, query: ByteArray, shape: QueryShape, timeoutMs: Int): UnlockDnsWire.Exchange? {
                calls += shape.key
                return if (shape == QueryShape.TC_Z && ++tcz == 2) real else null
            }
        }
        assertEquals(real, ShapedForwarder(t, book, { "cell:43235" }).resolve("193.233.112.67", query, true, 2500))
        assertEquals(listOf("tc_z", "plain", "tc_z"), calls)
    }
}
