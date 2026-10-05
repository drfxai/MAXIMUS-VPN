package com.example

import com.example.data.model.EngineType
import com.example.data.model.ProfileType
import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.mihomo.MihomoParser
import com.example.vpn.EndpointResolver
import com.example.vpn.engine.ConfigurationAdapter
import com.example.vpn.engine.EngineSelectionPolicy
import com.example.vpn.engine.ProtocolLinks
import com.example.vpn.engine.RuntimeCapabilities
import com.example.vpn.engine.UniversalImportEngine
import com.example.vpn.engine.WireGuardConf
import com.example.vpn.stealth.StealthPathFinder
import com.example.vpn.stealth.StealthVariants
import com.example.xray.RealDelayProbe
import com.example.xray.XrayConfigBuilder
import com.example.data.model.AppSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
/** War plan Phase 1 (every imported link runs on a real engine) and Phase 2 (stealth alternates). */
class WarPlanEnginesAndStealthTest {
    private val reality = VlessProfile(
        id = "r1", name = "REALITY", address = "203.0.113.10", port = 443,
        uuid = "11111111-2222-3333-4444-555555555555", transport = "tcp", security = "reality",
        sni = "www.example.com", publicKey = "pbk", shortId = "ab", flow = "xtls-rprx-vision", fingerprint = "chrome"
    )

    @Before fun clearMemory() = StealthPathFinder.memory.clear()

    // ---- Phase 1 -------------------------------------------------------------------------------

    @Test fun vlessEncryptionFromQuickConfigRunsOnXray() {
        val enc = reality.copy(security = "none", flow = "", publicKey = "", sni = "",
            encryption = "mlkem768x25519plus.native.0rtt.CLIENTKEY")
        assertNull(RuntimeCapabilities.unsupportedReason(enc))
        assertEquals(EngineSelectionPolicy.Runtime.XRAY, EngineSelectionPolicy.select(enc))
        val user = JSONObject(XrayConfigBuilder.buildJson(enc, AppSettings())).getJSONArray("outbounds")
            .getJSONObject(0).getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
            .getJSONArray("users").getJSONObject(0)
        assertEquals("mlkem768x25519plus.native.0rtt.CLIENTKEY", user.getString("encryption"))
        assertNotNull(RuntimeCapabilities.unsupportedReason(enc.copy(encryption = "aes-128-gcm")))
    }

    @Test fun mihomoTaggedProfilesNeverGoToTheEngineWithoutACore() {
        val tagged = reality.copy(engineType = EngineType.MIHOMO)
        assertEquals(EngineSelectionPolicy.Runtime.XRAY, EngineSelectionPolicy.select(tagged))
        assertEquals(EngineSelectionPolicy.Runtime.KOTLIN_TUNNEL,
            EngineSelectionPolicy.select(tagged.copy(security = "none", flow = "", protocolType = ProtocolType.SOCKS5, uuid = "")))
    }

    @Test fun tuicLinksBecomeMihomoProfiles() {
        val result = UniversalImportEngine.importText("tuic://uuid:p%40ss@1.2.3.4:443?sni=x.com&congestion_control=bbr#T")
        val profile = result.validProfiles.single()
        assertEquals(ProfileType.MIHOMO_YAML, profile.profileType)
        assertEquals(ProtocolType.TUIC, profile.protocolType)
        val proxy = com.example.vpn.sidecar.MihomoSidecar.proxyOf(profile)!!
        assertEquals("tuic", proxy.getString("type"))
        assertEquals("uuid", proxy.getString("uuid"))
        assertEquals("p@ss", proxy.getString("password"))
        assertEquals("x.com", proxy.getString("sni"))
        assertEquals("bbr", proxy.getString("congestion-controller"))
        assertEquals(com.example.vpn.sidecar.MihomoSidecar, com.example.vpn.sidecar.Sidecars.forProfile(profile))
        // Without the engine program on the phone the profile is refused at connect, with the reason.
        assertTrue(RuntimeCapabilities.unsupportedReason(profile)!!.contains("mihomo"))
        // A VLESS-shaped profile merely tagged TUIC is still nothing Xray can run.
        assertNotNull(RuntimeCapabilities.unsupportedReason(reality.copy(protocolType = ProtocolType.TUIC)))
    }

