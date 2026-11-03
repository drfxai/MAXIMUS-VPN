package com.example.panels.servers

import org.junit.Assert.*
import org.junit.Test

class SafeDnsRemovalRegressionTest {
    @Test fun dnsttOwnsEveryFirewallRedirectItAddsOrRemoves() {
        val install = ToolScripts.dnsttInstall(
            domain = "t.example.com", arch = "amd64",
            dnsPort = 5300, socksPort = 5800, newKey = false
        ).text
        val uninstall = ToolScripts.uninstall(ServerToolCatalog.DNSTT).text
        assertTrue(install.contains("-m comment --comment maximus-dnstt -j REDIRECT"))
        assertTrue(uninstall.contains("-m comment --comment maximus-dnstt -j REDIRECT"))
        assertFalse(uninstall.contains("--dport 53 -j REDIRECT"))
        assertTrue(install.contains("MUTATED=0"))
        assertTrue(install.contains("MUTATED=1"))
        assertTrue(install.contains("Existing DNS engine file requires manual review"))
    }

    @Test fun deletionOfUnownedServerServicesIsRejectedAndPrivateKeysAreRetained() {
        for ((id, binary, path) in listOf(
            Triple(ServerToolCatalog.DNSTT, "maximus-dnstt-server", ToolScripts.DNSTT_DIR),
            Triple(ServerToolCatalog.SOAR, "maximus-soar-server", "/etc/maximus/soar"),
            Triple(ServerToolCatalog.COTTEN, "maximus-cotten-server", "/etc/maximus/cotten")
        )) {
            val script = ToolScripts.uninstall(id).text
            assertTrue("$id: no service-ownership check", script.contains("grep -Fq 'ExecStart=/usr/local/bin/$binary'"))
            assertFalse("$id: no unattended private-key purge", script.contains("rm -rf $path"))
            assertTrue("$id: no retained data notice", script.contains("retained"))
        }
    }
}
