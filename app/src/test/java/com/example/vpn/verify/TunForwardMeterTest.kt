package com.example.vpn.verify

import com.example.data.model.ProfileType
import com.example.data.model.VlessProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** tun.forwarded: the byte meter on the TUN → engine hop and the verdict thresholds. */
class TunForwardMeterTest {
    private val closeables = mutableListOf<AutoCloseable>()
    @After fun stop() { closeables.forEach { runCatching { it.close() } } }

    private fun snap(down: Long, up: Long, flows: Int = 4, dataFlows: Int = 2, unreachable: Int = 0) =
        TunForwardMeter.Snapshot(at = 10_000, startedAt = 0, downBytes = down, upBytes = up, flows = flows,
            activeFlows = 0, dataFlows = dataFlows, engineUnreachable = unreachable, resets = 0)

    @Test fun theTunIsVerifiedOnlyWhenOtherAppsTrafficFlowedBothWaysWithoutErrors() {
        val handshakes = 4 * TunForwarded.SOCKS_UP_ALLOWANCE
        assertEquals(TunForwarded.State.VERIFIED, TunForwarded.verdict(snap(32 * 1024, handshakes + 1024)))
        // Too little down, or only SOCKS handshakes up: still waiting, never a failure.
        assertEquals(TunForwarded.State.WAITING, TunForwarded.verdict(snap(32 * 1024 - 1, handshakes + 1024)))
        assertEquals(TunForwarded.State.WAITING, TunForwarded.verdict(snap(64 * 1024, handshakes)))
        assertEquals(TunForwarded.State.WAITING, TunForwarded.verdict(snap(64 * 1024, handshakes + 4096, dataFlows = 0)))
        assertEquals(TunForwarded.State.WAITING, TunForwarded.verdict(snap(0, 0, flows = 0, dataFlows = 0)))
        // The engine refused the TUN's flows and nothing came back: failed. Errors beside traffic: not verified.
        assertEquals(TunForwarded.State.FAILED, TunForwarded.verdict(snap(0, 900, flows = 3, dataFlows = 0, unreachable = 3)))
        assertEquals(TunForwarded.State.WAITING, TunForwarded.verdict(snap(64 * 1024, handshakes + 4096, unreachable = 1)))
        assertEquals(TunForwarded.State.UNAVAILABLE, TunForwarded.verdict(null))
    }

    @Test fun aWindowIsTheDifferenceOfTwoSnapshots() {
        val a = snap(1000, 500, flows = 1, dataFlows = 1).copy(at = 1_000)
        val b = snap(50_000, 9_000, flows = 5, dataFlows = 3).copy(at = 31_000)
        val w = b - a
        assertEquals(49_000, w.downBytes)
        assertEquals(8_500, w.upBytes)
        assertEquals(4, w.flows)
        assertEquals(2, w.dataFlows)
        assertEquals(30_000, w.windowMs)
        val attrs = TunForwarded.attributes(w)
        assertEquals("49000", attrs["rx.bytes"])
        assertEquals("8500", attrs["tx.bytes"])
        assertEquals("4", attrs["flows"])
    }

    @Test fun theMeterRelaysAndCountsBothDirections() {
        val engine = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).also { closeables += it }
        val reply = ByteArray(40 * 1024) { (it % 251).toByte() }
        thread(isDaemon = true) {
            val c = runCatching { engine.accept() }.getOrNull() ?: return@thread
            c.use { s ->
                DataInputStream(s.getInputStream()).readFully(ByteArray(2048))
                s.getOutputStream().write(reply)
                s.getOutputStream().flush()
            }
        }
        val meter = TunForwardMeter.start(engine.localPort)!!
        closeables += meter
        Socket("127.0.0.1", meter.port).use { s ->
            s.soTimeout = 5_000
            s.getOutputStream().write(ByteArray(2048) { 7 })
            s.getOutputStream().flush()
            val got = s.getInputStream().readBytes()
            assertTrue(got.contentEquals(reply))
        }
        val deadline = System.currentTimeMillis() + 5_000
        var snap = meter.snapshot()
        while (System.currentTimeMillis() < deadline && (snap.dataFlows < 1 || snap.activeFlows > 0)) {
            Thread.sleep(50)
            snap = meter.snapshot()
        }
        assertEquals(1, snap.flows)
        assertEquals(1, snap.dataFlows)
        assertEquals(reply.size.toLong(), snap.downBytes)
        assertEquals(2048L, snap.upBytes)
        assertEquals(0, snap.engineUnreachable)
        assertEquals(TunForwarded.State.VERIFIED, TunForwarded.verdict(snap))
    }

    @Test fun anEngineThatRefusesTheTunsFlowsIsCounted() {
        val gone = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = gone.localPort
        gone.close()
        val meter = TunForwardMeter.start(port, connectTimeoutMs = 1_000)!!
        closeables += meter
        Socket("127.0.0.1", meter.port).use { s ->
            s.soTimeout = 5_000
            assertEquals(-1, s.getInputStream().read())
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && meter.snapshot().engineUnreachable == 0) Thread.sleep(50)
        val snap = meter.snapshot()
        assertEquals(1, snap.engineUnreachable)
        assertEquals(TunForwarded.State.FAILED, TunForwarded.verdict(snap))
        meter.close()
        assertTrue(meter.isClosed)
    }

    @Test fun onlyTheEnginesSocksForwardIsRewiredToTheMeter() {
        val raw = """{"outbounds":[{"tag":"proxy","protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":40123,"users":[{"user":"x","pass":"y"}]}]}}]}"""
        val p = VlessProfile(id = "e", name = "Tor", address = "", port = 0, uuid = "", profileType = ProfileType.XRAY_JSON, rawConfig = raw)
        val rewired = TunForwardMeter.rewire(p, 51000)!!
        assertEquals(Socks5.Endpoint("127.0.0.1", 51000, "x", "y"), Socks5.endpointOf(rewired))
        assertEquals("the profile the app's own checks use is unchanged", 40123, Socks5.endpointOf(p)!!.port)
        // ByeDPI wraps the real server with a SOCKS dialer: not a plain engine forward, left alone.
        val byedpi = """{"outbounds":[{"tag":"proxy","protocol":"vless","settings":{}},{"tag":"fake-sni-dialer","protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":40123}]}}]}"""
        assertNull(TunForwardMeter.rewire(p.copy(rawConfig = byedpi), 51000))
        assertNull(TunForwardMeter.rewire(p.copy(profileType = ProfileType.VLESS), 51000))
    }
}
