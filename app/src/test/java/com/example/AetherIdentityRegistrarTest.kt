package com.example

import com.example.vpn.sidecar.AetherIdentityRegistrar
import com.example.vpn.sidecar.AetherIdentityStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AetherIdentityRegistrarTest {
    @get:Rule val tmp = TemporaryFolder()
    private val hour = 60 * 60 * 1000L

    @Test fun registersOnlyThroughPsiphonOrTorWhenNothingIsSavedAndCooldownPassed() {
        val now = 100 * hour
        assertTrue(AetherIdentityRegistrar.shouldRegister("psiphon", hasIdentity = false, lastAttemptMs = 0, nowMs = now))
        assertTrue(AetherIdentityRegistrar.shouldRegister("tor", hasIdentity = false, lastAttemptMs = now - 7 * hour, nowMs = now))
        assertFalse(AetherIdentityRegistrar.shouldRegister("tor", hasIdentity = true, lastAttemptMs = 0, nowMs = now))
        assertFalse(AetherIdentityRegistrar.shouldRegister("psiphon", hasIdentity = false, lastAttemptMs = now - hour, nowMs = now))
        for (other in listOf("aether", "serverless", "mihomo", "cottendns")) {
            assertFalse(other, AetherIdentityRegistrar.shouldRegister(other, hasIdentity = false, lastAttemptMs = 0, nowMs = now))
        }
        // A clock that went backwards never blocks registration forever.
        assertTrue(AetherIdentityRegistrar.shouldRegister("tor", hasIdentity = false, lastAttemptMs = now + hour, nowMs = now))
    }

    @Test fun upstreamCarriesTheLoginOnlyWhenTheCarrierChecksOne() {
        assertEquals("socks5://127.0.0.1:1080", AetherIdentityRegistrar.upstream(1080, null, null))
        assertEquals("socks5://u1:p_2@127.0.0.1:1080", AetherIdentityRegistrar.upstream(1080, "u1", "p_2"))
        assertThrows(IllegalArgumentException::class.java) { AetherIdentityRegistrar.upstream(0, null, null) }
        assertThrows(IllegalArgumentException::class.java) { AetherIdentityRegistrar.upstream(1080, "a@b", "c") }
    }

    @Test fun theRegisteredKindCoversWireGuardAndWarpInWarp() {
        val files = AetherIdentityStore.required(AetherIdentityRegistrar.TUNNEL_KIND)
        assertTrue(files.containsAll(AetherIdentityStore.required(AetherIdentityStore.kindForTransport("wireguard"))))
        assertTrue(files.containsAll(AetherIdentityStore.required(AetherIdentityStore.kindForTransport("wiw"))))
    }

    @Test fun existingIdentitiesAreCarriedIntoTheStagedGeneration() {
        val root = tmp.newFolder("aether")
        File(root, "identity.toml").writeText("m".repeat(64))
        val staging = File(root, "pending-x").apply { mkdirs() }
        AetherIdentityRegistrar.retainExisting(root, staging)
        assertEquals("m".repeat(64), File(staging, "identity.toml").readText())
        assertFalse(File(staging, "wireguard.toml").exists())
        assertEquals(0L, AetherIdentityRegistrar.lastAttempt(root))
    }
}
