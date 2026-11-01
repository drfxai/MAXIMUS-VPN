package com.example.panels.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline negative tests: never fetch unreviewed server binaries in Android unit tests. */
class PrivatePsiphonArtifactTest {
    @Test fun artifactIsAnImmutableOfficialCommitWithReviewedDigestAndSize() {
        assertTrue(PrivatePsiphonArtifact.COMMIT.matches(Regex("[0-9a-f]{40}")))
        assertTrue(PrivatePsiphonArtifact.SHA256.matches(Regex("[0-9a-f]{64}")))
        assertTrue(PrivatePsiphonArtifact.SIZE in 1_000_000..RemoteShell.MAX_TRANSFER)
        assertEquals(
            "https://raw.githubusercontent.com/Psiphon-Labs/psiphon-tunnel-core-binaries/" +
                PrivatePsiphonArtifact.COMMIT + "/psiphond/psiphond",
            PrivatePsiphonArtifact.URL)
        assertFalse(PrivatePsiphonArtifact.URL.contains("/master/"))
    }

    @Test fun randomPayloadAndEmptyPayloadAreNeverTrusted() {
        assertFalse(PrivatePsiphonArtifact.verify(ByteArray(0)))
        assertFalse(PrivatePsiphonArtifact.verify(ByteArray(PrivatePsiphonArtifact.SIZE)))
        assertFalse(PrivatePsiphonArtifact.verify(ByteArray(PrivatePsiphonArtifact.SIZE) { 123 }))
    }

    @Test fun architectureAndPrivilegesAreStrictlyScoped() {
        val verified = ServerFacts(root = true, systemd = true,
            socketInspectionAvailable = true, arch = "x86_64")
        assertTrue(PrivatePsiphonArtifact.supported(verified))
        assertFalse(PrivatePsiphonArtifact.supported(verified.copy(arch = "aarch64")))
        assertFalse(PrivatePsiphonArtifact.supported(verified.copy(root = false)))
        assertFalse(PrivatePsiphonArtifact.supported(verified.copy(systemd = false)))
        assertFalse(PrivatePsiphonArtifact.supported(verified.copy(socketInspectionAvailable = false)))
    }
}
