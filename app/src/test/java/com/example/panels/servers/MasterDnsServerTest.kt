package com.example.panels.servers

import com.example.vpn.engine.UniversalImportEngine
import com.example.vpn.sidecar.CottenDnsSidecar
import com.example.vpn.sidecar.WhiteDnsProfileLinks
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.Base64

/** MasterDnsVPN server installer: pinned script, saved profile, share link and shared entry points. */
class MasterDnsServerTest {
    private val server = ManagedServer(name = "Frankfurt", host = "203.0.113.7", location = ServerLocation.ABROAD,
        hostKeySha256 = "SHA256:abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG", password = "pw")
    private val key = "0123456789abcdef0123456789abcdef"
    private fun tool(k: String = key) = InstalledTool(ServerToolCatalog.MASTERDNS, port = 53, settings = mapOf(
        ToolProfiles.DNSTT_DOMAIN to "t.example.com", ToolProfiles.MASTERDNS_KEY to k))

    @Test fun catalogEntryIsAnAbroadDnsTunnelThatExcludesTheOtherEngines() {
        val t = requireNotNull(ServerToolCatalog.byId(ServerToolCatalog.MASTERDNS))
        assertEquals(ToolGroup.TUNNELS, t.group)
        assertTrue(t.needsDomain)
        assertFalse(t.fitsOn(ServerLocation.IRAN))
        assertEquals(setOf(ServerToolCatalog.DNSTT, ServerToolCatalog.SOAR, ServerToolCatalog.COTTEN), t.conflicts)
        for (other in listOf(ServerToolCatalog.DNSTT, ServerToolCatalog.SOAR, ServerToolCatalog.COTTEN))
            assertTrue(other, ServerToolCatalog.MASTERDNS in ServerToolCatalog.byId(other)!!.conflicts)
        assertTrue(ServerToolCatalog.MASTERDNS in ServerToolCatalog.DNS_TUNNELS)
        assertEquals(listOf("maximus-masterdns"), ToolScripts.UNITS[ServerToolCatalog.MASTERDNS])
        assertTrue(ServerFacts.SCRIPT.contains("maximus-masterdns"))
        assertEquals(listOf(SmartPortPlanner.Binding(ServerToolCatalog.MASTERDNS, SmartPortPlanner.Protocol.UDP, 53)),
            SmartPortPlanner.knownPublicBindings(ServerToolCatalog.MASTERDNS))
    }

    @Test fun installScriptUsesThePinnedReleaseAndNeverRunsARemoteScript() {
        for ((arch, sha) in DnsServerScripts.MASTERDNS_SHA) {
            val text = DnsServerScripts.masterDns("t.example.com", arch).text
            assertTrue(text.contains("https://github.com/masterking32/MasterDnsVPN/releases/download/" +
                DnsServerScripts.MASTERDNS_VERSION + "/MasterDnsVPN_Server_Linux_${arch.uppercase()}.zip"))
            assertTrue(text.contains("printf '%s  %s\\n' '$sha'"))
            assertTrue(text.contains("sha256sum -c -"))
            assertFalse(text.contains("server_linux_install.sh"))
            assertFalse(Regex("curl[^\\n]*\\|\\s*(sudo\\s+)?(ba)?sh(\\s|$)").containsMatchIn(text))
            assertFalse(text.contains("/main/"))
        }
        assertEquals(64, DnsServerScripts.MASTERDNS_SHA.getValue("amd64").length)
        assertEquals(setOf("amd64", "arm64"), DnsServerScripts.MASTERDNS_SHA.keys)
    }

