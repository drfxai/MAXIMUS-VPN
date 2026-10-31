package com.example

import com.example.data.model.AppSettings
import com.example.data.model.OperationalMode
import com.example.vpn.sidecar.PrivatePsiphonStore
import com.example.vpn.sidecar.PsiphonSidecar
import com.example.vpn.sidecar.SidecarContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class PrivatePsiphonClientTest {
    private val id = UUID.randomUUID().toString()
    // Hex-encoded fixture, NOT a real server entry or usable cryptographic key.
    private val fixture = "31".repeat(300)
    private val settings = JSONObject()
        .put("SponsorId", "0123456789ABCDEF")
        .put("PropagationChannelId", "FEDCBA9876543210")
        .put("TargetServerEntry", fixture)
        .toString()

    @Test fun rejectsMalformedEntryAndIdentifiers() {
        assertFalse(PrivatePsiphonStore.validEntry(""))
        assertFalse(PrivatePsiphonStore.validEntry("AG".repeat(100)))
        assertFalse(PrivatePsiphonStore.validEntry("31".repeat(200_000)))
        assertTrue(PrivatePsiphonStore.validEntry(fixture))
        assertFalse(PrivatePsiphonStore.validIdentifier("ANOTHER-APPLICATION-ID"))
        assertTrue(PrivatePsiphonStore.validIdentifier("0123456789ABCDEF"))
    }

    @Test fun privateBootstrapNeverDependsOnPublicServerListSettings() {
        val engine = PsiphonSidecar(configProvider = { null }, privateConfigProvider = { key ->
            if (key == id) settings else null
        })
        val profile = PsiphonSidecar.privateProfile(id, "Test VPS", "example.invalid")
        assertEquals(id, PsiphonSidecar.privateId(profile))
        assertNull(engine.problem(profile))
        assertTrue(engine.handles(profile))
        assertFalse(profile.extraSettings.contains(fixture))
        val dir = Files.createTempDirectory("private-psiphon").toFile()
        try {
            engine.prepare(profile, AppSettings(), SidecarContext(
                workDir = dir, executable = File(dir, "libpsiphon.so"), socksPort = 40123,
                socksUser = "u", socksPass = "p", mode = OperationalMode.GOD_MODE
            ))
            val generated = JSONObject(File(dir, PsiphonSidecar.CONFIG_FILE).readText())
            assertEquals(fixture, generated.getString("TargetServerEntry"))
            assertFalse(generated.has("RemoteServerListURLs"))
            assertFalse(generated.has("ObfuscatedServerListRootURLs"))
            assertTrue(generated.getBoolean("DisableLocalHTTPProxy"))
            assertFalse(generated.optBoolean("EnableUpgradeDownload"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun missingPrivateBootstrapDoesNotFallbackToPublicSettings() {
        val privateProfile = PsiphonSidecar.privateProfile(id, "VPS", "example.invalid")
        val engine = PsiphonSidecar(configProvider = { settings }, privateConfigProvider = { null })
        assertTrue(engine.problem(privateProfile)?.contains("encrypted storage") == true)
        assertTrue(PsiphonSidecar.validatePrivateSettings(JSONObject(settings)
            .put("RemoteServerListURLs", org.json.JSONArray().put("https://example.invalid/list"))) != null)
    }
}
