package com.example.panels.servers

import com.example.panels.servers.panel.HiddifyPanelProvider
import com.example.panels.servers.panel.HiddifyScripts
import com.example.panels.servers.panel.PanelState
import com.example.vpn.engine.ConfigurationAdapter
import com.example.vpn.engine.UniversalImportEngine
import com.example.vpn.sidecar.CottenDnsSidecar
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DnsPanelFieldRegressionTest {
    private val server = ManagedServer(name = "Fixture", host = "192.0.2.10", username = "root", location = ServerLocation.ABROAD, password = "fixture")

    @Test fun installerProducedCottenLinksRoundTripThroughTheActualAddNodeImporter() {
        for (length in listOf(32, 64)) {
            val key = "a".repeat(length)
            val tool = InstalledTool(ServerToolCatalog.COTTEN, settings = mapOf(
                ToolProfiles.COTTEN_KEY to key, ToolProfiles.DNSTT_DOMAIN to "t.example.com"))
            val link = ToolProfiles.cottenLink(server, tool)
            for (input in listOf(link, link.replace("://", "://\n").chunked(80).joinToString("\n"))) {
                val profiles = ConfigurationAdapter.importConfiguration(input)
                assertEquals(1, profiles.size)
                val settings = CottenDnsSidecar.settings(profiles.single())
                assertEquals(key, settings.key)
                assertEquals("legacy", settings.mode)
                assertEquals("t.example.com", settings.domain)
            }
        }
    }

    @Test fun invalidCottenKeysStayRejectedWithoutCredentialSnippets() {
        val tool = InstalledTool(ServerToolCatalog.COTTEN, settings = mapOf(
            ToolProfiles.COTTEN_KEY to "b".repeat(48), ToolProfiles.DNSTT_DOMAIN to "t.example.com"))
        val invalid = UniversalImportEngine.importText(ToolProfiles.cottenLink(server, tool))
        assertEquals(0, invalid.validProfiles.size)
        assertTrue(invalid.invalidEntries.none { it.contains("b".repeat(48)) })
    }

    @Test fun directoryWithoutServicesCanBeRegisteredForDiagnosticsWithoutClaimingHealth() {
        val remote = object : RemoteShell {
            override fun run(script: String, timeoutMs: Long, onLine: (String) -> Unit): ShellResult {
                val text = "##RESULT INSTALLED=1\n##RESULT VERSION=\n"
                text.lineSequence().forEach(onLine)
                return ShellResult(0, text)
            }
            override fun close() = Unit
        }
        val provider = HiddifyPanelProvider { remote }
        assertEquals(PanelState.STOPPED, provider.status(server).state)
        assertTrue(provider.status(server).detail.contains("incomplete or unverified"))
        assertNotNull(provider.import(server).tool)
    }

    @Test fun postInstallServiceSnapshotReplacesPreInstallStoppedState() {
        var reads = 0
        val remote = object : RemoteShell {
            override fun run(script: String, timeoutMs: Long, onLine: (String) -> Unit): ShellResult {
                if (script == ServerFacts.SCRIPT) {
                    reads++
                    return ShellResult(0, "---MX-FACTS---\nARCH=x86_64\nUID=0\nSYSTEMD=1\nSVC_maximus-cotten=${if (reads == 1) "inactive" else "active"}\n")
                }
                val result = "##RESULT COTTEN_KEY=${"a".repeat(32)}"
                onLine(result)
                return ShellResult(0, result)
            }
            override fun close() = Unit
        }
        val out = ServerManager(connect = { remote }).install(server, ServerToolCatalog.COTTEN,
            InstallOptions("example.com", "t"), object : ActionProgress {
                override fun step(index: Int) = Unit
                override fun log(line: String) = Unit
            })
        assertEquals(2, reads)
        assertTrue(out.server.facts!!.active("maximus-cotten"))
    }

    private fun rootScript(script: String) = script.replace("if [ \"$(id -u)\" -ne 0 ]; then", "if false; then")

    private fun run(script: String, dir: File, shims: Map<String, String>): Pair<Int, String> {
        val bin = File(dir, "bin").apply { mkdirs() }
        shims.forEach { (name, body) -> File(bin, name).apply { writeText("#!/bin/sh\n$body\n"); setExecutable(true) } }
        val outputFile = File(dir, "output-${java.util.UUID.randomUUID()}.log")
        val p = ProcessBuilder("sh", "-s").redirectErrorStream(true).redirectOutput(outputFile).apply {
            environment()["PATH"] = bin.absolutePath + ":" + System.getenv("PATH")
            environment()["FIXTURE"] = dir.absolutePath
        }.start()
        p.outputStream.use { it.write(rootScript(script).toByteArray()) }
        val finished = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) p.destroyForcibly()
        assertTrue("shell fixture exceeded deadline", finished)
        return p.exitValue() to outputFile.readText()
    }

    @Test fun aptLockUsesBoundedNativeWaitAndDoesNotRetryUpdateOrDeleteLocks() {
        val dir = Files.createTempDirectory("apt-lock-fixture").toFile()
        try {
            val apt = """
                echo "${'$'}*" >> "${'$'}FIXTURE/apt-calls"
                echo 'E: Could not get lock /var/lib/dpkg/lock-frontend. It is held by process 1916' >&2
                exit 100
            """.trimIndent()
            val script = ToolScripts.script("pkg_install fixture-package", emptyList()).text
            val (code, output) = run(script, dir, mapOf("apt-get" to apt))
            assertEquals(21, code)
            assertTrue(File(dir, "apt-calls").readText().contains("DPkg::Lock::Timeout=120"))
            assertEquals(1, File(dir, "apt-calls").readLines().size)
            assertTrue(output.contains("No lock was removed"))
            assertFalse(script.contains("rm -f /var/lib/dpkg"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun oneTransientActiveStateDoesNotVerifyAServiceThatImmediatelyCrashes() {
        val dir = Files.createTempDirectory("systemd-fixture").toFile()
        try {
            val systemctl = """
                if [ "${'$'}1" = is-active ]; then
                  n=0; [ ! -f "${'$'}FIXTURE/count" ] || n=${'$'}(cat "${'$'}FIXTURE/count")
                  n=${'$'}((n + 1)); echo "${'$'}n" > "${'$'}FIXTURE/count"
                  [ "${'$'}n" -eq 1 ] && exit 0
                  exit 3
                fi
                exit 0
            """.trimIndent()
            val (code, output) = run(ToolScripts.service(ServerToolCatalog.SOAR, "start").text, dir,
                mapOf("systemctl" to systemctl, "sleep" to "exit 0", "journalctl" to "echo 'fixture process exited'"))
            assertEquals(40, code)
            assertTrue(output.contains("did not remain active"))
            assertEquals(15, File(dir, "count").readText().trim().toInt())
        } finally { dir.deleteRecursively() }
    }

    @Test fun incompleteHiddifyIsQuarantinedButConfigurationDataBlocksCleanup() {
        val dir = Files.createTempDirectory("hiddify-partial").toFile()
        try {
            val target = File(dir, "panel").apply { mkdirs() }
            File(target, "download.tmp").writeText("fixture")
            val script = HiddifyScripts.uninstall(false).text.replace(HiddifyScripts.DIR, target.absolutePath)
            val (code, output) = run(script, dir, mapOf("systemctl" to "exit 0"))
            assertEquals(0, code)
            assertFalse(target.exists())
            assertTrue(dir.listFiles()!!.any { it.name.startsWith("panel.incomplete-") && File(it, "download.tmp").exists() })
            assertTrue(output.contains("recoverable backup"))
            File(target, "data").mkdirs()
            File(target, "data/current.json").writeText("{\"fixture\":true}")
            val (denied, _) = run(script, dir, mapOf("systemctl" to "exit 0"))
            assertEquals(57, denied)
            assertTrue(File(target, "data/current.json").exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun removingManagedHiddifyStopsOnlyItsUnitsAndPreservesData() {
        val dir = Files.createTempDirectory("hiddify-stop").toFile()
        try {
            val target = File(dir, "panel/data").apply { mkdirs() }.parentFile!!
            File(target, "data/current.json").writeText("{\"fixture\":true}")
            val systemctl = """
                case "${'$'}1" in
                  list-unit-files) echo 'hiddify-panel.service disabled';;
                  list-units) :;;
                  *) echo "${'$'}*" >> "${'$'}FIXTURE/actions";;
                esac
            """.trimIndent()
            val script = HiddifyScripts.uninstall(false).text.replace(HiddifyScripts.DIR, target.absolutePath)
            assertEquals(0, run(script, dir, mapOf("systemctl" to systemctl)).first)
            assertEquals(listOf("stop hiddify-panel", "disable hiddify-panel"), File(dir, "actions").readLines())
            assertTrue(File(target, "data/current.json").exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun disabledHiddifyUnitsAreStillDetectedWithCanonicalUnitNames() {
        val dir = Files.createTempDirectory("hiddify-disabled").toFile()
        try {
            val target = File(dir, "panel").apply { mkdirs() }
            val systemctl = """
                case "${'$'}1" in
                  list-unit-files) echo 'hiddify-panel.service disabled';;
                  is-active) echo inactive; exit 3;;
                esac
            """.trimIndent()
            val script = HiddifyScripts.status().text.replace(HiddifyScripts.DIR, target.absolutePath)
            val (code, output) = run(script, dir, mapOf("systemctl" to systemctl))
            assertEquals(0, code)
            assertTrue(output.contains("##RESULT SVC_hiddify-panel=inactive"))
            assertFalse(output.contains("SVC_hiddify-panel.service="))
        } finally { dir.deleteRecursively() }
    }
}
