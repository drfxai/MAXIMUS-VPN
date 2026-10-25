package com.example

import com.example.vpn.sidecar.*
import com.example.vpn.engine.UniversalImportEngine
import com.example.core.SecretRedactor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class WhiteDnsProfileLinksTest {
    private fun link(engine: String, count: Int = 2, method: Int = 5): String {
        val server = JSONObject().put("domain", "t.example.com")
        if (engine == "soar") server.put("public_key", Base64.getEncoder().encodeToString(ByteArray(32) { 1 }))
        else server.put("encryption_key", "12".repeat(32)).put("encryption_method", method)
        val p = JSONObject().put("name", "My server").put("server", server)
            .put("resolvers", JSONArray((1..count).map { "37.32.2.$it" }))
        if (engine != "soar") p.put("engine", engine)
        else p.put("soarSettings", JSONObject().put("discovery", true).put("transport", "auto"))
        val root = JSONObject().put("schema", "whitedns.profile").put("version", 1).put("profile", p)
        return "$engine://" + Base64.getUrlEncoder().withoutPadding().encodeToString(root.toString().toByteArray())
    }
    @Test fun standardCottenUsesItsOwnModeAndKeepsAllResolvers() {
        val p = WhiteDnsProfileLinks.parse(link("cottendns", 28))
        val s = CottenDnsSidecar.settings(p)
        assertEquals("legacy", s.mode)
        assertEquals(28, s.resolvers.size)
        assertEquals("37.32.2.28:53", s.resolvers.last())
        assertEquals(s, CottenDnsSidecar.settings(CottenDnsSidecar.parse(CottenDnsSidecar.toLink(p))))
    }
    @Test fun soarRemainsSeparateAndManualResolverEditsPersist() {
        val p = WhiteDnsProfileLinks.parse(link("soar"))
        assertTrue(SoarSidecar().handles(p)); assertFalse(CottenDnsSidecar().handles(p))
        assertEquals(com.example.vpn.lab.PathFamily.DNS_TUNNEL, com.example.vpn.lab.PathFamily.of(p, "soar"))
        val edited = SoarSidecar.withResolvers(p, "31.214.172.58\n37.32.2.2\n")
        val config = SoarSidecar.config(edited)
        assertEquals("31.214.172.58:53", config.getJSONArray("resolvers").getString(0))
        assertFalse(config.getJSONObject("tuning").has("discovery"))
        assertNotEquals(p.canonicalFingerprint, edited.canonicalFingerprint)
        assertEquals(p.uuid, edited.uuid)
    }
    @Test fun invalidCryptoAndResolverListsFailClosed() {
        assertThrows(IllegalArgumentException::class.java) { WhiteDnsProfileLinks.parse(link("cottendns", method = 1)) }
        assertThrows(IllegalArgumentException::class.java) { WhiteDnsProfileLinks.parse(link("soar", 65)) }
        val p = WhiteDnsProfileLinks.parse(link("soar"))
        for (s in listOf("", "1.1.1.1\n1.1.1.1", "dns.example.com", "127.0.0.1", "1.1.1.0/24")) {
            assertThrows(IllegalArgumentException::class.java) { SoarSidecar.withResolvers(p, s) }
        }
    }
    @Test fun bulkImportUsesExistingPipelineAndRedactsErrors() {
        val result = UniversalImportEngine.importText(link("soar") + "\n" + link("cottendns"))
        assertEquals(2, result.validCount)
        val bad = "cottendns://" + "secret".repeat(10)
        assertFalse(UniversalImportEngine.importText(bad).invalidEntries.toString().contains("secret"))
        assertFalse(SecretRedactor.redact(link("cottendns")).contains("eyJ"))
        assertFalse(SecretRedactor.redact(link("cottendns").replace("cottendns://", "COTTENDNS://")).contains("eyJ"))
    }
}
