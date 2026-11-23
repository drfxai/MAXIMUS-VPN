package com.example.vpn.unlock

import com.example.vpn.dns.DnsMessageValidator
import com.example.vpn.sidecar.ResolverReach
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CircumVolve query shapes: byte-exact builders, answer restoring, per-network memory and the retry policy. */
class UnlockDnsShapingTest {
    private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val query = ResolverReach.query(0x1234, "a.bc")
    private val name = "01 61 02 62 63 00"

    @Test fun theAppQueryIsTheTextbookQuestion() {
        assertArrayEquals(hex("1234 0100 0001 0000 0000 0000 $name 0001 0001"), query)
    }

    @Test fun flagShapesSetOnlyTheirBits() {
        assertArrayEquals(hex("1234 0340 0001 0000 0000 0000 $name 0001 0001"), UnlockDnsShaping.shape(query, QueryShape.TC_Z))
        assertArrayEquals(hex("1234 0140 0001 0000 0000 0000 $name 0001 0001"), UnlockDnsShaping.shape(query, QueryShape.Z))
        assertArrayEquals(hex("1234 0300 0001 0000 0000 0000 $name 0001 0001"), UnlockDnsShaping.shape(query, QueryShape.TC))
    }

    @Test fun countClaimAnnouncesAnAnswerItDoesNotSend() {
        assertArrayEquals(hex("1234 0100 0001 0001 0000 0000 $name 0001 0001"), UnlockDnsShaping.shape(query, QueryShape.COUNT_CLAIM))
    }

    @Test fun pointerChainTakesTwentyOneJumpsToTheName() {
        val links = (1..20).joinToString(" ") { "c0%02x".format(18 + 2 * it) }
        val expected = hex("1234 0100 0001 0000 0000 0000 c012 0001 0001 $links $name")
        val shaped = UnlockDnsShaping.shape(query, QueryShape.POINTER_CHAIN)!!
        assertArrayEquals(expected, shaped)
        // Follow the chain the way a resolver does: 21 pointer jumps end on the literal name.
        var p = 12
        var jumps = 0
        while ((shaped[p].toInt() and 0xc0) == 0xc0) {
            p = ((shaped[p].toInt() and 0x3f) shl 8) or (shaped[p + 1].toInt() and 0xff)
            jumps++
        }
        assertEquals(21, jumps)
        assertTrue(jumps > 18)
        assertArrayEquals(hex(name), shaped.copyOfRange(p, shaped.size))
    }

    @Test fun shapesKeepTheIdTypeAndRdAndDropEdns() {
        // AAAA question with RD+CD and an EDNS OPT record (ARCOUNT=1).
        val withOpt = hex("beef 0110 0001 0000 0000 0001 $name 001c 0001 00 0029 1000 00000000 0000")
        assertArrayEquals(hex("beef 0350 0001 0000 0000 0000 $name 001c 0001"), UnlockDnsShaping.shape(withOpt, QueryShape.TC_Z))
    }

    @Test fun onlyPlainSingleQuestionQueriesAreShaped() {
        val twoQuestions = query.copyOf().also { it[5] = 2 }
        assertNull(UnlockDnsShaping.shape(twoQuestions, QueryShape.Z))
        val response = query.copyOf().also { it[2] = 0x81.toByte() }
        assertNull(UnlockDnsShaping.shape(response, QueryShape.Z))
        val compressed = hex("1234 0100 0001 0000 0000 0000 c00c 0001 0001")
        assertNull(UnlockDnsShaping.shape(compressed, QueryShape.Z))
        assertNull(UnlockDnsShaping.shape(ByteArray(10), QueryShape.Z))
    }

    /** A resolver's answer: [echoed] question bytes, then one A record pointing at offset 12. */
    private fun answer(echoed: String, ip: String = "01020304", flags: String = "8180", id: String = "1234", qd: String = "0001") =
        hex("$id $flags $qd 0001 0000 0000 $echoed 0001 0001 c00c 0001 0001 0000003c 0004 $ip")

