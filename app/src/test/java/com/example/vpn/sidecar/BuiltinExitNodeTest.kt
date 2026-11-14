package com.example.vpn.sidecar

import com.example.data.model.AppSettings
import com.example.data.model.OperationalMode
import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.vpn.connections.ConnectionEngineRunner
import com.example.vpn.engine.ProfileExtras
import com.example.xray.XrayConfigBuilder
import com.example.xray.XrayConfigParser
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * WARP through a verified saved server (wireguard-relay / h2-relay) failed in ~47 ms with "engine process could
 * not start" on build 140 (Irancell, VMess over a CDN). The exit hop never started its process: building the
 * saved server's outbound threw before it.
 */
class BuiltinExitNodeTest {
    private val cdnVmess = VlessProfile(id = "us-vmess-30", name = "US VMESS 30", address = "cdn.example.com", port = 443,
        uuid = "11111111-2222-3333-4444-555555555555", protocolType = ProtocolType.VMESS, transport = "ws",
        security = "tls", path = "/ws")
    private val saved = mutableMapOf<String, VlessProfile>()
    private var resolves = 0
    private val originalResolve = BuiltinExitNode.resolveAddress

    @Before fun setUp() {
        saved.clear(); resolves = 0
        saved[cdnVmess.id] = cdnVmess
        BuiltinExitNode.loadProfile = { saved[it] }
        BuiltinExitNode.resolveAddress = { resolves++; error("network lookup in a unit test") }
    }

    @After fun tearDown() {
        BuiltinExitNode.loadProfile = null
        BuiltinExitNode.resolveAddress = originalResolve
        Sidecars.nativeLibraryDir = null
    }

    private fun options(exitNode: String = cdnVmess.id) = AetherSettings(exitNode = exitNode)

    /** The root cause: the old path sanitized the builder's own config, which adds the private DNS outbound twice. */
    @Test fun sanitizingTheBuildersOwnConfigIsRejectedSoItMustNotBeUsedForTheExitHop() {
        val pinned = com.example.vpn.smart.RouteRecipe.pinEndpoint(cdnVmess, "104.18.1.2")
        for (settings in listOf(AppSettings(), AppSettings(ipv6Enabled = false))) {
            val built = XrayConfigBuilder.buildJson(pinned, settings)
            assertTrue(built.contains("private-dns-out"))
            assertThrows(IllegalArgumentException::class.java) { XrayConfigParser.sanitizeForExecution(built, settings) }
        }
    }

    @Test fun savedServerExitBuildsOneLiteralAddressProxyOutbound() {
        saved[cdnVmess.id] = cdnVmess.copy(address = "104.18.1.2", sni = "cdn.example.com", host = "cdn.example.com")
        val outbound = BuiltinExitNode.selected(options(), AppSettings())!!
        assertEquals("builtin-exit", outbound.getString("tag"))
        assertEquals("vmess", outbound.getString("protocol"))
        assertEquals("104.18.1.2", outbound.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getString("address"))
        assertEquals(0, resolves)
    }

    @Test fun exitHopDialsTheVerifiedRouteAddressInsteadOfResolvingTheName() {
        val outbound = BuiltinExitNode.selected(options(), AppSettings(), BuiltinExitNode.Route(endpoint = "104.18.1.2"))!!
        assertEquals(0, resolves)
        assertEquals("104.18.1.2", outbound.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getString("address"))
        val stream = outbound.getJSONObject("streamSettings")
        // TLS still checks the server's own name and the CDN still sees its Host.
        assertEquals("cdn.example.com", stream.getJSONObject("tlsSettings").getString("serverName"))
        assertEquals("cdn.example.com", stream.getJSONObject("wsSettings").getJSONObject("headers").getString("Host"))
    }

    @Test fun exitHopAppliesTheVerifiedStealthAlternate() {
        val pinned = com.example.vpn.smart.RouteRecipe.pinEndpoint(cdnVmess, "104.18.1.2")
        val variant = com.example.vpn.stealth.StealthVariants.of(pinned).firstOrNull() ?: return
        val outbound = BuiltinExitNode.selected(options(), AppSettings(), BuiltinExitNode.Route("104.18.1.2", variant.key))!!
        val expected = JSONObject(XrayConfigBuilder.buildJson(variant.profile, AppSettings())).getJSONArray("outbounds")
            .getJSONObject(0).getJSONObject("streamSettings")
        assertEquals(expected.toString(), outbound.getJSONObject("streamSettings").toString())
        val gone = assertThrows(BuiltinExitNode.ExitNodeFailure::class.java) {
            BuiltinExitNode.selected(options(), AppSettings(), BuiltinExitNode.Route("104.18.1.2", "no-such-alternate"))
        }
        assertEquals(BuiltinExitNode.ROUTE_UNAVAILABLE, gone.message)
    }

