package com.example.vpn.connections

import com.example.data.model.AppSettings
import com.example.data.model.OperationalMode
import com.example.data.model.VlessProfile
import com.example.vpn.engine.ProfileExtras
import com.example.vpn.sidecar.AetherSettings
import com.example.vpn.sidecar.AetherSidecar
import com.example.vpn.sidecar.BuiltinExitNode
import com.example.vpn.sidecar.LanternRelayHop
import com.example.vpn.sidecar.RunningEngine
import com.example.vpn.sidecar.SidecarContext
import com.example.vpn.sidecar.SidecarEngine
import com.example.vpn.sidecar.SidecarLaunch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Build 170 (Irancell, metered, cf_quick = filtered): the user's own Aether run ("Irancell MASQUE HTTP/2: h2-split, h2")
 * spent ~45 s per gateway scan, each "completed without a verified endpoint", the hold was remembered, and the next two
 * runs inside the hold repeated both scans and ended TLS_HANDSHAKE_FAILED, while Lantern, Serverless and Tor carried
 * traffic on the same network.
 */
class LanternRelayFallbackTest {
    private val store = HashMap<String, String>()
    private lateinit var previousRead: (String) -> String?
    private lateinit var previousWrite: (String, String?) -> Unit
    private lateinit var previousNetwork: () -> String?
    private var network = "wifi:lantern-test"

    private val attempted = mutableListOf<String>()
    private val attemptedExtras = mutableListOf<org.json.JSONObject>()

    /** Every carrier fails to start; the Lantern hop fails with its fixed "not ready" reason. */
    private val engine = object : SidecarEngine {
        override val id = AetherSidecar.ID
        override val binary = id
        override fun handles(profile: VlessProfile) = AetherSidecar.handles(profile)
        override fun prepare(profile: VlessProfile, settings: AppSettings, context: SidecarContext) = SidecarLaunch(listOf("unused"))
        override fun startPrepared(profile: VlessProfile, settings: AppSettings, launch: SidecarLaunch, context: SidecarContext): RunningEngine? {
            val e = ProfileExtras.read(profile)
            attemptedExtras += e
            attempted += when {
                e.optBoolean(LanternRelayHop.EXTRA) -> "lantern"
                e.optBoolean(AetherSidecar.SNI_SPLIT) -> "h2-split"
                else -> e.getString("carrier")
            }
            if (e.optBoolean(LanternRelayHop.EXTRA)) throw BuiltinExitNode.ExitNodeFailure(LanternRelayHop.NOT_READY)
            error("carrier did not start")
        }
    }
    private val context = SidecarContext(File("build/tmp/lantern-relay-test").apply { mkdirs() }, File("unused"), 12345,
        "u", "p", OperationalMode.DAILY)

    @Before fun setUp() {
        previousRead = BuiltinMethodMemory.read
        previousWrite = BuiltinMethodMemory.write
        previousNetwork = BuiltinMethodMemory.network
        BuiltinMethodMemory.read = { store[it] }
        BuiltinMethodMemory.write = { k, v -> if (v == null) store.remove(k) else store[k] = v }
        BuiltinMethodMemory.network = { network }
        WarpDataPlaneHold.resetForTest()
        WarpRegistrationMemory.resetForTest()
        AetherLadderGuard.resetForTest()
        LanternRelayMemory.resetForTest()
    }

    @After fun tearDown() {
        BuiltinMethodMemory.read = previousRead
        BuiltinMethodMemory.write = previousWrite
        BuiltinMethodMemory.network = previousNetwork
        WarpDataPlaneHold.resetForTest()
        WarpRegistrationMemory.resetForTest()
        AetherLadderGuard.resetForTest()
        LanternRelayMemory.resetForTest()
    }

    private fun run(manual: Boolean, cloudflareBlocked: Boolean, lantern: Boolean, relayExitNode: String? = null) =
        assertThrows(ConnectionEngineRunner.NoVerifiedPath::class.java) {
            ConnectionEngineRunner.start(engine, AetherSidecar.profile(), AppSettings(), context,
                preferWireGuard = cloudflareBlocked, cloudflareBlocked = cloudflareBlocked, manual = manual,
                lanternAvailable = lantern, relayExitNode = relayExitNode)
        }

