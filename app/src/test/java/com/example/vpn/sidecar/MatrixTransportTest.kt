package com.example.vpn.sidecar

import com.example.data.model.AppSettings
import com.example.data.model.OperationalMode
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MatrixTransportTest {
    @Test fun everyCarrierUsesPrivateLoopbackAndAuthenticatedRegistration() {
        val context = SidecarContext(File("/private/work"), File("/private/bin"), 19000, "u", "p", OperationalMode.DAILY)
        for (mode in MatrixTransportSidecar.MODES) {
            val p = MatrixTransportSidecar.profile(mode)
            assertNull(MatrixTransportSidecar.problem(p))
            val launch = MatrixTransportSidecar.prepare(p, AppSettings(), context)
            assertTrue("127.0.0.1:19000" in launch.command)
            assertTrue("external" in launch.command)
            assertFalse("--no-data-check" in launch.command)
            assertEquals(mode == "h2", "--h2" in launch.command)
        }
    }

    @Test fun psiphonCanUseTheHttp2CarrierButTheCarrierCannotDialPsiphon() {
        val chain = EngineChain.Chain(listOf(EngineChain.Hop("psiphon"), EngineChain.Hop("matrix-transport")))
        assertNull(EngineChain.problem(chain))
        assertNotNull(EngineChain.problem(EngineChain.Chain(chain.hops.reversed())))
    }

    @Test fun importedPsiphonSettingsRequireHttpsLists() {
        val good = org.json.JSONObject().put("PropagationChannelId", "own-channel").put("SponsorId", "own-sponsor")
            .put("RemoteServerListSignaturePublicKey", "own-public-key")
            .put("RemoteServerListURLs", org.json.JSONArray().put(org.json.JSONObject().put("URL", "https://example.org/list")))
        assertNull(PsiphonSidecar.validateNetworkSettings(good.toString()))
        good.put("RemoteServerListURLs", org.json.JSONArray().put("http://example.org/list"))
        assertNotNull(PsiphonSidecar.validateNetworkSettings(good.toString()))
        assertNotNull(PsiphonSidecar.validateNetworkSettings("{}"))
    }
    @Test fun tcpOnlyPsiphonNeverAddsQuicOrBroadensAnExplicitRestriction() {
        val cfg = org.json.JSONObject().put("LimitTunnelProtocols", org.json.JSONArray().put("SSH").put("QUIC-OSSH"))
        val out = PsiphonSidecar.buildConfig(cfg, "", 9000, File("/private/data"), tcpOnly = true)
        assertEquals("[\"SSH\"]", out.getJSONArray("LimitTunnelProtocols").toString())
        assertFalse(out.getBoolean("EmitSensitiveNetworkParameters"))
    }

}