    @Test fun anAnswerToAShapedQuestionIsRestoredForTheApp() {
        // The resolver echoed the name in other letter case and kept the Z bit; the app gets its own question.
        val upper = "01 41 02 42 43 00"
        val fromResolver = answer(upper, flags = "81c0")
        val restored = UnlockDnsShaping.restore(query, fromResolver)
        assertNotNull(restored)
        assertArrayEquals(answer(name, flags = "8180"), restored)
        assertTrue(DnsMessageValidator.isResponseTo(query, restored!!, restored.size))
        assertEquals(listOf("1.2.3.4"), com.example.vpn.dns.ResolverScanner.aRecords(query, restored, restored.size))
    }

    @Test fun refusalsAndForeignAnswersAreNotRestored() {
        assertNull("FORMERR", UnlockDnsShaping.restore(query, hex("1234 8181 0000 0000 0000 0000")))
        assertNull("FORMERR with question", UnlockDnsShaping.restore(query, answer(name, flags = "8181")))
        assertNull("NOTIMP", UnlockDnsShaping.restore(query, answer(name, flags = "8184")))
        assertNull("other id", UnlockDnsShaping.restore(query, answer(name, id = "4321")))
        assertNull("a query", UnlockDnsShaping.restore(query, answer(name, flags = "0100")))
        assertNull("other name", UnlockDnsShaping.restore(query, answer("01 61 02 62 64 00")))
        assertNull("two questions", UnlockDnsShaping.restore(query, answer(name, qd = "0002")))
        assertNull("compressed echo", UnlockDnsShaping.restore(query, hex("1234 8180 0001 0000 0000 0000 c00c 0001 0001")))
        val nx = UnlockDnsShaping.restore(query, hex("1234 8183 0001 0000 0000 0000 $name 0001 0001"))
        assertNotNull("NXDOMAIN is a real answer", nx)
    }

    @Test fun blockPageAddressesAreSeenAsFiltered() {
        assertTrue(UnlockDnsShaping.poisoned(query, answer(name, ip = "0a0a2223")))  // 10.10.34.35
        assertFalse(UnlockDnsShaping.poisoned(query, answer(name, ip = "01020304")))
        assertFalse(UnlockDnsShaping.poisoned(query, hex("1234 8183 0001 0000 0000 0000 $name 0001 0001")))
    }

    @Test fun theShapeBookRoundTripsAndStaysSmall() {
        val book = ShapeBook(maxEntries = 2)
        var saved = ""
        book.onChange = { saved = it }
        book.put("wifi:abc123", "143.20.64.55", ShapeBook.Entry(QueryShape.TC_Z, always = true))
        book.put("cell:43235", "5.175.161.3", ShapeBook.Entry(QueryShape.POINTER_CHAIN, always = false))
        assertEquals("wifi:abc123|143.20.64.55=tc_z/A,cell:43235|5.175.161.3=chain/P", saved)
        val copy = ShapeBook().apply { decode(saved + ",junk,x|1.2.3.4=nope/A,y|1.2.3.4=z/Q") }
        assertEquals(ShapeBook.Entry(QueryShape.TC_Z, true), copy.get("wifi:abc123", "143.20.64.55"))
        assertEquals(ShapeBook.Entry(QueryShape.POINTER_CHAIN, false), copy.get("cell:43235", "5.175.161.3"))
        assertNull(copy.get("x", "1.2.3.4"))
        assertNull(copy.get("wifi:other", "143.20.64.55"))
        book.put("wifi:new", "45.13.237.213", ShapeBook.Entry(QueryShape.Z, false))
        assertNull("oldest dropped", book.get("wifi:abc123", "143.20.64.55"))
        book.remove("wifi:new", "45.13.237.213")
        assertNull(book.get("wifi:new", "45.13.237.213"))
    }

    // ---- ShapedForwarder with a fake transport ----