    @Test fun insideTheHoldWithCloudflareFilteredAndNoLanternTheUsersRunFailsAtOnce() {
        WarpDataPlaneHold.record(network)
        val failure = run(manual = true, cloudflareBlocked = true, lantern = false)
        assertTrue(attempted.toString(), attempted.isEmpty())
        val message = failure.message.orEmpty()
        assertTrue(message, message.startsWith("Aether skipped: Cloudflare is filtered"))
        assertTrue(message, message.contains("Lantern or Serverless"))
    }

    @Test fun insideTheHoldWithCloudflareFilteredTheUsersRunGoesStraightThroughLantern() {
        WarpDataPlaneHold.record(network)
        val failure = run(manual = true, cloudflareBlocked = true, lantern = true)
        assertEquals(listOf("lantern"), attempted)
        val extras = attemptedExtras.single()
        assertEquals("h2", extras.getString("carrier"))
        assertFalse(extras.optBoolean(AetherSidecar.SNI_SPLIT))
        assertTrue(extras.optString("exitNode").isBlank())
        assertTrue(failure.message.orEmpty(), failure.message.orEmpty()
            .contains("${ConnectionEngineRunner.H2_LANTERN}: ${LanternRelayHop.NOT_READY}"))
        // A failure through Lantern says nothing new about this network's own path: the hold is unchanged.
        assertTrue(WarpDataPlaneHold.blocked(network))
    }

