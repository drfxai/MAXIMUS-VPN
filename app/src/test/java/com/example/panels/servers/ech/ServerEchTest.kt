package com.example.panels.servers.ech

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Build 181 Phase 6: server-side ECH on the user's 3X-UI server: key/config parsing in Xray's format, key rotation
 * overlap, inbound edits that keep everything else, eligibility, rollback on failure, outer-name suggestions.
 */
class ServerEchTest {
    companion object {
        private fun u16(o: ByteArrayOutputStream, v: Int) { o.write(v shr 8); o.write(v and 0xff) }

        /** ECHConfig bytes exactly as Xray's `tls ech` marshals them (main/commands/all/tls/ech.go marshalBinary). */
        fun echConfig(publicName: String, keyByte: Int): ByteArray {
            val contents = ByteArrayOutputStream()
            contents.write(0)                                   // config_id (Xray always 0)
            u16(contents, 0x0020)                               // DHKEM(X25519)
            u16(contents, 32); repeat(32) { contents.write(keyByte) }
            u16(contents, 4); u16(contents, 1); u16(contents, 1) // HKDF-SHA256 / AES-128-GCM
            contents.write(0)                                   // maximum_name_length
            contents.write(publicName.length); contents.write(publicName.toByteArray())
            u16(contents, 0)                                    // extensions
            val c = contents.toByteArray()
            val out = ByteArrayOutputStream()
            u16(out, 0xfe0d); u16(out, c.size); out.write(c)
            return out.toByteArray()
        }

        /** (echServerKeys, echConfigList) base64, as `xray tls ech --serverName [name]` prints them. */
        fun generated(name: String, keyByte: Int = 1): Pair<String, String> {
            val cfg = echConfig(name, keyByte)
            val keys = ByteArrayOutputStream(); u16(keys, 32); repeat(32) { keys.write(keyByte + 100) }; u16(keys, cfg.size); keys.write(cfg)
            val list = ByteArrayOutputStream(); u16(list, cfg.size); list.write(cfg)
            val enc = Base64.getEncoder()
            return enc.encodeToString(keys.toByteArray()) to enc.encodeToString(list.toByteArray())
        }

        fun tlsInbound(id: Int, port: Int, stringified: Boolean = true, network: String = "tcp", security: String = "tls",
                       protocol: String = "vless", cert: String = "/root/cert/fullchain.pem"): JSONObject {
            val stream = JSONObject().put("network", network).put("security", security)
                .put("tlsSettings", JSONObject().put("serverName", "vpn.example.org").put("alpn", JSONArray().put("h2"))
                    .put("certificates", JSONArray().put(JSONObject().put("certificateFile", cert).put("keyFile", "/root/cert/privkey.pem"))))
            val settings = JSONObject().put("clients", JSONArray().put(JSONObject().put("id", "uuid-$id")))
            return JSONObject().put("id", id).put("port", port).put("protocol", protocol).put("enable", true).put("remark", "r$id")
                .put("tag", "in-$port").put("up", 5).put("down", 7).put("clientStats", JSONArray().put(JSONObject().put("email", "x")))
                .put("settings", if (stringified) settings.toString() else settings)
                .put("streamSettings", if (stringified) stream.toString() else stream)
                .put("sniffing", "{}")
        }
    }

    // ---- key material ---------------------------------------------------------------------------------------------

    @Test fun parsesXraysServerKeysAndConfigList() {
        val (keys, list) = generated("qwertyuiopas.com")
        val sets = ServerEch.keySets(keys)!!
        assertEquals(1, sets.size)
        assertEquals(32, sets[0].privateKeyLength)
        assertTrue(sets[0].config.contentEquals(ServerEch.configs(list)!![0]))
        assertEquals("qwertyuiopas.com", ServerEch.outerName(list))
        assertNull(ServerEch.checkGenerated(keys, list, "qwertyuiopas.com"))
    }

    @Test fun rejectsMalformedOrMismatchedKeys() {
        val (keys, list) = generated("aaaa.com", 1)
        val (_, otherList) = generated("aaaa.com", 2)
        assertNull(ServerEch.keySets("not base64!"))
        assertNull(ServerEch.keySets(Base64.getEncoder().encodeToString(byteArrayOf(0, 32, 1, 2))))
        assertNull(ServerEch.configs(Base64.getEncoder().encodeToString(byteArrayOf(0, 9, 1))))
        assertEquals("the ECH config list does not match the server keys", ServerEch.checkGenerated(keys, otherList, "aaaa.com"))
        assertEquals("the ECH outer name is aaaa.com, not bbbb.com", ServerEch.checkGenerated(keys, list, "bbbb.com"))
    }

