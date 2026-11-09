package com.example

import com.example.data.model.AppSettings
import com.example.data.model.OperationalMode
import com.example.vpn.sidecar.*
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class AetherApiRefreshRouteTest {
    @Test fun storedKeysDoNotSilentlyBypassAForcedAccountRoute() {
        val dir = Files.createTempDirectory("aether-refresh").toFile()
        val oldDirectory = AetherSidecar.identityDirectory
        val oldLibraries = Sidecars.nativeLibraryDir
        try {
            AetherSidecar.identityDirectory = { dir }
            Sidecars.nativeLibraryDir = null
            File(dir, "identity.toml").writeText("test-only".repeat(8))
            val profile = AetherSidecar.profile("h3", advanced = AetherSettings(apiRoute = "oob"))
            assertTrue(AetherApiBridge.hasStoredKeys(profile))
            val context = SidecarContext(dir, File(dir, "unused"), 12345, "user", "pass", OperationalMode.DAILY)
            // No runtime exists in this test. Even with keys present a forced OOB
            // route must fail explicitly rather than silently refreshing natively.
            val error = assertThrows(IllegalStateException::class.java) {
                AetherApiBridge.start(profile, AppSettings(), context)
            }
            assertEquals("Direct API runtime is missing", error.message)
        } finally {
            AetherSidecar.identityDirectory = oldDirectory
            Sidecars.nativeLibraryDir = oldLibraries
            dir.deleteRecursively()
        }
    }
}
