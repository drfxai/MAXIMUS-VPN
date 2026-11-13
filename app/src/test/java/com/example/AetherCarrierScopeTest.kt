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
        assertEquals(60_000L, budget("aether", true, true, "h2", 180_000L))
        assertEquals(75_000L, budget("aether", true, true, "h3", 180_000L))
        assertEquals(75_000L, budget("aether", true, true, "wireguard", 180_000L))
        assertEquals(180_000L, budget("aether", false, true, "h2", 180_000L))
        assertEquals(180_000L, budget("aether", true, false, "h2", 180_000L))
        assertEquals(180_000L, budget("serverless", true, true, "fragA", 180_000L))
        assertEquals(30_000L, budget("aether", true, true, "h2", 30_000L))
        assertEquals(90_000L, ConnectionEngineRunner.readinessBudgetMs("aether", true, false, "wiw", 180_000L))
    }

    @Test fun retriesNeverShrinkBelowAScanThatCanFinish() {
        // Same window on the first try and on every retry of a filtered link (it used to go 180 → 65 → 40 s).
        for (carrier in listOf("h2", "h3", "wireguard")) {
            val first = ConnectionEngineRunner.readinessBudgetMs("aether", true, true, carrier, 180_000L, failedHere = false)
            val retry = ConnectionEngineRunner.readinessBudgetMs("aether", true, true, carrier, 180_000L, failedHere = true)
            assertEquals(first, retry)
        }
        // A recent failure on a link that is not measured as filtered keeps a floor that a scan can finish in.
        for (carrier in listOf("h2", "h3", "wireguard")) {
            assertEquals(ConnectionEngineRunner.RETRY_FLOOR_MS,
                ConnectionEngineRunner.readinessBudgetMs("aether", true, false, carrier, 180_000L, failedHere = true))
        }
        assertEquals(180_000L, ConnectionEngineRunner.readinessBudgetMs("aether", false, false, "h2", 180_000L, failedHere = true))
    }

    @Test fun linkHintsComeOnlyFromMeasuredFailures() {
        val p = com.example.vpn.smart.NetworkCapabilityProfile(transport = "cellular", ipv4Available = true, ipv6Available = false,
            udpAvailable = true, cloudflareReachable = false, measuredAt = 1)
        assertEquals(ConnectionEngineRunner.LinkHints(ipv4Only = true, cloudflareFiltered = true), ConnectionEngineRunner.linkHints(p))
        assertTrue(ConnectionEngineRunner.linkHints(p.copy(cloudflareReachable = null, cloudflareIpv4Tls = false)).cloudflareFiltered)
        // UDP not answering: WireGuard is no better than MASQUE, nothing is reordered.
        assertFalse(ConnectionEngineRunner.linkHints(p.copy(udpAvailable = false)).cloudflareFiltered)
        assertFalse(ConnectionEngineRunner.linkHints(p.copy(cloudflareReachable = null)).cloudflareFiltered)
        assertFalse(ConnectionEngineRunner.linkHints(p.copy(ipv6Available = null)).ipv4Only)
        assertEquals(ConnectionEngineRunner.LinkHints(), ConnectionEngineRunner.linkHints(null))
    }

    @Test fun obfuscatedWireGuardRetryOnlyFollowsDefaultObfuscation() {
        fun follow(carrier: String = "wireguard", noize: String = "auto", queued: Boolean = false,
                   reverse: Boolean = false, automatic: Boolean = true, engine: String = "aether") =
            ConnectionEngineRunner.obfuscatedWireGuardFollowUp(engine, automatic, carrier, noize, queued, reverse)
        assertTrue(follow())
        assertFalse(follow(carrier = "h2"))
        assertFalse(follow(carrier = "wiw"))
        assertFalse(follow(noize = "gfw"))
        assertFalse(follow(noize = "light"))
        assertFalse(follow(queued = true))
        assertFalse(follow(reverse = true))
        assertFalse(follow(automatic = false))
        assertFalse(follow(engine = "serverless"))
        val retry = ConnectionEngineRunner.obfuscatedWireGuard(AetherSidecar.profile("auto", "ipv4"))
        val extras = ProfileExtras.read(retry)
        assertEquals("wireguard", extras.optString("carrier"))
        assertEquals("gfw", extras.optString("noize"))
        assertTrue(extras.optBoolean("rescan"))
        assertEquals("ipv4", extras.optString("family"))
        val args = AetherSettings.read(extras).arguments("wireguard", true)
        assertEquals("gfw", args[args.indexOf("--noize") + 1])
        assertNull(AetherSidecar.problem(retry))
    }

    @Test fun missingIdentityFailsFastOnlyOnTheAutomaticRouteWithBlockedApi() {
        assertTrue(com.example.vpn.sidecar.AetherApiBridge.failFastWithoutIdentity("auto", apiReachable = false, hasIdentity = false))
        assertFalse(com.example.vpn.sidecar.AetherApiBridge.failFastWithoutIdentity("auto", apiReachable = false, hasIdentity = true))
        assertFalse(com.example.vpn.sidecar.AetherApiBridge.failFastWithoutIdentity("auto", apiReachable = true, hasIdentity = false))
        assertFalse(com.example.vpn.sidecar.AetherApiBridge.failFastWithoutIdentity("native", apiReachable = false, hasIdentity = false))
        // The fixed reason is recognised as a blocked bootstrap, so carriers without a saved identity are skipped.
        assertTrue(com.example.vpn.sidecar.AetherApiBridge.bootstrapBlocked(com.example.vpn.sidecar.AetherApiBridge.UNREACHABLE_WITHOUT_IDENTITY))
    }

    @Test fun warpInWarpFollowsOnlyABlockedWireGuardHandshake() {
        fun follow(carrier: String = "wireguard", handshake: Boolean = true, queued: Boolean = false,
                   reverse: Boolean = false, automatic: Boolean = true, engine: String = "aether") =
            ConnectionEngineRunner.warpInWarpFollowUp(engine, automatic, carrier, handshake, queued, reverse)
        assertTrue(follow())
        assertFalse(follow(carrier = "h3"))
        assertFalse(follow(handshake = false))
        assertFalse(follow(queued = true))
        assertFalse(follow(reverse = true))
        assertFalse(follow(automatic = false))
        assertFalse(follow(engine = "serverless"))
    }

    @Test fun filteredLinkTriesUdpCarriersFirstAndRecentFailuresLast() {
        val all = listOf("h2", "h3", "wireguard")
        assertEquals(listOf("wireguard", "h3", "h2"), ConnectionEngineRunner.order(all, null, true, emptySet()))
        assertEquals(listOf("h2", "h3", "wireguard"), ConnectionEngineRunner.order(all, null, false, emptySet()))
        assertEquals(listOf("h2", "h3", "wireguard"), ConnectionEngineRunner.order(all, null, false, setOf("wireguard")))
        assertEquals(listOf("h3", "h2", "wireguard"), ConnectionEngineRunner.order(all, null, true, setOf("wireguard")))
        // Every carrier failed here: WireGuard (fastest answer) first, H2 last.
        assertEquals(listOf("wireguard", "h3", "h2"), ConnectionEngineRunner.order(all, null, false, all.toSet()))
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
