package com.example.panels.servers

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class ServerMutationGuardTest {
    /** Actual generated shell, redirected into a private fixture; no sudo or system paths are touched. */
    private fun fixture(dir: File, body: String): String {
        val script = ToolScripts.script(body, listOf("fixture")).text
            .replace("/run/maximus-manager", File(dir, "run").absolutePath)
            .replace("/var/lib/maximus-manager/operations", File(dir, "operations").absolutePath)
        return """
            id() { echo 0; }
            install() { for target do :; done; mkdir -p "${'$'}target"; }
        """.trimIndent() + "\n" + script
    }

    @Test fun childExitTrapCannotEraseFailureJournalOrChangeExitCode() {
        val dir = Files.createTempDirectory("maximus-guard").toFile()
        try {
            val p = ProcessBuilder("sh", "-c", fixture(dir, "trap ':' EXIT\nexit 7")).redirectErrorStream(true).start()
            val output = p.inputStream.bufferedReader().readText()
            assertTrue(p.waitFor(10, TimeUnit.SECONDS))
            assertEquals(output, 7, p.exitValue())
            assertEquals("FAILED 7", File(dir, "operations").listFiles()!!.single().readText().trim())
            // A new process may acquire the lock after failure; no stale mkdir lock remains.
            val retry = ProcessBuilder("sh", "-c", fixture(dir, "exit 0")).redirectErrorStream(true).start()
            val retryOutput = retry.inputStream.bufferedReader().readText()
            assertTrue(retry.waitFor(10, TimeUnit.SECONDS))
            assertEquals(retryOutput, 0, retry.exitValue())
        } finally { dir.deleteRecursively() }
    }

    @Test fun twoSshSessionsCannotMutateTheSameServerConcurrently() {
        val dir = Files.createTempDirectory("maximus-lock").toFile()
        var first: Process? = null
        try {
            val marker = File(dir, "started")
            first = ProcessBuilder("sh", "-c", fixture(dir, "touch '${marker.absolutePath}'\nsleep 2")).redirectErrorStream(true).start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!marker.exists() && System.nanoTime() < deadline) Thread.sleep(20)
            assertTrue("first operation started", marker.exists())
            val second = ProcessBuilder("sh", "-c", fixture(dir, "exit 0")).redirectErrorStream(true).start()
            val output = second.inputStream.bufferedReader().readText()
            assertTrue(second.waitFor(5, TimeUnit.SECONDS))
            assertEquals(output, 75, second.exitValue())
            assertTrue(first.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, first.exitValue())
        } finally { first?.destroy(); dir.deleteRecursively() }
    }

    @Test fun keyPurgeRequiresExplicitOptionAndKeepsOwnershipGuard() {
        val kept = ToolScripts.uninstall(ServerToolCatalog.SOAR).text
        val purged = ToolScripts.uninstall(ServerToolCatalog.SOAR, purgeData = true).text
        assertFalse(kept.contains("rm -rf -- /etc/maximus/soar"))
        assertTrue(purged.contains("rm -rf -- /etc/maximus/soar"))
        assertTrue(purged.indexOf("SOAR ownership not verified") < purged.indexOf("rm -rf -- /etc/maximus/soar"))
        assertFalse(ToolScripts.logs(ServerToolCatalog.SOAR).text.contains("flock -n"))
    }
}
