package com.example.vpn.verify

import com.example.data.model.ProfileType
import com.example.data.model.VlessProfile
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** Checks through an engine program's local SOCKS5 port, against a small SOCKS5 server in the test. */
class Socks5Test {
    private lateinit var web: MockWebServer
    private lateinit var socks: ServerSocket
    @Volatile private var seenHost: String? = null

    @Before fun start() {
        web = MockWebServer()
        socks = ServerSocket(0)
    }

    @After fun stop() {
        runCatching { socks.close() }
        runCatching { web.shutdown() }
    }

    /** A SOCKS5 server with one credential that connects every request to [web], whatever name it names. */
    private fun serveSocks(user: String = "u", pass: String = "p", reply: Int = 0) = thread(isDaemon = true) {
        val client = runCatching { socks.accept() }.getOrNull() ?: return@thread
        client.use { c ->
            val input = DataInputStream(c.getInputStream())
            val out = c.getOutputStream()
            input.readUnsignedByte()
            val methods = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
            if (!methods.contains(2)) { out.write(byteArrayOf(5, 0xFF.toByte())); return@thread }
            out.write(byteArrayOf(5, 2))
            input.readUnsignedByte()
            val u = String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) })
            val p = String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) })
            if (u != user || p != pass) { out.write(byteArrayOf(1, 1)); return@thread }
            out.write(byteArrayOf(1, 0))
            input.readUnsignedByte(); input.readUnsignedByte(); input.readUnsignedByte()
            check(input.readUnsignedByte() == 3)
            seenHost = String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) })
            input.readUnsignedShort()
            out.write(byteArrayOf(5, reply.toByte(), 0, 1, 127, 0, 0, 1, 0, 80))
            if (reply != 0) return@thread
            Socket("127.0.0.1", web.port).use { upstream ->
                val up = thread(isDaemon = true) { runCatching { c.getInputStream().copyTo(upstream.getOutputStream()) } }
                runCatching { upstream.getInputStream().copyTo(out) }
                up.join(1000)
            }
        }
    }

    @Test fun theRequestIsCarriedByTheEngineAndTheNameIsResolvedThere() {
        web.enqueue(MockResponse().setBody(Buffer().write(ByteArray(2048))))
        web.start()
        serveSocks()
        val endpoint = Socks5.Endpoint("127.0.0.1", socks.localPort, "u", "p")
        val check = TrafficCheck(RouteDialers.engine(endpoint), tls = false,
            downloads = listOf(TrafficCheck.Target("reference.example", 80, "/bytes", 2048)), stepTimeoutMs = 3_000)
        assertTrue(check.download() is LegResult.Passed)
        // The phone never looked the name up: the engine received it as a name.
        assertEquals("reference.example", seenHost)
    }

    @Test fun aRefusedCredentialFails() {
        web.start()
        serveSocks(pass = "other")
        try {
            Socks5.connect(Socks5.Endpoint("127.0.0.1", socks.localPort, "u", "p"), "reference.example", 80, 2_000)
            fail("a refused credential must not connect")
        } catch (e: Socks5.Socks5Exception) {
            assertTrue(e.message!!.contains("credential"))
        }
    }

    @Test fun aTargetTheEngineCannotReachIsNotTestedRatherThanAStall() {
        web.start()
        serveSocks(reply = 4)
        val check = TrafficCheck(RouteDialers.engine(Socks5.Endpoint("127.0.0.1", socks.localPort, "u", "p")), tls = false,
            downloads = listOf(TrafficCheck.Target("reference.example", 80, "/bytes", 2048)), stepTimeoutMs = 3_000)
        assertTrue(check.download() is LegResult.NotTested)
    }

    @Test fun requestsAreEncodedPerTheRfc() {
        assertArrayEquals(byteArrayOf(5, 2, 0, 2), Socks5.greeting(true))
        assertArrayEquals(byteArrayOf(1, 1, 'a'.code.toByte(), 1, 'b'.code.toByte()), Socks5.authRequest("a", "b"))
        assertArrayEquals(byteArrayOf(5, 1, 0, 3, 1, 'h'.code.toByte(), 1, 0xBB.toByte()), Socks5.connectRequest("h", 443))
    }

    @Test fun theEnginePortIsReadFromTheForwardingProfile() {
        val raw = """{"outbounds":[{"tag":"proxy","protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":40123,"users":[{"user":"x","pass":"y"}]}]}}]}"""
        val p = VlessProfile(id = "e", name = "Tor", address = "", port = 0, uuid = "", profileType = ProfileType.XRAY_JSON, rawConfig = raw)
        assertEquals(Socks5.Endpoint("127.0.0.1", 40123, "x", "y"), Socks5.endpointOf(p))
        // A SOCKS outbound to a remote server is a user's proxy, not an engine's local port.
        val remote = p.copy(rawConfig = raw.replace("127.0.0.1", "198.51.100.4"))
        assertNull(Socks5.endpointOf(remote))
        assertNull(Socks5.endpointOf(p.copy(profileType = ProfileType.VLESS)))
    }
}