    @Test fun withoutTheHoldTheDirectCarriersRunFirstAndLanternLast() {
        val failure = run(manual = true, cloudflareBlocked = true, lantern = true)
        assertEquals("lantern", attempted.last())
        assertEquals(1, attempted.count { it == "lantern" })
        assertTrue(attempted.toString(), attempted.containsAll(listOf("h2", "h3", "wireguard")))
        assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains(ConnectionEngineRunner.H2_LANTERN))
    }

    @Test fun onIrancellTheHttp2PlanRunsThenLantern() {
        network = "cell:43235"
        run(manual = true, cloudflareBlocked = true, lantern = true)
        assertEquals("lantern", attempted.last())
        assertTrue(attempted.toString(), attempted.dropLast(1).isNotEmpty() && attempted.dropLast(1).all { it == "h2" || it == "h2-split" })
    }

    @Test fun cloudflareReachableInsideTheHoldKeepsTheUsersFullRunAndNoLantern() {
        WarpDataPlaneHold.record(network)
        run(manual = true, cloudflareBlocked = false, lantern = true)
        assertFalse(attempted.toString(), "lantern" in attempted)
        assertTrue(attempted.toString(), attempted.isNotEmpty())
    }

    @Test fun automaticRunsNeverUseLanternAndStillSkipInsideTheHold() {
        run(manual = false, cloudflareBlocked = true, lantern = true)
        assertFalse(attempted.toString(), "lantern" in attempted)
        attempted.clear()
        AetherLadderGuard.resetForTest()
        WarpDataPlaneHold.record(network)
        run(manual = false, cloudflareBlocked = true, lantern = true)
        assertTrue(attempted.toString(), attempted.isEmpty())
    }

    @Test fun eligibilityNeedsTheUsersRunFilteredCloudflareRadianceAndNoSavedServerRelay() {
        val plain = AetherSettings()
        fun eligible(automatic: Boolean = true, manual: Boolean = true, cf: Boolean = true, lantern: Boolean = true,
                     options: AetherSettings = plain, exit: String? = null, relayOnly: Boolean = false) =
            ConnectionEngineRunner.lanternRelayEligible(AetherSidecar.ID, automatic, manual, cf, lantern, options, exit, relayOnly)
        assertTrue(eligible())
        assertFalse(eligible(automatic = false))
        assertFalse(eligible(manual = false))
        assertFalse(eligible(cf = false))
        assertFalse(eligible(lantern = false))
        assertFalse(eligible(exit = "saved-server"))
        assertFalse(eligible(relayOnly = true))
        assertFalse(eligible(options = plain.copy(psiphon = "chain")))
        assertFalse(ConnectionEngineRunner.lanternRelayEligible("serverless", true, true, true, true, plain, null, false))
    }

    @Test fun theLanternCandidateIsPlainHttp2WithNoSplitOrRememberedGateway() {
        val split = AetherSidecar.profile("h2").let { p ->
            p.copy(extraSettings = org.json.JSONObject(p.extraSettings).put(AetherSidecar.SNI_SPLIT, true)
                .put(AetherSidecar.CLEAN_H2_PEER, "162.159.198.1:443").put("rescan", true).toString())
        }
        val e = ProfileExtras.read(ConnectionEngineRunner.lanternRelayed(split))
        assertTrue(LanternRelayHop.requested(e))
        assertEquals("h2", e.getString("carrier"))
        assertFalse(e.has(AetherSidecar.SNI_SPLIT) || e.has(AetherSidecar.CLEAN_H2_PEER) || e.has("rescan"))
        assertTrue(e.getBoolean("relayFollowUp"))
    }

    @Test fun lanternHopFailuresAreShownByTheirFixedReason() {
        for (reason in LanternRelayHop.REASONS) {
            assertEquals(reason, ConnectionEngineRunner.startFailureReason(AetherSidecar.ID,
                BuiltinExitNode.ExitNodeFailure(reason), "engine process could not start"))
        }
        assertEquals("socks5://user:pass@127.0.0.1:2080", LanternRelayHop.upstream("user", "pass", 2080))
    }

    @Test fun theFastFailMessageSaysHowLongTheHoldLasts() {
        WarpDataPlaneHold.record(network, now = 0)
        val message = WarpDataPlaneHold.fastFailMessage(network, now = 10 * 60_000L)
        assertTrue(message, message.contains("10 min ago"))
        assertTrue(message, message.contains("about 20 more min"))
        assertTrue(message, message.contains("Lantern or Serverless"))
    }

    // --- Update from the reference review: the Lantern hop fails once per network, then fast ---

    @Test fun aFailedLanternHopIsRememberedAndTheNextRunInsideTheHoldStopsAtOnce() {
        WarpDataPlaneHold.record(network)
        run(manual = true, cloudflareBlocked = true, lantern = true)
        assertEquals(listOf("lantern"), attempted)
        assertTrue(LanternRelayMemory.blocked(network))
        assertTrue(store.containsKey("lanternHopFailed#$network"))

        attempted.clear()
        val failure = run(manual = true, cloudflareBlocked = true, lantern = true)
        assertTrue(attempted.toString(), attempted.isEmpty())
        val message = failure.message.orEmpty()
        assertTrue(message, message.startsWith("Aether skipped: Cloudflare is filtered"))
        assertTrue(message, message.contains("WARP through Lantern also failed here"))
        assertTrue(message, message.contains("Lantern or Serverless"))
    }

    @Test fun outsideTheHoldARememberedLanternFailureLeavesOnlyTheDirectCarriers() {
        LanternRelayMemory.record(network)
        run(manual = true, cloudflareBlocked = true, lantern = true)
        assertFalse(attempted.toString(), "lantern" in attempted)
        assertTrue(attempted.toString(), attempted.containsAll(listOf("h2", "h3", "wireguard")))
    }

    @Test fun theLanternMemoryIsPerNetworkExpiresAndIgnoresAMissingRuntime() {
        LanternRelayMemory.record(network, now = 0)
        assertTrue(LanternRelayMemory.blocked(network, now = LanternRelayMemory.HOLD_MS - 1))
        assertFalse(LanternRelayMemory.blocked(network, now = LanternRelayMemory.HOLD_MS))
        assertFalse(LanternRelayMemory.blocked("cell:other", now = 1))
        // Survives a restart through BuiltinMethodMemory's store.
        LanternRelayMemory.resetForTest()
        assertTrue(LanternRelayMemory.blocked(network, now = 1))
        LanternRelayMemory.clear(network)
        assertFalse(LanternRelayMemory.blocked(network, now = 1))

        assertFalse(LanternRelayMemory.counts(LanternRelayHop.RUNTIME_MISSING))
        assertTrue(LanternRelayMemory.counts(LanternRelayHop.NOT_READY))
        assertTrue(LanternRelayMemory.counts(LanternRelayHop.CLOUDFLARE_UNREACHABLE))
        assertTrue(LanternRelayMemory.counts("local proxy did not become ready"))
    }

    @Test fun theFastFailMessageSaysWhenTheLanternHopFailedToo() {
        WarpDataPlaneHold.record(network, now = 0)
        val message = WarpDataPlaneHold.fastFailMessage(network, now = 10 * 60_000L, lanternFailedAt = 5 * 60_000L)
        assertTrue(message, message.contains("WARP through Lantern also failed here 5 min ago"))
        assertTrue(message, message.contains("not retried for about 25 more min"))
        assertFalse(WarpDataPlaneHold.fastFailMessage(network, now = 10 * 60_000L).contains("also failed"))
    }

    /** A loopback SOCKS5 server for the preflight: no auth or user/password, then the given CONNECT reply code. */
    private fun fakeSocks(reply: Int): ServerSocket {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { s ->
                    val input = DataInputStream(s.getInputStream())
                    val out = s.getOutputStream()
                    input.readUnsignedByte(); val n = input.readUnsignedByte(); input.readFully(ByteArray(n))
                    out.write(byteArrayOf(5, 2)); out.flush()
                    input.readUnsignedByte(); input.readFully(ByteArray(input.readUnsignedByte()))
                    input.readFully(ByteArray(input.readUnsignedByte()))
                    out.write(byteArrayOf(1, 0)); out.flush()
                    input.readFully(ByteArray(3))
                    when (input.readUnsignedByte()) {
                        1 -> input.readFully(ByteArray(4))
                        3 -> input.readFully(ByteArray(input.readUnsignedByte()))
                        4 -> input.readFully(ByteArray(16))
                    }
                    input.readFully(ByteArray(2))
                    out.write(byteArrayOf(5, reply.toByte(), 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
                    Thread.sleep(500)
                }
            }
        }
        return server
    }

    @Test fun thePreflightNeedsACloudflareHandshakeThroughLantern() {
        val hosts = mutableListOf<String>()
        fakeSocks(reply = 0).use { server ->
            val endpoint = com.example.vpn.verify.Socks5.Endpoint("127.0.0.1", server.localPort, "u", "p")
            assertTrue(LanternRelayHop.reachesCloudflare(endpoint, timeoutMs = 3_000) { _, host, _ -> hosts += host })
        }
        assertEquals(listOf(LanternRelayHop.PREFLIGHT_HOST), hosts)
        fakeSocks(reply = 0).use { server ->
            val endpoint = com.example.vpn.verify.Socks5.Endpoint("127.0.0.1", server.localPort, "u", "p")
            assertFalse(LanternRelayHop.reachesCloudflare(endpoint, timeoutMs = 3_000) { _, _, _ ->
                throw javax.net.ssl.SSLHandshakeException("reset") })
        }
        // Lantern's exit could not open the connection (SOCKS reply 5: refused): the handshake is never attempted.
        fakeSocks(reply = 5).use { server ->
            val endpoint = com.example.vpn.verify.Socks5.Endpoint("127.0.0.1", server.localPort, "u", "p")
            assertFalse(LanternRelayHop.reachesCloudflare(endpoint, timeoutMs = 3_000) { _, _, _ -> error("not reached") })
        }
        // Nothing listening.
        val closed = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        assertFalse(LanternRelayHop.reachesCloudflare(com.example.vpn.verify.Socks5.Endpoint("127.0.0.1", closed, "u", "p"),
            timeoutMs = 1_000) { _, _, _ -> })
    }
}