    @Test fun installScriptWritesTheTunnelConfigAndKeepsTheKeyOutOfLogs() {
        val text = DnsServerScripts.masterDns("t.example.com", "amd64").text
        assertTrue(text.contains("DOMAIN = [\"t.example.com\"]"))
        assertTrue(text.contains("UDP_PORT = 53"))
        assertTrue(text.contains("UDP_HOST = \\\"\$DNS_BIND\\\""))
        assertTrue(text.contains("DATA_ENCRYPTION_METHOD = 5"))
        assertTrue(text.contains("ENCRYPTION_KEY_FILE = \"/etc/maximus/masterdns/encrypt_key.txt\""))
        assertTrue(text.contains("LOG_LEVEL = \"WARN\""))
        assertTrue(text.contains("od -An -tx1 -N16 /dev/urandom"))
        assertTrue(text.contains("result MASTERDNS_KEY"))
        // The key goes to the app only as a ##RESULT line, which the app keeps out of the visible log.
        assertFalse(Regex("echo[^\\n]*\\\$key").containsMatchIn(text))
        // System DNS stays up: the listener binds the server's own address, systemd-resolved is untouched.
        assertTrue(text.contains("select_dns_bind"))
        assertTrue(text.contains("require_free_listener udp 53"))
        assertFalse(text.contains("systemctl stop systemd-resolved"))
        assertFalse(text.contains("DNSStubListener"))
        // Locked service, rollback on failure, no overwrite of an existing install.
        assertTrue(text.contains("User=maximus"))
        assertTrue(text.contains("ExecStart=/usr/local/bin/maximus-masterdns-server -nowait -config /etc/maximus/masterdns/server_config.toml"))
        assertTrue(text.contains("IPAddressDeny=localhost"))
        assertTrue(text.contains("trap rollback EXIT"))
        assertTrue(text.contains("Already installed; do not replace a running server"))
        assertTrue(text.contains("fw_allow 53 udp"))
        assertFalse(text.contains("fw_allow 53 tcp"))
        assertFalse(text.contains("§"))
    }

    @Test fun installScriptIsValidShell() {
        val bash = listOf("/bin/bash", "/usr/bin/bash").firstOrNull { File(it).canExecute() } ?: return
        for (arch in listOf("amd64", "arm64")) {
            val f = File.createTempFile("masterdns", ".sh")
            try {
                f.writeText(DnsServerScripts.masterDns("t.example.com", arch).text)
                val p = ProcessBuilder(bash, "-n", f.absolutePath).redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                assertEquals(out, 0, p.waitFor())
            } finally { f.delete() }
        }
    }

    @Test fun unsafeInputsAreRejected() {
        for (bad in listOf("t.example.com;id", "\$(id).example.com", "t example.com", "a", "t.example.com\nx")) {
            try { DnsServerScripts.masterDns(bad, "amd64"); fail("accepted $bad") } catch (_: IllegalArgumentException) {}
        }
        try { DnsServerScripts.masterDns("t.example.com", "mips"); fail("accepted mips") } catch (_: IllegalArgumentException) {}
    }

    @Test fun uninstallChecksOwnershipAndKeepsTheKeyUnlessPurged() {
        val keep = ToolScripts.uninstall(ServerToolCatalog.MASTERDNS).text
        assertTrue(keep.contains("grep -Fq 'ExecStart=/usr/local/bin/maximus-masterdns-server'"))
        assertTrue(keep.contains("systemctl disable --now maximus-masterdns"))
        assertTrue(keep.contains("retained"))
        assertFalse(keep.contains("rm -rf -- /etc/maximus/masterdns"))
        assertTrue(ToolScripts.uninstall(ServerToolCatalog.MASTERDNS, purgeData = true).text.contains("rm -rf -- /etc/maximus/masterdns"))
        assertTrue(ToolScripts.service(ServerToolCatalog.MASTERDNS, "restart").text.contains("systemctl restart maximus-masterdns"))
        assertTrue(ToolScripts.logs(ServerToolCatalog.MASTERDNS).text.contains("-u maximus-masterdns"))
    }

    @Test fun profileUsesTheBuiltInMasterDnsClient() {
        val p = ToolProfiles.masterDnsProfile(server, tool())
        val s = CottenDnsSidecar.settings(p)
        assertEquals("masterdns", s.mode)
        assertEquals(5, s.method)
        assertEquals("t.example.com", s.domain)
        assertEquals(key, s.key)
        assertEquals("MasterDNS · Frankfurt", p.name)
    }

    @Test fun shareLinkRoundTripsThroughTheImporter() {
        val link = ToolProfiles.masterDnsLink(server, tool())
        assertTrue(link.startsWith("masterdns://"))
        val root = JSONObject(String(Base64.getUrlDecoder().decode(link.removePrefix("masterdns://"))))
        assertEquals("whitedns.profile", root.getString("schema"))
        val srv = root.getJSONObject("profile").getJSONObject("server")
        assertEquals("t.example.com", srv.getString("domain"))
        assertEquals(key, srv.getString("encryption_key"))
        assertEquals(5, srv.getInt("encryption_method"))
        assertEquals("1.1.1.1", root.getJSONObject("profile").getJSONArray("resolvers").getString(0))
        val parsed = CottenDnsSidecar.settings(WhiteDnsProfileLinks.parse(link))
        assertEquals(CottenDnsSidecar.settings(ToolProfiles.masterDnsProfile(server, tool())), parsed)
        assertEquals(1, UniversalImportEngine.importText(link).validCount)
    }

