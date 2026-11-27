package com.example.vpn.connections

import com.example.data.model.AppSettings
import com.example.data.model.OperationalMode
import com.example.data.model.VlessProfile
import com.example.vpn.engine.ProfileExtras
import com.example.vpn.sidecar.AetherSidecar
import com.example.vpn.sidecar.RunningEngine
import com.example.vpn.sidecar.SidecarContext
import com.example.vpn.sidecar.SidecarEngine
import com.example.vpn.sidecar.SidecarLaunch
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Build 171 report (Iranian cellular, cf_quick = filtered): every automatic Aether run still spent up to three gateway
 * scans (h2-split ≈46 s, h2 ≈47 s, h2-lantern 90 s) while a chain profile, Serverless, Lantern and Tor had carried
 * verified traffic on the same network minutes earlier; and the user's own MASQUE HTTP/2 pick inside the hold ran a
 * 186 s scan (19:01:18Z → 19:04:24Z).
 */
class AetherVerifiedFallbackTest {
    private val store = HashMap<String, String>()
    private lateinit var previousRead: (String) -> String?
    private lateinit var previousWrite: (String, String?) -> Unit
    private lateinit var previousNetwork: () -> String?
    private val network = "wifi:verified-fallback-test"
    private val attempted = mutableListOf<String>()

    private val engine = object : SidecarEngine {
        override val id = AetherSidecar.ID
        override val binary = id
        override fun handles(profile: VlessProfile) = AetherSidecar.handles(profile)
        override fun prepare(profile: VlessProfile, settings: AppSettings, context: SidecarContext) = SidecarLaunch(listOf("unused"))
        override fun startPrepared(profile: VlessProfile, settings: AppSettings, launch: SidecarLaunch, context: SidecarContext): RunningEngine? {
            attempted += ProfileExtras.read(profile).optString("carrier")
            error("carrier did not start")
        }
    }
    private val context = SidecarContext(File("build/tmp/verified-fallback-test").apply { mkdirs() }, File("unused"), 12346,
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

    private fun run(profile: VlessProfile = AetherSidecar.profile(), manual: Boolean = false, cloudflareBlocked: Boolean = true,
                    verified: String? = "Chain (51 KB/s)", handOver: Boolean = verified != null) =
        assertThrows(ConnectionEngineRunner.NoVerifiedPath::class.java) {
            ConnectionEngineRunner.start(engine, profile, AppSettings(), context,
                preferWireGuard = cloudflareBlocked, cloudflareBlocked = cloudflareBlocked, manual = manual,
                verifiedAlternative = verified, handOver = handOver)
        }

    private fun explicitH2(): VlessProfile = AetherSidecar.profile(transport = "h2")

    @Test fun automaticAetherOnAFilteredLinkHandsOverAtOnceWithoutAnyScan() {
        val began = System.currentTimeMillis()
        val failure = run()
        assertTrue(attempted.toString(), attempted.isEmpty())
        assertTrue(System.currentTimeMillis() - began < 2_000)
        val message = failure.message.orEmpty()
        assertTrue(message, message.contains("Chain (51 KB/s) carried verified traffic here recently"))
        // No hold is invented from a run that never scanned.
        assertFalse(WarpDataPlaneHold.blocked(network))
    }

    @Test fun withNothingVerifiedHereOrCloudflareReachableTheScansStillRun() {
        run(verified = null)
        assertTrue(attempted.toString(), attempted.isNotEmpty())
        attempted.clear()
        AetherLadderGuard.resetForTest()
        run(cloudflareBlocked = false)
        assertTrue(attempted.toString(), attempted.isNotEmpty())
    }

    @Test fun theUsersOwnAutomaticRunIsNeverHandedOverSilently() {
        // The power button on Aether outside the hold: its scans run as before.
        run(manual = true)
        assertTrue(attempted.toString(), attempted.isNotEmpty())
    }

    @Test fun theUsersOwnHttp2PickInsideTheHoldStillRuns() {
        // Build 179 device test (N3): the hold only steers Smart Connect and the background relays; the user's own pick
        // always runs (it used to be refused for 30 min after any WARP failure, Smart Connect's included).
        WarpDataPlaneHold.record(network)
        val failure = run(profile = explicitH2(), manual = true, handOver = false)
        assertTrue(attempted.toString(), attempted.contains("h2"))
        assertFalse(failure.message.orEmpty(), failure.message.orEmpty().startsWith("Aether skipped"))
        // A start failure that is not a gateway-scan failure says nothing new about the hold.
        assertTrue(WarpDataPlaneHold.blocked(network))
    }

    @Test fun theUsersOwnHttp2PickStillRunsOutsideTheHoldOrWithCloudflareReachable() {
        run(profile = explicitH2(), manual = true, handOver = false)
        assertTrue(attempted.toString(), attempted.contains("h2"))
        attempted.clear()
        WarpDataPlaneHold.record(network)
        run(profile = explicitH2(), manual = true, cloudflareBlocked = false, handOver = false)
        assertTrue(attempted.toString(), attempted.contains("h2"))
    }

    @Test fun rulesAreNarrow() {
        val a = AetherSidecar.ID
        assertTrue(ConnectionEngineRunner.skipScansForVerifiedFallback(a, automatic = true, manual = false, relayOnly = false,
            cloudflareBlocked = true, fallbackAvailable = true))
        assertFalse(ConnectionEngineRunner.skipScansForVerifiedFallback(a, true, manual = true, relayOnly = false, cloudflareBlocked = true, fallbackAvailable = true))
        assertFalse(ConnectionEngineRunner.skipScansForVerifiedFallback(a, true, false, relayOnly = true, cloudflareBlocked = true, fallbackAvailable = true))
        assertFalse(ConnectionEngineRunner.skipScansForVerifiedFallback(a, true, false, false, cloudflareBlocked = false, fallbackAvailable = true))
        assertFalse(ConnectionEngineRunner.skipScansForVerifiedFallback(a, true, false, false, true, fallbackAvailable = false))
        assertFalse(ConnectionEngineRunner.skipScansForVerifiedFallback("serverless", true, false, false, true, true))
        assertTrue(ConnectionEngineRunner.explicitH2FastFail(a, automatic = false, relayOnly = false, cloudflareBlocked = true, carrier = "h2", held = true))
        assertFalse(ConnectionEngineRunner.explicitH2FastFail(a, false, false, true, carrier = "wireguard", held = true))
        assertFalse(ConnectionEngineRunner.explicitH2FastFail(a, false, false, true, carrier = "h3", held = true))
        assertFalse(ConnectionEngineRunner.explicitH2FastFail(a, automatic = true, relayOnly = false, cloudflareBlocked = true, carrier = "h2", held = true))
        assertFalse(ConnectionEngineRunner.explicitH2FastFail(a, false, false, true, "h2", held = false))
    }

    @Test fun theDefaultFastFailMessageIsUnchangedWithoutAVerifiedConnection() {
        WarpDataPlaneHold.record(network)
        assertTrue(WarpDataPlaneHold.fastFailMessage(network).contains("Connect with Lantern or Serverless instead."))
        assertTrue(WarpDataPlaneHold.fastFailMessage(network, verified = "Serverless (41 KB/s)")
            .endsWith("Connect with Serverless (41 KB/s) instead: it worked on this network recently."))
    }
}
