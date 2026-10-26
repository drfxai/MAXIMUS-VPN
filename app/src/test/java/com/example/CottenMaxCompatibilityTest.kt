package com.example

import com.example.data.model.AppSettings
import com.example.data.model.OperationalMode
import com.example.vpn.sidecar.CottenDnsSidecar
import com.example.vpn.sidecar.SidecarContext
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class CottenMaxCompatibilityTest {
    @Test fun maxImportEditAndLaunchPreserveModeKeyAndEncryptedTransport() {
        val key = "ab".repeat(32)
        val original = CottenDnsSidecar.profile(CottenDnsSidecar.Settings(
            "t.example.com", key, listOf("1.1.1.1"), transport = "doh",
            tlsName = "cloudflare-dns.com", mode = "cottenmax"))
        val imported = CottenDnsSidecar.parse(CottenDnsSidecar.toLink(original))
        val edited = CottenDnsSidecar.withSettings(imported,
            CottenDnsSidecar.settings(imported).copy(resolvers = listOf("8.8.8.8"), tlsName = "dns.google"))
        assertEquals(key, edited.uuid)
        assertEquals("cottenmax", CottenDnsSidecar.settings(edited).mode)
        val dir = Files.createTempDirectory("max-contract").toFile()
        try {
            val launch = CottenDnsSidecar().prepare(edited, AppSettings(),
                SidecarContext(dir, File(dir, "libcottendns.so"), 41001, "user", "password", OperationalMode.DAILY))
            val config = File(dir, "cotten-client.toml").readText()
            assertTrue(config.contains("TUNNEL_MODE = \"cottenmax\""))
            assertTrue(config.contains("RESOLVER_TRANSPORT = \"doh\""))
            assertTrue(config.contains("RESOLVER_TLS_INSECURE_SKIP_VERIFY = false"))
            assertTrue(config.contains("QUERY_RATE_LIMIT_PER_SECOND = 0.0"))
            assertTrue(launch.socksAuth)
            assertFalse(launch.command.joinToString().contains(key))
            assertEquals("8.8.8.8:53\n", File(dir, "cotten-resolvers.txt").readText())
        } finally { dir.deleteRecursively() }
    }
}
