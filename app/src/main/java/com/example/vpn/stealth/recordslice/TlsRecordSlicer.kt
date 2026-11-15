package com.example.vpn.stealth.recordslice

import kotlin.random.Random

/**
 * Re-frames the first TLS record a client sends (its ClientHello) into several smaller, valid TLS handshake
 * records, cut around the server name, so a filter that reads the SNI from one record (or one TCP segment)
 * cannot. The handshake bytes are never changed: the records' payloads, joined, are exactly the original
 * ClientHello, so the TLS (and REALITY) server reassembles the same message and the REALITY authentication,
 * which is computed over that message, still verifies.
 *
 * Rules every vector keeps (REALITY-safe):
 * - no zero-length record: Go's TLS stack, and so REALITY, answers an empty handshake record with
 *   `unexpected_message` (crypto/tls conn.go, `case recordTypeHandshake: if len(data) == 0`), which is what
 *   stopped the old empty-record split on REALITY servers;
 * - record versions only 0x0301 or 0x0303 (accepted before the version is negotiated);
 * - record type stays handshake (0x16) and every record is at most [MAX_RECORD] bytes.
 *
 * Xray's own `finalmask` fragment (`packets: "tlshello"`, libXray v26.10.31) also re-frames the ClientHello into
 * records, but only at fixed or random lengths and with the original record version: it cannot find the server
 * name (Chrome's fingerprint shuffles the extension order, so its offset changes per connection) nor alternate
 * record versions. These vectors therefore run in a local bridge ([RecordSliceBridge]).
 *
 * Vectors adapted from Alan-RIP-Dpi (DrAlanK / LabAlan, MIT; TlsByteMutator `sni_dot_3rec`, `fibonacci_7rec`
 * and `chameleon_interleave`, see THIRD_PARTY_NOTICES.md), rewritten with an exact ClientHello parser. Pure.
 */
object TlsRecordSlicer {
    const val CONTENT_HANDSHAKE = 0x16
    const val HANDSHAKE_CLIENT_HELLO = 0x01
    const val TLS10 = 0x0301
    const val TLS12 = 0x0303
    const val HEADER = 5
    const val MAX_RECORD = 16384

    /** Where the second write starts in a two-burst send: about 340–380 bytes in (Alan-RIP-Dpi's tail stride). */
    const val BURST_CUT_MIN = 340
    const val BURST_CUT_MAX = 380

    enum class Vector(val key: String, val label: String, val defaultBursts: Int) {
        /** Three records: up to the first dot of the server name, the next 3 bytes, the rest. */
        SNI_DOT_3("sni-dot-3", "TLS handshake in 3 records cut at the server name", 1),
        /** Seven records: two up to just before the first dot, then 1, 2, 3 and 5 bytes, then the rest. */
        SNI_FIB_7("sni-fib-7", "TLS handshake in 7 records around the server name", 2),
        /** Five records alternating 0x0301 / 0x0303: up to the SNI extension, then cut inside the server name. */
        ALT_VERSION("alt-version", "TLS handshake in records of alternating versions", 2);

        companion object {
            fun of(key: String?): Vector? = entries.firstOrNull { it.key == key }
        }
    }

    /**
     * Offsets inside the first record's payload (the handshake message, record header excluded).
     * [firstDot] is -1 when the name has no dot.
     */
    data class Layout(
        val recordVersion: Int,
        val payloadLength: Int,
        val sniExtStart: Int,
        val nameStart: Int,
        val nameLength: Int,
        val firstDot: Int
    )

    /**
     * [records] are the new records in order (each with its 5-byte header); [bursts] are the writes they leave
     * in (one or two, together exactly the records); [trailing] is whatever followed the ClientHello record in
     * the input, to be written after them unchanged.
     */
    class Sliced(val records: List<ByteArray>, val bursts: List<ByteArray>, val trailing: ByteArray) {
        val wire: ByteArray get() = bursts.fold(ByteArray(0)) { acc, b -> acc + b } + trailing
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun u24(b: ByteArray, i: Int) = (u8(b, i) shl 16) or (u8(b, i + 1) shl 8) or u8(b, i + 2)

    /** Bytes the first record needs in total (header included), or null when [data] does not start a handshake record. */
    fun firstRecordLength(data: ByteArray, length: Int = data.size): Int? {
        if (length < HEADER) return null
        if (u8(data, 0) != CONTENT_HANDSHAKE || u8(data, 1) != 0x03 || u8(data, 2) > 0x04) return null
        val len = u16(data, 3)
        if (len !in 1..MAX_RECORD) return null
        return HEADER + len
    }