    @Test fun clientConfigCarriesTheUpstreamFieldNames() {
        val c = ToolProfiles.masterDnsClientConfig(tool())
        assertTrue(c.contains("DOMAINS = [\"t.example.com\"]"))
        assertTrue(c.contains("DATA_ENCRYPTION_METHOD = 5"))
        assertTrue(c.contains("ENCRYPTION_KEY = \"$key\""))
    }

    @Test fun badKeysNeverBecomeALink() {
        for (bad in listOf("", "xyz", key + key, key.uppercase().replace('0', 'G'))) {
            try { ToolProfiles.masterDnsLink(server, tool(bad)); fail("accepted $bad") }
            catch (_: IllegalArgumentException) {}
        }
    }

    /** A server that records scripts and answers like the real one would. */
    private class FakeServer(val facts: String) {
        var answer: (String) -> Pair<Int, List<String>> = { 0 to emptyList() }
        fun connect(@Suppress("UNUSED_PARAMETER") s: ManagedServer): RemoteShell = object : RemoteShell {
            override fun run(script: String, timeoutMs: Long, onLine: (String) -> Unit): ShellResult {
                if (script == ServerFacts.SCRIPT) return ShellResult(0, facts)
                val (code, lines) = answer(script)
                lines.forEach(onLine)
                return ShellResult(code, lines.joinToString("\n"))
            }
            override fun close() {}
        }
    }

    private val factsText = "---MX-FACTS---\nARCH=aarch64\nUID=0\nSYSTEMD=1\nPKG=apt-get\nSOCKETS=1\nUDP=\nTCP=22\nDISK_FREE_KB=9000000\n"

    private fun noProgress() = object : ActionProgress {
        override fun step(index: Int) {}
        override fun log(line: String) {}
    }

    @Test fun managerInstallSavesTheKeyProfileAndLink() {
        val fake = FakeServer(factsText)
        var ran = ""
        fake.answer = { script -> ran = script; 0 to listOf("##STEP 6", "##RESULT MASTERDNS_KEY=$key") }
        val out = ServerManager(connect = fake::connect).install(server, ServerToolCatalog.MASTERDNS,
            InstallOptions("example.com", "t"), noProgress())
        assertTrue(ran.contains("MasterDnsVPN_Server_Linux_ARM64.zip"))
        assertEquals(key, out.tool!!.settings[ToolProfiles.MASTERDNS_KEY])
        assertEquals("t.example.com", out.tool!!.settings[ToolProfiles.DNSTT_DOMAIN])
        assertEquals(53, out.tool!!.port)
        assertTrue(out.link!!.startsWith("masterdns://"))
        assertNotNull(out.profile)
        assertEquals("masterdns", CottenDnsSidecar.settings(out.profile!!).mode)
        assertNotNull(out.server.tool(ServerToolCatalog.MASTERDNS))

        fake.answer = { 0 to listOf("##RESULT MASTERDNS_KEY=not-a-key") }
        try {
            ServerManager(connect = fake::connect).install(server, ServerToolCatalog.MASTERDNS,
                InstallOptions("example.com", "t"), noProgress())
            fail("A malformed key must not be saved")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun occupiedUdp53AndAnotherDnsEngineBlockTheInstall() {
        val busy = FakeServer(factsText.replace("UDP=\n", "UDP=53\n"))
        try {
            ServerManager(connect = busy::connect).install(server, ServerToolCatalog.MASTERDNS,
                InstallOptions("example.com", "t"), noProgress())
            fail("UDP 53 in use must block")
        } catch (_: IllegalStateException) {}
        val withCotten = server.withTool(InstalledTool(ServerToolCatalog.COTTEN, port = 53))
        try {
            ServerManager(connect = FakeServer(factsText)::connect).install(withCotten, ServerToolCatalog.MASTERDNS,
                InstallOptions("example.com", "t"), noProgress())
            fail("Another DNS engine must block")
        } catch (_: IllegalStateException) {}
    }

    @Test fun smartManagerOffersMasterDns() {
        val inspection = SmartManagerPlanner.inspect(server)
        assertNotNull(inspection.candidate(ServerToolCatalog.MASTERDNS))
    }

    @Test fun dnsRecordsTheUserMustCreate() {
        val records = DnsDelegation.records("example.com", "t", "203.0.113.7")
        assertTrue(records.any { it.type == "A" && it.value == "203.0.113.7" })
        assertTrue(records.any { it.type == "NS" && it.name == "t.example.com" })
    }
}
