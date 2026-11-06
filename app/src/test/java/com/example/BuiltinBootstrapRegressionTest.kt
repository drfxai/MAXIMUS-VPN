package com.example

import com.example.vpn.connections.BuiltinMethodMemory
import com.example.vpn.sidecar.*
import com.example.vpn.verify.BoundedDnsLookup
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BuiltinBootstrapRegressionTest {
    @Test fun successfulMethodStaysWithinNetworkOptionsAndExpires() {
        val previousRead = BuiltinMethodMemory.read
        val previousWrite = BuiltinMethodMemory.write
        val values = mutableMapOf<String, String>()
        try {
            BuiltinMethodMemory.read = { values[it] }
            BuiltinMethodMemory.write = { key, value -> if (value == null) values.remove(key) else values[key] = value }
            BuiltinMethodMemory.record("cell-options-a", "oob", 1000)
            assertEquals("oob", BuiltinMethodMemory.preferred("cell-options-a", listOf("fragA", "fragB", "oob"), 2000))
            assertNull(BuiltinMethodMemory.preferred("wifi-options-a", listOf("oob"), 2000))
            assertNull(BuiltinMethodMemory.preferred("cell-options-b", listOf("oob"), 2000))
            assertNull(BuiltinMethodMemory.preferred("cell-options-a", listOf("fragA"), 2000))
            assertNull(BuiltinMethodMemory.preferred("cell-options-a", listOf("oob"), 999))
            assertNull(BuiltinMethodMemory.preferred("cell-options-a", listOf("oob"), 1000 + BuiltinMethodMemory.TTL_MS))
            BuiltinMethodMemory.forget("cell-options-a")
            assertNull(BuiltinMethodMemory.preferred("cell-options-a", listOf("oob"), 2000))
        } finally { BuiltinMethodMemory.read = previousRead; BuiltinMethodMemory.write = previousWrite }
    }

    @Test fun apiBridgeCannotOverrideExplicitExitOrRelay() {
        assertTrue(AetherApiBridge.allowed(AetherSettings()))
        assertFalse(AetherApiBridge.allowed(AetherSettings(apiRoute = "native")))
        assertFalse(AetherApiBridge.allowed(AetherSettings(exitNode = "saved-profile")))
        assertFalse(AetherApiBridge.allowed(AetherSettings(exitFinalMask = "{\"tcp\":[]}")))
        for (mode in listOf("chain", "reverse", "only")) {
            assertFalse(AetherApiBridge.allowed(AetherSettings(tor = mode)))
            assertFalse(AetherApiBridge.allowed(AetherSettings(psiphon = mode)))
        }
        val options = AetherSettings(apiRoute = "oob")
        assertEquals(options, AetherSettings.read(options.write(JSONObject())))
        assertNotNull(AetherSettings(apiRoute = "arbitrary").problem())
    }

    @Test fun registrationSummaryDoesNotEraseCertificateOrTimeoutCause() {
        val certificate = SidecarProcess.preferFailure(null, "TLS CERTIFICATE_VERIFY_FAILED")
        assertEquals("certificate trust or hostname verification failed", certificate)
        assertEquals(certificate, SidecarProcess.preferFailure(certificate, "registration failed"))
        val timeout = SidecarProcess.failureCategory("TLS request timed out")
        assertEquals("network operation timed out", timeout)
        assertEquals(timeout, SidecarProcess.preferFailure(timeout, "registration failed"))
    }

    @Test fun stalledVpnDnsIsBoundedAndDoesNotPreventAnotherLookup() {
        val release = CountDownLatch(1)
        try {
            try {
                BoundedDnsLookup.resolve(100) { release.await(5, TimeUnit.SECONDS); emptyArray() }
                fail("DNS lookup must time out")
            } catch (_: SocketTimeoutException) { }
            assertEquals("127.0.0.1", BoundedDnsLookup.resolve(1000) {
                arrayOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
            }.single().hostAddress)
        } finally { release.countDown() }
    }
}
