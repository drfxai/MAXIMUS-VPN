package com.example

import com.example.vpn.sidecar.AetherIdentityStore
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class AetherIdentityStoreTest {
    @Test fun layeredTransportsRequireBothIdentitiesAndUseTheMatchingRenewalKind() {
        val root = Files.createTempDirectory("aether-layered").toFile()
        try {
            val modes = mapOf("wireguard" to "wg", "wiw" to "gool-classic", "mim" to "mim", "gool" to "gool")
            modes.forEach { (transport, kind) -> assertEquals(kind, AetherIdentityStore.kindForTransport(transport)) }
            assertEquals("masque", AetherIdentityStore.kindForTransport("h3"))
            File(root, "wireguard.toml").writeText("x".repeat(64))
            File(root, "identity.toml").writeText("x".repeat(64))
            assertTrue(AetherIdentityStore.hasStored(root, "wg"))
            assertFalse(AetherIdentityStore.hasStored(root, "gool-classic"))
            assertFalse(AetherIdentityStore.hasStored(root, "mim"))
            assertFalse(AetherIdentityStore.hasStored(root, "gool"))
            File(root, "wireguard-secondary.toml").writeText("x")
            assertFalse(AetherIdentityStore.hasStored(root, "gool-classic"))
            File(root, "wireguard-secondary.toml").writeText("x".repeat(64))
            assertTrue(AetherIdentityStore.hasStored(root, "gool-classic"))
            File(root, "identity-secondary.toml").writeText("x".repeat(131073))
            assertFalse(AetherIdentityStore.hasStored(root, "mim"))
        } finally { root.deleteRecursively() }
    }

    @Test fun incompleteMultipleKeyRegistrationKeepsThePreviousGeneration() {
        val root = Files.createTempDirectory("aether-identities").toFile()
        try {
            File(root, "wireguard.toml").writeText("old-key".repeat(8))
            val staging = File(root, "pending").apply { mkdirs() }
            File(staging, "wireguard.toml").writeText("new-key".repeat(8))
            assertThrows(IllegalStateException::class.java) { AetherIdentityStore.publish(root, staging, "all", "incomplete") }
            assertEquals(root, AetherIdentityStore.current(root))
            assertEquals("old-key".repeat(8), File(root, "wireguard.toml").readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun onePointerPublishesEveryRequestedKeyAndRetainsOtherProtocols() {
        val root = Files.createTempDirectory("aether-identities").toFile()
        try {
            File(root, "wireguard.toml").writeText("old-wg".repeat(8))
            val staging = File(root, "pending").apply { mkdirs() }
            AetherIdentityStore.retainUnchanged(root, staging, "mim")
            for (name in AetherIdentityStore.required("mim")) File(staging, name).writeText("new-$name".repeat(8))
            File(staging, "temporary-upstream.json").writeText("temporary")
            AetherIdentityStore.publish(root, staging, "mim", "complete")
            val active = AetherIdentityStore.current(root)
            assertNotEquals(root, active)
            assertEquals("old-wg".repeat(8), File(active, "wireguard.toml").readText())
            assertTrue(AetherIdentityStore.required("mim").all { File(active, it).isFile })
            assertFalse(File(active, "temporary-upstream.json").exists())
        } finally { root.deleteRecursively() }
    }
}