    /**
     * The layout of a ClientHello carried whole in the first record of [data], or null when it is not one (another
     * record type, a hello spread over several records, a malformed hello, or no server name).
     */
    fun parse(data: ByteArray, length: Int = data.size): Layout? {
        val total = firstRecordLength(data, length) ?: return null
        if (length < total) return null
        val p = HEADER
        val payloadLength = total - HEADER
        try {
            if (u8(data, p) != HANDSHAKE_CLIENT_HELLO) return null
            // Exactly one whole ClientHello in this record.
            if (u24(data, p + 1) + 4 != payloadLength) return null
            var i = p + 4 + 2 + 32 // type, length, legacy_version, random
            val sid = u8(data, i); i += 1 + sid
            val suites = u16(data, i); i += 2 + suites
            val compression = u8(data, i); i += 1 + compression
            val extTotal = u16(data, i); i += 2
            val extEnd = i + extTotal
            if (extEnd != total) return null
            while (i + 4 <= extEnd) {
                val type = u16(data, i)
                val len = u16(data, i + 2)
                val body = i + 4
                if (body + len > extEnd) return null
                if (type == 0x0000) {
                    if (len < 5) return null
                    val listLen = u16(data, body)
                    if (listLen + 2 != len || u8(data, body + 2) != 0) return null
                    val nameLen = u16(data, body + 3)
                    val name = body + 5
                    if (nameLen < 1 || name + nameLen > body + len) return null
                    var dot = -1
                    for (k in name until name + nameLen) if (data[k] == '.'.code.toByte()) { dot = k; break }
                    return Layout(
                        recordVersion = u16(data, 1), payloadLength = payloadLength,
                        sniExtStart = i - p, nameStart = name - p, nameLength = nameLen,
                        firstDot = if (dot < 0) -1 else dot - p
                    )
                }
                i = body + len
            }
            return null
        } catch (_: IndexOutOfBoundsException) {
            return null
        }
    }

    /**
     * Cut points inside the payload for [vector]: strictly increasing, each in 1 until [Layout.payloadLength], so
     * every record carries at least one byte.
     */
    fun cuts(layout: Layout, vector: Vector): List<Int> {
        val n = layout.payloadLength
        val nameEnd = layout.nameStart + layout.nameLength
        // A name without a dot is cut in its middle instead.
        val dot = if (layout.firstDot >= 0) layout.firstDot else layout.nameStart + layout.nameLength / 2
        val raw = when (vector) {
            Vector.SNI_DOT_3 -> listOf(dot, dot + 3)
            Vector.SNI_FIB_7 -> {
                val first = layout.nameStart + 2
                val anchor = maxOf(first + 1, dot - 3)
                listOf(first, anchor, anchor + 1, anchor + 3, anchor + 6, anchor + 11)
            }
            Vector.ALT_VERSION -> {
                val step = (layout.nameLength / 3).coerceIn(3, 6)
                val inName = layout.nameStart + 2
                listOf(layout.sniExtStart, inName, minOf(inName + step, nameEnd), minOf(inName + 2 * step, nameEnd))
            }
        }
        val out = mutableListOf<Int>()
        for (c in raw) if (c in 1 until n && (out.isEmpty() || c > out.last())) out += c
        return out
    }

    /** Record versions for [vector], record by record (repeating). */
    fun versions(vector: Vector): IntArray = when (vector) {
        Vector.SNI_DOT_3 -> intArrayOf(TLS10, TLS12, TLS12)
        Vector.SNI_FIB_7 -> intArrayOf(TLS10, TLS12, TLS12, TLS10, TLS12, TLS12, TLS10)
        Vector.ALT_VERSION -> intArrayOf(TLS10, TLS12)
    }

    /** [payload] cut at [cuts] into handshake records with [versions] (repeating). */
    fun frame(payload: ByteArray, cuts: List<Int>, versions: IntArray): List<ByteArray> {
        require(versions.isNotEmpty())
        val bounds = listOf(0) + cuts + payload.size
        val out = ArrayList<ByteArray>(bounds.size - 1)
        for (k in 0 until bounds.size - 1) {
            val from = bounds[k]
            val len = bounds[k + 1] - from
            require(len in 1..MAX_RECORD) { "record $k would carry $len bytes" }
            val v = versions[k % versions.size]
            val r = ByteArray(HEADER + len)
            r[0] = CONTENT_HANDSHAKE.toByte()
            r[1] = (v ushr 8).toByte()
            r[2] = v.toByte()
            r[3] = (len ushr 8).toByte()
            r[4] = len.toByte()
            System.arraycopy(payload, from, r, HEADER, len)
            out += r
        }
        return out
    }

    /**
     * The records joined into one write, or into two when [bursts] is 2: the first ~[BURST_CUT_MIN]–
     * [BURST_CUT_MAX] bytes, then the rest (a TCP-level cut; record boundaries are unaffected). A wire too short
     * for that is cut after the first records instead; a single record stays one write.
     */
    fun bursts(records: List<ByteArray>, bursts: Int, random: Random = Random.Default): List<ByteArray> {
        val wire = records.fold(ByteArray(0)) { acc, r -> acc + r }
        if (bursts < 2 || records.size < 2) return listOf(wire)
        var cut = BURST_CUT_MIN + random.nextInt(BURST_CUT_MAX - BURST_CUT_MIN + 1)
        if (cut >= wire.size - 24) cut = records.take((records.size + 1) / 2).sumOf { it.size }
        if (cut !in 1 until wire.size) return listOf(wire)
        return listOf(wire.copyOfRange(0, cut), wire.copyOfRange(cut, wire.size))
    }

    /**
     * [data] (the client's first bytes, [length] of them) with its ClientHello record re-framed for [vector] in
     * [burstCount] writes; null when the first record is not a whole ClientHello with a server name (the caller
     * then forwards the bytes unchanged).
     */
    fun slice(data: ByteArray, length: Int, vector: Vector, burstCount: Int = vector.defaultBursts, random: Random = Random.Default): Sliced? {
        val layout = parse(data, length) ?: return null
        val end = HEADER + layout.payloadLength
        val payload = data.copyOfRange(HEADER, end)
        val cuts = cuts(layout, vector)
        if (cuts.isEmpty()) return null
        val records = frame(payload, cuts, versions(vector))
        return Sliced(records, bursts(records, burstCount, random), data.copyOfRange(end, length))
    }
}
