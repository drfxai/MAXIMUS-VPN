package com.example

import com.example.vpn.connections.AetherPrewarm
import com.example.vpn.connections.CleanH2PeerMemory
import com.example.vpn.connections.ConnectionEngineRunner
import com.example.vpn.connectivity.IranHelloSplit
import com.example.vpn.connectivity.NetworkFirewalls
import com.example.vpn.connectivity.RecoveryProfiles
import com.example.vpn.connectivity.RecoverySecurityGate
import com.example.data.model.VlessProfile
import com.example.vpn.engine.ProfileExtras
import com.example.vpn.sidecar.AetherSettings
import com.example.vpn.sidecar.AetherSidecar
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Logic ported from UAC SNI Spoofer: clean H2 gateways, screen-on prewarm, Iran ClientHello split, MCI ladder. */
class UacPortTest {
    private val store = mutableMapOf<String, String>()

    @Before fun setUp() {
        store.clear()
        CleanH2PeerMemory.read = { store[it] }
        CleanH2PeerMemory.write = { k, v -> if (v == null) store.remove(k) else store[k] = v }
        AetherPrewarm.resetForTest()
    }

    @After fun tearDown() {
        CleanH2PeerMemory.read = { null }
        CleanH2PeerMemory.write = { _, _ -> }
    }

    private val irancell = "cell:43235"
    private val mci = "cell:43211"

    @Test fun cleanH2PeersArePerNetworkMostRecentFirstCappedAndExpire() {
        CleanH2PeerMemory.remember(irancell, "162.159.198.1:443", now = 1_000)
        CleanH2PeerMemory.remember(irancell, "162.159.198.2:443", split = true, now = 2_000)
        assertEquals(listOf("162.159.198.2:443", "162.159.198.1:443"), CleanH2PeerMemory.peers(irancell, now = 3_000).map { it.address })
        assertTrue(CleanH2PeerMemory.peers(irancell, now = 3_000).first().split)
        // Another network never sees them.
        assertTrue(CleanH2PeerMemory.peers(mci, now = 3_000).isEmpty())
        assertTrue(CleanH2PeerMemory.peers(null, now = 3_000).isEmpty())
        // Remembering again moves it to the front without a duplicate; the list is capped.
        CleanH2PeerMemory.remember(irancell, "162.159.198.1:443", now = 4_000)
        CleanH2PeerMemory.remember(irancell, "162.159.198.3:443", now = 5_000)
        CleanH2PeerMemory.remember(irancell, "[2606:4700:103::1]:443", now = 6_000)
        val peers = CleanH2PeerMemory.peers(irancell, now = 7_000)
        assertEquals(CleanH2PeerMemory.MAX_PEERS, peers.size)
        assertEquals("[2606:4700:103::1]:443", peers.first().address)
        // A failed connect forgets the peer; old entries expire.
        CleanH2PeerMemory.forget(irancell, "[2606:4700:103::1]:443")
        assertEquals("162.159.198.3:443", CleanH2PeerMemory.peers(irancell, now = 7_000).first().address)
        assertTrue(CleanH2PeerMemory.peers(irancell, now = 6_000 + CleanH2PeerMemory.TTL_MS).isEmpty())
    }

    @Test fun onlyPublicIpLiteralPeersAreStored() {
        listOf("10.0.0.1:443", "127.0.0.1:443", "example.com:443", "1.2.3.4", "1.2.3.4:0", "1.2.3.4:443 ", "[fd00::1]:443", "2606:4700::1:443")
            .forEach { assertFalse(it, CleanH2PeerMemory.validPeer(it)) }
        assertTrue(CleanH2PeerMemory.validPeer("162.159.198.2:443"))
        assertTrue(CleanH2PeerMemory.validPeer("[2606:4700:103::2]:443"))
        CleanH2PeerMemory.remember(irancell, "192.168.1.1:443", now = 1)
        assertTrue(CleanH2PeerMemory.peers(irancell, now = 2).isEmpty())
        assertEquals("162.159.198.x:443", CleanH2PeerMemory.masked("162.159.198.2:443"))
    }

