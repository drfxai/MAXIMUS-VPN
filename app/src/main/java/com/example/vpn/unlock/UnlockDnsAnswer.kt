package com.example.vpn.unlock

/**
 * Turns an upstream Unlock DNS answer into one an app can use over the DNS-only TUN.
 *
 * The TUN answers DNS over UDP only: an app that gets a reply with the TC (truncated) bit retries over TCP to the
 * TUN's DNS address, which nothing answers, and the lookup fails (curl: "Could not resolve host"). Two things put
 * TC in front of apps in Build 177:
 *  - the TC_Z / TC query shapes set TC in the question, and some resolvers copy the question's flags into the
 *    answer, so a complete answer came back marked truncated (the health check reads A records and never saw it);
 *  - a real truncated UDP answer when the TCP retry did not get through.
 * And a long answer (a TCP answer or a big EDNS answer) could be larger than the app said it can take.
 *
 * [prepare] never hands an app a TC reply: a truncated answer that still carries answer records is passed on with
 * TC cleared (the records it has are whole); one with no answer records is unusable (the caller tries another
 * server). An answer larger than the app's UDP size is cut at a record boundary, keeping the answer section first.
 */
object UnlockDnsAnswer {
    /** Smallest UDP size every DNS client accepts (RFC 1035). */
    const val CLASSIC_UDP = 512
    /** Packet room taken by the IPv4 + UDP headers. */
    const val PACKET_OVERHEAD = 28

    /** Why an upstream answer could not be used; shown in the log. */
    enum class Problem(val text: String) {
        NO_ANSWER("no answer over UDP or TCP"),
        TRUNCATED_EMPTY("a truncated answer without records (TCP retry failed)"),
        TOO_LARGE("an answer too large for the app that could not be cut"),
        MALFORMED("a malformed answer")
    }

    sealed class Result {
        data class Ok(val response: ByteArray, val note: String? = null) : Result()
        data class Unusable(val problem: Problem) : Result()
    }

    private fun u16(b: ByteArray, p: Int) = ((b[p].toInt() and 0xff) shl 8) or (b[p + 1].toInt() and 0xff)

    private fun put16(b: ByteArray, p: Int, v: Int) {
        b[p] = (v shr 8).toByte(); b[p + 1] = v.toByte()
    }

    /** The UDP payload size the app accepts: its EDNS size (RFC 6891), at least 512; 512 without EDNS. */
    fun appUdpSize(query: ByteArray): Int {
        if (query.size < 12) return CLASSIC_UDP
        var p = skipQuestions(query) ?: return CLASSIC_UDP
        val records = u16(query, 6) + u16(query, 8) + u16(query, 10)
        repeat(records) {
            val nameEnd = skipName(query, p) ?: return CLASSIC_UDP
            if (nameEnd + 10 > query.size) return CLASSIC_UDP
            val type = u16(query, nameEnd)
            if (type == 41) return u16(query, nameEnd + 2).coerceAtLeast(CLASSIC_UDP)
            p = nameEnd + 10 + u16(query, nameEnd + 8)
        }
        return CLASSIC_UDP
    }

    /**
     * [response] (an upstream answer to the app's [query]) made safe for the TUN: no TC bit, no larger than the
     * app's UDP size and the TUN's [mtu].
     */
    fun prepare(query: ByteArray, response: ByteArray, mtu: Int): Result {
        if (response.size < 12) return Result.Unusable(Problem.MALFORMED)
        var out = response
        var note: String? = null
        if (UnlockDnsWire.truncated(out)) {
            if (u16(out, 6) == 0) return Result.Unusable(Problem.TRUNCATED_EMPTY)
            out = out.copyOf().also { it[2] = (it[2].toInt() and 0x02.inv()).toByte() }
            note = "truncated flag cleared (answer records present)"
        }
        val limit = minOf(appUdpSize(query), mtu - PACKET_OVERHEAD, UnlockDnsWire.MAX_MESSAGE)
        if (out.size > limit) {
            out = fit(out, limit) ?: return Result.Unusable(Problem.TOO_LARGE)
            note = listOfNotNull(note, "answer cut to $limit bytes").joinToString("; ")
        }
        return Result.Ok(out, note)
    }

    /**
     * The first whole answer records of [response] that fit in [limit] bytes, with the authority and additional
     * sections (EDNS included) dropped. Compression pointers only point backwards, so a prefix of the message
     * stays valid. Null when not even one answer record fits.
     */
    fun fit(response: ByteArray, limit: Int): ByteArray? {
        var end = skipQuestions(response) ?: return null
        var kept = 0
        for (i in 0 until u16(response, 6)) {
            val next = skipRecord(response, end) ?: return null
            if (next > limit) break
            end = next
            kept++
        }
        if (kept == 0) return null
        return response.copyOf(end).also { put16(it, 6, kept); put16(it, 8, 0); put16(it, 10, 0) }
    }

    private fun skipName(b: ByteArray, start: Int): Int? {
        var p = start
        var steps = 0
        while (p < b.size && ++steps <= 128) {
            val l = b[p].toInt() and 0xff
            if (l == 0) return p + 1
            if (l and 0xc0 == 0xc0) return if (p + 2 <= b.size) p + 2 else null
            if (l > 63) return null
            p += 1 + l
        }
        return null
    }

    private fun skipQuestions(b: ByteArray): Int? {
        if (b.size < 12) return null
        var p = 12
        repeat(u16(b, 4)) {
            p = (skipName(b, p) ?: return null) + 4
            if (p > b.size) return null
        }
        return p
    }

    private fun skipRecord(b: ByteArray, start: Int): Int? {
        val nameEnd = skipName(b, start) ?: return null
        if (nameEnd + 10 > b.size) return null
        val end = nameEnd + 10 + u16(b, nameEnd + 8)
        return end.takeIf { it <= b.size }
    }

    /** "gemini.google.com A" for the log; null when the question cannot be read. */
    fun describe(query: ByteArray): String? {
        val q = UnlockDnsShaping.question(query) ?: return null
        val labels = mutableListOf<String>()
        var p = 0
        while (p < q.name.size) {
            val l = q.name[p].toInt() and 0xff
            if (l == 0) break
            labels += String(q.name, p + 1, l, Charsets.US_ASCII)
            p += 1 + l
        }
        val type = when (q.type) { 1 -> "A"; 28 -> "AAAA"; 5 -> "CNAME"; 65 -> "HTTPS"; 15 -> "MX"; 16 -> "TXT"; else -> "type ${q.type}" }
        return labels.joinToString(".") + " " + type
    }

    /** True when [shape] sets TC in the question, so an answer's TC bit may be the resolver echoing it. */
    fun setsTc(shape: QueryShape): Boolean = shape == QueryShape.TC_Z || shape == QueryShape.TC
}
