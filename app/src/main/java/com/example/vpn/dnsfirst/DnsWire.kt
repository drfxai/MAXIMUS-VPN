package com.example.vpn.dnsfirst

import java.io.ByteArrayOutputStream
import java.util.Random

/**
 * The small part of the DNS wire format the DNS-first probes need: queries with any QTYPE, an optional EDNS(0)
 * OPT record with RFC 7830 padding (block padding as RFC 8467 recommends for queries), and a reply check that
 * matches the ID and the echoed question and reads the RCODE, the TC bit, the answer types and the EDNS payload
 * size the resolver advertises. Pure; no sockets.
 */
object DnsWire {
    const val TYPE_A = 1
    const val TYPE_CNAME = 5
    const val TYPE_NULL = 10
    const val TYPE_TXT = 16
    const val TYPE_OPT = 41

    const val RCODE_NOERROR = 0
    const val RCODE_FORMERR = 1
    const val RCODE_SERVFAIL = 2
    const val RCODE_NXDOMAIN = 3
    const val RCODE_REFUSED = 5

    /** EDNS padding option code (RFC 7830). */
    const val OPTION_PADDING = 12
    /** Recommended query padding block (RFC 8467). */
    const val PAD_BLOCK = 128
    /** The "DNS flag day 2020" payload size: no IP fragmentation on almost any path. */
    const val EDNS_PAYLOAD = 1232
    /** The longest name the wire format allows, in presentation form without the final dot. */
    const val MAX_NAME = 253

    data class Edns(val udpPayload: Int = EDNS_PAYLOAD, val padBlock: Int = 0)

    data class Reply(
        val rcode: Int,
        val truncated: Boolean,
        val answerTypes: List<Int>,
        /** The UDP payload size in the reply's OPT record; null when the reply carries no EDNS. */
        val ednsPayload: Int?
    ) {
        /** The name was looked up: an answer, or a clean "no such name". REFUSED/SERVFAIL/FORMERR are not. */
        val resolved: Boolean get() = rcode == RCODE_NOERROR || rcode == RCODE_NXDOMAIN
    }

    /** A recursive query for [name] of [type]; [edns] adds an OPT record (padded when its block is above 0). */
    fun query(id: Int, name: String, type: Int, edns: Edns? = null, recursion: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id shr 8 and 0xff); out.write(id and 0xff)
        out.write(if (recursion) 0x01 else 0x00); out.write(0x00)
        out.write(0); out.write(1) // QDCOUNT
        out.write(0); out.write(0) // ANCOUNT
        out.write(0); out.write(0) // NSCOUNT
        out.write(0); out.write(if (edns != null) 1 else 0) // ARCOUNT
        out.write(encodeName(name))
        out.write(type shr 8 and 0xff); out.write(type and 0xff)
        out.write(0); out.write(1) // IN
        if (edns != null) {
            val base = out.size() + 11
            val pad = if (edns.padBlock > 0) {
                val withHeader = base + 4
                (edns.padBlock - withHeader % edns.padBlock) % edns.padBlock
            } else -1
            val rdLength = if (pad >= 0) 4 + pad else 0
            out.write(0) // root name
            out.write(TYPE_OPT shr 8); out.write(TYPE_OPT and 0xff)
            out.write(edns.udpPayload shr 8 and 0xff); out.write(edns.udpPayload and 0xff)
            out.write(0); out.write(0); out.write(0); out.write(0) // extended RCODE, version, flags
            out.write(rdLength shr 8 and 0xff); out.write(rdLength and 0xff)
            if (pad >= 0) {
                out.write(0); out.write(OPTION_PADDING)
                out.write(pad shr 8 and 0xff); out.write(pad and 0xff)
                repeat(pad) { out.write(0) }
            }
        }
        return out.toByteArray()
    }

    fun encodeName(name: String): ByteArray {
        val trimmed = name.trim().trimEnd('.')
        require(trimmed.length in 1..MAX_NAME) { "Invalid DNS name length" }
        val out = ByteArrayOutputStream()
        trimmed.split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            require(bytes.size in 1..63) { "Invalid DNS label" }
            out.write(bytes.size); out.write(bytes)
        }
        out.write(0)
        return out.toByteArray()
    }

    /** Where the question of [query] ends (the byte after QCLASS). */
    fun questionEnd(query: ByteArray): Int {
        var p = 12
        while (p < query.size && query[p].toInt() != 0) p += 1 + (query[p].toInt() and 0xff)
        return p + 5
    }

    /**
     * The reply to [query] in the first [length] bytes of [response], or null when it is not one: another ID,
     * not a response, more or fewer than one question, or a question that is not [query]'s (compared without
     * case, so DNS 0x20 answers match).
     */
    fun parse(query: ByteArray, response: ByteArray, length: Int = response.size): Reply? {
        val qEnd = questionEnd(query)
        if (length < qEnd || length > response.size || qEnd > query.size) return null
        fun u8(i: Int) = response[i].toInt() and 0xff
        fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        if (response[0] != query[0] || response[1] != query[1]) return null
        if (u8(2) and 0x80 == 0) return null
        if (u16(4) != 1) return null
        fun lower(b: Byte) = (b.toInt() and 0xff).let { if (it in 65..90) it + 32 else it }
        if ((12 until qEnd).any { lower(response[it]) != lower(query[it]) }) return null
        val rcode = u8(3) and 0x0f
        val truncated = u8(2) and 0x02 != 0
        val an = u16(6); val ns = u16(8); val ar = u16(10)
        var p = qEnd
        val answerTypes = mutableListOf<Int>()
        var edns: Int? = null
        fun skipName(): Boolean {
            var guard = 0
            while (p < length && guard++ < 128) {
                val l = u8(p)
                when {
                    l == 0 -> { p += 1; return true }
                    l and 0xc0 == 0xc0 -> { p += 2; return p <= length }
                    else -> p += 1 + l
                }
            }
            return false
        }
        for (i in 0 until an + ns + ar) {
            if (!skipName() || p + 10 > length) break
            val type = u16(p); val klass = u16(p + 2); val rd = u16(p + 8)
            p += 10
            if (p + rd > length) break
            if (i < an) answerTypes += type
            if (i >= an + ns && type == TYPE_OPT) edns = klass
            p += rd
        }
        return Reply(rcode, truncated, answerTypes, edns)
    }

    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

    fun randomLabel(random: Random, length: Int): String {
        require(length in 1..63)
        return buildString { repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
    }

    /**
     * A name under [domain] that looks like tunnel traffic: random 63-byte labels (shorter last one) until the
     * whole name is [total] characters (capped at [MAX_NAME]). dnstt and Slipstream queries are this shape.
     */
    fun tunnelLikeName(random: Random, domain: String, total: Int = 200): String {
        val zone = domain.trim().trimEnd('.')
        val budget = total.coerceAtMost(MAX_NAME) - zone.length - 1
        if (budget < 2) return randomLabel(random, 12) + "." + zone
        val labels = mutableListOf<String>()
        var left = budget
        while (left >= 2) {
            val len = minOf(63, left - 1)
            labels += randomLabel(random, len)
            left -= len + 1
        }
        return (labels + zone).joinToString(".")
    }

    /** [query] with the reserved Z bit set (CircumVolve Table 2 row 10); everything else unchanged. */
    fun withZ(query: ByteArray): ByteArray = query.copyOf().also { it[3] = (it[3].toInt() or 0x40).toByte() }
}