    @Test fun prewarmReordersByConnectTimeAndDropsRepeatedlySilentPeers() {
        CleanH2PeerMemory.remember(irancell, "162.159.198.1:443", now = 1_000)
        CleanH2PeerMemory.remember(irancell, "162.159.198.2:443", now = 2_000)
        CleanH2PeerMemory.remember(irancell, "162.159.198.3:443", now = 3_000)
        val logs = mutableListOf<String>()
        val rtt = mapOf("162.159.198.3:443" to null, "162.159.198.2:443" to 240L, "162.159.198.1:443" to 80L)
        val out = AetherPrewarm.run(irancell, "screen-on", vpnActive = false, powerSave = false, metered = true,
            now = 10_000, connect = { rtt[it] }, log = { logs += it })!!
        assertEquals(3, out.probed); assertEquals(2, out.answered); assertEquals(80L, out.bestMs)
        assertEquals(listOf("162.159.198.1:443", "162.159.198.2:443", "162.159.198.3:443"),
            CleanH2PeerMemory.peers(irancell, now = 10_000).map { it.address })
        assertTrue(logs.single(), logs.single().startsWith("aether/prewarm · cell:43235 · screen-on · 2/3 clean H2 peer(s) answer · best 162.159.198.x:443 80 ms"))
        // The silent peer is dropped after three misses in a row (each prewarm after the metered gap).
        var now = 10_000L
        repeat(CleanH2PeerMemory.MAX_PREWARM_MISSES - 1) {
            now += AetherPrewarm.METERED_GAP_MS
            AetherPrewarm.run(irancell, "network-change", false, false, true, now = now, connect = { rtt[it] }, log = {})
        }
        assertEquals(2, CleanH2PeerMemory.peers(irancell, now = now).size)
    }

    @Test fun prewarmStaysOffWhileTheVpnRunsInBatterySaverWithNothingRememberedOrTooSoon() {
        val connects = mutableListOf<String>()
        fun run(network: String? = irancell, vpn: Boolean = false, saver: Boolean = false, metered: Boolean = true, now: Long) =
            AetherPrewarm.run(network, "screen-on", vpn, saver, metered, now = now, connect = { connects += it; 50L }, log = {})
        assertNull("nothing remembered here", run(now = 1_000))
        CleanH2PeerMemory.remember(irancell, "162.159.198.1:443", now = 1_000)
        assertNull(run(vpn = true, now = 2_000))
        assertNull(run(saver = true, now = 2_000))
        assertNull(run(network = null, now = 2_000))
        assertTrue(connects.isEmpty())
        assertNotNull(run(now = 2_000))
        // Metered (Irancell): one TCP handshake per peer, then quiet for 20 minutes on this network.
        assertNull(run(now = 2_000 + AetherPrewarm.METERED_GAP_MS - 1))
        assertNotNull(run(now = 2_000 + AetherPrewarm.METERED_GAP_MS))
        assertEquals(2, connects.size)
        assertEquals(AetherPrewarm.Skip.TOO_SOON, AetherPrewarm.skip(irancell, false, false, false, 1, 2_000 + AetherPrewarm.METERED_GAP_MS + 1))
        assertNull(AetherPrewarm.skip(irancell, false, false, false, 1, 2_000 + AetherPrewarm.METERED_GAP_MS + AetherPrewarm.UNMETERED_GAP_MS))
    }

    @Test fun aetherLastConnectionFileGivesTheScannedHttp2Gateway() {
        assertEquals("162.159.198.2:443", AetherSidecar.parseLastConnection(
            "peer = \"162.159.198.2:443\"\nprofile = \"firewall\"\ncarrier = \"masque-h2\"\nrecent = [\"162.159.198.2:443\"]\n"))
        assertNull(AetherSidecar.parseLastConnection("peer = \"162.159.198.2:443\"\ncarrier = \"masque-h3\"\n"))
        assertNull(AetherSidecar.parseLastConnection("peer = \"10.0.0.2:443\"\ncarrier = \"masque-h2\"\n"))
        assertNull(AetherSidecar.parseLastConnection("garbage"))
    }

