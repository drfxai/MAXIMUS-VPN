package com.example

import com.example.data.model.AppSettings
import com.example.data.model.OperationalMode
import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.vpn.sidecar.PsiphonSidecar
import com.example.vpn.sidecar.SidecarContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files

class PsiphonSidecarTest {
    // Placeholders only: real network settings come from Psiphon Inc. through a CI secret.
    private val settings = JSONObject()
        .put("PropagationChannelId", "TEST-CHANNEL")
        .put("SponsorId", "TEST-SPONSOR")
        .put("RemoteServerListURLs", org.json.JSONArray().put(JSONObject().put("URL", "aHR0cHM6Ly9leGFtcGxlLmludmFsaWQvbGlzdA==")))
        .put("RemoteServerListSignaturePublicKey", "TEST-KEY")
        .put("LocalHttpProxyPort", 8080)
        .put("LocalSocksProxyPort", 1080)
        .put("UpstreamProxyURL", "http://10.0.0.1:3128")
        .toString()

    private fun context(dir: File) = SidecarContext(
        workDir = dir, executable = File(dir, "libpsiphon.so"), socksPort = 40123,
        socksUser = "u", socksPass = "p", mode = OperationalMode.GOD_MODE
    )

    @Test
    fun profileIsMarkedForPsiphonAndHandledOnlyByIt() {
        val engine = PsiphonSidecar { settings }
        val profile = PsiphonSidecar.profile("de")
        assertEquals(ProtocolType.MIXED, profile.protocolType)
        assertEquals("DE", PsiphonSidecar.region(profile))
        assertTrue(engine.handles(profile))
        assertTrue(engine.handles(PsiphonSidecar.profile()))
        assertFalse(engine.handles(VlessProfile(name = "v", address = "example.com", port = 443, uuid = "x")))
        assertEquals("psiphon", engine.id)
        assertEquals("psiphon", engine.binary)
    }

    @Test
    fun missingNetworkSettingsGiveAClearMessage() {
        val profile = PsiphonSidecar.profile()
        for (provider in listOf<() -> String?>({ null }, { "  " }, { throw FileNotFoundException("psiphon/config.json") })) {
            val problem = PsiphonSidecar(provider).problem(profile)
            assertEquals(PsiphonSidecar.MISSING_SETTINGS, problem)
        }
        assertTrue(PsiphonSidecar.MISSING_SETTINGS.contains("Psiphon network settings"))
    }

    @Test
    fun incompleteOrBrokenSettingsAreReported() {
        val profile = PsiphonSidecar.profile()
        val noSponsor = JSONObject(settings).apply { remove("SponsorId") }.toString()
        assertTrue(PsiphonSidecar { noSponsor }.problem(profile)!!.contains("SponsorId"))
        val noList = JSONObject(settings).apply { remove("RemoteServerListURLs") }.toString()
        assertTrue(PsiphonSidecar { noList }.problem(profile)!!.contains("server list"))
        assertTrue(PsiphonSidecar { "{not json" }.problem(profile)!!.contains("not valid JSON"))
        assertTrue(PsiphonSidecar { settings }.problem(PsiphonSidecar.profile("Germany"))!!.contains("two-letter"))
        assertNull(PsiphonSidecar { settings }.problem(profile))
    }

    @Test
    fun prepareWritesTheConfigAndCommandLine() {
        val dir = Files.createTempDirectory("psiphon").toFile()
        try {
            val launch = PsiphonSidecar { settings }.prepare(PsiphonSidecar.profile("ca"), AppSettings(), context(dir))
            val configFile = File(dir, PsiphonSidecar.CONFIG_FILE)
            val dataDir = File(dir, PsiphonSidecar.DATA_DIR)
            assertEquals(
                listOf(
                    File(dir, "libpsiphon.so").absolutePath,
                    "-config", configFile.absolutePath,
                    "-dataRootDirectory", dataDir.absolutePath,
                    "-formatNotices"
                ),
                launch.command
            )
            assertFalse(launch.socksAuth)
            assertTrue(launch.readyTimeoutMs >= 30_000)
            assertTrue("Psiphon needs its data directory to exist", dataDir.isDirectory)

            val config = JSONObject(configFile.readText())
            assertEquals(40123, config.getInt("LocalSocksProxyPort"))
            assertTrue(config.getBoolean("DisableLocalHTTPProxy"))
            assertFalse(config.has("LocalHttpProxyPort"))
            assertFalse("the build's settings must not route Psiphon through another proxy", config.has("UpstreamProxyURL"))
            assertEquals(dataDir.absolutePath, config.getString("DataRootDirectory"))
            assertEquals("CA", config.getString("EgressRegion"))
            assertFalse(config.getBoolean("EnableUpgradeDownload"))
            assertEquals("TEST-CHANNEL", config.getString("PropagationChannelId"))
            assertEquals("TEST-SPONSOR", config.getString("SponsorId"))
            assertEquals(3, config.getJSONArray("DNSResolverAlternateServers").length())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun anyRegionLeavesEgressRegionOutAndBuildDnsSettingsWin() {
        val withDns = JSONObject(settings).put("DNSResolverAlternateServers", org.json.JSONArray().put("9.9.9.9")).toString()
        val config = PsiphonSidecar.buildConfig(JSONObject(withDns), "", 5000, File("/data/x"))
        assertFalse(config.has("EgressRegion"))
        assertEquals(1, config.getJSONArray("DNSResolverAlternateServers").length())
    }

    @Test
    fun assetProviderReadsTheAssetOrReportsItMissing() {
        assertEquals("{}", PsiphonSidecar.assetProvider { path ->
            assertEquals("psiphon/config.json", path)
            ByteArrayInputStream("{}".toByteArray())
        }())
        assertNull(PsiphonSidecar.assetProvider { throw FileNotFoundException(it) }())
    }
}
