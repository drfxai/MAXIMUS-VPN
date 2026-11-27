package com.example.vpn.smartsni

import com.example.data.model.VlessProfile
import com.example.panels.servers.ech.ServerEch
import com.example.panels.servers.ech.ServerEchTest
import com.example.vpn.connections.BuiltinConnections.ConfigFreeMethod
import com.example.vpn.connections.BuiltinMethodMemory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Build 181 Phase 6: the user's own ECH server on the client: which configs get its ECH, plain-TLS fallback with a
 * per-network cooldown, the "Personal server (ECH)" pick and its ranking, and the stored record round trip.
 */
class PersonalEchTest {
    private val store = mutableMapOf<String, String>()
    private val config = ServerEchTest.generated("qwertyuiopas.com").second
    private val a = PersonalEch.Entry("s1", "Frankfurt", "203.0.113.9", 443, "vpn.example.org", "", config, "qwertyuiopas.com",
        "vless://u@203.0.113.9:443?security=tls#a")
    private val b = PersonalEch.Entry("s2", "Helsinki", "198.51.100.7", 8443, "198.51.100.7", "cd".repeat(32), config, "qwertyuiopas.com",
        "trojan://p@198.51.100.7:8443?security=tls#b")

    private fun profile(address: String = "203.0.113.9", port: Int = 443, security: String = "tls", ech: String = "", fp: String = "") =
        VlessProfile(name = "mine", address = address, port = port, uuid = "u", security = security, echConfigList = ech, fingerprint = fp)

    @Before fun setUp() {
        store.clear()
        BuiltinMethodMemory.read = { store[it] }
        BuiltinMethodMemory.write = { k, v -> if (v == null) store.remove(k) else store[k] = v }
        PersonalEch.set(listOf(a, b))
        PersonalEch.parseLink = { link ->
            when {
                link.startsWith("vless://") -> profile()
                link.startsWith("trojan://") -> profile("198.51.100.7", 8443)
                else -> null
            }
        }
    }

    @After fun tearDown() {
        BuiltinMethodMemory.read = { null }
        BuiltinMethodMemory.write = { _, _ -> }
        PersonalEch.set(emptyList())
        PersonalEch.parseLink = { null }
    }

    @Test fun onlyTlsConfigsOfTheSameHostAndPortMatch() {
        assertEquals(a, PersonalEch.match(profile()))
        assertEquals(a, PersonalEch.match(profile(address = "[203.0.113.9]")))
        assertNull(PersonalEch.match(profile(port = 8443)))
        assertNull(PersonalEch.match(profile(security = "reality")))
        assertNull(PersonalEch.match(profile(address = "192.0.2.1")))
    }

    @Test fun matchingConfigsGetTheServersCurrentEchAndAStaleListIsReplaced() {
        val d = PersonalEch.decide(profile(ech = "c3RhbGU="), "net-1", now = 10)
        assertTrue(d.applies)
        val p = PersonalEch.applyTo(profile(ech = "c3RhbGU="), d)
        assertEquals(config, p.echConfigList)
        assertEquals("chrome", p.fingerprint)
        assertEquals("vpn.example.org", p.sni)
        assertEquals("firefox", PersonalEch.applyTo(profile(fp = "firefox"), d).fingerprint)
        // The server's self-signed certificate is pinned, never skipped.
        val pb = PersonalEch.applyTo(profile("198.51.100.7", 8443), PersonalEch.decide(profile("198.51.100.7", 8443), "net-1"))
        assertEquals("cd".repeat(32), pb.pinnedPeerCertSha256)
        assertFalse(pb.allowInsecure)
    }

    @Test fun aFailureMeansPlainTlsOnThatNetworkForSixHoursThenEchAgain() {
        val logs = mutableListOf<String>()
        val d = PersonalEch.decide(profile(), "net-1", now = 1_000)
        PersonalEch.onResult("net-1", d, verified = false, now = 1_000) { logs += it }
        assertTrue(logs.single().contains("plain TLS"))
        val after = PersonalEch.decide(profile(ech = config), "net-1", now = 2_000)
        assertFalse(after.applies)
        assertEquals("", PersonalEch.applyTo(profile(ech = config), after).echConfigList)
        assertTrue(PersonalEch.decide(profile(), "net-2", now = 2_000).applies) // other networks unaffected
        assertTrue(PersonalEch.decide(profile(), "net-1", now = 1_000 + PersonalEch.FAIL_COOLDOWN_MS).applies)
        assertTrue("qwertyuiopas.com" in SmartSni.cooling("net-1", 2_000))
    }

    @Test fun thePickPrefersTheServerThatIsNotFailingAndWorkedHereLast() {
        assertEquals("203.0.113.9", PersonalEch.profile("net-1", now = 5)!!.address)
        PersonalEch.onResult("net-1", PersonalEch.decide(profile(), "net-1", now = 5), verified = false, now = 5)
        assertEquals("198.51.100.7", PersonalEch.profile("net-1", now = 6)!!.address)
        PersonalEch.onResult("net-2", PersonalEch.decide(profile("198.51.100.7", 8443), "net-2", 7), verified = true, now = 7)
        assertEquals(listOf("s2", "s1"), PersonalEch.ranked("net-2", now = 8).map { it.serverId })
        assertTrue(PersonalEch.status("net-1", now = 6).contains("plain TLS on this network"))
    }

    @Test fun thePickIsUnavailableWithoutAnEchServer() {
        assertNull(ConfigFreeMethod.PERSONAL_ECH.missingDependency())
        PersonalEch.set(emptyList())
        assertNotNull(ConfigFreeMethod.PERSONAL_ECH.missingDependency())
        assertNull(PersonalEch.profile("net-1"))
        assertEquals("no personal server with ECH", PersonalEch.status("net-1"))
    }

    @Test fun entriesComeFromTheStoredRecord() {
        val r = ServerEch.Record(true, "qwertyuiopas.com", config, 1, 0, listOf(ServerEch.Inbound(3, 443, "", "", "vless://x")))
        val e = PersonalEch.fromRecord("s9", "Mine", "203.0.113.50", ServerEch.record(ServerEch.withRecord(emptyMap(), r))).single()
        assertEquals("203.0.113.50", e.sni) // inner name falls back to the host
        assertEquals(config, e.config)
        assertTrue(PersonalEch.fromRecord("s9", "Mine", "h", null).isEmpty())
    }
}
