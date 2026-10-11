package com.example.panels.servers

import com.example.data.model.ProtocolType
import com.example.panels.ManagedPanel
import com.example.panels.PanelType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class ServerToolsTest {
    private val facts = ServerFacts.parse(
        """
        Welcome banner
        ---MX-FACTS---
        OS_ID=ubuntu
        OS_VERSION="24.04"
        OS_NAME="Ubuntu 24.04.1 LTS"
        ARCH=x86_64
        KERNEL=6.8.0-45-generic
        CPUS=2
        RAM_KB=2014320
        RAM_AVAIL_KB=1500000
        SWAP_KB=0
        DISK_KB=41152736
        DISK_FREE_KB=30000000
        UPTIME=86400
        LOAD1=0.12
        UID=0
        SUDO=0
        SYSTEMD=1
        PKG=apt-get
        TCP=22,2053,443,
        UDP=
        CC=cubic
        FIREWALL=ufw-inactive
        F2B=0
        AUTOUPD=0
        SSH_PASSWORD=yes
        SSHD_DROPINS=1
        SVC_x-ui=active
        SVC_maximus-dnstt=inactive
        SVC_fail2ban=
        """.trimIndent()
    )
    private val server = ManagedServer(name = "Frankfurt", host = "203.0.113.7", location = ServerLocation.ABROAD,
        hostKeySha256 = "SHA256:abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG", password = "pw")

    @Test fun factsAreParsed() {
        assertEquals("Ubuntu 24.04.1 LTS", facts.osName)
        assertEquals("24.04", facts.osVersion)
        assertEquals("amd64", facts.goArch)
        assertEquals(setOf(22, 443, 2053), facts.tcpPorts)
        assertTrue(facts.udpPorts.isEmpty())
        assertTrue(facts.root && facts.systemd && facts.usesApt && facts.canAdmin)
        assertEquals(1967L, facts.ramMb)
        assertEquals(true, facts.sshPasswordLogin)
        assertTrue(facts.active("x-ui"))
        assertFalse(facts.active("maximus-dnstt"))
        assertEquals("inactive", facts.services["fail2ban"])
        assertNull(facts.blocker())
        assertNotNull(facts.copy(arch = "mips").blocker())
        assertNotNull(facts.copy(root = false, sudo = false).blocker())
    }

    @Test fun pinnedHashesMatchTheReproducibleBuild() {
        val sums = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "tools/server-tools/SHA256SUMS") }.firstOrNull { it.isFile }
            ?: return fail("tools/server-tools/SHA256SUMS not found")
        val pinned = sums.readLines().filter { it.isNotBlank() }.associate { l -> l.split(Regex("\\s+")).let { it[1] to it[0] } }
        assertEquals(pinned, ServerBinaries.SHA256)
    }

    private fun allScripts(): List<ToolScript> = listOf(
        ToolScripts.dnsttInstall("t.example.com", "amd64", 34567, 45678, newKey = false),
        ToolScripts.dnsttInstall("t.example.com", "arm64", 34567, 45678, newKey = true),
        ToolScripts.hysteriaInstall("amd64", "203.0.113.7", 41234, "A".repeat(24), "b".repeat(24), newCert = false, oldPort = 40000),
        ToolScripts.hysteriaInstall("arm64", "2001:db8::7", 443, "A".repeat(24), "b".repeat(24), newCert = true),
        ToolScripts.keyLoginAdd(ServerKeys.generate().publicKeyLine),
        ToolScripts.passwordLogin(true), ToolScripts.passwordLogin(false),
        ToolScripts.firewall(22, setOf(2053), setOf(53, 41234)),
        ToolScripts.fail2ban(2222), ToolScripts.autoUpdates(), ToolScripts.bbr(), ToolScripts.swap(1024),
        ToolScripts.restart(ServerToolCatalog.DNSTT), ToolScripts.logs(ServerToolCatalog.HYSTERIA2)
    ) + listOf(ServerToolCatalog.DNSTT, ServerToolCatalog.HYSTERIA2, ServerToolCatalog.XUI, ServerToolCatalog.FIREWALL,
        ServerToolCatalog.FAIL2BAN, ServerToolCatalog.AUTO_UPDATES, ServerToolCatalog.BBR, ServerToolCatalog.SWAP)
        .map { ToolScripts.uninstall(it, 41234) }

    @Test fun everyScriptIsValidShell() {
        val shells = listOf("sh", "bash", "dash").filter { sh ->
            runCatching { ProcessBuilder(sh, "-c", "true").start().waitFor() == 0 }.getOrDefault(false)
        }
        assertTrue("no shell to check with", shells.isNotEmpty())
        (allScripts() + ToolScript(ServerFacts.SCRIPT, emptyList())).forEachIndexed { i, s ->
            for (sh in shells) {
                val p = ProcessBuilder(sh, "-n").redirectErrorStream(true).start()
                p.outputStream.use { it.write(s.text.toByteArray()) }
                val out = p.inputStream.bufferedReader().readText()
                assertEquals("script $i under $sh: $out", 0, p.waitFor())
            }
        }
    }

    @Test fun scriptsNeverRunDownloadsAndCarryNoPlaceholders() {
        for (s in allScripts()) {
            assertFalse(Regex("\\|\\s*(sudo\\s+)?(ba)?sh\\b").containsMatchIn(s.text))
            assertFalse(s.text.contains("§"))
            assertFalse(s.text.contains("@@"))
        }
        val dnstt = ToolScripts.dnsttInstall("t.example.com", "amd64", 34567, 45678, newKey = false).text
        assertTrue(dnstt.contains(ServerBinaries.SHA256.getValue("dnstt-server-linux-amd64")))
        assertTrue(dnstt.contains(ServerBinaries.SHA256.getValue("maximus-socks-linux-amd64")))
        assertTrue(dnstt.contains("ExecStart=/usr/local/bin/maximus-dnstt-server -udp :34567 -privkey-file /etc/maximus/dnstt/server.key t.example.com 127.0.0.1:45678"))
        // Heredoc terminators must start their lines or the unit files would swallow the script.
        assertTrue(dnstt.lines().count { it == "UNIT" } == 2)
        assertTrue(dnstt.lines().any { it == "HELPER" })
        val hy = ToolScripts.hysteriaInstall("arm64", "203.0.113.7", 41234, "A".repeat(24), "b".repeat(24), false, 40000).text
        assertTrue(hy.contains(ServerBinaries.SHA256.getValue("hysteria-linux-arm64")))
        assertTrue(hy.contains("listen: :41234"))
        assertTrue(hy.contains("fw_close 40000 udp"))
        assertTrue(hy.contains("reject(169.254.0.0/16)"))
        assertTrue(hy.lines().any { it == "CONF" })
    }

    @Test fun valuesThatCouldBreakOutOfAScriptAreRefused() {
        val bad = listOf<() -> Unit>(
            { ToolScripts.dnsttInstall("t.example.com; reboot", "amd64", 34567, 45678, false) },
            { ToolScripts.dnsttInstall("t.example.com", "mips", 34567, 45678, false) },
            { ToolScripts.dnsttInstall("t.example.com", "amd64", 53, 45678, false) },
            { ToolScripts.dnsttInstall("t.example.com", "amd64", 34567, 45678, false, listOf("t.example.net \$(reboot)")) },
            { ToolScripts.hysteriaInstall("amd64", "1.2.3.4'", 443, "A".repeat(24), "b".repeat(24), false) },
            { ToolScripts.hysteriaInstall("amd64", "1.2.3.4", 443, "short", "b".repeat(24), false) },
            { ToolScripts.hysteriaInstall("amd64", "1.2.3.4", 70000, "A".repeat(24), "b".repeat(24), false) },
            { ToolScripts.keyLoginAdd("ssh-ed25519 AAAA' ; rm -rf ~") },
            { ToolScripts.swap(100_000) }
        )
        bad.forEachIndexed { i, f ->
            try { f(); fail("case $i was accepted") } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun phoneKeysAreOpenSshEcdsa() {
        val k = ServerKeys.generate()
        assertTrue(k.publicKeyLine.startsWith("ecdsa-sha2-nistp256 "))
        assertTrue(k.privateKeyPem.contains("PRIVATE KEY"))
        assertNotEquals(k.publicKeyLine, ServerKeys.generate().publicKeyLine)
    }

    @Test fun progressLinesAreReadAndSecretsKeptOut() {
        val steps = mutableListOf<Int>()
        val log = mutableListOf<String>()
        val p = ScriptProgress({ steps += it }, { log += it })
        listOf("##STEP 1", "Downloading", "##RESULT PUBKEY=ab12", "password: hunter2", "", "##STEP 2\r", "done").forEach(p::line)
        assertEquals(listOf(1, 2), steps)
        assertEquals(listOf("Downloading", "done"), log)
        assertEquals("ab12", p.results["PUBKEY"])
    }

    @Test fun randomPortsAvoidPortsInUse() {
        val used = (20_000..59_990).toSet()
        repeat(50) {
            val port = ServerRandom.port(used)
            assertTrue(port in 59_991..59_999)
        }
        assertEquals(24, ServerRandom.secret().length)
        assertTrue(ServerRandom.secret(32).matches(Regex("[A-Za-z0-9]{32}")))
    }

    @Test fun serversSurviveStorage() {
        val s = server.copy(
            tools = listOf(InstalledTool(ServerToolCatalog.DNSTT, port = 53, settings = mapOf("pubkey" to "ab"), profileId = "p")),
            facts = facts, privateKey = "KEY", publicKey = "ecdsa-sha2-nistp256 AAA", passwordLoginOff = true
        )
        val back = ServerJson.decode(ServerJson.encode(listOf(s)))
        assertEquals(listOf(s), back)
        assertTrue(ServerJson.decode("not json").isEmpty())
    }

    @Test fun oldThreeXUiPanelsBecomeServersNeedingASignIn() {
        val panels = listOf(
            ManagedPanel("a", PanelType.XUI, "3X-UI", "https://1.2.3.4:2053/", host = "1.2.3.4", hostKeySha256 = "SHA256:x", createdAt = 1),
            ManagedPanel("b", PanelType.XUI, "3X-UI", "https://1.2.3.4:2054/", host = "1.2.3.4", hostKeySha256 = "SHA256:x", createdAt = 2),
            ManagedPanel("c", PanelType.BPB_WORKER, "BPB", "https://w.dev/")
        )
        val servers = ServerMigration.fromPanels(panels)
        assertEquals(1, servers.size)
        assertEquals("b", servers[0].tool(ServerToolCatalog.XUI)?.panelId)
        assertEquals(ServerLocation.ABROAD, servers[0].location)
        assertFalse(servers[0].canSignIn)
    }

    @Test fun dnsRecordsAndTheirCheck() {
        assertNull(DnsDelegation.problem("Example.com", "t"))
        assertNotNull(DnsDelegation.problem("example", "t"))
        assertNotNull(DnsDelegation.problem("example.com", "t t"))
        val records = DnsDelegation.records("https://Example.com/", "t", "203.0.113.7")
        assertEquals("ns.example.com", records[0].name)
        assertEquals("A", records[0].type)
        assertEquals("t.example.com", records[1].name)
        val a = """{"Status":0,"Answer":[{"name":"ns.example.com.","type":1,"data":"203.0.113.7"}]}"""
        val ns = """{"Status":0,"Answer":[{"name":"t.example.com.","type":2,"data":"ns.example.com."}]}"""
        assertEquals(listOf(DnsDelegation.Status.OK, DnsDelegation.Status.OK), DnsDelegation.judge(records, a, ns).map { it.status })
        val wrongA = """{"Status":0,"Answer":[{"type":1,"data":"198.51.100.1"}]}"""
        val servfail = """{"Status":2}"""
        assertEquals(listOf(DnsDelegation.Status.WRONG, DnsDelegation.Status.UNKNOWN), DnsDelegation.judge(records, wrongA, servfail).map { it.status })
        assertEquals(listOf(DnsDelegation.Status.MISSING, DnsDelegation.Status.UNKNOWN), DnsDelegation.judge(records, """{"Status":3}""", null).map { it.status })
    }

    @Test fun hysteriaProfilesPinTheCertificateAndUseSalamander() {
        val tool = InstalledTool(ServerToolCatalog.HYSTERIA2, port = 41234, settings = mapOf(
            ToolProfiles.HY_AUTH to "A".repeat(24), ToolProfiles.HY_OBFS to "b".repeat(24), ToolProfiles.HY_PIN to "c".repeat(64)))
        val p = ToolProfiles.hysteriaProfile(server, tool)
        assertEquals(ProtocolType.HYSTERIA2, p.protocolType)
        assertEquals(41234, p.port)
        assertEquals("c".repeat(64), p.pinnedPeerCertSha256)
        assertFalse(p.allowInsecure)
        assertEquals("salamander", JSONObject(p.finalMask).getJSONArray("udp").getJSONObject(0).getString("type"))
        assertTrue(ToolProfiles.hysteriaLink(server.copy(host = "2001:db8::7"), tool).contains("@[2001:db8::7]:41234"))
    }

    @Test fun checksAndAdviceFollowTheServer() {
        val dnstt = ServerToolCatalog.byId(ServerToolCatalog.DNSTT)!!
        val inIran = server.copy(location = ServerLocation.IRAN)
        assertTrue(InstallChecks.forTool(dnstt, inIran, facts).any { it.blocking && it.label == "Right place" })
        assertTrue(InstallChecks.forTool(dnstt, server, facts).none { it.blocking })
        val advice = ToolAdvisor.suggest(server, facts).map { it.toolId }
        assertTrue(ServerToolCatalog.KEY_LOGIN in advice)
        assertTrue(ServerToolCatalog.BBR in advice)
        assertTrue(ServerToolCatalog.SWAP in advice)
        assertTrue(ServerToolCatalog.DNSTT in advice)
        assertFalse(ServerToolCatalog.DNSTT in ToolAdvisor.suggest(inIran, facts).map { it.toolId })
        assertTrue(ToolAdvisor.suggest(server, null).isEmpty())
    }

    /** A server that records scripts and answers like the real one would. */
    private class FakeServer(val facts: String) {
        val scripts = mutableListOf<String>()
        val logins = mutableListOf<ManagedServer>()
        var answer: (String) -> Pair<Int, List<String>> = { 0 to emptyList() }
        fun connect(s: ManagedServer): RemoteShell { logins += s; return object : RemoteShell {
            override fun run(script: String, timeoutMs: Long, onLine: (String) -> Unit): ShellResult {
                scripts += script
                if (script == ServerFacts.SCRIPT) return ShellResult(0, facts)
                val (code, lines) = answer(script)
                lines.forEach(onLine)
                return ShellResult(code, lines.joinToString("\n"))
            }
            override fun close() {}
        } }
    }

    private val factsText = "---MX-FACTS---\nARCH=aarch64\nUID=0\nSYSTEMD=1\nPKG=apt-get\nUDP=\nTCP=22\nDISK_FREE_KB=9000000\n"

    private fun noProgress() = object : ActionProgress {
        val steps = mutableListOf<Int>()
        override fun step(index: Int) { steps += index }
        override fun log(line: String) {}
    }

    @Test fun aDnsTunnelInstallReturnsAWorkingProfile() {
        val fake = FakeServer(factsText)
        val key = "0123456789abcdef".repeat(4)
        fake.answer = { listOf("##STEP 1", "##STEP 6", "##RESULT PUBKEY=$key") .let { 0 to it } }
        val manager = ServerManager(connect = fake::connect)
        val out = manager.install(server, ServerToolCatalog.DNSTT, InstallOptions("example.com", "t"), noProgress())
        assertTrue(fake.scripts.last().contains("dnstt-server-linux-arm64"))
        val tool = out.server.tool(ServerToolCatalog.DNSTT)!!
        assertEquals("t.example.com", tool.settings[ToolProfiles.DNSTT_DOMAIN])
        assertEquals(key, tool.settings[ToolProfiles.DNSTT_PUBKEY])
        assertEquals("t.example.com", out.profile!!.address)
        assertTrue(out.link!!.startsWith("dnstt://$key@t.example.com?doh="))
        // A second install keeps the same internal ports.
        val again = manager.install(out.server, ServerToolCatalog.DNSTT, InstallOptions("example.com", "t"), noProgress())
        assertEquals(tool.settings[ToolProfiles.DNSTT_PORT], again.tool!!.settings[ToolProfiles.DNSTT_PORT])
    }

    @Test fun aFailedScriptReportsItsLastLines() {
        val fake = FakeServer(factsText)
        fake.answer = { 30 to listOf("##STEP 2", "Checksum mismatch for hysteria-linux-arm64, refusing to install it") }
        try {
            ServerManager(connect = fake::connect).install(server, ServerToolCatalog.HYSTERIA2, InstallOptions(), noProgress())
            fail("should fail")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Checksum mismatch"))
        }
    }

    @Test fun keyLoginIsProvedBeforeItIsKept() {
        val fake = FakeServer(factsText)
        fake.answer = { 0 to listOf("##STEP 1", "##RESULT KEY=added") }
        val out = ServerManager(connect = fake::connect).install(server, ServerToolCatalog.KEY_LOGIN, InstallOptions(), noProgress())
        assertFalse("the key is added over the password session", fake.logins.first().usesKey)
        assertTrue("then a key-only sign-in proves it", fake.logins.last().usesKey)
        assertTrue(out.server.usesKey)
        assertEquals("pw", out.server.password)
        assertTrue(fake.scripts.any { it.contains(out.server.publicKey) })
    }

    @Test fun passwordLoginGoesOffOnlyWithAKeyAndForgetsThePassword() {
        val fake = FakeServer(factsText)
        fake.answer = { 0 to listOf("##RESULT PASSWORD_LOGIN=no") }
        val manager = ServerManager(connect = fake::connect)
        try { manager.setPasswordLogin(server, true, noProgress()); fail("needs a key") } catch (_: IllegalArgumentException) {}
        val keyed = server.copy(privateKey = "KEY", publicKey = "ecdsa-sha2-nistp256 AAA")
        val off = manager.setPasswordLogin(keyed, true, noProgress())
        assertTrue(off.passwordLoginOff)
        assertEquals("", off.password)
    }

    @Test fun dnsTunnelBackupDomainsReachTheServerTheRecordsAndTheProfile() {
        val dnstt = ToolScripts.dnsttInstall("t.example.com", "amd64", 34567, 45678, newKey = false,
            backups = listOf("t.example.net", "T.Example.org")).text
        assertTrue(dnstt.contains("server.key t.example.com,t.example.net,t.example.org 127.0.0.1:45678"))
        assertEquals(listOf("example.net", "example.org"), DnsDelegation.parseBackups(" Example.net,\nexample.org example.net"))
        assertNull(DnsDelegation.backupProblem("example.com", listOf("example.net")))
        assertNotNull(DnsDelegation.backupProblem("example.com", listOf("example.com")))
        assertNotNull(DnsDelegation.backupProblem("example.com", listOf("a.net", "b.net", "c.net", "d.net")))
        assertNotNull(DnsDelegation.backupProblem("example.com", listOf("bad")))
        val records = DnsDelegation.records("example.com", "t", "203.0.113.7", listOf("example.net"))
        assertEquals(listOf("ns.example.com", "t.example.com", "ns.example.net", "t.example.net"), records.map { it.name })
        assertEquals("ns.example.net", records[3].value)

        val tool = InstalledTool(ServerToolCatalog.DNSTT, port = 53, settings = mapOf(
            ToolProfiles.DNSTT_DOMAIN to "t.example.com", ToolProfiles.DNSTT_PUBKEY to "a".repeat(64),
            ToolProfiles.DNSTT_BACKUP_BASES to "example.net", ToolProfiles.DNSTT_BACKUPS to "t.example.net"))
        val server = ManagedServer(name = "de", host = "203.0.113.7", location = ServerLocation.ABROAD)
        val profile = ToolProfiles.dnsttProfile(server, tool)
        assertEquals(listOf("t.example.net"), com.example.vpn.sidecar.DnsttSidecar.settings(profile).backupDomains)
        val link = ToolProfiles.dnsttLink(server, tool)
        assertEquals(listOf("t.example.net"),
            com.example.vpn.sidecar.DnsttSidecar.settings(com.example.vpn.sidecar.DnsttSidecar.parse(link)).backupDomains)
        assertEquals(listOf("example.net"), ToolProfiles.backupBases(tool))
    }

    @Test fun reinstallingADnsTunnelKeepsThePhonesResolversAndRate() {
        val sidecar = com.example.vpn.sidecar.DnsttSidecar
        val old = sidecar.parse("dnstt://${"a".repeat(64)}@t.example.com?udp=8.8.8.8&udp=1.1.1.1&qps=2&perresolver=1")
        val fresh = sidecar.profile("b".repeat(64), "t.example.com", backupDomains = listOf("t.example.net"))
        val merged = sidecar.settings(sidecar.carryOver(old, fresh))
        assertEquals("b".repeat(64), merged.pubkey)
        assertEquals(listOf("t.example.net"), merged.backupDomains)
        assertEquals(listOf("8.8.8.8:53", "1.1.1.1:53"), merged.resolvers.map { it.address })
        assertEquals(2, merged.qps)
        assertTrue(merged.perResolver)
    }
}