    @Test fun exitFailuresCarryFixedNonSecretReasons() {
        fun reason(block: () -> Unit) = assertThrows(BuiltinExitNode.ExitNodeFailure::class.java) { block() }.message
        assertEquals(BuiltinExitNode.ADDRESS_UNRESOLVED, reason { BuiltinExitNode.selected(options(), AppSettings()) })
        assertEquals(1, resolves)
        BuiltinExitNode.resolveAddress = { "not-an-address" }
        assertEquals(BuiltinExitNode.ADDRESS_UNRESOLVED, reason { BuiltinExitNode.selected(options(), AppSettings()) })
        assertEquals(BuiltinExitNode.PROFILE_UNAVAILABLE, reason { BuiltinExitNode.selected(options("deleted"), AppSettings()) })
        saved["aether"] = AetherSidecar.profile().copy(id = "aether")
        assertEquals(BuiltinExitNode.PROFILE_UNSUPPORTED, reason { BuiltinExitNode.selected(options("aether"), AppSettings()) })
        Sidecars.nativeLibraryDir = null
        val context = SidecarContext(File("build/tmp/exit-test").apply { mkdirs() }, File("unused"), 12345, "u", "p", OperationalMode.DAILY)
        assertEquals(BuiltinExitNode.RUNTIME_MISSING, reason { BuiltinExitNode.start(options(), AppSettings(), context) })
        BuiltinExitNode.REASONS.forEach { assertFalse(it, it.contains("example.com") || it.any(Char::isDigit)) }
    }

    @Test fun executableOutboundCheckRejectsNamesAndDisabledCertificates() {
        val ok = JSONObject("""{"protocol":"vless","settings":{"vnext":[{"address":"1.2.3.4","port":443}]}}""")
        XrayConfigParser.requireExecutableOutbound(ok)
        assertThrows(IllegalArgumentException::class.java) {
            XrayConfigParser.requireExecutableOutbound(JSONObject("""{"protocol":"vless","settings":{"vnext":[{"address":"cdn.example.com","port":443}]}}"""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            XrayConfigParser.requireExecutableOutbound(JSONObject("""{"protocol":"freedom","settings":{}}"""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            XrayConfigParser.requireExecutableOutbound(JSONObject("""{"protocol":"trojan","settings":{"servers":[{"address":"1.2.3.4"}]},"streamSettings":{"tlsSettings":{"allowInsecure":true}}}"""))
        }
    }

    @Test fun relayCarriesTheVerifiedRouteToTheExitHop() {
        val relayed = ConnectionEngineRunner.relayed(AetherSidecar.profile("wireguard"), "wireguard", cdnVmess.id,
            BuiltinExitNode.Route("104.18.1.2", "fragment"))
        val route = BuiltinExitNode.Route.read(ProfileExtras.read(relayed))
        assertEquals(BuiltinExitNode.Route("104.18.1.2", "fragment"), route)
        val plain = ConnectionEngineRunner.relayed(AetherSidecar.profile("h2"), "h2", cdnVmess.id)
        assertFalse(ProfileExtras.read(plain).has(BuiltinExitNode.EXIT_ENDPOINT))
    }

    /** The session runner shows an exit-node failure as its fixed reason, never the generic stage or a raw message. */
    @Test fun relayStartupFailureNamesTheExitNodeReason() {
        val attempted = mutableListOf<String>()
        val engine = object : SidecarEngine {
            override val id = AetherSidecar.ID
            override val binary = id
            override fun handles(profile: VlessProfile) = AetherSidecar.handles(profile)
            override fun prepare(profile: VlessProfile, settings: AppSettings, context: SidecarContext) = SidecarLaunch(listOf("unused"))
            override fun startPrepared(profile: VlessProfile, settings: AppSettings, launch: SidecarLaunch, context: SidecarContext): RunningEngine? {
                val e = ProfileExtras.read(profile)
                attempted += e.getString("carrier") + if (e.optBoolean("relayFollowUp")) "-relay" else ""
                if (e.optBoolean("relayFollowUp")) throw BuiltinExitNode.ExitNodeFailure(BuiltinExitNode.ADDRESS_UNRESOLVED)
                error("raw failure mentioning cdn.example.com 104.18.1.2")
            }
        }
        val context = SidecarContext(File("build/tmp/relay-test").apply { mkdirs() }, File("unused"), 12345, "u", "p", OperationalMode.DAILY)
        val failure = assertThrows(ConnectionEngineRunner.NoVerifiedPath::class.java) {
            ConnectionEngineRunner.start(engine, AetherSidecar.profile(), AppSettings(), context, relayExitNode = cdnVmess.id)
        }
        val message = failure.message.orEmpty()
        assertTrue(attempted.toString(), attempted.containsAll(listOf("wireguard-relay", "h2-relay")))
        assertTrue(message, message.contains("wireguard-relay: ${BuiltinExitNode.ADDRESS_UNRESOLVED}"))
        assertTrue(message, message.contains("h2-relay: ${BuiltinExitNode.ADDRESS_UNRESOLVED}"))
        assertTrue(message, message.contains("h3: engine process could not start"))
        assertFalse(message, message.contains("example.com") || message.contains("104.18"))
    }
}
