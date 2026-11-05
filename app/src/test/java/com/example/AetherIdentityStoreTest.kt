package com.example

import com.example.vpn.sidecar.AetherIdentityStore
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class AetherIdentityStoreTest {
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
            AetherIdentityStore.publish(root, staging, "mim", "complete")
            val active = AetherIdentityStore.current(root)
            assertNotEquals(root, active)
            assertEquals("old-wg".repeat(8), File(active, "wireguard.toml").readText())
            assertTrue(AetherIdentityStore.required("mim").all { File(active, it).isFile })
        } finally { root.deleteRecursively() }
    }
}
