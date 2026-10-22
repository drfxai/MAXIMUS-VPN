package com.example.panels.servers.panel

import com.example.panels.servers.InstallChecks
import com.example.panels.servers.InstalledTool
import com.example.panels.servers.ManagedServer
import com.example.panels.servers.RemoteShell
import com.example.panels.servers.ServerFacts
import com.example.panels.servers.ServerLocation
import com.example.panels.servers.ServerToolCatalog
import com.example.panels.servers.ShellResult
import com.example.panels.servers.ToolScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** V6 items 78 and 79: one provider interface for 3x-ui and Hiddify; Hiddify only on a server of its own. */
class ServerPanelTest {
    private val server = ManagedServer(name = "de", host = "203.0.113.7", location = ServerLocation.ABROAD, hostKeySha256 = "x", password = "p")

    private fun facts(os: String = "ubuntu", tcp: String = "22", ramKb: Long = 2_000_000, diskFreeKb: Long = 30_000_000, extra: String = "") =
        ServerFacts.parse(
            "---MX-FACTS---\nOS_ID=$os\nOS_NAME=\"$os\"\nARCH=x86_64\nUID=0\nSYSTEMD=1\nPKG=apt-get\n" +
                "RAM_KB=$ramKb\nDISK_FREE_KB=$diskFreeKb\nTCP=$tcp\nUDP=\n$extra"
        )

    private val hiddify = HiddifyPanelProvider { error("no SSH in this test") }

    @Test fun hiddifyNeedsAServerOfItsOwn() {
        assertNull(hiddify.installBlocker(server, facts()))
        assertNotNull(hiddify.installBlocker(server, facts(os = "debian")))
        assertNotNull(hiddify.installBlocker(server, facts(tcp = "22,443")))
        assertNotNull(hiddify.installBlocker(server, facts(tcp = "22,80")))
        assertNotNull(hiddify.installBlocker(server, facts(ramKb = 512_000)))
        assertNotNull(hiddify.installBlocker(server, facts(diskFreeKb = 2_000_000)))
        // Every server reports an x-ui unit state; only real 3x-ui files count.
        assertNull(hiddify.installBlocker(server, facts(extra = "SVC_x-ui=inactive\n")))
        assertNotNull(hiddify.installBlocker(server, facts(extra = "XUI=1\n")))
        assertNotNull(hiddify.installBlocker(server, facts(extra = "MXTUNNEL=1\n")))
        assertNotNull(hiddify.installBlocker(server, facts(extra = "HIDDIFY=1\nHIDDIFY_VERSION=14.0.5\n")))
        assertNotNull(hiddify.installBlocker(server.withTool(InstalledTool(ServerToolCatalog.XUI)), facts()))
        assertNotNull(hiddify.installBlocker(server.withTool(InstalledTool(ServerToolCatalog.MAXIMUS_TUNNEL)), facts()))
    }

    @Test fun xuiIsNotInstalledBesideHiddify() {
        val xui = XuiPanelProvider(connect = { error("no SSH in this test") })
        assertNull(xui.installBlocker(server, facts()))
        assertNotNull(xui.installBlocker(server.withTool(InstalledTool(ServerToolCatalog.HIDDIFY)), facts()))
        assertNotNull(xui.installBlocker(server, facts(extra = "HIDDIFY=1\n")))
        assertFalse(PanelOperation.UPDATE in xui.operations)
        assertEquals("v3.8.5", xui.pinnedVersion)
    }

    @Test fun factsFindPanelsInstalledFromElsewhere() {
        val f = facts(extra = "HIDDIFY=1\nHIDDIFY_VERSION=14.0.5\nSVC_hiddify-panel=active\nSVC_hiddify-xray=failed\n")
        assertTrue(f.hiddifyInstalled)
        assertEquals("14.0.5", f.hiddifyVersion)
        assertEquals("failed", f.services["hiddify-xray"])
        assertFalse(facts().hiddifyInstalled || facts().xuiInstalled || facts().tunnelInstalled)
        assertTrue(HiddifyPanelProvider.checks(server, facts()).none { it.blocking })
    }

    @Test fun versionsCompareNumerically() {
        assertTrue(PanelStatus.compareVersions("v14.0.10", "14.0.5") > 0)
        assertEquals(0, PanelStatus.compareVersions("v14.0", "14.0.0"))
        assertTrue(PanelStatus("hiddify", PanelState.RUNNING, "13.9.1").updateAvailable("v14.0.5"))
        assertFalse(PanelStatus("hiddify", PanelState.RUNNING, "14.0.5").updateAvailable("v14.0.5"))
        assertFalse(PanelStatus("hiddify", PanelState.RUNNING, null).updateAvailable("v14.0.5"))
    }

    @Test fun stateComesFromEveryUnit() {
        assertEquals(PanelState.NOT_INSTALLED, stateOf(false, false, emptyMap()))
        assertEquals(PanelState.INSTALLING, stateOf(false, true, emptyMap()))
        assertEquals(PanelState.UNKNOWN, stateOf(true, false, emptyMap()))
        assertEquals(PanelState.RUNNING, stateOf(true, false, mapOf("a" to "active", "b" to "active")))
        assertEquals(PanelState.DEGRADED, stateOf(true, false, mapOf("a" to "active", "b" to "failed")))
        assertEquals(PanelState.STOPPED, stateOf(true, false, mapOf("a" to "inactive")))
    }