    private class FakeTransport(
        val plainAnswer: (ByteArray) -> UnlockDnsWire.Exchange?,
        val shapedAnswer: (QueryShape, ByteArray) -> UnlockDnsWire.Exchange?,
        val onShaped: () -> Unit = {}
    ) : ShapedForwarder.Transport {
        val calls = mutableListOf<String>()
        override fun plain(server: String, query: ByteArray, timeoutMs: Int): UnlockDnsWire.Exchange? {
            calls += "plain"; return plainAnswer(query)
        }
        override fun shaped(server: String, query: ByteArray, shape: QueryShape, timeoutMs: Int): UnlockDnsWire.Exchange? {
            calls += shape.key; onShaped(); return shapedAnswer(shape, query)
        }
    }

    private val blocked = UnlockDnsWire.Exchange(answer(name, ip = "0a0a2223"), 5, intercepted = false, tcp = false)
    private val real = UnlockDnsWire.Exchange(answer(name, ip = "68c41010"), 40, intercepted = false, tcp = false)

    @Test fun withShapingOffOnlyThePlainQuestionIsSent() {
        val t = FakeTransport({ blocked }, { _, _ -> real })
        val f = ShapedForwarder(t, ShapeBook(), { "wifi:a" })
        assertEquals(blocked, f.resolve("143.20.64.55", query, shaping = false, timeoutMs = 2500))
        assertEquals(listOf("plain"), t.calls)
    }

    @Test fun aCleanPlainAnswerNeedsNoShape() {
        val t = FakeTransport({ real }, { _, _ -> error("not expected") })
        val book = ShapeBook()
        assertEquals(real, ShapedForwarder(t, book, { "wifi:a" }).resolve("143.20.64.55", query, true, 2500))
        assertEquals(listOf("plain"), t.calls)
        assertEquals("", book.encode())
    }

    @Test fun aFilteredAnswerTriesShapesAndRemembersTheWinnerPerNetworkAndServer() {
        val t = FakeTransport({ blocked }, { shape, _ ->
            when (shape) { QueryShape.TC_Z -> null; QueryShape.Z -> blocked; QueryShape.TC -> real; else -> error("too far") }
        })
        val book = ShapeBook()
        var network = "wifi:a"
        val f = ShapedForwarder(t, book, { network })
        assertEquals(real, f.resolve("143.20.64.55", query, true, 2500))
        assertEquals(listOf("plain", "tc_z", "z", "tc"), t.calls)
        assertEquals(ShapeBook.Entry(QueryShape.TC, always = false), book.get("wifi:a", "143.20.64.55"))

        // Same network and server: plain first, then the remembered shape first.
        t.calls.clear()
        assertEquals(real, f.resolve("143.20.64.55", query, true, 2500))
        assertEquals(listOf("plain", "tc"), t.calls)

        // Another network starts from scratch.
        network = "cell:43235"
        t.calls.clear()
        f.resolve("143.20.64.55", query, true, 2500)
        assertEquals(listOf("plain", "tc_z", "z", "tc"), t.calls)
    }

    @Test fun whenNoShapeWorksTheAppGetsThePlainAnswer() {
        val t = FakeTransport({ blocked }, { _, _ -> null })
        val book = ShapeBook()
        assertEquals(blocked, ShapedForwarder(t, book, { "wifi:a" }).resolve("143.20.64.55", query, true, 2500))
        assertEquals(listOf("plain") + QueryShape.entries.map { it.key }, t.calls)
        assertEquals("", book.encode())
    }

    @Test fun anInterceptedPlainAnswerIsRetriedAndNeverHandedOver() {
        val intercepted = real.copy(intercepted = true)
        val t = FakeTransport({ intercepted }, { shape, _ -> if (shape == QueryShape.Z) real else null })
        assertEquals(real, ShapedForwarder(t, ShapeBook(), { "n" }).resolve("1.2.3.4", query, true, 2500))
        val none = FakeTransport({ intercepted }, { _, _ -> null })
        assertNull(ShapedForwarder(none, ShapeBook(), { "n" }).resolve("1.2.3.4", query, true, 2500))
    }

    @Test fun silenceIsNotRetriedSoFailoverStaysFast() {
        val t = FakeTransport({ null }, { _, _ -> real })
        assertNull(ShapedForwarder(t, ShapeBook(), { "n" }).resolve("1.2.3.4", query, true, 2500))
        assertEquals(listOf("plain"), t.calls)
    }

