package com.example

import com.example.vpn.hub.HubManifest
import com.example.vpn.hub.HubSnapshots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** V1.0.1 step 14: only the aggregator's signed list is accepted, and three snapshots are kept. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HubManifestTest {
    private val manifestJson = """{"count":2,"created":"2026-10-05T12:00:00Z","files":{"free.txt":{"bytes":4,"sha256":"${sha("list")}"}},"version":1}"""
        .toByteArray()

    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val publicKey = java.util.Base64.getEncoder().encodeToString(keys.public.encoded)
    private val signature = java.util.Base64.getEncoder().encodeToString(
        Signature.getInstance("SHA256withECDSA").run { initSign(keys.private); update(manifestJson); sign() })

    private fun sha(text: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test fun aSignedListIsAcceptedAndItsFilesAreCheckedAgainstTheManifest() {
        val manifest = HubManifest.verify(manifestJson, signature, publicKey)
        assertEquals(2, manifest.count)
        assertTrue(HubManifest.matches(manifest, "free.txt", "list".toByteArray()))
        assertFalse(HubManifest.matches(manifest, "free.txt", "list changed".toByteArray()))
        assertFalse(HubManifest.matches(manifest, "free-base64.txt", "list".toByteArray()))
    }

    @Test fun anUnsignedChangedOrForeignListIsRefused() {
        assertThrows(HubManifest.Refused::class.java) { HubManifest.verify(manifestJson, "", publicKey) }
        assertThrows(HubManifest.Refused::class.java) { HubManifest.verify(manifestJson, signature, "") }
        assertThrows(HubManifest.Refused::class.java) {
            HubManifest.verify((String(manifestJson) + " ").toByteArray(), signature, publicKey)
        }
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        assertThrows(HubManifest.Refused::class.java) {
            HubManifest.verify(manifestJson, signature, java.util.Base64.getEncoder().encodeToString(other.public.encoded))
        }
        // The built-in key is whatever the build was given, and nothing else.
        assertEquals(com.example.BuildConfig.HUB_PUBLIC_KEY, HubManifest.PUBLIC_KEY_DER_BASE64)
    }

    @Test fun aListThatWorkedIsKeptWhenTheNextOneBringsNothing() {
        val first = HubSnapshots.Snapshot("1", 2, "a")
        val second = HubSnapshots.Snapshot("2", 3, "b")
        var snapshots = HubSnapshots().accept(first).recordApproved(2)
        snapshots = snapshots.accept(second).recordApproved(0)
        assertEquals(second, snapshots.current)
        assertEquals(first.copy(approved = 2), snapshots.lastKnownGood)
        assertEquals(first.copy(approved = 2), snapshots.previous)
        assertEquals(second, snapshots.best())
        assertEquals(first.copy(approved = 2), HubSnapshots(previous = first.copy(approved = 2), lastKnownGood = first.copy(approved = 2)).best())
        assertNull(HubSnapshots().best())
    }
}
