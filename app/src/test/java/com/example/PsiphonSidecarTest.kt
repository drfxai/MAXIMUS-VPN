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

    @Test fun keepsLibraryEstablishmentDefaultsAndPlainAndroidPlatform() {
        val dir = Files.createTempDirectory("psiphon-defaults").toFile()
        try {
            val direct = PsiphonSidecar.buildConfig(JSONObject(settings).put("ClientAPILevel", 1), "", 40123, dir)
            assertEquals("Android", direct.getString("ClientPlatform"))
            assertFalse(direct.has("ClientAPILevel"))
            // #258 forced 16/12 workers and a 1 s pause; build 170 on Irancell never got a direct tunnel.
            assertFalse(direct.has("ConnectionWorkerPoolSize"))
            assertFalse(direct.has("EstablishTunnelPausePeriodSeconds"))
            assertTrue(direct.getBoolean("EmitDiagnosticNotices"))
            assertFalse(direct.has("LimitTunnelProtocols"))
            val chained = PsiphonSidecar.buildConfig(JSONObject(settings), "", 40123, dir, upstreamSocks = 40124)
            assertFalse(chained.has("ConnectionWorkerPoolSize"))
            // The build's own choices still win, except EmitDiagnosticNotices: the app reads those notices to see
            // whether Psiphon dials at all (build 178: the public settings' false made every start look silent).
            val own = PsiphonSidecar.buildConfig(JSONObject(settings).put("ConnectionWorkerPoolSize", 4)
                .put("EstablishTunnelPausePeriodSeconds", 5).put("EmitDiagnosticNotices", false), "", 40123, dir)
            assertEquals(4, own.getInt("ConnectionWorkerPoolSize"))
            assertEquals(5, own.getInt("EstablishTunnelPausePeriodSeconds"))
            assertTrue(own.getBoolean("EmitDiagnosticNotices"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun recentNoticesAreBoundedSanitizedAndCollapseNetworkIdRepeats() {
        val progress = PsiphonSidecar.StartupProgress()
        assertNull(progress.recentNotices())
        progress.observe("""Sun 2026-11-22 08:44 PM AvailableEgressRegions {"regions":["US","DE"]}""")
        repeat(5) { progress.observe("""Sun 2026-11-22 08:44 PM NetworkID {"ID":"MOBILE-43235-direct"}""") }
        progress.observe("""{"noticeType":"ConnectingServer","data":{"diagnosticID":"secretdiag","region":"NL","protocol":"OSSH","candidateNumber":3,"ipAddress":"198.51.100.7"}}""")
        progress.observe("""{"noticeType":"Warning","data":{"message":"psiphon.(*Controller).establishTunnelWorker#1234: dial tcp 198.51.100.7:443: i/o timeout via https://cdn.example.com/x/y and front.example.net, key AAAABBBBCCCCDDDDEEEEFFFF1234"}}""")
        val text = progress.recentNotices()!!
        assertTrue(text, text.contains("AvailableEgressRegions regions=2"))
        assertTrue(text, text.contains("NetworkID ×5"))
        assertTrue(text, text.contains("ConnectingServer protocol=OSSH region=NL candidateNumber=3"))
        assertTrue(text, text.contains("i/o timeout"))
        listOf("198.51.100.7", "cdn.example.com", "front.example.net", "secretdiag", "AAAABBBB", "MOBILE-43235", "establishTunnelWorker")
            .forEach { assertFalse("$it leaked: $text", text.contains(it)) }
        // NetworkID never becomes "the last notice"; the dial stage is now visible.
        val failure = progress.failureAfter(120_000)
        assertTrue(failure, failure.contains("server dialing did not establish a tunnel"))
        assertTrue(failure, failure.contains("last notice: Warning"))
        repeat(40) { progress.observe("""{"noticeType":"ConnectingServer","data":{"protocol":"P$it"}}""") }
        assertEquals(PsiphonSidecar.StartupProgress.RECENT_NOTICES, progress.recentNotices()!!.split(" | ").size)
    }

    @Test fun networkIdAloneIsNotReportedAsTheLastNotice() {
        val progress = PsiphonSidecar.StartupProgress()
        progress.observe("""Sun 2026-11-22 08:44 PM AvailableEgressRegions {"regions":["US"]}""")
        progress.observe("""Sun 2026-11-22 08:44 PM NetworkID {"ID":"MOBILE-1-direct"}""")
        assertFalse(progress.failureAfter(120_000).contains("last notice: NetworkID"))
    }

    // --- Update from the reference review: dial accounting and the TCP-only retry ---

    @Test fun dialsAreCountedByProtocolFromDiagnosticNotices() {
        val progress = PsiphonSidecar.StartupProgress()
        assertEquals("no_dials", progress.dials().verdict)
        progress.observe("""{"noticeType":"CandidateServers","data":{"region":"","count":37,"initialCount":12,"duration":"1s"}}""")
        repeat(4) { progress.observe("""{"noticeType":"ConnectingServer","data":{"protocol":"OSSH","diagnosticID":"x","region":"NL"}}""") }
        repeat(3) { progress.observe("""{"noticeType":"ConnectingServer","data":{"protocol":"TLS-OSSH"}}""") }
        repeat(5) { progress.observe("""{"noticeType":"ConnectingServer","data":{"protocol":"QUIC-OSSH"}}""") }
        // Not a protocol name: never counted, never reported.
        progress.observe("""{"noticeType":"ConnectingServer","data":{"protocol":"198.51.100.7:443"}}""")
        val dials = progress.dials()
        assertEquals(7, dials.tcp)
        assertEquals(5, dials.other)
        assertEquals(0, dials.connected)
        assertEquals(37, dials.candidates)
        assertEquals("dials_failed", dials.verdict)
        assertEquals("dials_failed: candidates=37; dialed OSSH×4 QUIC-OSSH×5 TLS-OSSH×3; connected 0", dials.summary())
        assertFalse(dials.summary().contains("198.51.100.7"))
        progress.observe("""{"noticeType":"ConnectedServer","data":{"protocol":"OSSH"}}""")
        assertEquals("connected_no_tunnel", progress.dials().verdict)
    }

    @Test fun verdictsForNoCandidatesAndUpstreamErrors() {
        assertEquals("no_candidates", PsiphonSidecar.StartupDials(candidates = 0).verdict)
        assertEquals("no_dials", PsiphonSidecar.StartupDials(candidates = 12).verdict)
        val upstream = PsiphonSidecar.StartupProgress()
        upstream.observe("""{"noticeType":"UpstreamProxyError","data":{"message":"socks connect failed"}}""")
        assertEquals("upstream_proxy_error", upstream.dials().verdict)
        assertTrue(upstream.dials().summary().endsWith("upstream proxy errors 1"))
    }

    @Test fun theTcpOnlyRetryIsSkippedOnlyWhenTcpWasAlreadyDisprovedAndTheChainMayTakeOver() {
        val disproved = PsiphonSidecar.StartupDials(tcp = PsiphonSidecar.TCP_DIALS_BEFORE_CHAIN, other = 9)
        assertTrue(PsiphonSidecar.skipTcpRetry(disproved, chainAllowed = true))
        // No chain to hand over to (pinned, exit-locked, forced, no Radiance...): the retry is the only next step.
        assertFalse(PsiphonSidecar.skipTcpRetry(disproved, chainAllowed = false))
        // Too few TCP dials to say anything (the first start mostly tried QUIC).
        assertFalse(PsiphonSidecar.skipTcpRetry(PsiphonSidecar.StartupDials(tcp = PsiphonSidecar.TCP_DIALS_BEFORE_CHAIN - 1,
            other = 40), chainAllowed = true))
        // A server answered: the handshake, not the transport, is the problem; keep the retry.
        assertFalse(PsiphonSidecar.skipTcpRetry(disproved.copy(connected = 1), chainAllowed = true))
        assertFalse(PsiphonSidecar.skipTcpRetry(disproved.copy(upstreamErrors = 1), chainAllowed = true))
        // Without diagnostic notices nothing is counted, so nothing is skipped.
        assertFalse(PsiphonSidecar.skipTcpRetry(PsiphonSidecar.StartupDials(), chainAllowed = true))
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