    private val conf = """
        [Interface]
        PrivateKey = cHJpdmF0ZWtleXByaXZhdGVrZXlwcml2YXRla2V5cHI=
        Address = 10.8.0.2/32, fd00::2/128
        DNS = 1.1.1.1
        MTU = 1280
        Jc = 4
        Jmin = 40
        Jmax = 70
        S1 = 0
        S2 = 0
        H1 = 1
        H2 = 2
        H3 = 3
        H4 = 4

        [Peer]
        PublicKey = cHVibGlja2V5cHVibGlja2V5cHVibGlja2V5cHVibGk=
        PresharedKey = cHNrcHNrcHNrcHNrcHNrcHNrcHNrcHNrcHNrcHNrcHM=
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = 198.51.100.7:51820
        PersistentKeepalive = 25
    """.trimIndent()

    @Test fun amneziaWgJunkPacketsBecomeXrayNoise() {
        val profile = WireGuardConf.parse(conf, "home")
        assertEquals(ProtocolType.WIREGUARD, profile.protocolType)
        assertEquals("198.51.100.7", profile.address)
        assertEquals(51820, profile.port)
        assertNull(RuntimeCapabilities.unsupportedReason(profile))
        val outbound = JSONObject(XrayConfigBuilder.buildJson(profile, AppSettings())).getJSONArray("outbounds").getJSONObject(0)
        assertEquals("10.8.0.2/32", outbound.getJSONObject("settings").getJSONArray("address").getString(0))
        val noise = outbound.getJSONObject("streamSettings").getJSONObject("finalmask").getJSONArray("udp").getJSONObject(0)
        assertEquals("noise", noise.getString("type"))
        assertEquals(4, noise.getJSONObject("settings").getJSONArray("noise").length())
        assertEquals("40-70", noise.getJSONObject("settings").getJSONArray("noise").getJSONObject(0).getString("rand"))
    }

    @Test fun amneziaWgWithChangedHeadersRunsOnMihomo() {
        val changed = conf.replace("S1 = 0", "S1 = 86").replace("H1 = 1", "H1 = 1020325451")
        val item = UniversalImportEngine.parseWireGuardConf(changed)
        val profile = (item as UniversalImportEngine.ParsedItem.Success).profile
        assertEquals(ProfileType.MIHOMO_YAML, profile.profileType)
        val proxy = com.example.vpn.sidecar.MihomoSidecar.proxyOf(profile)!!
        assertEquals("wireguard", proxy.getString("type"))
        assertEquals("10.8.0.2", proxy.getString("ip"))
        assertEquals("fd00::2", proxy.getString("ipv6"))
        val awg = proxy.getJSONObject("amnezia-wg-option")
        assertEquals(86, awg.getInt("s1"))
        assertEquals(1020325451L, awg.getLong("h1"))
        assertEquals(4, awg.getInt("jc"))
        // The standard format still runs on Xray.
        assertEquals(ProfileType.VLESS, ConfigurationAdapter.importConfiguration(conf).single().profileType)
    }

    @Test fun mihomoYamlWireGuardAndVlessRunOnXray() {
        val yaml = """
            proxies:
              - name: wg
                type: wireguard
                server: 198.51.100.8
                port: 2408
                ip: 172.16.0.2
                private-key: cHJpdmF0ZWtleXByaXZhdGVrZXlwcml2YXRla2V5cHI=
                public-key: cHVibGlja2V5cHVibGlja2V5cHVibGlja2V5cHVibGk=
                reserved: [1, 2, 3]
                amnezia-wg-option:
                  jc: 3
                  jmin: 20
                  jmax: 40
              - name: awg-custom
                type: wireguard
                server: 198.51.100.9
                port: 2408
                ip: 172.16.0.3
                private-key: cHJpdmF0ZWtleXByaXZhdGVrZXlwcml2YXRla2V5cHI=
                public-key: cHVibGlja2V5cHVibGlja2V5cHVibGlja2V5cHVibGk=
                amnezia-wg-option:
                  s1: 15
              - name: vless-enc
                type: vless
                server: 198.51.100.10
                port: 8443
                uuid: 11111111-2222-3333-4444-555555555555
                encryption: mlkem768x25519plus.native.0rtt.KEY
              - name: tuic
                type: tuic
                server: 198.51.100.11
                port: 443
                uuid: 11111111-2222-3333-4444-555555555555
                password: p
        """.trimIndent()
        val all = MihomoParser.toVlessProfiles(yaml)
        // TUIC and the changed-format AmneziaWG server go to the Mihomo engine.
        assertEquals(listOf("awg-custom", "tuic"), all.filter { it.profileType == ProfileType.MIHOMO_YAML }.map { it.name })
        val profiles = all.filter { it.profileType != ProfileType.MIHOMO_YAML }
        assertEquals(listOf("vless-enc", "wg"), profiles.map { it.name })
        assertTrue(profiles.all { it.engineType == EngineType.XRAY && RuntimeCapabilities.unsupportedReason(it) == null })
        assertEquals("1,2,3", JSONObject(profiles[1].extraSettings).getString("wgReserved"))
        assertEquals("mlkem768x25519plus.native.0rtt.KEY", profiles[0].encryption)
    }

