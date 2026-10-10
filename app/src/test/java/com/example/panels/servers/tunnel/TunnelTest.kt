package com.example.panels.servers.tunnel

import com.example.panels.servers.ManagedServer
import com.example.panels.servers.ServerBinaries
import com.example.panels.servers.ServerLocation
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelTest {
    private val iran = ManagedServer(name = "Tehran", host = "198.51.100.20", location = ServerLocation.IRAN,
        hostKeySha256 = "SHA256:abc", password = "pw")
    private val abroad = ManagedServer(name = "Frankfurt", host = "203.0.113.7", location = ServerLocation.ABROAD,
        hostKeySha256 = "SHA256:def", password = "pw")
    private val cert = "-----BEGIN CERTIFICATE-----\n" + "A".repeat(64) + "\n" + "B".repeat(40) + "==\n-----END CERTIFICATE-----"

    private fun spec(rotation: Int = 24, transports: Set<TunnelTransport> = TunnelTransport.entries.toSet()) = TunnelSpec(
        iranId = iran.id, abroadId = abroad.id, transports = transports, rotationHours = rotation,
        seed = "00112233445566778899aabbccddeeff", avoidPorts = setOf(22, 443, 25366),
        fixedPorts = mapOf(TunnelTransport.REALITY to 31001, TunnelTransport.XHTTP to 31002),
        entryPort = 42001, entryUuid = "11111111-2222-4333-8444-555555555555", entrySni = "www.digikala.com",
        entryPublicKey = "Qjpp4Zp0kE1cvofhWqu_QoqZ_f_bhszCleWdklJqU3I", entryShortId = "a1b2c3d4",
        linkUuid = "66666666-7777-4888-9999-000000000000", exitSni = "www.microsoft.com",
        exitPublicKey = "l1IYwESWpLc7k1N7CzQ9a6gW_amt-Q8nNOXWZLDXT3Y", exitShortId = "0f0e0d0c",
        xhttpPath = "/a1b2c3d4e5f6", hyPort = 45000, hyAuth = "A".repeat(24), hyObfs = "b".repeat(24),
        hyCertPem = cert, hyLocalPort = 15000, reverseUuid = "77777777-8888-4999-aaaa-bbbbbbbbbbbb"
    )

    /** Values computed with `openssl dgst -sha256 -hmac`, exactly as the rotate script on the servers does. */
    @Test fun portsMatchTheServerScript() {
        val seed = "00112233445566778899aabbccddeeff"
        assertEquals(25366, TunnelPorts.derive(seed, "reality", 20000, emptySet()))
        assertEquals(46491, TunnelPorts.derive(seed, "xhttp", 20000, emptySet()))
        assertEquals(43102, TunnelPorts.derive(seed, "reality", 19999, emptySet()))
        assertEquals(50507, TunnelPorts.derive(seed, "xhttp", 123456, emptySet()))
        // An avoided port moves on to the next counter, never to a port in the avoid list.
        val moved = TunnelPorts.derive(seed, "reality", 20000, setOf(25366))
        assertNotEquals(25366, moved)
        assertTrue(moved in 20000..59999)
    }

    @Test fun rotationPeriodsAndFixedPorts() {
        val day = 24 * 3_600_000L
        val now = 20000 * day + 5_000
        assertEquals(20000L, TunnelPorts.epoch(24, now))
        assertEquals(20001 * day, TunnelPorts.nextChangeMs(24, now))
        assertNull(TunnelPorts.nextChangeMs(0, now))
        assertEquals(listOf(31001), TunnelPorts.current(spec(rotation = 0), TunnelTransport.REALITY, now))
        val rotating = TunnelPorts.current(spec(), TunnelTransport.XHTTP, now)
        assertEquals(listOf(46491, TunnelPorts.derive("00112233445566778899aabbccddeeff", "xhttp", 19999, setOf(22, 443, 25366))), rotating)
    }

    @Test fun settingsRoundTrip() {
        val s = spec()
        assertEquals(s, TunnelSpec.fromSettings(s.toSettings(TunnelSpec.ROLE_IRAN)))
        assertEquals(TunnelSpec.ROLE_ABROAD, s.toSettings(TunnelSpec.ROLE_ABROAD)[TunnelSpec.K_ROLE])
        assertNull(TunnelSpec.fromSettings(mapOf("role" to "iran")))
    }

    private fun fill(template: String, port: Int, tag: String) =
        JSONObject(template.replace(TunnelConfigs.PORT, port.toString()).replace(TunnelConfigs.TAG, tag))

    @Test fun configsAreValidJsonAndKeepPrivateKeysOnTheServers() {
        val s = spec()
        val texts = listOf(
            TunnelConfigs.abroadBase(), TunnelConfigs.iranBase(s), TunnelConfigs.iranHysteriaOutbound(s),
            TunnelConfigs.abroadTemplate(s, TunnelTransport.REALITY), TunnelConfigs.abroadTemplate(s, TunnelTransport.XHTTP),
            TunnelConfigs.iranTemplate(s, abroad.host, TunnelTransport.REALITY), TunnelConfigs.iranTemplate(s, abroad.host, TunnelTransport.XHTTP)
        )
        for (t in texts) {
            fill(t, 30000, "t-x-now")
            assertFalse(t.contains("allowInsecure", ignoreCase = true) || t.contains("insecure", ignoreCase = true))
        }
        // Server-side REALITY settings carry only the placeholder the server swaps for its own key.
        val exitIn = fill(TunnelConfigs.abroadTemplate(s, TunnelTransport.REALITY), 30000, "t-reality-now")
            .getJSONArray("inbounds").getJSONObject(0)
        assertEquals(TunnelScripts.KEY_PLACEHOLDER, exitIn.getJSONObject("streamSettings").getJSONObject("realitySettings").getString("privateKey"))
        assertEquals("xtls-rprx-vision", exitIn.getJSONObject("settings").getJSONArray("clients").getJSONObject(0).getString("flow"))
        val iranOut = fill(TunnelConfigs.iranTemplate(s, abroad.host, TunnelTransport.XHTTP), 30001, "t-xhttp-now")
            .getJSONArray("outbounds").getJSONObject(0)
        assertEquals("xhttp", iranOut.getJSONObject("streamSettings").getString("network"))
        assertEquals(s.exitPublicKey, iranOut.getJSONObject("streamSettings").getJSONObject("realitySettings").getString("publicKey"))
        // The Iran server never sends traffic out directly: the file Xray reads last (whose outbounds
        // it puts first) holds only a blackhole, which becomes the default outbound.
        val base = JSONObject(TunnelConfigs.iranBase(s))
        assertFalse(base.has("outbounds"))
        assertEquals("blackhole", JSONObject(TunnelConfigs.iranDefaultBlock()).getJSONArray("outbounds").getJSONObject(0).getString("protocol"))
        assertTrue(TunnelConfigs.IRAN_BLOCK_FILE > "30-t-hy2.json" && TunnelConfigs.IRAN_BLOCK_FILE > "21-t-xhttp-prev.json")
        assertEquals("IPIfNonMatch", JSONObject(TunnelConfigs.abroadBase()).getJSONObject("routing").getString("domainStrategy"))
        assertEquals("t-reality-now", base.getJSONObject("routing").getJSONArray("balancers").getJSONObject(0).getString("fallbackTag"))
        assertEquals("t-hy2", TunnelConfigs.fallbackTag(spec(transports = setOf(TunnelTransport.HYSTERIA2))))
        val client = TunnelConfigs.iranHysteriaClient(s, abroad.host, "/etc/maximus/tunnel/hy")
        assertTrue(client.contains("ca: /etc/maximus/tunnel/hy/abroad-cert.pem"))
        assertFalse(client.contains("insecure"))
        assertTrue(TunnelConfigs.abroadHysteria(s, "/d").contains("    - reject(10.0.0.0/8)\n"))
    }

    @Test fun phoneLinkPointsAtTheIranServer() {
        val link = TunnelConfigs.entryLink(spec(), iran, abroad)
        assertTrue(link.startsWith("vless://11111111-2222-4333-8444-555555555555@198.51.100.20:42001?"))
        assertTrue(link.contains("security=reality") && link.contains("pbk=Qjpp4Zp0kE1cvofhWqu_QoqZ_f_bhszCleWdklJqU3I"))
        assertTrue(link.contains("sni=www.digikala.com") && link.contains("flow=xtls-rprx-vision"))
    }

    private fun allScripts() = listOf(
        TunnelScripts.check(TunnelScripts.IRAN_SNI, abroad.host, 22),
        TunnelScripts.abroadInstall(spec(), "amd64", abroad.host, renewKeys = false),
        TunnelScripts.abroadInstall(spec(rotation = 0, transports = setOf(TunnelTransport.REALITY)), "arm64", "exit.example.com", renewKeys = true),
        TunnelScripts.iranInstall(spec(), "amd64", abroad.host, renewKeys = false, oldEntryPort = 40000),
        TunnelScripts.iranInstall(spec(rotation = 6, transports = setOf(TunnelTransport.XHTTP)), "arm64", abroad.host, renewKeys = true, oldEntryPort = null),
        TunnelScripts.iranStatus(), TunnelScripts.reseed("ffeeddccbbaa99887766554433221100"),
        TunnelScripts.uninstall(TunnelSpec.ROLE_ABROAD, null, 45000), TunnelScripts.uninstall(TunnelSpec.ROLE_IRAN, 42001, null),
        TunnelScripts.restart(), TunnelScripts.logs(),
        TunnelScripts.abroadInstall(spec(transports = setOf(TunnelTransport.REVERSE)), "amd64", abroad.host, renewKeys = false),
        TunnelScripts.iranInstall(spec(transports = setOf(TunnelTransport.REVERSE)), "amd64", abroad.host, renewKeys = false, oldEntryPort = null),
        TunnelScripts.abroadReverse(spec(), iran.host)
    )

    @Test fun scriptsAreValidShellWithPinnedPrograms() {
        val shells = listOf("sh", "bash", "dash").filter { sh ->
            runCatching { ProcessBuilder(sh, "-c", "true").start().waitFor() == 0 }.getOrDefault(false)
        }
        assertTrue(shells.isNotEmpty())
        allScripts().forEachIndexed { i, s ->
            assertFalse("script $i", s.text.contains("§") || s.text.contains("@@"))
            assertFalse(Regex("\\|\\s*(sudo\\s+)?(ba)?sh\\b").containsMatchIn(s.text))
            for (sh in shells) {
                val p = ProcessBuilder(sh, "-n").redirectErrorStream(true).start()
                p.outputStream.use { it.write(s.text.toByteArray()) }
                val out = p.inputStream.bufferedReader().readText()
                assertEquals("script $i under $sh: $out", 0, p.waitFor())
            }
        }
        val abroadText = allScripts()[1].text
        assertTrue(abroadText.contains(ServerBinaries.SHA256.getValue("xray-linux-amd64")))
        assertTrue(abroadText.contains(ServerBinaries.SHA256.getValue("hysteria-linux-amd64")))
        assertTrue(abroadText.contains("subjectAltName=IP:203.0.113.7"))
        assertTrue(abroadText.contains("OnCalendar=*-*-* 00/24:00:05 UTC"))
        assertTrue(abroadText.lines().count { it == "EOF_TPL" } == 2)
        assertTrue(abroadText.lines().any { it == "EOF_ROTATE" })
        // Without Hysteria2 the program is not downloaded.
        assertFalse(allScripts()[2].text.contains("fetch_bin hysteria-linux-arm64 ") && allScripts()[2].text.contains("if [ 1 = 1 ]; then\n  fetch_bin hysteria"))
        val iranText = allScripts()[3].text
        assertTrue(iranText.contains("fw_allow 42001 tcp") && iranText.contains("fw_close 40000 tcp"))
        assertTrue(iranText.lines().any { it == "EOF_CERT" })
    }

    @Test fun unsafeValuesAreRefused() {
        val bad = listOf<() -> Unit>(
            { TunnelScripts.validate(spec().copy(entrySni = "x.com; reboot")) },
            { TunnelScripts.validate(spec().copy(seed = "short")) },
            { TunnelScripts.validate(spec().copy(xhttpPath = "/a b")) },
            { TunnelScripts.validate(spec().copy(entryUuid = "1' ; rm -rf /")) },
            { TunnelScripts.validate(spec().copy(transports = emptySet())) },
            { TunnelScripts.validate(spec().copy(rotationHours = 5)) },
            { TunnelScripts.iranInstall(spec().copy(exitPublicKey = ""), "amd64", abroad.host, false, null) },
            { TunnelScripts.iranInstall(spec().copy(hyCertPem = "EOF_CERT\nreboot"), "amd64", abroad.host, false, null) },
            { TunnelScripts.abroadInstall(spec(), "amd64", "1.2.3.4'", false) },
            { TunnelScripts.reseed("zz") },
            { TunnelScripts.validate(spec().copy(reverseUuid = "")) },
            { TunnelScripts.validate(spec().copy(reverseUuid = spec().entryUuid)) },
            { TunnelScripts.abroadReverse(spec(transports = setOf(TunnelTransport.REALITY)), iran.host) },
            { TunnelScripts.abroadReverse(spec(), "1.2.3.4; reboot") },
            { TunnelScripts.check(TunnelScripts.IRAN_SNI, "x\nreboot", 22) }
        )
        bad.forEachIndexed { i, f -> assertNotNull("case $i", runCatching(f).exceptionOrNull()) }
    }

    @Test fun reverseWayLinksBackThroughThePhonePort() {
        val s = spec()
        // The Iran server accepts the server abroad's id on the phone's port and turns it into "t-rv".
        val clients = JSONObject(TunnelConfigs.iranBase(s)).getJSONArray("inbounds").getJSONObject(0)
            .getJSONObject("settings").getJSONArray("clients")
        assertEquals(2, clients.length())
        assertEquals(s.reverseUuid, clients.getJSONObject(1).getString("id"))
        assertEquals("t-rv", clients.getJSONObject(1).getJSONObject("reverse").getString("tag"))
        assertEquals(1, JSONObject(TunnelConfigs.iranBase(s.copy(transports = setOf(TunnelTransport.REALITY))))
            .getJSONArray("inbounds").getJSONObject(0).getJSONObject("settings").getJSONArray("clients").length())
        // The server abroad dials the Iran server with REALITY, checked against the Iran server's key.
        val out = JSONObject(TunnelConfigs.abroadReverse(s, iran.host)).getJSONArray("outbounds").getJSONObject(0)
        val set = out.getJSONObject("settings")
        assertEquals(iran.host, set.getString("address"))
        assertEquals(s.entryPort, set.getInt("port"))
        assertEquals(TunnelConfigs.REVERSE_IN, set.getJSONObject("reverse").getString("tag"))
        assertEquals(s.entryPublicKey, out.getJSONObject("streamSettings").getJSONObject("realitySettings").getString("publicKey"))
        assertFalse(out.toString().contains("insecure", ignoreCase = true))
        // Its outbound (a later file) would become the default; the last rule keeps "direct" the default.
        val rules = JSONObject(TunnelConfigs.abroadBase()).getJSONObject("routing").getJSONArray("rules")
        assertEquals("block", rules.getJSONObject(0).getString("outboundTag"))
        assertEquals("direct", rules.getJSONObject(rules.length() - 1).getString("outboundTag"))
        assertEquals("t-rv", TunnelConfigs.fallbackTag(spec(transports = setOf(TunnelTransport.REVERSE))))
        val abroadOnlyReverse = TunnelScripts.abroadInstall(spec(transports = setOf(TunnelTransport.REVERSE)), "amd64", abroad.host, false).text
        assertFalse(abroadOnlyReverse.contains("EOF_TPL"))
        assertTrue(TunnelScripts.iranInstall(s, "amd64", abroad.host, false, null).text.contains("RV_FROM=203.0.113.7\nRV_PORT=42001"))
    }

    @Test fun checkPicksEveryWayThatCanConnect() {
        val all = TunnelTransport.entries.toSet()
        assertEquals(all, TunnelTransport.recommended(true, true))
        assertEquals(all, TunnelTransport.recommended(null, null))
        assertEquals(setOf(TunnelTransport.REVERSE), TunnelTransport.recommended(false, true))
        assertEquals(TunnelTransport.FORWARD.toSet(), TunnelTransport.recommended(true, false))
        assertEquals(all, TunnelTransport.recommended(false, false))
    }
}
