package com.example.panels.servers

import com.example.panels.servers.panel.HiddifyPanelProvider
import com.example.panels.servers.panel.HiddifyScripts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddifyAdminUrlTest {
    @Test fun acceptsOfficialV14AdminUrlShape() {
        val secretUrl = "https://panel.example.com/SecretProxyPath/8b7f6b5a-1234-4567-89ab-1234567890ab/"
        assertEquals(secretUrl, HiddifyPanelProvider.validatedAdminUrl(secretUrl))
    }

    @Test fun rejectsInsecureAndMisleadingLinks() {
        assertNull(HiddifyPanelProvider.validatedAdminUrl(null))
        assertNull(HiddifyPanelProvider.validatedAdminUrl("http://panel.example.com/secret/admin-uuid/"))
        assertNull(HiddifyPanelProvider.validatedAdminUrl("https://panel.example.com"))
        assertNull(HiddifyPanelProvider.validatedAdminUrl("https://user:password@panel.example.com/secret/admin-uuid/"))
        assertNull(HiddifyPanelProvider.validatedAdminUrl("https://panel.example.com:8080/secret/admin-uuid/"))
    }

    @Test fun rejectsQueryEscapesAndTerminalControls() {
        listOf(
            "https://panel.example.com/secret/admin-uuid/?token=hidden",
            "https://panel.example.com/secret/%2e%2e/",
            "https://panel.example.com/secret/admin-uuid/\u001b[0m",
            "https://panel.example.com/secret/admin-uuid/#hidden",
            "https://panel.example.com/secret/admin-uuid/;"
        ).forEach { assertNull(HiddifyPanelProvider.validatedAdminUrl(it)) }
    }

    @Test fun realShellReadsFixtureAndResultChannelNeverLogsSecretUrl() {
        val dir = java.nio.file.Files.createTempDirectory("hiddify-admin-test").toFile()
        try {
            val synthetic = "https://panel.example.com/SecretProxyPath/8b7f6b5a-1234-4567-89ab-1234567890ab/"
            java.io.File(dir, "data").mkdirs()
            java.io.File(dir, "data/current.json").writeText(org.json.JSONObject()
                .put("panel_links", org.json.JSONArray(listOf("http://192.0.2.1/secret/id/", synthetic))).toString())
            val script = HiddifyScripts.adminUrl().text.replace(HiddifyScripts.DIR, dir.absolutePath)
                .replace("if [ \"$(id -u)\" -ne 0 ]; then", "if false; then")
            val process = ProcessBuilder("sh", "-s").redirectErrorStream(true).start()
            process.outputStream.use { it.write(script.toByteArray()) }
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor())
            val logs = mutableListOf<String>()
            val progress = ScriptProgress({}, { logs += it })
            output.lineSequence().forEach(progress::line)
            assertEquals(synthetic, progress.results["ADMIN_URL"])
            assertTrue(logs.none { it.contains("SecretProxyPath") })
            java.io.File(dir, "data/current.json").writeText("{broken")
            val bad = ProcessBuilder("sh", "-s").redirectErrorStream(true).start()
            bad.outputStream.use { it.write(script.toByteArray()) }
            val failure = bad.inputStream.bufferedReader().readText()
            assertEquals(56, bad.waitFor())
            assertTrue(!failure.contains("SecretProxyPath"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun capabilityUrlIsRemovedFromLogsIncludingScriptFailure() {
        val secret = "https://panel.example.com/SecretProxyPath/admin-uuid/?api_key=hidden"
        assertTrue(!ServerLogSanitizer.redact("request failed: $secret").contains("SecretProxyPath"))
        assertTrue(!ServerLogSanitizer.redact("request failed: $secret").contains("hidden"))
        val logs = mutableListOf<String>()
        ScriptProgress({}, { logs += it }).line("error fetching $secret")
        assertTrue(logs.none { it.contains("SecretProxyPath") })
        val shell = object : RemoteShell {
            override fun run(script: String, timeoutMs: Long, onLine: (String) -> Unit): ShellResult =
                ShellResult(1, "failed at $secret")
            override fun close() = Unit
        }
        val err = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            shell.runTool(ToolScript("unused", emptyList()), object : ActionProgress {
                override fun step(index: Int) = Unit
                override fun log(line: String) = Unit
            })
        }
        assertTrue(!err.message.orEmpty().contains("SecretProxyPath"))
    }

    @Test fun scriptsReadDurableLinksWithoutRunningMutatingAdminCommand() {
        val install = HiddifyScripts.install(update = false).text
        val recover = HiddifyScripts.adminUrl().text
        assertTrue(install.contains("data/current.json"))
        assertTrue(recover.contains("data/current.json"))
        assertTrue(!recover.contains("SUDO hiddify admin"))
        assertTrue(!install.contains("grep -o 'https://[^\" ]*/admin/"))
        assertTrue(recover.contains("result ADMIN_URL"))
    }
}

