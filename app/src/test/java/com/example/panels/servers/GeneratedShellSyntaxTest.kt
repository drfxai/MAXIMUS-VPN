package com.example.panels.servers

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Syntax verification of generated commands, NOT proof of remote installation or
 * operational safety. Runs /bin/sh parser without executing any command on a VPS.
 */
class GeneratedShellSyntaxTest {
    private fun validShell(name: String, script: String) {
        val p = ProcessBuilder("sh", "-n").redirectErrorStream(true).start()
        p.outputStream.bufferedWriter().use { it.write(script); it.newLine() }
        val output = p.inputStream.bufferedReader().readText()
        assertEquals("$name: $output", 0, p.waitFor())
    }

    @Test fun allPrivatePsiphonScriptsParseWithPosixShell() {
        val request = PrivatePsiphonServerScripts.Request(
            "8.8.8.8", 9999,
            "/tmp/maximus-psiphond-${"a".repeat(32)}.bin", "c".repeat(64))
        val scripts = mapOf(
            "psiphon-install" to PrivatePsiphonServerScripts.install(request).text,
            "psiphon-uninstall-preserve" to PrivatePsiphonServerScripts.uninstall().text,
            "psiphon-uninstall-purge" to PrivatePsiphonServerScripts.uninstall(purge = true).text,
            "facts" to ServerFacts.SCRIPT,
            "locked-install" to InstallerShellLock.wrap(PrivatePsiphonServerScripts.install(request).text)
        )
        scripts.forEach { (name, shell) -> validShell(name, shell) }
    }

    @Test fun privatePsiphonValidatesGlobalUnicastAddressAndCleanupContract() {
        for (address in listOf("127.0.0.1", "10.1.2.3", "192.168.1.1", "172.19.1.1",
                "169.254.1.2", "100.64.0.1", "192.0.2.10", "198.51.100.20",
                "203.0.113.5", "224.0.0.1", "0.1.1.1", "1.2.3.999"))
            org.junit.Assert.assertFalse(address, PrivatePsiphonServerScripts.validIpv4(address))
        org.junit.Assert.assertTrue(PrivatePsiphonServerScripts.validIpv4("8.8.8.8"))
        val spec = PrivatePsiphonServerScripts.Request(
            "8.8.8.8", 9999, "/tmp/maximus-psiphond-${"b".repeat(32)}.bin", "c".repeat(64))
        val install = PrivatePsiphonServerScripts.install(spec).text
        org.junit.Assert.assertTrue(install.contains("trap cleanup_install 0"))
        org.junit.Assert.assertTrue(install.contains("listeners=$(ss -Hlnt) ||"))
        org.junit.Assert.assertTrue(install.indexOf("mv '/tmp/") < install.indexOf("sha256sum '/opt/"))
        val uninstall = PrivatePsiphonServerScripts.uninstall().text
        org.junit.Assert.assertFalse(uninstall.contains("PRIVATE_PSIPHON_PURGED=1"))
        org.junit.Assert.assertTrue(PrivatePsiphonServerScripts.uninstall(purge = true).text
            .contains("PRIVATE_PSIPHON_PURGED=1"))
    }
}
