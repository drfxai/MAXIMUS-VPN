package com.example.vpn.stealth.recordslice

import com.example.vpn.stealth.recordslice.TlsRecordSlicer.Vector
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/** Record framing: the records' payloads joined are the original ClientHello, and each record is a valid TLS record. */
class TlsRecordSlicerTest {

    companion object {
        /**
         * A ClientHello like Chrome's with a post-quantum key share (~1.8 KB), the server name extension at
         * [sniIndex] among the extensions, wrapped in one TLS record of version [recordVersion].
         */
        fun clientHello(
            name: String = "www.example.com",
            sniIndex: Int = 3,
            recordVersion: Int = 0x0301,
            keyShareBytes: Int = 1216,
            seed: Int = 7
        ): ByteArray {
            val rnd = Random(seed)
            fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
            fun ext(type: Int, body: ByteArray) = u16(type) + u16(body.size) + body
            val nameBytes = name.toByteArray()
            val sni = ext(0x0000, u16(nameBytes.size + 3) + byteArrayOf(0) + u16(nameBytes.size) + nameBytes)
            val others = mutableListOf(
                ext(0x0a0a, ByteArray(0)),
                ext(0x0017, ByteArray(0)),
                ext(0xff01, byteArrayOf(0)),
                ext(0x000a, u16(6) + u16(0x11ec) + u16(0x001d) + u16(0x0017)),
                ext(0x0010, u16(12) + byteArrayOf(2) + "h2".toByteArray() + byteArrayOf(8) + "http/1.1".toByteArray()),
                ext(0x002b, byteArrayOf(4) + u16(0x0304) + u16(0x0303)),
                ext(0x0033, u16(keyShareBytes + 4) + u16(0x11ec) + u16(keyShareBytes) + rnd.nextBytes(keyShareBytes)),
                ext(0x0015, ByteArray(37))
            )
            others.add(sniIndex.coerceIn(0, others.size), sni)
            val exts = others.fold(ByteArray(0)) { a, b -> a + b }
            val suites = (0 until 16).flatMap { listOf(0x13, it) }.map { it.toByte() }.toByteArray()
            val body = u16(0x0303) + rnd.nextBytes(32) + byteArrayOf(32) + rnd.nextBytes(32) +
                u16(suites.size) + suites + byteArrayOf(1, 0) + u16(exts.size) + exts
            val hs = byteArrayOf(1, (body.size ushr 16).toByte(), (body.size ushr 8).toByte(), body.size.toByte()) + body
            return byteArrayOf(0x16, (recordVersion ushr 8).toByte(), recordVersion.toByte()) + u16(hs.size) + hs
        }

        /** Reads [wire] as TLS records; each must be a valid handshake record. Returns the joined payloads and the headers. */
        fun reassemble(wire: ByteArray): Pair<ByteArray, List<Triple<Int, Int, Int>>> {
            val out = ByteArrayOutputStream()
            val headers = mutableListOf<Triple<Int, Int, Int>>()
            var i = 0
            while (i < wire.size) {
                assertTrue("a whole header at $i", i + 5 <= wire.size)
                val type = wire[i].toInt() and 0xFF
                val version = ((wire[i + 1].toInt() and 0xFF) shl 8) or (wire[i + 2].toInt() and 0xFF)
                val len = ((wire[i + 3].toInt() and 0xFF) shl 8) or (wire[i + 4].toInt() and 0xFF)
                assertEquals(0x16, type)
                assertTrue("record version $version", version == 0x0301 || version == 0x0303)
                assertTrue("never an empty record (REALITY rejects it)", len >= 1)
                assertTrue(len <= TlsRecordSlicer.MAX_RECORD)
                assertTrue("record body fits", i + 5 + len <= wire.size)
                out.write(wire, i + 5, len)
                headers += Triple(type, version, len)
                i += 5 + len
            }
            return out.toByteArray() to headers
        }
    }

    private val hellos = listOf(
        clientHello(),
        clientHello(name = "cdn.a.example.org", sniIndex = 0),
        clientHello(name = "ab.cd", sniIndex = 9),
        clientHello(name = "localhostname", sniIndex = 5),
        clientHello(name = "x.y", sniIndex = 2, recordVersion = 0x0303),
        clientHello(name = "very-long-subdomain-name-for-testing.static.cloudfront.example.net", sniIndex = 4, keyShareBytes = 32)
    )

    @Test fun parsesTheServerName() {
        val hello = clientHello(name = "www.example.com")
        val layout = TlsRecordSlicer.parse(hello)!!
        assertEquals("www.example.com", String(hello, 5 + layout.nameStart, layout.nameLength))
        assertEquals('.'.code.toByte(), hello[5 + layout.firstDot])
        assertEquals(hello.size - 5, layout.payloadLength)
        assertEquals(0x0000, ((hello[5 + layout.sniExtStart].toInt() and 0xFF) shl 8) or (hello[6 + layout.sniExtStart].toInt() and 0xFF))
        assertEquals(-1, TlsRecordSlicer.parse(clientHello(name = "localhostname"))!!.firstDot)
    }

