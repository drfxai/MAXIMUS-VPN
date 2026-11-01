package com.example.vpn.sidecar

import com.example.data.model.AppSettings
import com.example.data.model.OperationalMode
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MatrixTransportTest {
    @Test fun everyCarrierUsesPrivateLoopbackAndProvisionedIdentity() {
        val work = java.nio.file.Files.createTempDirectory("matrix-transport-test").toFile()
        val identity = File(work, "identity.toml")
        assertNotNull(MatrixTransportSidecar.configurationProblem(work))
        identity.writeText("test-identity-placeholder")
        assertNull(MatrixTransportSidecar.configurationProblem(work))
        val context = SidecarContext(work, File("/private/bin"), 19000, "u", "p", OperationalMode.DAILY)
        for (mode in MatrixTransportSidecar.MODES) {
            val p = MatrixTransportSidecar.profile(mode)
            assertNull(MatrixTransportSidecar.problem(p))
            val launch = MatrixTransportSidecar.prepare(p, AppSettings(), context)
            assertTrue("127.0.0.1:19000" in launch.command)
            assertTrue("external" in launch.command)
            assertFalse("--no-data-check" in launch.command)
            assertEquals(mode == "h2", "--h2" in launch.command)
        }
        work.deleteRecursively()
    }

    @Test fun matrixTransportDoesNotStartWithoutPrivateConfiguration() {
        val work = java.nio.file.Files.createTempDirectory("matrix-missing-config").toFile()
        try {
            val context = SidecarContext(work, File("/private/bin"), 19000, "u", "p", OperationalMode.DAILY)
            val result = runCatching { MatrixTransportSidecar.prepare(
                MatrixTransportSidecar.profile(), AppSettings(), context) }
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("identity.toml"))
        } finally {
            work.deleteRecursively()
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
            .put("InitialLimitTunnelProtocols", org.json.JSONArray().put("QUIC-OSSH"))
            .put("InitialLimitTunnelProtocolsCandidateCount", 10)
        val out = PsiphonSidecar.buildConfig(cfg, "", 9000, File("/private/data"), tcpOnly = true)
        assertEquals("[\"SSH\"]", out.getJSONArray("LimitTunnelProtocols").toString())
        assertFalse(out.getBoolean("EmitSensitiveNetworkParameters"))
        assertFalse(out.has("InitialLimitTunnelProtocols"))
        assertFalse(out.has("InitialLimitTunnelProtocolsCandidateCount"))
    }

}