    @Test fun cleanGatewaysGoFirstAndOperatorLaddersFollowUac() {
        val base = listOf("h2", "h3", "wireguard")
        // Hamrah-e-Aval: full-hello H2, split H2, H3, WireGuard; a remembered clean gateway before all of them.
        assertEquals(listOf("h2", "h2-split", "h3", "wireguard"),
            ConnectionEngineRunner.order(listOf("h2", "h2-split", "h3", "wireguard"), null, false, emptySet(), mci = true))
        assertEquals(listOf("h2-clean", "h2", "h2-split", "h3", "wireguard"),
            ConnectionEngineRunner.order(listOf("h2", "h2-split", "h3", "wireguard", "h2-clean"), null, true, emptySet(), mci = true))
        // Irancell keeps H3 first and adds the split H2 last.
        assertEquals(listOf("h3", "wireguard", "h2", "h2-split"),
            ConnectionEngineRunner.order(listOf("h2", "h2-split", "h3", "wireguard"), null, false, emptySet(), irancell = true))
        assertEquals(listOf("h2-clean", "h3", "wireguard", "h2", "h2-split"),
            ConnectionEngineRunner.order(listOf("h2-clean", "h2", "h2-split", "h3", "wireguard"), null, false, emptySet(), irancell = true))
        // A verified method still wins, and other networks are unchanged.
        assertEquals("h3", ConnectionEngineRunner.order(listOf("h2-clean", "h2", "h3"), "h3", false, emptySet(), irancell = true).first())
        assertEquals(base, ConnectionEngineRunner.order(base, null, false, emptySet()))
        assertTrue(ConnectionEngineRunner.mci(mci)); assertFalse(ConnectionEngineRunner.mci(irancell))
    }

    private fun carriers(list: List<VlessProfile>) = list.map { ProfileExtras.read(it) }

    @Test fun mciSendsTheFullHelloFirstAndAddsTheSniSplitOverIpv4() {
        val auto = ConnectionEngineRunner.candidates(AetherSidecar.profile("auto", "auto"))
        val onMci = carriers(ConnectionEngineRunner.operatorVariants(auto, irancell = false, mci = true))
        assertEquals(listOf("h2", "h2", "h3", "wireguard"), onMci.map { it.optString("carrier") })
        assertFalse("full ClientHello first", onMci[0].optBoolean("fragment", true))
        assertTrue(onMci[1].optBoolean(AetherSidecar.SNI_SPLIT)); assertTrue(onMci[1].optBoolean("fragment"))
        assertEquals("ipv4", onMci[1].optString("family"))
        val onIrancell = carriers(ConnectionEngineRunner.operatorVariants(auto, irancell = true, mci = false))
        assertTrue("Irancell keeps its fragmented H2", onIrancell[0].optBoolean("fragment", true))
        assertTrue(onIrancell[1].optBoolean(AetherSidecar.SNI_SPLIT))
        // Other networks and reverse relays are left alone.
        assertEquals(auto, ConnectionEngineRunner.operatorVariants(auto, irancell = false, mci = false))
        val reverse = ConnectionEngineRunner.candidates(AetherSidecar.profile("auto", "auto", advanced = AetherSettings(tor = "reverse")))
        assertEquals(reverse, ConnectionEngineRunner.operatorVariants(reverse, irancell = false, mci = true))
    }

    @Test fun cleanCandidatesDialTheRememberedGatewayWithTheHelloThatWorked() {
        val variants = ConnectionEngineRunner.operatorVariants(ConnectionEngineRunner.candidates(AetherSidecar.profile("auto", "auto")), irancell = false, mci = true)
        val peers = listOf(CleanH2PeerMemory.Peer("162.159.198.2:443", split = true, at = 1), CleanH2PeerMemory.Peer("[2606:4700:103::2]:443", at = 1),
            CleanH2PeerMemory.Peer("162.159.198.1:443", at = 1))
        val clean = carriers(ConnectionEngineRunner.cleanH2Candidates(variants, peers, ipv4Only = false))
        assertEquals(ConnectionEngineRunner.CLEAN_H2_TRIES, clean.size)
        assertEquals("162.159.198.2:443", clean[0].optString(AetherSidecar.CLEAN_H2_PEER))
        assertTrue(clean[0].optBoolean(AetherSidecar.SNI_SPLIT))
        assertEquals("ipv6", clean[1].optString("family")); assertFalse(clean[1].optBoolean(AetherSidecar.SNI_SPLIT))
        val v4 = carriers(ConnectionEngineRunner.cleanH2Candidates(variants, peers, ipv4Only = true))
        assertEquals(listOf("162.159.198.2:443", "162.159.198.1:443"), v4.map { it.optString(AetherSidecar.CLEAN_H2_PEER) })
        // A user endpoint or exit node, or no H2 at all, means no remembered gateway.
        val own = ConnectionEngineRunner.candidates(AetherSidecar.profile("auto", "auto", advanced = AetherSettings(endpoint = "162.159.198.9:443")))
        assertTrue(ConnectionEngineRunner.cleanH2Candidates(own, peers, false).isEmpty())
        assertTrue(ConnectionEngineRunner.cleanH2Candidates(ConnectionEngineRunner.candidates(AetherSidecar.profile("wireguard")), peers, false).isEmpty())
    }