    @Test fun hiddifyStatusReadsTheServer() {
        val shell = object : RemoteShell {
            override fun run(script: String, timeoutMs: Long, onLine: (String) -> Unit): ShellResult {
                val lines = listOf("##STEP 1", "##RESULT INSTALLED=1", "##RESULT VERSION=14.0.5",
                    "##RESULT SVC_hiddify-panel=active", "##RESULT SVC_hiddify-xray=failed")
                lines.forEach(onLine)
                return ShellResult(0, lines.joinToString("\n"))
            }
            override fun close() {}
        }
        val st = HiddifyPanelProvider { shell }.status(server)
        assertEquals(PanelState.DEGRADED, st.state)
        assertEquals("14.0.5", st.version)
        assertEquals(mapOf("hiddify-panel" to "active", "hiddify-xray" to "failed"), st.services)
        // An existing Hiddify is recorded, never reinstalled.
        val imported = HiddifyPanelProvider { shell }.import(server)
        assertEquals("1", imported.tool!!.settings[HiddifyPanelProvider.K_IMPORTED])
    }

    @Test fun unreachableServerIsUnknownNotMissing() {
        val st = HiddifyPanelProvider { throw java.io.IOException("timeout") }.status(server)
        assertEquals(PanelState.UNKNOWN, st.state)
        try { HiddifyPanelProvider { throw java.io.IOException("timeout") }.import(server); fail() } catch (_: IllegalStateException) {}
    }

    @Test fun hiddifyInstallerIsPinnedAndChecked() {
        val text = HiddifyScripts.install(update = false).text
        assertTrue(text.contains("refs/tags/${HiddifyScripts.VERSION}/"))
        for ((file, sha) in HiddifyScripts.INSTALLER_SHA256) {
            assertTrue(Regex("^[0-9a-f]{64}$").matches(sha))
            assertTrue(text.contains("/$file\"") && text.contains(sha))
        }
        assertTrue(text.contains("exit ${HiddifyScripts.EXIT_ALREADY}"))
        assertFalse(HiddifyScripts.install(update = true).text.contains("exit ${HiddifyScripts.EXIT_ALREADY}"))
    }

    private fun allScripts(): List<ToolScript> = listOf(
        HiddifyScripts.status(), HiddifyScripts.install(false), HiddifyScripts.install(true),
        HiddifyScripts.service("restart"), HiddifyScripts.service("start"), HiddifyScripts.service("stop"),
        HiddifyScripts.backup(), HiddifyScripts.logs(40), HiddifyScripts.uninstall(false), HiddifyScripts.uninstall(true),
        XuiScripts.status(), XuiScripts.service("start"), XuiScripts.service("stop"), XuiScripts.backup()
    )

    @Test fun everyPanelScriptIsValidShellAndRunsNoDownload() {
        val shells = listOf("sh", "bash", "dash").filter { sh ->
            runCatching { ProcessBuilder(sh, "-c", "true").start().waitFor() == 0 }.getOrDefault(false)
        }
        assertTrue("no shell to check with", shells.isNotEmpty())
        allScripts().forEachIndexed { i, s ->
            assertFalse(Regex("\\|\\s*(sudo\\s+)?(ba)?sh\\b").containsMatchIn(s.text))
            assertFalse(s.text.contains("§"))
            assertFalse(s.text.contains("@@"))
            for (sh in shells) {
                val p = ProcessBuilder(sh, "-n").redirectErrorStream(true).start()
                p.outputStream.use { it.write(s.text.toByteArray()) }
                val out = p.inputStream.bufferedReader().readText()
                assertEquals("script $i under $sh: $out", 0, p.waitFor())
            }
        }
    }

    @Test fun providersAreListedById() {
        assertEquals(listOf(ServerToolCatalog.XUI, ServerToolCatalog.HIDDIFY), ServerPanels.all().map { it.id })
        val tool = ServerToolCatalog.byId(ServerToolCatalog.HIDDIFY)!!
        assertTrue(tool.ownServer)
        assertEquals(setOf(ServerToolCatalog.XUI, ServerToolCatalog.MAXIMUS_TUNNEL), tool.conflicts)
        assertTrue(ServerToolCatalog.HIDDIFY in ServerToolCatalog.byId(ServerToolCatalog.XUI)!!.conflicts)
    }

    @Test fun theWizardCheckRefusesASharedServer() {
        val tool = ServerToolCatalog.byId(ServerToolCatalog.HIDDIFY)!!
        assertTrue(InstallChecks.forTool(tool, server, facts()).none { it.blocking })
        assertTrue(InstallChecks.forTool(tool, server, facts(extra = "XUI=1\n")).any { it.blocking })
        val xui = ServerToolCatalog.byId(ServerToolCatalog.XUI)!!
        assertTrue(InstallChecks.forTool(xui, server, facts(extra = "HIDDIFY=1\n")).any { it.blocking })
    }
}