    @Test fun aHealthCheckShapeLeadsAndPlainIsTheFallback() {
        val book = ShapeBook().apply { put("wifi:a", "5.175.161.3", ShapeBook.Entry(QueryShape.POINTER_CHAIN, always = true)) }
        val works = FakeTransport({ error("not expected") }, { _, _ -> real })
        assertEquals(real, ShapedForwarder(works, book, { "wifi:a" }).resolve("5.175.161.3", query, true, 2500))
        assertEquals(listOf("chain"), works.calls)

        val stopped = FakeTransport({ real }, { _, _ -> null })
        assertEquals(real, ShapedForwarder(stopped, book, { "wifi:a" }).resolve("5.175.161.3", query, true, 2500))
        assertEquals(listOf("chain", "plain"), stopped.calls)
    }

    @Test fun retriesStopAtTheBudget() {
        var now = 0L
        val t = FakeTransport({ blocked }, { _, _ -> null }, onShaped = { now += 1_000 })
        ShapedForwarder(t, ShapeBook(), { "n" }, clock = { now }).resolve("1.2.3.4", query, true, 2500)
        assertEquals(listOf("plain", "tc_z", "z", "tc"), t.calls)
    }

    // ---- Health checks with shapes ----

    private class FakeProbe(val answers: Map<String, Lookup?>, val relay: String = "143.20.64.10") : UnlockProbe {
        var lookups = 0
        override fun lookup(server: String, name: String, timeoutMs: Int): Lookup? { lookups++; return answers[name] }
        override fun reach(ip: String, port: Int, timeoutMs: Int): Long? = if (ip == relay) 30 else null
    }

    private val good = FakeProbe(mapOf(UnlockDnsHealth.CONTROL_NAME to Lookup(listOf("1.1.1.1"), 30),
        UnlockDnsHealth.UNLOCK_PROBE to Lookup(listOf("143.20.64.10"), 35)))
    private val hijacked = FakeProbe(mapOf(UnlockDnsHealth.CONTROL_NAME to Lookup(listOf("10.10.34.35"), 4)))
    private val silent = FakeProbe(emptyMap())

    @Test fun aHijackedServerPassesWithTheFirstShapeThatWorks() {
        val asked = mutableListOf<QueryShape>()
        val check = UnlockDnsHealth.checkShaped("5.175.161.3", hijacked, { s -> asked += s; if (s == QueryShape.Z) good else silent }, null)
        assertTrue(check.usable)
        assertEquals(QueryShape.Z, check.shape)
        assertEquals(listOf(QueryShape.TC_Z, QueryShape.Z), asked)
    }

    @Test fun aDeadServerGetsNoShapedAttempts() {
        val asked = mutableListOf<QueryShape>()
        val check = UnlockDnsHealth.checkShaped("5.175.161.3", silent, { s -> asked += s; good }, null)
        assertEquals(ServerHealth.BLOCKED, check.health)
        assertEquals(BlockReason.NO_ANSWER, check.reason)
        assertTrue(asked.isEmpty())
    }

    @Test fun aRememberedShapeIsTriedFirstAndForgottenWhenPlainWorksAgain() {
        val asked = mutableListOf<QueryShape>()
        val first = UnlockDnsHealth.checkShaped("5.175.161.3", hijacked, { s -> asked += s; good }, QueryShape.COUNT_CLAIM)
        assertEquals(QueryShape.COUNT_CLAIM, first.shape)
        assertEquals(listOf(QueryShape.COUNT_CLAIM), asked)

        val plainAgain = UnlockDnsHealth.checkShaped("5.175.161.3", good, { silent }, QueryShape.COUNT_CLAIM)
        assertTrue(plainAgain.usable)
        assertNull(plainAgain.shape)
    }

    @Test fun shapedChecksStopAtTheirBudget() {
        var now = 0L
        val asked = mutableListOf<QueryShape>()
        val check = UnlockDnsHealth.checkShaped("5.175.161.3", hijacked, { s -> asked += s; now += 2_000; silent }, null, clock = { now })
        assertEquals(BlockReason.HIJACKED, check.reason)
        assertEquals(3, asked.size)
    }
}
