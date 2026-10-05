package com.example

import com.example.data.model.AppSettings
import com.example.data.model.OperationalMode
import com.example.vpn.engine.UniversalImportEngine
import com.example.vpn.sidecar.MihomoSidecar
import com.example.vpn.sidecar.SidecarChain
import com.example.vpn.sidecar.SidecarContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The Mihomo engine program gets one proxy, one rule and a SOCKS port only this connection can use. */
class MihomoSidecarTest {
    @get:Rule val tmp = TemporaryFolder()

    private val link = "tuic://44444444-4444-4444-4444-444444444444:pw@203.0.113.10:443?sni=t.example&alpn=h3#T"

    private fun context(dir: File) = SidecarContext(dir, File("/system/bin/true"), 18900, "u1", "p1", OperationalMode.GOD_MODE)

    @Test fun theConfigSendsEverythingToTheOneProxyAndNeedsTheLogin() {
        val profile = UniversalImportEngine.importText(link).validProfiles.single()
        val dir = tmp.newFolder()
        val launch = MihomoSidecar.prepare(profile, AppSettings(), context(dir))
        val config = JSONObject(File(dir, "config.yaml").readText())
        assertEquals(18900, config.getInt("socks-port"))
        assertEquals("127.0.0.1", config.getString("bind-address"))
        assertFalse(config.getBoolean("allow-lan"))
        assertEquals("u1:p1", config.getJSONArray("authentication").getString(0))
        assertEquals(0, config.getJSONArray("skip-auth-prefixes").length())
        assertEquals(listOf("MATCH,node"), List(config.getJSONArray("rules").length()) { config.getJSONArray("rules").getString(it) })
        // No controller, no TUN, no DNS through the network's resolver.
        listOf("external-controller", "tun", "mixed-port", "port").forEach { assertFalse(it, config.has(it)) }
        val dns = config.getJSONObject("dns")
        assertFalse(dns.has("default-nameserver"))
        assertTrue(dns.getJSONArray("proxy-server-nameserver").toString().contains("https://1.1.1.1/dns-query"))
        assertEquals("tuic", config.getJSONArray("proxies").getJSONObject(0).getString("type"))
        assertEquals(listOf("-d", dir.absolutePath, "-f", File(dir, "config.yaml").absolutePath), launch.command.drop(1))

        // Xray's only proxy becomes that port, with the login.
        val chained = SidecarChain.xrayProfile(profile, context(dir), launch)
        val server = JSONObject(chained.rawConfig).getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("settings").getJSONArray("servers").getJSONObject(0)
        assertEquals("127.0.0.1", server.getString("address"))
        assertEquals("p1", server.getJSONArray("users").getJSONObject(0).getString("pass"))
    }

    @Test fun anUncheckedCertificateNeedsItsFingerprint() {
        val insecure = UniversalImportEngine.importText(link.replace("alpn=h3", "alpn=h3&allow_insecure=1")).validProfiles.single()
        assertNotNull(MihomoSidecar.problem(insecure))
        val pinned = UniversalImportEngine.importText(link.replace("alpn=h3", "alpn=h3&allow_insecure=1&pinsha256=" + "a".repeat(64))).validProfiles.single()
        assertNull(MihomoSidecar.problem(pinned))
    }
}