    @Test fun addServerPasteAcceptsEveryLinkTypeAndSeveralLines() {
        val vmess = "vmess://" + java.util.Base64.getEncoder().encodeToString(
            """{"v":"2","ps":"vm","add":"198.51.100.12","port":"443","id":"11111111-2222-3333-4444-555555555555","net":"ws","tls":"tls","host":"a.example.com","path":"/"}""".toByteArray()
        )
        assertEquals(ProtocolType.VMESS, ConfigurationAdapter.importConfiguration(vmess).single().protocolType)
        val two = "trojan://pw@198.51.100.13:443?security=tls#a\nhy2://pw@198.51.100.14:443#b"
        assertEquals(2, ConfigurationAdapter.importConfiguration(two).size)
    }

    // ---- Phase 2 -------------------------------------------------------------------------------

    @Test fun everyXrayProfileGetsAtLeastTwoStealthAlternates() {
        val cdn = reality.copy(security = "tls", transport = "ws", flow = "", publicKey = "", sni = "edge.example.com", host = "edge.example.com")
        val hy2 = ProtocolLinks.parseHysteria2("hysteria2://pw@198.51.100.15:443?sni=h.example.com#h")
        val wg = WireGuardConf.parse(conf.replace("Jc = 4", "Jc = 0"))
        val enc = reality.copy(security = "none", flow = "", publicKey = "", encryption = "mlkem768x25519plus.native.0rtt.K")
        for (p in listOf(reality, cdn, hy2, wg, enc)) {
            val variants = StealthVariants.of(p)
            assertTrue("${p.name} has ${variants.size}", variants.size >= 2)
            variants.forEach { v ->
                assertNull(v.label, RuntimeCapabilities.unsupportedReason(v.profile))
                JSONObject(XrayConfigBuilder.buildJson(v.profile, AppSettings())) // builds
            }
        }
        assertTrue(StealthVariants.of(cdn).any { it.key == StealthVariants.ECH && it.profile.echConfigList == "edge.example.com+${StealthVariants.ECH_DNS}" })
        val frag = StealthVariants.of(reality).first { it.key == StealthVariants.FRAGMENT }.profile
        assertEquals("tlshello", JSONObject(frag.finalMask).getJSONArray("tcp").getJSONObject(0).getJSONObject("settings").getString("packets"))
        // Plain VLESS on the Kotlin tunnel has no Xray alternates.
        assertTrue(StealthVariants.of(reality.copy(security = "none", flow = "", publicKey = "")).isEmpty())
    }

    @Test fun aWorkingProfileCostsOneRequestAndIsUsedAsSaved() {
        val calls = mutableListOf<Int>()
        val finder = StealthPathFinder(probe = { p, _ -> calls += p.size; p.map { RealDelayProbe.Outcome.Delay(120) } }, log = {})
        val choice = finder.choose(reality, { it }, emptyList())
        assertEquals(reality, choice.profile)
        assertEquals(listOf(1), calls)
    }

    @Test fun aBlockedHandshakeSwitchesToTheAlternateThatCarriesTrafficAndRemembersIt() {
        // Simulated SNI filter: only the split handshake gets through.
        val probe: (List<VlessProfile>, Int) -> List<RealDelayProbe.Outcome> = { list, _ ->
            list.map { if (it.finalMask.contains("tlshello")) RealDelayProbe.Outcome.Delay(300) else RealDelayProbe.Outcome.Failed("timeout") }
        }
        val first = StealthPathFinder(probe, log = {}).choose(reality, { it }, emptyList())
        assertTrue(first.profile.finalMask.contains("tlshello"))
        assertEquals(reality.id, first.owner.id)
        var calls = 0
        val again = StealthPathFinder({ l, t -> calls++; probe(l, t) }, log = {}).choose(reality, { it }, emptyList())
        assertTrue(again.profile.finalMask.contains("tlshello"))
        assertEquals("the remembered alternate goes first", 1, calls)
    }

