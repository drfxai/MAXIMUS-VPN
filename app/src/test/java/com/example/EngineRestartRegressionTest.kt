package com.example

import com.example.data.model.AppSettings
import com.example.data.model.OperationalMode
import com.example.vpn.sidecar.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

class EngineRestartRegressionTest {
    @Test fun dnsEnginesCanPrepareTwiceWithNewCredentialsInSameDirectory() {
        val dir = Files.createTempDirectory("engine-restart").toFile()
        try {
            val first = SidecarContext(dir, File(dir, "unused"), 41001, "first-user", "first-pass", OperationalMode.DAILY)
            val second = first.copy(socksPort = 41002, socksUser = "second-user", socksPass = "second-pass")
            val soar = SoarSidecar.profile("t.example.com", Base64.getEncoder().encodeToString(ByteArray(32) { 1 }),
                listOf("1.1.1.1"), JSONObject(), "test")
            val cotten = CottenDnsSidecar.profile(CottenDnsSidecar.Settings("t.example.com", "a".repeat(64), listOf("1.1.1.1"), mode = "legacy"))
            listOf(SoarSidecar() to soar, CottenDnsSidecar() to cotten).forEach { (engine, profile) ->
                engine.prepare(profile, AppSettings(), first)
                engine.prepare(profile, AppSettings(), second)
            }
            listOf("soar.json", "cotten-client.toml").forEach { name ->
                val text = File(dir, name).readText()
                assertTrue(text.contains("second-pass"))
                assertFalse(text.contains("first-pass"))
                assertTrue(text.contains("41002"))
            }
            assertFalse(dir.listFiles()!!.any { it.name.endsWith(".tmp") })
        } finally { dir.deleteRecursively() }
    }

    @Test fun existingSymlinkCannotRedirectConfigSecrets() {
        val dir = Files.createTempDirectory("engine-link").toFile()
        val outside = Files.createTempFile("engine-outside", ".txt").toFile()
        try {
            outside.writeText("unchanged")
            Files.createSymbolicLink(File(dir, "soar.json").toPath(), outside.toPath())
            val profile = SoarSidecar.profile("t.example.com", Base64.getEncoder().encodeToString(ByteArray(32) { 1 }),
                listOf("1.1.1.1"), JSONObject(), "test")
            val ctx = SidecarContext(dir, File(dir, "unused"), 41001, "test-user", "test-pass", OperationalMode.DAILY)
            assertThrows(IllegalStateException::class.java) { SoarSidecar().prepare(profile, AppSettings(), ctx) }
            assertEquals("unchanged", outside.readText())
        } finally { dir.deleteRecursively(); outside.delete() }
    }

    @Test fun dnsAndMasqueAdaptersRefuseUnsupportedCarrierInsteadOfBypassingIt() {
        val ctx = SidecarContext(File("."), File("unused"), 41001, "user", "pass", OperationalMode.DAILY, upstreamSocks = 42001)
        listOf(DnsttSidecar(), SlipstreamSidecar(), MasqueSidecar).forEach { engine ->
            assertThrows(IllegalArgumentException::class.java) {
                engine.prepare(MasqueSidecar.profile(), AppSettings(), ctx)
            }
        }
    }

    @Test fun mihomoPassesAuthenticatedCarrierCredentialsAndRejectsPartialPair() {
        val ctx = SidecarContext(File("."), File("unused"), 41001, "user", "pass", OperationalMode.DAILY,
            upstreamSocks = 42001, upstreamUser = "carrier-user", upstreamPass = "carrier-pass")
        val proxy = JSONObject().put("type", "socks5").put("server", "example.com").put("port", 1080)
        val hops = MihomoSidecar.config(proxy, ctx).getJSONArray("proxies")
        assertEquals("maximus-upstream", hops.getJSONObject(0).getString("dialer-proxy"))
        assertEquals("carrier-user", hops.getJSONObject(1).getString("username"))
        assertEquals("carrier-pass", hops.getJSONObject(1).getString("password"))
        assertThrows(IllegalArgumentException::class.java) { MihomoSidecar.config(proxy, ctx.copy(upstreamPass = null)) }
    }

    @Test fun reuseRejectsChangedNetworkDeadEngineAndUnverifiedRoute() {
        listOf("tor", "soar", "cotten-dns").forEach { id ->
            assertTrue(ModeSwitchReuse.allowed(id, true, true, true, true))
            assertFalse(ModeSwitchReuse.allowed(id, false, true, true, true))
            assertFalse(ModeSwitchReuse.allowed(id, true, false, true, true))
            assertFalse(ModeSwitchReuse.allowed(id, true, true, false, true))
            assertFalse(ModeSwitchReuse.allowed(id, true, true, true, false))
        }
        assertFalse(ModeSwitchReuse.allowed("unknown", true, true, true, true))
        assertFalse(ModeSwitchReuse.allowed(null, true, true, true, true))
    }
}
