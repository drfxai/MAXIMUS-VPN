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
import java.io.File

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
    }

    @After fun tearDown() {
        BuiltinMethodMemory.read = previousRead
        BuiltinMethodMemory.write = previousWrite
        BuiltinMethodMemory.network = previousNetwork
        WarpDataPlaneHold.resetForTest()
        WarpRegistrationMemory.resetForTest()
        AetherLadderGuard.resetForTest()
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
}