    @Test fun rotationServesTheNewKeyFirstAndKeepsOnlyThePreviousOne() {
        val k1 = generated("one.com", 1).first
        val k2 = generated("two.com", 2).first
        val k3 = generated("three.com", 3).first
        val c12 = ServerEch.combine(k2, k1)
        assertEquals(listOf("two.com", "one.com"), ServerEch.keySets(c12)!!.map { name(it.config) })
        val c23 = ServerEch.combine(k3, c12)
        assertEquals(listOf("three.com", "two.com"), ServerEch.keySets(c23)!!.map { name(it.config) })
        assertEquals(k1, ServerEch.combine(k1, null))
        assertEquals(k1, ServerEch.combine(k1, "garbage"))
        assertEquals(1, ServerEch.keySets(ServerEch.combine(k1, k1))!!.size)
    }

    private fun name(cfg: ByteArray): String {
        val o = ByteArrayOutputStream(); u16(o, cfg.size); o.write(cfg)
        return ServerEch.outerName(Base64.getEncoder().encodeToString(o.toByteArray()))!!
    }

    // ---- inbounds -------------------------------------------------------------------------------------------------

    @Test fun onlyTcpTlsInboundsWithACertificateAreEligible() {
        assertNull(ServerEch.ineligible(tlsInbound(1, 443)))
        assertNull(ServerEch.ineligible(tlsInbound(1, 443, network = "xhttp", protocol = "trojan")))
        assertEquals("security reality", ServerEch.ineligible(tlsInbound(1, 443, security = "reality")))
        assertEquals("protocol hysteria", ServerEch.ineligible(tlsInbound(1, 443, protocol = "hysteria")))
        assertEquals("transport kcp", ServerEch.ineligible(tlsInbound(1, 443, network = "kcp")))
        val noCert = tlsInbound(1, 443, stringified = false)
        noCert.getJSONObject("streamSettings").getJSONObject("tlsSettings").put("certificates", JSONArray())
        assertEquals("no certificate", ServerEch.ineligible(noCert))
        assertEquals(1, ServerEch.eligible(JSONArray().put(tlsInbound(1, 443)).put(tlsInbound(2, 8443, security = "none"))).size)
    }

    @Test fun withEchKeepsEveryOtherFieldAndTheRepresentation() {
        val (keys, list) = generated("abcdefghijkl.com")
        for (stringified in listOf(true, false)) {
            val inb = tlsInbound(4, 443, stringified)
            val on = ServerEch.withEch(inb, keys, list)
            assertEquals(stringified, on.get("streamSettings") is String)
            assertEquals(keys, ServerEch.currentServerKeys(on))
            assertEquals(list, ServerEch.storedConfigList(on))
            val tls = ServerEch.obj(on.get("streamSettings"))!!.getJSONObject("tlsSettings")
            assertEquals("vpn.example.org", tls.getString("serverName"))
            assertEquals(1, tls.getJSONArray("certificates").length())
            assertNull(ServerEch.currentServerKeys(inb)) // the input is untouched
            val off = ServerEch.withEch(on, null, null)
            assertNull(ServerEch.currentServerKeys(off)); assertNull(ServerEch.storedConfigList(off))
        }
    }

    @Test fun updatePayloadDropsPanelOnlyFieldsAndStringifiesSettings() {
        val p = ServerEch.updatePayload(tlsInbound(4, 443, stringified = false), stringified = true)
        assertFalse(p.has("clientStats"))
        assertTrue(p.get("streamSettings") is String)
        assertEquals(443, p.getInt("port")); assertEquals(5, p.getInt("up")); assertEquals("in-443", p.getString("tag"))
        assertTrue(ServerEch.updatePayload(tlsInbound(4, 443), stringified = false).get("streamSettings") is JSONObject)
    }

    // ---- provisioning with rollback -------------------------------------------------------------------------------

