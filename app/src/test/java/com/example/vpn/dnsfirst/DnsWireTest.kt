package com.example.vpn.dnsfirst

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsWireTest {
    @Test
    fun queryCarriesTypeAndQuestion() {
        val q = DnsWire.query(0x1234, "abc.t.example.com", DnsWire.TYPE_TXT)
        assertEquals(0x12, q[0].toInt() and 0xff)
        assertEquals(0x34, q[1].toInt() and 0xff)
        assertEquals(0x01, q[2].toInt()) // RD
        assertEquals(DnsWire.TYPE_TXT, FakeResolver.qtype(q))
        assertEquals(0, q[11].toInt()) // no OPT
        assertEquals(q.size, DnsWire.questionEnd(q))
    }

    @Test
    fun paddedEdnsQueryIsAMultipleOfTheBlock() {
        for (len in listOf(3, 20, 60, 120)) {
            val name = DnsWire.randomLabel(Random(len.toLong()), minOf(len, 63)) + ".t.example.com"
            val q = DnsWire.query(1, name, DnsWire.TYPE_TXT, DnsWire.Edns(1232, 128))
            assertEquals("length ${q.size} for $len", 0, q.size % 128)
            assertEquals(1, q[11].toInt())
        }
        val plain = DnsWire.query(1, "a.example.com", DnsWire.TYPE_A, DnsWire.Edns(1232, 0))
        assertEquals(DnsWire.questionEnd(plain) + 11, plain.size)
    }

    @Test
    fun parseMatchesIdQuestionAndReadsEdns() {
        val q = DnsWire.query(7, "x.t.example.com", DnsWire.TYPE_TXT, DnsWire.Edns())
        val r = DnsWire.parse(q, FakeResolver.reply(q, DnsWire.RCODE_NOERROR, txt = true, ednsPayload = 1400))
        assertNotNull(r)
        assertEquals(DnsWire.RCODE_NOERROR, r!!.rcode)
        assertEquals(listOf(DnsWire.TYPE_TXT), r.answerTypes)
        assertEquals(1400, r.ednsPayload)
        assertTrue(r.resolved)
        assertFalse(r.truncated)
    }

    @Test
    fun parseAcceptsCaseChangesButNotOtherQuestionsOrIds() {
        val q = DnsWire.query(9, "mixed.t.example.com", DnsWire.TYPE_A)
        assertNotNull(DnsWire.parse(q, FakeResolver.reply(q, upperCase = true)))
        val other = DnsWire.query(9, "other.t.example.com", DnsWire.TYPE_A)
        assertNull(DnsWire.parse(q, FakeResolver.reply(other)))
        val otherId = DnsWire.query(10, "mixed.t.example.com", DnsWire.TYPE_A)
        assertNull(DnsWire.parse(q, FakeResolver.reply(otherId)))
        assertNull("a query is not a reply", DnsWire.parse(q, q))
    }

    @Test
    fun refusedAndServfailAreNotResolved() {
        val q = DnsWire.query(3, "r.t.example.com", DnsWire.TYPE_A)
        assertFalse(DnsWire.parse(q, FakeResolver.reply(q, DnsWire.RCODE_REFUSED))!!.resolved)
        assertFalse(DnsWire.parse(q, FakeResolver.reply(q, DnsWire.RCODE_SERVFAIL))!!.resolved)
        assertTrue(DnsWire.parse(q, FakeResolver.reply(q, DnsWire.RCODE_NXDOMAIN))!!.resolved)
        assertTrue(DnsWire.parse(q, FakeResolver.reply(q, tc = true))!!.truncated)
    }

    @Test
    fun tunnelLikeNamesStayUnderTheDomainAndTheLimit() {
        val random = Random(1)
        for (total in listOf(60, 120, 200, 253, 400)) {
            val name = DnsWire.tunnelLikeName(random, "t.example.com", total)
            assertTrue(name.endsWith(".t.example.com"))
            assertTrue(name.length <= DnsWire.MAX_NAME)
            assertTrue(name.split('.').all { it.length in 1..63 })
            DnsWire.encodeName(name) // valid on the wire
        }
        assertTrue(DnsWire.tunnelLikeName(random, "t.example.com", 200).length >= 190)
    }

    @Test
    fun zBitIsTheOnlyChange() {
        val q = DnsWire.query(5, "z.t.example.com", DnsWire.TYPE_A)
        val z = DnsWire.withZ(q)
        assertTrue(FakeResolver.zSet(z))
        assertEquals(q.size, z.size)
        assertEquals(q.toList().filterIndexed { i, _ -> i != 3 }, z.toList().filterIndexed { i, _ -> i != 3 })
    }
}
