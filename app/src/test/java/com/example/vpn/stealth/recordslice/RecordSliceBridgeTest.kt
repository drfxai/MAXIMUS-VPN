package com.example.vpn.stealth.recordslice

import com.example.vpn.stealth.recordslice.TlsRecordSlicer.Vector
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** The loopback bridge end to end: the server receives valid records carrying the exact ClientHello, then the bytes unchanged. */
class RecordSliceBridgeTest {

    /** A fake server that reads [expect] bytes, answers [reply] and returns what it read. */
    private fun server(expect: Int, reply: ByteArray): Pair<ServerSocket, CompletableFuture<ByteArray>> {
        val ss = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        val got = CompletableFuture<ByteArray>()
        Thread {
            runCatching {
                ss.accept().use { s ->
                    s.soTimeout = 10_000
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    while (out.size() < expect) {
                        val r = s.getInputStream().read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                    }
                    s.getOutputStream().write(reply)
                    s.getOutputStream().flush()
                    got.complete(out.toByteArray())
                }
            }.onFailure { got.completeExceptionally(it) }
        }.apply { isDaemon = true }.start()
        return ss to got
    }

    private fun exchange(bridge: RecordSliceBridge, send: ByteArray, replyLen: Int): ByteArray =
        Socket().use { c ->
            c.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), bridge.port), 5_000)
            c.soTimeout = 10_000
            c.getOutputStream().write(send)
            c.getOutputStream().flush()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (out.size() < replyLen) {
                val r = c.getInputStream().read(buf)
                if (r < 0) break
                out.write(buf, 0, r)
            }
            out.toByteArray()
        }

    @Test fun theClientHelloArrivesAsValidRecordsAndTheRestUnchanged() {
        for (vector in Vector.entries) {
            val hello = TlsRecordSlicerTest.clientHello()
            val appData = Random(3).nextBytes(40_000)
            val reply = Random(4).nextBytes(5_000)
            val layout = TlsRecordSlicer.parse(hello)!!
            val records = TlsRecordSlicer.cuts(layout, vector).size + 1
            val (ss, got) = server(hello.size + records * 5 - 5 + appData.size, reply)
            var protected = 0
            val bridge = RecordSliceBridge("127.0.0.1", ss.localPort, RecordSliceConfig.Spec(vector, vector.defaultBursts, mss = 1360),
                RecordSliceBridge.Hooks(protect = { protected++; true }, setMss = { _, mss -> assertEquals(1360, mss); true })).start()
            try {
                assertArrayEquals(reply, exchange(bridge, hello + appData, reply.size))
                val wire = got.get(10, TimeUnit.SECONDS)
                val helloWire = wire.copyOfRange(0, wire.size - appData.size)
                val (payload, headers) = TlsRecordSlicerTest.reassemble(helloWire)
                assertArrayEquals("$vector", hello.copyOfRange(5, hello.size), payload)
                assertEquals(records, headers.size)
                assertArrayEquals(appData, wire.copyOfRange(wire.size - appData.size, wire.size))
                assertEquals(1, bridge.sliced.get())
                assertEquals(1, protected)
            } finally {
                bridge.close(); ss.close()
            }
        }
    }

    @Test fun anythingElseIsRelayedAsIs() {
        val data = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray() + Random(5).nextBytes(20_000)
        val (ss, got) = server(data.size, byteArrayOf(1, 2, 3))
        val bridge = RecordSliceBridge("127.0.0.1", ss.localPort, RecordSliceConfig.Spec(Vector.SNI_DOT_3, 1)).start()
        try {
            assertArrayEquals(byteArrayOf(1, 2, 3), exchange(bridge, data, 3))
            assertArrayEquals(data, got.get(10, TimeUnit.SECONDS))
            assertEquals(0, bridge.sliced.get())
            assertEquals(1, bridge.passedThrough.get())
        } finally {
            bridge.close(); ss.close()
        }
    }

    @Test fun aRefusedProtectionNeverDialsTheServer() {
        val ss = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        val bridge = RecordSliceBridge("127.0.0.1", ss.localPort, RecordSliceConfig.Spec(Vector.SNI_DOT_3, 1),
            RecordSliceBridge.Hooks(protect = { false }, firstRecordWaitMs = 200)).start()
        try {
            val hello = TlsRecordSlicerTest.clientHello()
            assertEquals(0, exchange(bridge, hello, 1).size)
            ss.soTimeout = 300
            assertTrue(runCatching { ss.accept() }.isFailure)
        } finally {
            bridge.close(); ss.close()
        }
    }

    @Test fun oneBridgePerServerAndSpecIsShared() {
        RecordSliceBridges.closeAll()
        try {
            val spec = RecordSliceConfig.Spec(Vector.SNI_FIB_7, 2)
            val a = RecordSliceBridges.portFor("203.0.113.10", 443, spec)
            assertEquals(a, RecordSliceBridges.portFor("203.0.113.10", 443, spec))
            assertTrue(a != RecordSliceBridges.portFor("203.0.113.10", 443, spec.copy(vector = Vector.ALT_VERSION)))
        } finally {
            RecordSliceBridges.closeAll()
        }
    }
}