    private class FakePanel(var inbounds: MutableList<JSONObject>) : EchPanelApi {
        var gen = generated("abcdefghijkl.com")
        var failUpdateOn: Int? = null
        var dropEchOn: Int? = null
        var states = ArrayDeque<String>()
        val updates = mutableListOf<Int>()
        var restarts = 0
        override fun listInbounds() = JSONArray(inbounds.map { JSONObject(it.toString()) })
        override fun newEchCert(outer: String) = gen
        override fun updateInbound(id: Int, inbound: JSONObject) {
            if (id == failUpdateOn) { failUpdateOn = null; error("HTTP 500") }
            updates += id
            val stored = if (id == dropEchOn) ServerEch.withEch(inbound, null, null) else JSONObject(inbound.toString())
            inbounds[inbounds.indexOfFirst { it.optInt("id") == id }] = stored
        }
        override fun inbound(id: Int) = inbounds.firstOrNull { it.optInt("id") == id }?.let { JSONObject(it.toString()) }
        override fun xrayState() = states.removeFirstOrNull() ?: "running"
        override fun restartXray() { restarts++ }
        override fun links() = listOf("vless://uuid-1@203.0.113.9:443?security=tls#a", "trojan://p@203.0.113.9:8443?security=tls#b")
    }

    private fun provisioner(panel: FakePanel, backups: MutableList<String?>, logs: MutableList<String> = mutableListOf()) =
        ServerEchProvisioner(panel, backup = { backups += it }, pinFor = { if (it.optInt("port") == 8443) "ab".repeat(32) else "" },
            sleep = {}, now = { 1000L }, log = { logs += it })

    @Test fun enableInstallsKeysOnEveryTlsInboundAndReturnsThePublicRecord() {
        val panel = FakePanel(mutableListOf(tlsInbound(1, 443), tlsInbound(2, 8443, network = "ws"), tlsInbound(3, 2053, security = "reality")))
        val backups = mutableListOf<String?>()
        val logs = mutableListOf<String>()
        val r = provisioner(panel, backups, logs).enable("203.0.113.9", "AbcdefghijKL.com", 30)
        assertEquals(listOf(1, 2), panel.updates)
        assertEquals(1, panel.restarts)
        assertEquals("abcdefghijkl.com", r.outer)
        assertEquals(panel.gen.second, r.config)
        assertEquals(listOf(443, 8443), r.inbounds.map { it.port })
        assertEquals("vpn.example.org", r.inbounds[0].sni)
        assertEquals("ab".repeat(32), r.inbounds[1].pin)
        assertTrue(r.inbounds[0].link.startsWith("vless://")); assertTrue(r.inbounds[1].link.startsWith("trojan://"))
        assertEquals(2, backups.size); assertNotNull(backups[0]); assertNull(backups[1]) // copy kept, then cleared
        assertTrue(logs.none { it.contains(panel.gen.first) || it.contains(panel.gen.second) }) // no keys in logs
        // The record round-trips through the tool settings and carries no private key.
        val settings = ServerEch.withRecord(mapOf("other" to "x"), r)
        assertEquals(r, ServerEch.record(settings))
        assertFalse(settings.values.any { it.contains(panel.gen.first) })
        assertEquals("x", settings["other"])
    }

