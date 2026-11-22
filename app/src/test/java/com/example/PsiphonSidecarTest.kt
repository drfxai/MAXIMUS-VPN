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
    @Test fun reportsLastObservedStartupStageWithoutUsingRawNoticeData() {
        val progress = PsiphonSidecar.StartupProgress()
        assertTrue(progress.failureAfter(60_000).contains("discovery"))
        progress.observe("""2026-11-20T08:00:00.000Z AvailableEgressRegions {"regions":["US"]}""")
        assertTrue(progress.failureAfter(60_000).contains("candidates"))
        progress.observe("""{"noticeType":"ConnectingServer","data":{"ipAddress":"example-secret"}}""")
        assertTrue(progress.failureAfter(60_000).contains("dialing"))
        assertFalse(progress.failureAfter(60_000).contains("example-secret"))
    }

    @Test fun upstreamProxyAllowsAllServerEntrySourcesOnlyInAChain() {
        val dir = Files.createTempDirectory("psiphon-up").toFile()
        try {
            // The build's settings cannot switch the flag on for a direct Psiphon start.
            val source = JSONObject(settings).put("UpstreamProxyAllowAllServerEntrySources", true)
            val direct = PsiphonSidecar.buildConfig(source, "", 40123, dir)
            assertFalse(direct.has("UpstreamProxyURL"))
            assertFalse(direct.has("UpstreamProxyAllowAllServerEntrySources"))

            val chained = PsiphonSidecar.buildConfig(JSONObject(settings), "", 40123, dir,
                upstreamSocks = 40124, tcpOnly = true)
            assertEquals("socks5://127.0.0.1:40124", chained.getString("UpstreamProxyURL"))
            assertTrue(chained.getBoolean("UpstreamProxyAllowAllServerEntrySources"))

            // prepare() with an upstream hop (the chain path) writes both, and only upstream-capable
            // TCP protocols (psiphon-tunnel-core rejects QUIC, in-proxy and refraction through a proxy).
            val ctx = context(dir).copy(upstreamSocks = 40125)
            PsiphonSidecar { settings }.prepare(PsiphonSidecar.profile(), AppSettings(), ctx)
            val written = JSONObject(File(dir, PsiphonSidecar.CONFIG_FILE).readText())
            assertTrue(written.getBoolean("UpstreamProxyAllowAllServerEntrySources"))
            val protocols = written.getJSONArray("LimitTunnelProtocols").let { a -> (0 until a.length()).map { a.getString(it) } }
            assertTrue(protocols.isNotEmpty())
            assertTrue(protocols.none { it.contains("QUIC") || it.startsWith("INPROXY") || it.startsWith("TAPDANCE") || it.startsWith("CONJURE") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun timeoutCarriesTheLastSkipReasonButNoAddress() {
        val progress = PsiphonSidecar.StartupProgress()
        progress.observe("""Sat 2026-11-21 10:00 AM AvailableEgressRegions {"regions":[]}""")
        progress.observe("""{"noticeType":"SkipServerEntry","data":{"reason":"restricted provider ID: 1a2b3c"}}""")
        progress.observe("""{"noticeType":"SkipServerEntry","data":{"reason":"server entry source disallowed with upstream proxy: OBFUSCATED"}}""")
        var failure = progress.failureAfter(60_000)
        assertTrue(failure, failure.contains("server entry source disallowed with upstream proxy: OBFUSCATED"))
        assertTrue(failure, failure.contains("2 skips"))
        assertTrue(failure, failure.contains("last notice: SkipServerEntry"))
        progress.observe("""{"noticeType":"SkipServerEntry","data":{"reason":"dial failed: 203.0.113.9:443 refused"}}""")
        failure = progress.failureAfter(60_000)
        assertFalse(failure, failure.contains("203.0.113.9"))
    }

    @Test fun psiphonKeepsTacticsSeparateForWifiMobileAndRelays() {
        assertEquals("MOBILE-43235-direct", PsiphonSidecar.networkId("cell:43235", relayed = false))
        assertEquals("WIFI-abcdef123456-relay", PsiphonSidecar.networkId("wifi:abcdef123456", relayed = true))
        assertNull(PsiphonSidecar.networkId("wifi:raw-network-name", relayed = false))
        assertNull(PsiphonSidecar.networkId(null, relayed = false))
    }

    @Test fun publicSettingsValidateDecodedTransferUrlsAtLaunch() {
        val valid = JSONObject(settings)
        assertNull(PsiphonSidecar.validateNetworkSettings(valid.toString()))
        val url = valid.getJSONArray("RemoteServerListURLs").getJSONObject(0)
        url.put("URL", java.util.Base64.getEncoder().encodeToString("http://example.invalid/list".toByteArray()))
        assertTrue(PsiphonSidecar.validateNetworkSettings(valid.toString())!!.contains("HTTPS"))
        assertTrue(PsiphonSidecar { valid.toString() }.problem(PsiphonSidecar.profile())!!.contains("HTTPS"))
    }
    // Placeholders only: real network settings come from Psiphon Inc. through a CI secret.
    @Test fun startBudgetIsLongerOnCellularMeteredAndChains() {
        assertEquals(60_000L, PsiphonSidecar.readyTimeoutMs("wifi:abcdef123456", metered = false, chained = false))
        assertEquals(60_000L, PsiphonSidecar.readyTimeoutMs(null, metered = false, chained = false))
        assertEquals(120_000L, PsiphonSidecar.readyTimeoutMs("cell:43235", metered = false, chained = false))
        assertEquals(120_000L, PsiphonSidecar.readyTimeoutMs("wifi:abcdef123456", metered = true, chained = false))
        assertEquals(120_000L, PsiphonSidecar.readyTimeoutMs("wifi:abcdef123456", metered = false, chained = true))
    }

    @Test fun reportsAndroidClientPlatformAndFasterEstablishment() {
        assertEquals("Android_14_com.drfxai.maximusvpn", PsiphonSidecar.clientPlatform("14", "com.drfxai.maximusvpn"))
        assertEquals("Android", PsiphonSidecar.clientPlatform(null, "com.drfxai.maximusvpn"))
        assertEquals("Android_15QPR1_x", PsiphonSidecar.clientPlatform("15 QPR1", "x"))
        val dir = Files.createTempDirectory("psiphon-fast").toFile()
        try {
            val direct = PsiphonSidecar.buildConfig(JSONObject(settings).put("ClientAPILevel", 1), "", 40123, dir,
                clientPlatform = "Android_14_com.drfxai.maximusvpn", clientApiLevel = 34)
            assertEquals("Android_14_com.drfxai.maximusvpn", direct.getString("ClientPlatform"))
            assertEquals(34, direct.getInt("ClientAPILevel"))
            assertEquals(PsiphonSidecar.FAST_WORKER_POOL, direct.getInt("ConnectionWorkerPoolSize"))
            assertEquals(PsiphonSidecar.FAST_PAUSE_SECONDS, direct.getInt("EstablishTunnelPausePeriodSeconds"))
            assertFalse(direct.has("LimitTunnelProtocols"))
            val chained = PsiphonSidecar.buildConfig(JSONObject(settings), "", 40123, dir, upstreamSocks = 40124)
            assertEquals(PsiphonSidecar.CHAIN_WORKER_POOL, chained.getInt("ConnectionWorkerPoolSize"))
            // The build's own choices win.
            val own = PsiphonSidecar.buildConfig(JSONObject(settings).put("ConnectionWorkerPoolSize", 4)
                .put("EstablishTunnelPausePeriodSeconds", 5), "", 40123, dir)
            assertEquals(4, own.getInt("ConnectionWorkerPoolSize"))
            assertEquals(5, own.getInt("EstablishTunnelPausePeriodSeconds"))
        } finally {
            dir.deleteRecursively()
        }
    }

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
    fun tcpRetryKeepsTheSelectedProfileAndDoesNotLoop() {
        val profile = PsiphonSidecar.profile("ca").copy(id = "selected")
        val retry = checkNotNull(PsiphonSidecar.tcpStartupRetry(profile))
        assertEquals(profile.id, retry.id)
        assertEquals("CA", PsiphonSidecar.region(retry))
        assertTrue(PsiphonSidecar.tcpOnly(retry))
        assertNull(PsiphonSidecar.tcpStartupRetry(retry))
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
            assertFalse(config.has("ListenInterface"))
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
    fun assetProviderFallsBackToThePublicProfileWithoutDistributorSettings() {
        val requested = mutableListOf<String>()
        val provider = PsiphonSidecar.assetProvider { path ->
            requested += path
            if (path == "psiphon/config.json") throw FileNotFoundException(path)
            ByteArrayInputStream("public-profile".toByteArray())
        }
        assertEquals("public-profile", provider())
        assertEquals(listOf("psiphon/config.json", "psiphon/public-config.json"), requested)
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