    @Test fun whenEveryDisguiseFailsAnotherKindOnTheSameServerIsTried() {
        val hy2 = ProtocolLinks.parseHysteria2("hysteria2://pw@203.0.113.10:443?sni=h.example.com#same-host").copy(id = "h1")
        val otherHost = hy2.copy(id = "h2", address = "198.51.100.99")
        val choice = StealthPathFinder({ list, _ ->
            list.map { if (it.protocolType == ProtocolType.HYSTERIA2) RealDelayProbe.Outcome.Delay(90) else RealDelayProbe.Outcome.Failed("reset") }
        }, log = {}).choose(reality, { it }, listOf(reality, otherHost, hy2))
        assertEquals("h1", choice.owner.id)
    }

    @Test fun underSeveralFiltersTheSameServersOtherKindIsTriedInDisguise() {
        // VLESS Encryption is caught as "fully encrypted", REALITY's server name is filtered: only a
        // split REALITY handshake gets through.
        val enc = reality.copy(id = "e1", security = "none", flow = "", publicKey = "", port = 8443,
            encryption = "mlkem768x25519plus.native.0rtt.K")
        val choice = StealthPathFinder({ list, _ ->
            list.map {
                if (it.security == "reality" && it.finalMask.contains("tlshello")) RealDelayProbe.Outcome.Delay(200)
                else RealDelayProbe.Outcome.Failed("reset")
            }
        }, log = {}).choose(enc, { it }, listOf(enc, reality))
        assertEquals("r1", choice.owner.id)
        assertTrue(choice.profile.finalMask.contains("tlshello"))
        assertEquals(StealthVariants.FRAGMENT, StealthPathFinder.memory["r1"])
    }

    @Test fun wireGuardAlternatesOfOneKeyAreNeverProbedTogether() {
        // The server follows a key's newest address, so parallel handshakes knock each other out.
        val wg = WireGuardConf.parse(conf.replace("Jc = 4", "Jc = 0"))
        val batches = mutableListOf<Int>()
        val choice = StealthPathFinder({ list, _ ->
            batches += list.size
            list.map { if (it.finalMask.contains("noise")) RealDelayProbe.Outcome.Delay(50) else RealDelayProbe.Outcome.Failed("x") }
        }, log = {}).choose(wg, { it }, emptyList())
        assertTrue(choice.profile.finalMask.contains("noise"))
        assertTrue(batches.all { it == 1 })
    }

    @Test fun noProbeMeansNoDelayAndNoChange() {
        var calls = 0
        val choice = StealthPathFinder({ l, _ -> calls++; l.map { RealDelayProbe.Outcome.NotRun("core busy") } }, log = {})
            .choose(reality, { it }, emptyList())
        assertEquals(reality, choice.profile)
        assertEquals(1, calls)
    }

    @Test fun droppedSystemDnsFallsBackToDohWithinTheGracePeriod() {
        val answer = """{"Status":0,"Answer":[{"name":"x","type":1,"data":"104.21.30.40"}]}"""
        val open: (URL) -> HttpURLConnection = { url ->
            object : HttpURLConnection(url) {
                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy() = false
                override fun getResponseCode() = if (url.host == "1.1.1.1") 200 else 503
                override fun getInputStream(): InputStream = ByteArrayInputStream(answer.toByteArray())
            }
        }
        val start = System.nanoTime()
        // The network drops DNS: the lookup hangs far longer than the grace period.
        val result = EndpointResolver.resolve("blocked.example", { Thread.sleep(20_000); emptyList<InetAddress>() }, open)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(EndpointResolver.Result("104.21.30.40", viaDoh = true), result)
        assertTrue("took $ms ms", ms < EndpointResolver.SYSTEM_GRACE_MS + 1000)
    }

    @Test fun wireFormatDohAnswersAreParsed() {
        val query = EndpointResolver.wireQuery("a.example", id = 0x1234)
        // Header (response, 1 question, 2 answers), the echoed question, a CNAME and an A record.
        val answer = query.copyOf().also { it[2] = 0x81.toByte(); it[3] = 0x80.toByte(); it[7] = 2 } +
            byteArrayOf(0xc0.toByte(), 12, 0, 5, 0, 1, 0, 0, 0, 60, 0, 2, 0xc0.toByte(), 12) +
            byteArrayOf(0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 104, 16, 1, 2)
        assertEquals("104.16.1.2", EndpointResolver.parseWireAnswer(query, answer))
        val blockPage = answer.copyOf().also { it[it.size - 4] = 10; it[it.size - 3] = 10; it[it.size - 2] = 34; it[it.size - 1] = 36 }
        assertNull(EndpointResolver.parseWireAnswer(query, blockPage))
    }
}