    @Test fun aFailedUpdateRollsBackEveryChangedInbound() {
        val before = listOf(tlsInbound(1, 443), tlsInbound(2, 8443))
        val panel = FakePanel(before.map { JSONObject(it.toString()) }.toMutableList()).apply { failUpdateOn = 2 }
        try { provisioner(panel, mutableListOf()).enable("203.0.113.9", "abcdefghijkl.com", 0); fail() }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("previous inbound settings were restored")) }
        assertNull(ServerEch.currentServerKeys(panel.inbound(1)!!))
        assertNull(ServerEch.currentServerKeys(panel.inbound(2)!!))
    }

    @Test fun aPanelThatDoesNotStoreTheKeysOrXrayThatDoesNotComeBackRollsBack() {
        val p1 = FakePanel(mutableListOf(tlsInbound(1, 443))).apply { dropEchOn = 1 }
        runCatching { provisioner(p1, mutableListOf()).enable("h", "abcdefghijkl.com", 0) }.onSuccess { fail() }
        assertNull(ServerEch.currentServerKeys(p1.inbound(1)!!))
        val p2 = FakePanel(mutableListOf(tlsInbound(1, 443))).apply { states = ArrayDeque(List(6) { "error" }) }
        val e = runCatching { provisioner(p2, mutableListOf()).enable("h", "abcdefghijkl.com", 0) }.exceptionOrNull()!!
        assertTrue(e.message!!.contains("Xray did not come back"))
        assertNull(ServerEch.currentServerKeys(p2.inbound(1)!!))
        assertEquals(2, p2.restarts) // the change, then the rollback
    }

    @Test fun badGeneratedKeysOrNoTlsInboundChangeNothing() {
        val p = FakePanel(mutableListOf(tlsInbound(1, 443))).apply { gen = generated("other.com") }
        runCatching { provisioner(p, mutableListOf()).enable("h", "abcdefghijkl.com", 0) }.onSuccess { fail() }
        assertTrue(p.updates.isEmpty())
        val reality = FakePanel(mutableListOf(tlsInbound(1, 443, security = "reality")))
        assertTrue(runCatching { provisioner(reality, mutableListOf()).enable("h", "abcdefghijkl.com", 0) }.exceptionOrNull() is NoEchInboundException)
        assertTrue(runCatching { provisioner(p, mutableListOf()).enable("h", "cloudflare-ech.com", 0) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun rotationKeepsThePreviousKeySetAndDisableRemovesEcho() {
        val panel = FakePanel(mutableListOf(tlsInbound(1, 443)))
        val prov = provisioner(panel, mutableListOf())
        prov.enable("h", "abcdefghijkl.com", 0)
        val first = ServerEch.currentServerKeys(panel.inbound(1)!!)!!
        panel.gen = generated("mnopqrstuvwx.com", 9)
        val r2 = prov.enable("h", "mnopqrstuvwx.com", 0)
        val served = ServerEch.keySets(ServerEch.currentServerKeys(panel.inbound(1)!!)!!)!!
        assertEquals(2, served.size)
        assertEquals("mnopqrstuvwx.com", r2.outer)
        assertTrue(served[1].config.contentEquals(ServerEch.keySets(first)!![0].config))
        prov.disable()
        assertNull(ServerEch.currentServerKeys(panel.inbound(1)!!))
        assertNull(ServerEch.storedConfigList(panel.inbound(1)!!))
    }

    // ---- outer names, records, links ------------------------------------------------------------------------------

    @Test fun suggestionsPreferRandomStyleWinnersAndNeverCloudflareNames() {
        val s = ServerEch.suggestions(listOf("www.speedtest.net", "cloudflare-ech.com", "zxcvbnmasdfg.com", "x.cloudflare.com"),
            cooling = setOf("blocked.com"), random = { "randomnameab.com" })
        assertEquals("zxcvbnmasdfg.com", s.first())
        assertTrue("www.speedtest.net" in s)
        assertTrue(s.none { it.contains("cloudflare") })
        assertEquals("randomnameab.com", ServerEch.suggestions(emptyList(), emptySet(), { "randomnameab.com" }).first())
        assertNull(ServerEch.outerProblem("qwertyuiopas.com"))
        assertNotNull(ServerEch.outerProblem("cloudflare-ech.com"))
        assertNotNull(ServerEch.outerProblem("not a name"))
    }

    @Test fun rotationIsDueAfterItsInterval() {
        val r = ServerEch.Record(true, "a.com", generated("a.com").second, at = 0L + 1, rotateDays = 7, inbounds = listOf(ServerEch.Inbound(1, 443, "s")))
        assertFalse(ServerEch.rotationDue(r, 6 * 86_400_000L))
        assertTrue(ServerEch.rotationDue(r, 7 * 86_400_000L + 1))
        assertFalse(ServerEch.rotationDue(r.copy(rotateDays = 0), Long.MAX_VALUE))
        assertNull(ServerEch.record(ServerEch.withRecord(ServerEch.withRecord(emptyMap(), r), null)))
        assertEquals("7", ServerEch.withRecord(ServerEch.withRecord(emptyMap(), r), null)[ServerEch.K_ROTATE_DAYS])
    }

    @Test fun shareLinksMatchByHostAndPort() {
        val vmess = "vmess://" + Base64.getEncoder().encodeToString("""{"add":"203.0.113.9","port":"443","id":"u"}""".toByteArray())
        assertTrue(ServerEchProvisioner.defaultLinkMatch(vmess, "203.0.113.9", 443))
        assertFalse(ServerEchProvisioner.defaultLinkMatch(vmess, "203.0.113.9", 8443))
        assertTrue(ServerEchProvisioner.defaultLinkMatch("vless://u@[2001:db8::1]:443?x=1#n", "2001:db8::1", 443))
        assertFalse(ServerEchProvisioner.defaultLinkMatch("ss://abc@203.0.113.9:443", "203.0.113.9", 443))
    }
}
