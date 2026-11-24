package com.example.vpn.dnsfirst

import java.io.ByteArrayOutputStream

/** Builds DNS replies for tests: same ID and question, QR set, a chosen RCODE, optional TXT answer and OPT. */
object FakeResolver {
    fun reply(query: ByteArray, rcode: Int = DnsWire.RCODE_NXDOMAIN, txt: Boolean = false, ednsPayload: Int? = null,
              tc: Boolean = false, upperCase: Boolean = false): ByteArray {
        val qEnd = DnsWire.questionEnd(query)
        val out = ByteArrayOutputStream()
        out.write(query[0].toInt()); out.write(query[1].toInt())
        out.write(0x81 or (if (tc) 0x02 else 0)); out.write(0x80 or rcode)
        out.write(0); out.write(1)
        out.write(0); out.write(if (txt) 1 else 0)
        out.write(0); out.write(0)
        out.write(0); out.write(if (ednsPayload != null) 1 else 0)
        for (i in 12 until qEnd) {
            val b = query[i].toInt() and 0xff
            out.write(if (upperCase && b in 97..122) b - 32 else b)
        }
        if (txt) {
            out.write(0xC0); out.write(12)
            out.write(0); out.write(DnsWire.TYPE_TXT); out.write(0); out.write(1)
            out.write(0); out.write(0); out.write(0); out.write(60)
            out.write(0); out.write(4); out.write(3); out.write('a'.code); out.write('b'.code); out.write('c'.code)
        }
        if (ednsPayload != null) {
            out.write(0); out.write(0); out.write(DnsWire.TYPE_OPT)
            out.write(ednsPayload shr 8); out.write(ednsPayload and 0xff)
            out.write(0); out.write(0); out.write(0); out.write(0); out.write(0); out.write(0)
        }
        return out.toByteArray()
    }

    fun qtype(query: ByteArray): Int { val e = DnsWire.questionEnd(query); return ((query[e - 4].toInt() and 0xff) shl 8) or (query[e - 3].toInt() and 0xff) }
    fun nameLength(query: ByteArray): Int = DnsWire.questionEnd(query) - 4 - 12
    fun zSet(query: ByteArray): Boolean = query[3].toInt() and 0x40 != 0

    /**
     * A transport that behaves like a resolver with the given habits. [answer] decides each reply
     * (null: silence); [burstLimit] is how many of a burst it answers.
     */
    class Behaviour(
        val burstLimit: Int = Int.MAX_VALUE,
        val tcpWorks: Boolean = true,
        val answer: (ByteArray) -> ByteArray? = { reply(it, txt = qtype(it) == DnsWire.TYPE_TXT, ednsPayload = 1232) }
    ) : TunnelResolverProbe.Transport {
        var queries = 0
        override fun exchange(query: ByteArray, timeoutMs: Int, tcp: Boolean): ByteArray? {
            queries++
            if (tcp && !tcpWorks) return null
            return answer(query)
        }
        override fun burst(queries: List<ByteArray>, timeoutMs: Int): List<ByteArray> =
            queries.take(burstLimit).mapNotNull(answer)
    }
}