    @Test fun everyVectorKeepsTheClientHelloExactAndEveryRecordValid() {
        for (hello in hellos) for (vector in Vector.entries) for (bursts in 1..2) {
            val sliced = TlsRecordSlicer.slice(hello, hello.size, vector, bursts, Random(1))
            assertNotNull("$vector on ${hello.size} B", sliced)
            sliced!!
            val wire = sliced.bursts.fold(ByteArray(0)) { a, b -> a + b }
            assertArrayEquals(sliced.records.fold(ByteArray(0)) { a, b -> a + b }, wire)
            val (payload, headers) = reassemble(wire)
            assertArrayEquals("$vector: joined payloads are the original ClientHello", hello.copyOfRange(5, hello.size), payload)
            assertEquals(sliced.records.size, headers.size)
            sliced.records.forEach { r -> assertEquals(r.size - 5, ((r[3].toInt() and 0xFF) shl 8) or (r[4].toInt() and 0xFF)) }
            assertTrue(sliced.bursts.size in 1..bursts)
            assertEquals(0, sliced.trailing.size)
        }
    }

    @Test fun recordCountsAndTheCutAtTheFirstDot() {
        val hello = clientHello(name = "www.example.com")
        val layout = TlsRecordSlicer.parse(hello)!!
        val three = TlsRecordSlicer.slice(hello, hello.size, Vector.SNI_DOT_3, 1)!!
        assertEquals(3, three.records.size)
        // The first record ends right before the dot; the second starts with it.
        assertEquals(layout.firstDot, three.records[0].size - 5)
        assertEquals('.'.code.toByte(), three.records[1][5])
        assertEquals(3, three.records[1].size - 5)
        assertEquals(7, TlsRecordSlicer.slice(hello, hello.size, Vector.SNI_FIB_7, 1)!!.records.size)
        val alt = TlsRecordSlicer.slice(hello, hello.size, Vector.ALT_VERSION, 1)!!
        assertEquals(5, alt.records.size)
        alt.records.forEachIndexed { i, r ->
            val v = ((r[1].toInt() and 0xFF) shl 8) or (r[2].toInt() and 0xFF)
            assertEquals(if (i % 2 == 0) 0x0301 else 0x0303, v)
        }
    }

    @Test fun noRecordCarriesTheWholeServerName() {
        for (hello in hellos) for (vector in Vector.entries) {
            val layout = TlsRecordSlicer.parse(hello)!!
            val name = String(hello, 5 + layout.nameStart, layout.nameLength)
            val records = TlsRecordSlicer.slice(hello, hello.size, vector, 1)!!.records
            assertFalse("$vector leaves '$name' whole", records.any { String(it, Charsets.ISO_8859_1).contains(name) })
        }
    }

    @Test fun twoBurstsCutTheWireAbout340To380BytesIn() {
        val hello = clientHello()
        repeat(20) { seed ->
            val sliced = TlsRecordSlicer.slice(hello, hello.size, Vector.SNI_FIB_7, 2, Random(seed))!!
            assertEquals(2, sliced.bursts.size)
            assertTrue(sliced.bursts[0].size in TlsRecordSlicer.BURST_CUT_MIN..TlsRecordSlicer.BURST_CUT_MAX)
        }
        // A short hello is cut after its first records instead; still exactly the records.
        val short = clientHello(name = "a.b", keyShareBytes = 32)
        val s = TlsRecordSlicer.slice(short, short.size, Vector.SNI_FIB_7, 2)!!
        assertEquals(2, s.bursts.size)
        assertArrayEquals(s.records.fold(ByteArray(0)) { a, b -> a + b }, s.bursts[0] + s.bursts[1])
    }

    @Test fun bytesAfterTheClientHelloRecordAreKept() {
        val hello = clientHello()
        val extra = byteArrayOf(0x17, 0x03, 0x03, 0x00, 0x02, 9, 9)
        val data = hello + extra + ByteArray(64)
        val sliced = TlsRecordSlicer.slice(data, hello.size + extra.size, Vector.SNI_DOT_3)!!
        assertArrayEquals(extra, sliced.trailing)
        assertArrayEquals(hello.copyOfRange(5, hello.size), reassemble(sliced.bursts.fold(ByteArray(0)) { a, b -> a + b }).first)
    }

    @Test fun anythingButAWholeClientHelloWithANameIsLeftAlone() {
        val hello = clientHello()
        // Not a handshake record.
        assertNull(TlsRecordSlicer.slice("GET / HTTP/1.1\r\n\r\n".toByteArray(), 18, Vector.SNI_DOT_3))
        // Incomplete record.
        assertNull(TlsRecordSlicer.slice(hello, hello.size - 10, Vector.SNI_DOT_3))
        // A ClientHello longer than its record (spread over two records).
        val spread = hello.copyOf().also { it[8] = (it[8] + 1).toByte() }
        assertNull(TlsRecordSlicer.parse(spread))
        // No server name extension.
        val noSni = clientHello().let { h ->
            val layout = TlsRecordSlicer.parse(h)!!
            h.copyOf().also { it[5 + layout.sniExtStart + 1] = 0x77 } // the extension type is no longer server_name
        }
        assertNull(TlsRecordSlicer.parse(noSni))
        // Zero-length record header.
        assertNull(TlsRecordSlicer.firstRecordLength(byteArrayOf(0x16, 3, 1, 0, 0)))
    }

    @Test fun framingRefusesEmptyRecords() {
        val payload = ByteArray(10) { it.toByte() }
        val ok = TlsRecordSlicer.frame(payload, listOf(3, 4), intArrayOf(0x0301))
        assertEquals(listOf(3, 1, 6), ok.map { it.size - 5 })
        assertTrue(runCatching { TlsRecordSlicer.frame(payload, listOf(3, 3), intArrayOf(0x0301)) }.isFailure)
    }
}
