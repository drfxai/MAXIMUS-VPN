package com.example

import com.example.data.model.AppSettings
import com.example.data.model.OperationalMode
import com.example.vpn.sidecar.CottenDnsSidecar
import com.example.vpn.sidecar.SidecarContext
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CottenDnsSidecarTest {
    private val key = "test-only-cotten-key-000000000000"
    private fun settings() = CottenDnsSidecar.Settings("t.example.com", key, listOf("1.1.1.1", "8.8.8.8:53"))
    private fun context(dir: File) = SidecarContext(dir, File(dir, "libcottendns.so"), 41000, "test-user", "test-pass", OperationalMode.DAILY)

    @Test fun shareRoundTripPreservesIdentityAndDnsChoices() {
        val s = settings().copy(transport = "tcp", backups = listOf("backup.example.com"))
        val p = CottenDnsSidecar.profile(s, "My tunnel")
        val parsed = CottenDnsSidecar.parse(CottenDnsSidecar.toLink(p))
        assertEquals(CottenDnsSidecar.settings(p), CottenDnsSidecar.settings(parsed))
        assertEquals(p.canonicalFingerprint, parsed.canonicalFingerprint)
        assertEquals("My tunnel", parsed.name)
        assertTrue(CottenDnsSidecar().handles(parsed))
    }
    @Test fun resolverChoiceAndCredentialsDoNotDeduplicateDifferentRoutes() {
        val a = CottenDnsSidecar.profile(settings())
        assertNotEquals(a.canonicalFingerprint, CottenDnsSidecar.profile(settings().copy(transport = "tcp")).canonicalFingerprint)
        assertNotEquals(a.canonicalFingerprint, CottenDnsSidecar.profile(settings().copy(key = key + "1")).canonicalFingerprint)
    }
    @Test fun cidrHostnamesAndCommandInjectionAreNotResolverInputs() {
        listOf("1.1.1.0/24", "dns.google", "1.1.1.1\n8.8.8.8", "1.1.1.1:0", "999.1.1.1", "127.0.0.1").forEach {
            assertThrows(IllegalArgumentException::class.java) { CottenDnsSidecar.validate(settings().copy(resolvers = listOf(it))) }
        }
    }
    @Test fun ipv6IsPassedAsABracketedLiteral() {
        val s = CottenDnsSidecar.validate(settings().copy(resolvers = listOf("2606:4700:4700::1111")))
        assertEquals("[2606:4700:4700::1111]:53", s.resolvers.single())
    }
    @Test fun legacyAndAmbiguousDuplicateParametersAreRejected() {
        val link = CottenDnsSidecar.toLink(CottenDnsSidecar.profile(settings()))
        assertThrows(IllegalArgumentException::class.java) { CottenDnsSidecar.parse(link.replace("mode=cottenmax", "mode=legacy")) }
        assertThrows(IllegalArgumentException::class.java) { CottenDnsSidecar.parse(link.substringBefore('#') + "&key=other") }
    }
    @Test fun generatedConfigKeepsEncryptionAndLimitsEnabled() {
        val config = CottenDnsSidecar.configuration(settings().copy(transport = "doh", tlsName = "dns.example.com"), context(File(".")))
        for (line in listOf("TUNNEL_MODE = \"cottenmax\"", "RESOLVER_TLS_INSECURE_SKIP_VERIFY = false", "RESOLVER_DISCOVERY_ENABLED = false",
            "SOCKS5_AUTH = true", "LISTEN_IP = \"127.0.0.1\"", "QUERY_RATE_LIMIT_PER_SECOND = 4.0", "LOG_TO_FILE = false")) assertTrue(line, line in config)
        assertTrue("RESOLVER_TRANSPORT = \"doh\"" in config)
    }
    @Test fun configInjectionThroughCredentialsIsRejectedOrQuoted() {
        assertThrows(IllegalArgumentException::class.java) { CottenDnsSidecar.validate(settings().copy(key = key + "\nSOCKS5_AUTH=false")) }
        val config = CottenDnsSidecar.configuration(settings().copy(key = key + "\"\\value"), context(File(".")))
        assertTrue("\\\"\\\\value" in config)
    }
    @Test fun launchRequiresTransferEvidenceAndDoesNotExposeKeysInArguments() {
        val dir = Files.createTempDirectory("cotten-test").toFile()
        try {
            val launch = CottenDnsSidecar().prepare(CottenDnsSidecar.profile(settings()), AppSettings(), context(dir))
            assertTrue(launch.socksAuth)
            assertFalse(launch.command.joinToString().contains(key))
            assertFalse(launch.readyLine!!.containsMatchIn("SOCKS listener started"))
            assertTrue(launch.readyLine!!.containsMatchIn("WD_PROGRESS phase=connected percent=100 mode=cottenmax"))
            assertFalse(launch.readyLine!!.containsMatchIn("WD_PROGRESS phase=connecting percent=10"))
            assertTrue(File(dir, "cotten-client.toml").readText().contains(key))
            assertEquals("1.1.1.1:53\n8.8.8.8:53\n", File(dir, "cotten-resolvers.txt").readText())
            val permissions = Files.getPosixFilePermissions(File(dir, "cotten-client.toml").toPath())
            assertFalse(permissions.any { it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_") })
        } finally { dir.deleteRecursively() }
    }
    @Test fun unsupportedCarrierChainingFailsExplicitly() {
        assertThrows(IllegalArgumentException::class.java) {
            CottenDnsSidecar().prepare(CottenDnsSidecar.profile(settings()), AppSettings(), context(File(".")).copy(upstreamSocks = 1234))
        }
    }
    @Test fun excessiveResolversAndRepeatedBackupDomainsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { CottenDnsSidecar.validate(settings().copy(resolvers = (1..7).map { "1.1.1.$it" })) }
        assertThrows(IllegalArgumentException::class.java) { CottenDnsSidecar.validate(settings().copy(backups = listOf("t.example.com"))) }
    }
}
