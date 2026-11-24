package com.example.vpn.sidecar

import com.example.data.model.AppSettings
import com.example.data.model.OperationalMode
import com.example.data.model.VlessProfile
import com.example.vpn.connections.BuiltinMethodMemory
import com.example.vpn.engine.ProfileExtras
import com.example.vpn.lab.PathFamily
import com.example.vpn.sidecar.ShirokhorshidSidecar.Mode
import com.example.vpn.sidecar.ShirokhorshidSidecar.Options
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class ShirokhorshidSidecarTest {
    private val settings = JSONObject()
        .put("PropagationChannelId", "TEST-CHANNEL")
        .put("SponsorId", "TEST-SPONSOR")
        .put("RemoteServerListURLs", JSONArray().put(JSONObject().put("URL", "aHR0cHM6Ly9leGFtcGxlLmludmFsaWQvbGlzdA==")))
        .put("RemoteServerListSignaturePublicKey", "TEST-KEY")
        // Fields the app owns: the settings cannot decide them.
        .put("FrontedMeekCDNScanUseBuiltInSpec", true)
        .put("GeoIPDatabasePath", "/sdcard/evil.mmdb")
        .put("LocalProxyPassword", "x")
        .put("InproxyRejectProxyCIDRs", JSONArray().put("0.0.0.0/0"))
        .toString()

    private fun config(): JSONObject =
        PsiphonSidecar.buildConfig(JSONObject(settings), "", 40123, File("/tmp/sok-test-data"))

    private fun list(config: JSONObject, key: String): List<String> =
        config.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()

    private fun context(dir: File) = SidecarContext(
        workDir = dir, executable = File(dir, "libshirokhorshid.so"), socksPort = 40124,
        socksUser = "u", socksPass = "p", mode = OperationalMode.GOD_MODE
    )

    @Test fun profileCarriesItsOptionsAndOnlyThisEngineHandlesIt() {
        val options = Options(Mode.DIRECT, "de", cdnFronting = false, edges = listOf("23.215.0.0/24"), avoidIranConduits = false)
        val profile = ShirokhorshidSidecar.profile(options.copy(region = "DE"))
        val engine = ShirokhorshidSidecar({ settings })
        assertTrue(engine.handles(profile))
        assertFalse(engine.handles(PsiphonSidecar.profile()))
        assertFalse(PsiphonSidecar { settings }.handles(profile))
        assertEquals(options.copy(region = "DE"), Options.read(profile))
        assertEquals("Shir o Khorshid (DE)", profile.name)
        assertEquals("shirokhorshid", engine.id)
        assertEquals("shirokhorshid", engine.binary)
        // The defaults: Auto, any exit country, CDN fronting on, Iran conduits skipped.
        assertEquals(Options(), Options.read(ShirokhorshidSidecar.profile()))
        assertEquals(Mode.AUTO, Options().mode)
        assertTrue(Options().cdnFronting && Options().avoidIranConduits && Options().region.isEmpty())
    }

    @Test fun problemsAreReportedBeforeAStart() {
        val engine = ShirokhorshidSidecar({ settings })
        assertNull(engine.problem(ShirokhorshidSidecar.profile()))
        assertEquals(ShirokhorshidSidecar.MISSING_SETTINGS, ShirokhorshidSidecar({ null }).problem(ShirokhorshidSidecar.profile()))
        assertTrue(engine.problem(ShirokhorshidSidecar.profile(Options(region = "Germany")))!!.contains("two-letter"))
        assertTrue(engine.problem(ShirokhorshidSidecar.profile(Options(edges = listOf("cdn.example"))))!!.contains("cdn.example"))
        val noSponsor = JSONObject(settings).apply { remove("SponsorId") }.toString()
        assertTrue(ShirokhorshidSidecar({ noSponsor }).problem(ShirokhorshidSidecar.profile())!!.contains("SponsorId"))
    }

    @Test fun edgesAreIpv4AddressesOrRanges() {
        for (ok in listOf("23.215.0.206", "1.1.1.1", "23.215.0.0/24", "104.16.0.0/12", "8.8.8.8/32")) assertTrue(ok, ShirokhorshidSidecar.validEdge(ok))
        for (bad in listOf("", "256.1.1.1", "1.2.3", "1.2.3.4/33", "1.2.3.4/7", "1.2.3.4/024", "::1", "cdn.example", "1.2.3.4/x", "01.2.3.4"))
            assertFalse(bad, ShirokhorshidSidecar.validEdge(bad))
        assertEquals(listOf("23.215.0.206", "1.2.3.0/24", "9.9.9.9"),
            ShirokhorshidSidecar.parseEdges(" 23.215.0.206\n1.2.3.0/24, 9.9.9.9;23.215.0.206 "))
    }

    @Test fun autoWithCdnFrontingUsesTheScannerAndUserEdgesFirst() {
        val out = ShirokhorshidSidecar.applyOptions(config(), Options(edges = listOf("23.215.0.206", "bogus")),
            rejectCidrs = listOf("2.144.0.0/14"), remembered = listOf("92.123.102.43", "23.215.0.206"))
        assertTrue(out.getBoolean("FrontedMeekCDNScanUseBuiltInSpec"))
        assertEquals(listOf("23.215.0.206", "92.123.102.43"), list(out.getJSONObject("FrontedMeekCDNScanSpec"), "IPCandidates"))
        // Auto lets the core choose: no protocol limit, tactics stay on.
        assertFalse(out.has("LimitTunnelProtocols"))
        assertFalse(out.has("DisableTactics"))
        assertEquals(listOf("2.144.0.0/14"), list(out, "InproxyRejectProxyCIDRs"))
        assertFalse(out.has("GeoIPDatabasePath"))
        assertFalse(out.has("LocalProxyPassword"))
        assertEquals(40123, out.getInt("LocalSocksProxyPort"))
    }

    @Test fun conduitModeUsesOnlyInproxyAndRejectsIranianVolunteers() {
        val out = ShirokhorshidSidecar.applyOptions(config(), Options(Mode.CONDUIT, cdnFronting = true, edges = listOf("1.2.3.4")),
            rejectCidrs = listOf("2.144.0.0/14", "5.22.0.0/17"))
        assertEquals(ShirokhorshidSidecar.CONDUIT_PROTOCOLS, list(out, "LimitTunnelProtocols"))
        assertTrue(list(out, "LimitTunnelProtocols").all { it.startsWith("INPROXY-WEBRTC-") })
        // CDN fronting does not apply to in-proxy protocols.
        assertFalse(out.has("FrontedMeekCDNScanUseBuiltInSpec"))
        assertFalse(out.has("FrontedMeekCDNScanSpec"))
        assertEquals(listOf("2.144.0.0/14", "5.22.0.0/17"), list(out, "InproxyRejectProxyCIDRs"))
    }

    @Test fun directModeAddsCdnProtocolsOnlyWhenCdnFrontingIsOn() {
        val withCdn = ShirokhorshidSidecar.applyOptions(config(), Options(Mode.DIRECT), rejectCidrs = listOf("2.144.0.0/14"))
        val protocols = list(withCdn, "LimitTunnelProtocols")
        assertTrue(protocols.containsAll(ShirokhorshidSidecar.CDN_PROTOCOLS))
        assertTrue(protocols.containsAll(listOf("OSSH", "TLS-OSSH", "FRONTED-MEEK-OSSH")))
        assertTrue(protocols.none { it.startsWith("INPROXY") })
        assertTrue(withCdn.getBoolean("DisableTactics"))
        assertFalse("no conduits in direct mode, so no conduit filter", withCdn.has("InproxyRejectProxyCIDRs"))

        val noCdn = ShirokhorshidSidecar.applyOptions(config(), Options(Mode.DIRECT, cdnFronting = false))
        assertTrue(list(noCdn, "LimitTunnelProtocols").none { it in ShirokhorshidSidecar.CDN_PROTOCOLS })
        // The settings' own CDN switch is removed: with CDN fronting off the core leaves the CDN protocols disabled.
        assertFalse(noCdn.has("FrontedMeekCDNScanUseBuiltInSpec"))
    }

    @Test fun modeLimitsReplaceInitialLimitsFromTheSettings() {
        val source = JSONObject(settings).put("InitialLimitTunnelProtocols", JSONArray().put("OSSH"))
            .put("InitialLimitTunnelProtocolsCandidateCount", 5).put("LimitTunnelProtocols", JSONArray().put("SSH"))
        val base = PsiphonSidecar.buildConfig(source, "NL", 40123, File("/tmp/sok-test-data"))
        val out = ShirokhorshidSidecar.applyOptions(base, Options(Mode.CONDUIT))
        assertFalse(out.has("InitialLimitTunnelProtocols"))
        assertFalse(out.has("InitialLimitTunnelProtocolsCandidateCount"))
        assertEquals(ShirokhorshidSidecar.CONDUIT_PROTOCOLS, list(out, "LimitTunnelProtocols"))
        assertEquals("NL", out.getString("EgressRegion"))
        // Auto keeps what the settings decided.
        val auto = ShirokhorshidSidecar.applyOptions(PsiphonSidecar.buildConfig(source, "", 1, File("/tmp/x")), Options())
        assertEquals(listOf("SSH"), list(auto, "LimitTunnelProtocols"))
        assertFalse(auto.has("EgressRegion"))
    }

    @Test fun aChainHopKeepsOnlyTcpProtocols() {
        val out = ShirokhorshidSidecar.applyOptions(config(), Options(Mode.DIRECT), chained = true)
        val protocols = list(out, "LimitTunnelProtocols")
        assertTrue(protocols.isNotEmpty())
        assertTrue(protocols.none { it.contains("QUIC") || it.startsWith("INPROXY") })
        assertTrue(protocols.contains("FRONTED-MEEK-CDN-OSSH"))
    }

    @Test fun prepareWritesTheConfigAndCommandLine() {
        val dir = Files.createTempDirectory("sok").toFile()
        try {
            val options = Options(Mode.AUTO, "CA", edges = listOf("23.215.0.206"))
            val engine = ShirokhorshidSidecar({ settings }, rejectCidrs = { listOf("2.144.0.0/14") }, rememberedEdges = { listOf("92.123.102.43") })
            val launch = engine.prepare(ShirokhorshidSidecar.profile(options), AppSettings(), context(dir))
            val configFile = File(dir, PsiphonSidecar.CONFIG_FILE)
            val dataDir = File(dir, PsiphonSidecar.DATA_DIR)
            assertEquals(listOf(File(dir, "libshirokhorshid.so").absolutePath, "-config", configFile.absolutePath,
                "-dataRootDirectory", dataDir.absolutePath, "-formatNotices"), launch.command)
            assertFalse(launch.socksAuth)
            assertEquals(ShirokhorshidSidecar.SCAN_READY_TIMEOUT_MS, launch.readyTimeoutMs)
            assertNotNull(launch.readyLine)
            assertTrue(launch.readyLine!!.containsMatchIn("""Tunnels {"count":1}"""))
            assertTrue(dataDir.isDirectory)
            val written = JSONObject(configFile.readText())
            assertEquals(40124, written.getInt("LocalSocksProxyPort"))
            assertEquals("CA", written.getString("EgressRegion"))
            assertEquals(false, written.getBoolean("EnableUpgradeDownload"))
            assertEquals(listOf("23.215.0.206", "92.123.102.43"), list(written.getJSONObject("FrontedMeekCDNScanSpec"), "IPCandidates"))
            assertEquals(listOf("2.144.0.0/14"), list(written, "InproxyRejectProxyCIDRs"))

            // Iran filter off and CDN off: neither list is read into the config.
            val plain = engine.prepare(ShirokhorshidSidecar.profile(Options(cdnFronting = false, avoidIranConduits = false)), AppSettings(), context(dir))
            val second = JSONObject(configFile.readText())
            assertFalse(second.has("InproxyRejectProxyCIDRs"))
            assertFalse(second.has("FrontedMeekCDNScanSpec"))
            assertTrue(plain.readyTimeoutMs < ShirokhorshidSidecar.SCAN_READY_TIMEOUT_MS)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun theScannerFoundNoticeBecomesARememberedEdge() {
        assertEquals("92.123.102.43", ShirokhorshidEdgeMemory.foundEdge(
            """Tue 2026-11-24 2:21 AM (UTC+03:30) Info {"message":"cdn fronting scan found (ip: 92\\.123\\.102\\.43, sni: none)"}"""))
        assertEquals("23.215.0.206", ShirokhorshidEdgeMemory.foundEdge(
            """Info {"message":"cdn fronting scan found (ip: 23.215.0.206, sni: a.akamaihd.net)"}"""))
        assertNull(ShirokhorshidEdgeMemory.foundEdge("""Info {"message":"cdn fronting scan found (ip: [redacted], sni: none)"}"""))
        assertNull(ShirokhorshidEdgeMemory.foundEdge("""Info {"message":"cdn fronting scan active (mode: normal, workers: 10)"}"""))

        val store = HashMap<String, String?>()
        val read = BuiltinMethodMemory.read
        val write = BuiltinMethodMemory.write
        BuiltinMethodMemory.read = { store[it] }
        BuiltinMethodMemory.write = { k, v -> store[k] = v }
        try {
            ShirokhorshidEdgeMemory.record("wifi:abc", "1.1.1.1", now = 1_000)
            ShirokhorshidEdgeMemory.record("wifi:abc", "2.2.2.2", now = 2_000)
            ShirokhorshidEdgeMemory.record("wifi:abc", "1.1.1.1", now = 3_000)
            ShirokhorshidEdgeMemory.record("wifi:abc", "not-an-ip", now = 3_000)
            assertEquals(listOf("1.1.1.1", "2.2.2.2"), ShirokhorshidEdgeMemory.edges("wifi:abc", now = 4_000))
            assertTrue(ShirokhorshidEdgeMemory.edges("cell:other", now = 4_000).isEmpty())
            assertTrue(ShirokhorshidEdgeMemory.edges(null).isEmpty())
            // Old finds expire.
            assertTrue(ShirokhorshidEdgeMemory.edges("wifi:abc", now = 3_000 + ShirokhorshidEdgeMemory.TTL_MS).isEmpty())
            repeat(20) { ShirokhorshidEdgeMemory.record("wifi:abc", "10.0.0.$it", now = 5_000L + it) }
            assertEquals(ShirokhorshidEdgeMemory.MAX, ShirokhorshidEdgeMemory.edges("wifi:abc", now = 6_000).size)
            assertEquals("10.0.0.19", ShirokhorshidEdgeMemory.edges("wifi:abc", now = 6_000).first())
        } finally {
            BuiltinMethodMemory.read = read
            BuiltinMethodMemory.write = write
        }
    }

    @Test fun iranRangesComeFromTheBundledGeoIpList() {
        val file = listOf(File("src/main/assets/geo/geoip-lite.dat"), File("app/src/main/assets/geo/geoip-lite.dat")).first { it.isFile }
        val ranges = GeoIpCidrs.ipv4(file.readBytes(), "IR")
        assertTrue("IR has thousands of IPv4 ranges, got ${ranges.size}", ranges.size > 1_000)
        assertTrue(ranges.contains("2.144.0.0/14"))
        assertTrue(ranges.all { ShirokhorshidSidecar.validEdge(it.substringBefore('/')) && it.substringAfter('/').toInt() in 0..32 })
        assertTrue(GeoIpCidrs.ipv4(file.readBytes(), "ZZ").isEmpty())
        assertTrue(GeoIpCidrs.ipv4(ByteArray(0), "IR").isEmpty())
        assertTrue(GeoIpCidrs.ipv4(byteArrayOf(0x0a, 0x7f, 0x01), "IR").isEmpty())
    }

    @Test fun geoIpParserReadsAHandBuiltList() {
        fun varint(out: ByteArrayOutputStream, value: Int) { var v = value; while (v >= 0x80) { out.write((v and 0x7f) or 0x80); v = v ushr 7 }; out.write(v) }
        fun field(out: ByteArrayOutputStream, number: Int, bytes: ByteArray) { varint(out, (number shl 3) or 2); varint(out, bytes.size); out.write(bytes) }
        fun cidr(ip: ByteArray, prefix: Int) = ByteArrayOutputStream().also { field(it, 1, ip); varint(it, (2 shl 3)); varint(it, prefix) }.toByteArray()
        fun entry(code: String, vararg cidrs: ByteArray) = ByteArrayOutputStream().also { o ->
            field(o, 1, code.toByteArray()); cidrs.forEach { field(o, 2, it) } }.toByteArray()
        val list = ByteArrayOutputStream().also { o ->
            field(o, 1, entry("CN", cidr(byteArrayOf(1, 0, 1, 0), 24)))
            field(o, 1, entry("IR", cidr(byteArrayOf(5, 22, 0, 0), 17), cidr(ByteArray(16), 64), cidr(byteArrayOf(-71, -113, -24, 0), 22)))
        }.toByteArray()
        assertEquals(listOf("5.22.0.0/17", "185.143.232.0/22"), GeoIpCidrs.ipv4(list, "ir"))
        assertEquals(listOf("1.0.1.0/24"), GeoIpCidrs.ipv4(list, "CN"))
    }

    @Test fun itIsAPsiphonFamilyEngineThatCarriesTcpOnly() {
        val profile = ShirokhorshidSidecar.profile()
        assertEquals(PathFamily.PSIPHON, PathFamily.of(profile, ShirokhorshidSidecar.ID))
        assertEquals(EngineUdp.Capability.TCP_ONLY, EngineUdp.of(profile))
        assertTrue(Sidecars.ENGINES.any { it is ShirokhorshidSidecar })
        assertTrue(com.example.vpn.engine.registry.EngineRegistry.requiredNativePrograms().contains(ShirokhorshidSidecar.BINARY))
        assertEquals(ShirokhorshidSidecar.REVISION, com.example.vpn.engine.registry.EngineRegistry.SHIROKHORSHID.version)
        assertEquals(ShirokhorshidSidecar.ID, ProfileExtras.read(profile).getString(PsiphonSidecar.KEY_ENGINE))
        assertFalse(VlessProfile(name = "v", address = "example.com", port = 443, uuid = "x").let { ShirokhorshidSidecar({ settings }).handles(it) })
    }

    @Test fun theBuildScriptPinsTheSameCommit() {
        val script = listOf(File("../scripts/engines/shirokhorshid.sh"), File("scripts/engines/shirokhorshid.sh")).first { it.isFile }.readText()
        val rev = Regex("^REV=([0-9a-f]{40})", RegexOption.MULTILINE).find(script)!!.groupValues[1]
        assertTrue(rev.startsWith(ShirokhorshidSidecar.REVISION))
        assertTrue(script.contains("https://github.com/shirokhorshid/psiphon-tunnel-core.git"))
        assertTrue(script.contains("Shirokhorshid-source.tar.gz"))
    }
}
