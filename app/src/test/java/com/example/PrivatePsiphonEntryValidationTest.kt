package com.example

import com.example.vpn.sidecar.PrivatePsiphonEntry
import com.example.vpn.sidecar.PrivatePsiphonStore
import com.example.vpn.sidecar.PsiphonSidecar
import com.example.vpn.smart.SmartConnect
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class PrivatePsiphonEntryValidationTest {
    private fun encode(raw: String): String = raw.toByteArray(Charsets.UTF_8).joinToString("") {
        "%02x".format(it.toInt() and 255)
    }

    @Test fun validObfuscatedSshEntryContainsExpectedIdentity() {
        val text = PrivatePsiphonFixtures.entry("203.0.113.7", 8899)
        val decoded = PrivatePsiphonEntry.inspect(text)
        assertEquals("203.0.113.7", decoded?.ipAddress)
        assertEquals(8899, decoded?.sshObfuscatedPort)
        assertTrue(PrivatePsiphonStore.validEntry(text))
    }

    @Test fun wrongJsonIpAndInvalidPortsAreRejected() {
        val original = PrivatePsiphonFixtures.entry()
        val decoded = buildString {
            for (i in original.indices step 2)
                append(((Character.digit(original[i], 16) shl 4) or
                    Character.digit(original[i + 1], 16)).toChar())
        }
        assertNull(PrivatePsiphonEntry.inspect(encode(
            decoded.replace("203.0.113.7", "203.0.113.8", ignoreCase = false)
                .replaceFirst("203.0.113.8", "203.0.113.7")
        )))
        assertNull(PrivatePsiphonEntry.inspect(encode(decoded.replace("8888", "65536"))))
        assertNull(PrivatePsiphonEntry.inspect(encode(decoded.replace("FAKE_TEST_SSH_KEY_NOT_USABLE", ""))))
        assertNull(PrivatePsiphonEntry.inspect("31".repeat(100)))
        assertFalse(PrivatePsiphonEntry.validIp("127.0.0.1;reboot"))
        assertFalse(PrivatePsiphonEntry.validIp("999.1.2.3"))
    }

    @Test fun privateBootstrapCannotBeAutoRecommendedWithoutRealEvidence() {
        val profile = PsiphonSidecar.privateProfile(UUID.randomUUID().toString(),
            "private", "203.0.113.7")
        assertTrue(SmartConnect.rankTopNodes(listOf(profile)).isEmpty())
        assertNull(SmartConnect.selectBestNode(listOf(profile)))
    }
}
