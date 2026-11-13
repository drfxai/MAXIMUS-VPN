package com.example
import com.example.vpn.connections.ConnectionEngineRunner
import com.example.vpn.engine.ProfileExtras
import com.example.vpn.sidecar.AetherSidecar
import com.example.vpn.sidecar.AetherSettings
import org.junit.Assert.*
import org.junit.Test
class AetherCarrierScopeTest {
    @Test fun udpWireGuardDoesNotInheritMasqueTlsOptions() {
        val options = AetherSettings(ech = true, fingerprint = "firefox")
        for (carrier in listOf("wireguard", "wiw")) {
            val args = options.arguments(carrier, true)
            assertFalse(args.contains("--fragment")); assertFalse(args.contains("--tls-ciphers")); assertFalse(args.contains("--ech"))
        }
        assertTrue(options.arguments("wiw", true).contains("--wiw-scan"))
        assertTrue(options.arguments("mim", true).contains("--mim-scan"))
        assertFalse(options.arguments("h3", true).contains("--fragment"))
        assertTrue(options.arguments("h2", true).contains("--fragment"))
    }
    @Test fun autoCarrierUsesIpv4OnlyWhenSameLinkMeasurementSaysNoIpv6() {
        val original = AetherSidecar.profile("auto", "auto")
        val measured = ConnectionEngineRunner.candidates(original, ipv4Only = true)
        assertEquals(listOf("h2", "h3", "wireguard"), measured.map { ProfileExtras.read(it).optString("carrier") })
        assertTrue(measured.all { ProfileExtras.read(it).optString("family") == "ipv4" })
        val unmeasured = ConnectionEngineRunner.candidates(original)
        assertTrue(unmeasured.all { ProfileExtras.read(it).optString("family") == "auto" })
        val userPinnedIpv6 = ConnectionEngineRunner.candidates(AetherSidecar.profile("auto", "ipv6"), ipv4Only = true)
        assertTrue(userPinnedIpv6.all { ProfileExtras.read(it).optString("family") == "ipv6" })
    }

    @Test fun blockedLinkAutomaticScanDoesNotBlockOtherCarriersForThreeMinutes() {
        val budget: (String, Boolean, Boolean, String, Long) -> Long = ConnectionEngineRunner::readinessBudgetMs
        assertEquals(40_000L, budget("aether", true, true, "h2", 180_000L))
        assertEquals(65_000L, budget("aether", true, true, "h3", 180_000L))
        assertEquals(75_000L, budget("aether", true, true, "wireguard", 180_000L))
        assertEquals(180_000L, budget("aether", false, true, "h2", 180_000L))
        assertEquals(180_000L, budget("aether", true, false, "h2", 180_000L))
        assertEquals(180_000L, budget("serverless", true, true, "fragA", 180_000L))
        assertEquals(30_000L, budget("aether", true, true, "h2", 30_000L))
    }

    @Test fun filteredLinkTriesUdpCarriersFirstAndRecentFailuresLast() {
        val all = listOf("h2", "h3", "wireguard")
        assertEquals(listOf("wireguard", "h3", "h2"), ConnectionEngineRunner.order(all, null, true, emptySet()))
        assertEquals(listOf("h2", "h3", "wireguard"), ConnectionEngineRunner.order(all, null, false, emptySet()))
        assertEquals(listOf("h2", "h3", "wireguard"), ConnectionEngineRunner.order(all, null, false, setOf("wireguard")))
        assertEquals(listOf("h3", "h2", "wireguard"), ConnectionEngineRunner.order(all, null, true, setOf("wireguard")))
        // A method verified here stays first even if it also failed once since.
        assertEquals("h2", ConnectionEngineRunner.order(all, "h2", true, setOf("h2")).first())
        // Nothing is ever dropped.
        assertEquals(all.toSet(), ConnectionEngineRunner.order(all, null, true, all.toSet()).toSet())
    }

    @Test fun failedMethodMemoryExpiresAndClears() {
        val previousRead = com.example.vpn.connections.BuiltinMethodMemory.read
        val previousWrite = com.example.vpn.connections.BuiltinMethodMemory.write
        val values = mutableMapOf<String, String>()
        try {
            com.example.vpn.connections.BuiltinMethodMemory.read = { values[it] }
            com.example.vpn.connections.BuiltinMethodMemory.write = { k, v -> if (v == null) values.remove(k) else values[k] = v }
            val m = com.example.vpn.connections.BuiltinMethodMemory
            m.recordFailure("net-a", "h2", 1_000)
            m.recordFailure("net-a", "h3", 2_000)
            assertEquals(setOf("h2", "h3"), m.recentlyFailed("net-a", 3_000))
            assertEquals(emptySet<String>(), m.recentlyFailed("net-b", 3_000))
            assertEquals(setOf("h3"), m.recentlyFailed("net-a", 1_000 + m.FAILED_TTL_MS))
            m.clearFailure("net-a", "h3")
            assertEquals(setOf("h2"), m.recentlyFailed("net-a", 3_000))
            assertEquals(emptySet<String>(), m.recentlyFailed(null, 3_000))
        } finally {
            com.example.vpn.connections.BuiltinMethodMemory.read = previousRead
            com.example.vpn.connections.BuiltinMethodMemory.write = previousWrite
        }
    }

    @Test fun aetherSmartFallbackRespectsExactAndTradeBoundaries() {
        fun permitted(smart: Boolean = true, auto: Boolean = false, exact: Boolean = false,
                      locked: Boolean = false, forced: Boolean = false, retried: Boolean = false,
                      aether: Boolean = true) =
            ConnectionEngineRunner.mayRecoverAether(
                aether = aether, smart = smart, autoFailover = auto,
                exact = exact, exitLocked = locked, force = forced, alreadyEscalated = retried)
        assertTrue(permitted())
        assertTrue(permitted(smart = false, auto = true))
        assertFalse(permitted(smart = false))
        assertFalse(permitted(exact = true))
        assertFalse(permitted(locked = true))
        assertFalse(permitted(forced = true))
        assertFalse(permitted(retried = true))
        assertFalse(permitted(aether = false))
    }

}