    @Test fun aetherGetsTheSniSplitAndTheCleanPeerOnlyWhereTheyApply() {
        val split = JSONObject().put(AetherSidecar.SNI_SPLIT, true).put("fragment", true)
        assertTrue(AetherSidecar.sniSplit(split, "h2"))
        assertFalse(AetherSidecar.sniSplit(split, "h3"))
        assertFalse(AetherSidecar.sniSplit(split, "wireguard"))
        assertFalse(AetherSidecar.sniSplit(JSONObject(split.toString()).put("fragment", false), "h2"))
        assertEquals("AETHER_MASQUE_H2_FRAGMENT_SNI", IranHelloSplit.AETHER_SNI_SPLIT_ENV)
        val clean = JSONObject().put(AetherSidecar.CLEAN_H2_PEER, "162.159.198.2:443")
        assertEquals("162.159.198.2:443", AetherSidecar.cleanH2Peer(clean, "h2", AetherSettings()))
        assertNull(AetherSidecar.cleanH2Peer(clean, "h3", AetherSettings()))
        assertNull(AetherSidecar.cleanH2Peer(clean, "h2", AetherSettings(endpoint = "162.159.198.9:443")))
        assertNull(AetherSidecar.cleanH2Peer(JSONObject().put(AetherSidecar.CLEAN_H2_PEER, "10.1.1.1:443"), "h2", AetherSettings()))
    }

    @Test fun iranHelloSplitIsBoundedPassesTheGateAndIsOrderedByOperator() {
        val p = VlessProfile(id = "x", name = "x", address = "x.example.workers.dev", port = 443,
            uuid = "11111111-1111-1111-1111-111111111111", transport = "xhttp", security = "tls",
            sni = "x.example.workers.dev", host = "x.example.workers.dev", path = "/x", fingerprint = "chrome")
        val split = RecoveryProfiles.BUILT_IN.single { it.profileId == IranHelloSplit.PROFILE_ID }
        val deep = RecoveryProfiles.BUILT_IN.single { it.profileId == IranHelloSplit.DEEP_PROFILE_ID }
        for (rp in listOf(split, deep)) {
            val d = rp.derive(p)!!
            assertTrue(rp.key, RecoverySecurityGate.check(p, d).passed)
            assertEquals("ALPN is untouched, so xhttp/gRPC keep h2", p.alpn, d.alpn)
        }
        assertEquals("unsafe", split.derive(p)!!.fingerprint)
        // The cut at byte 104 is UAC's exact mask: empty record, 104 bytes, then 1-byte pieces up to 11 splits.
        assertTrue(IranHelloSplit.MASK.contains("\"lengths\":[\"0\",\"104\",\"1\"]"))
        assertTrue(IranHelloSplit.MASK.contains("\"maxSplit\":\"11\""))
        assertEquals(0.5, NetworkFirewalls.bias(NetworkFirewalls.Firewall.MCI, split)!!, 1e-9)
        assertNull("other fragments stay skipped on Hamrah-e-Aval", NetworkFirewalls.bias(NetworkFirewalls.Firewall.MCI, deep))
        val echFirst = RecoveryProfiles.BUILT_IN.single { it.profileId == "ech-cloudflare" }
        fun score(f: NetworkFirewalls.Firewall, rp: com.example.vpn.connectivity.RecoveryProfile) = rp.confidence + NetworkFirewalls.bias(f, rp)!!
        assertTrue(score(NetworkFirewalls.Firewall.MCI, split) > score(NetworkFirewalls.Firewall.MCI, echFirst))
        assertTrue(score(NetworkFirewalls.Firewall.IRANCELL, split) < score(NetworkFirewalls.Firewall.IRANCELL, echFirst))
    }
}
