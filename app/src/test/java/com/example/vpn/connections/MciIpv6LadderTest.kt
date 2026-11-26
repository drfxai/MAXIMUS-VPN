package com.example.vpn.connections

import com.example.data.model.VlessProfile
import com.example.vpn.connectivity.NetworkFirewalls
import com.example.vpn.engine.ProfileExtras
import com.example.vpn.sidecar.AetherSettings
import com.example.vpn.sidecar.AetherSidecar
import com.example.vpn.sidecar.ServerlessSidecar
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * patterniha (2026-11): Hamrah-e-Aval's IPv4 is a whitelist and its IPv6 a blacklist. Aether there starts with WARP
 * over IPv6 (WireGuard, then MASQUE H2), remembers WireGuard/H3/H2 endpoints with family and fingerprint, and
 * Serverless tries its oob config first; Irancell keeps fragA, fragB, oob.
 */
class MciIpv6LadderTest {
    private val store = mutableMapOf<String, String>()
    private val mci = "cell:43211"
    private val irancell = "cell:43235"

    @Before fun setUp() {
        store.clear()
        CleanH2PeerMemory.read = { store[it] }
        CleanH2PeerMemory.write = { k, v -> if (v == null) store.remove(k) else store[k] = v }
    }

    @After fun tearDown() {
        CleanH2PeerMemory.read = { null }
        CleanH2PeerMemory.write = { _, _ -> }
    }

    private fun names(list: List<VlessProfile>): List<String> = list.map { c ->
        ProfileExtras.read(c).let {
            when {
                it.optString(AetherSidecar.CLEAN_H2_PEER).isNotBlank() -> ConnectionEngineRunner.CLEAN_H2
                it.optString(AetherSidecar.CLEAN_PEER).isNotBlank() && it.optString("carrier") == "h3" -> ConnectionEngineRunner.CLEAN_H3
                it.optString(AetherSidecar.CLEAN_PEER).isNotBlank() -> ConnectionEngineRunner.CLEAN_WG
                it.optBoolean(AetherSidecar.SNI_SPLIT) -> ConnectionEngineRunner.H2_SPLIT
                it.optBoolean(ConnectionEngineRunner.V6_FIRST) -> it.optString("carrier") + "-v6"
                else -> it.optString("carrier")
            }
        }
    }

    @Test fun mciLadderStartsWithWireGuardThenH2OverIpv6() {
        val variants = ConnectionEngineRunner.operatorVariants(ConnectionEngineRunner.candidates(AetherSidecar.profile("auto", "auto")), irancell = false, mci = true)
        val methods = names(variants)
        assertEquals(listOf("wireguard-v6", "h2-v6", "h2", "h2-split", "h3", "wireguard"),
            ConnectionEngineRunner.order(methods, null, false, emptySet(), mci = true))
        // The method names match the runner's constants.
        assertTrue(ConnectionEngineRunner.WIREGUARD_V6 in methods && ConnectionEngineRunner.H2_V6 in methods)
        // A verified method still wins; a method that failed here goes last.
        assertEquals("h2", ConnectionEngineRunner.order(methods, "h2", false, emptySet(), mci = true).first())
        assertEquals(listOf("h2-v6", "h2", "h2-split", "h3", "wireguard", "wireguard-v6"),
            ConnectionEngineRunner.order(methods, null, false, setOf("wireguard-v6"), mci = true))
        // Remembered endpoints go before every scan.
        assertEquals(listOf("wireguard-clean", "wireguard-v6"),
            ConnectionEngineRunner.order(listOf("wireguard-v6", "wireguard-clean"), null, false, emptySet(), mci = true))
    }

    @Test fun ipv6FirstStepsOnlyWhenTheFamilyIsAutoAndNotForRelaysOrPinnedEndpoints() {
        fun v6(profile: VlessProfile, ipv4Only: Boolean = false) = ConnectionEngineRunner.operatorVariants(
            ConnectionEngineRunner.candidates(profile, ipv4Only = ipv4Only), irancell = false, mci = true)
            .count { ProfileExtras.read(it).optBoolean(ConnectionEngineRunner.V6_FIRST) }
        assertEquals(2, v6(AetherSidecar.profile("auto", "auto")))
        assertEquals("measured IPv4-only link", 0, v6(AetherSidecar.profile("auto", "auto"), ipv4Only = true))
        assertEquals("the user chose IPv4", 0, v6(AetherSidecar.profile("auto", "ipv4")))
        assertEquals("the user chose IPv6: already IPv6, no duplicate", 0, v6(AetherSidecar.profile("auto", "ipv6")))
        assertEquals(0, v6(AetherSidecar.profile("auto", "auto", advanced = AetherSettings(tor = "reverse"))))
        assertEquals(0, v6(AetherSidecar.profile("auto", "auto", advanced = AetherSettings(endpoint = "162.159.192.1:2408"))))
        // Other networks never get them.
        assertEquals(0, ConnectionEngineRunner.operatorVariants(ConnectionEngineRunner.candidates(AetherSidecar.profile("auto", "auto")),
            irancell = true, mci = false).count { ProfileExtras.read(it).optBoolean(ConnectionEngineRunner.V6_FIRST) })
        assertEquals(ConnectionEngineRunner.candidates(AetherSidecar.profile("auto", "auto")),
            ConnectionEngineRunner.operatorVariants(ConnectionEngineRunner.candidates(AetherSidecar.profile("auto", "auto")), irancell = false, mci = false))
        // The H2 IPv6 step sends the full ClientHello; both steps get short windows.
        val h2v6 = ConnectionEngineRunner.operatorVariants(ConnectionEngineRunner.candidates(AetherSidecar.profile("auto", "auto")), false, true)
            .map { ProfileExtras.read(it) }.first { it.optBoolean(ConnectionEngineRunner.V6_FIRST) && it.optString("carrier") == "h2" }
        assertFalse(h2v6.optBoolean("fragment", true))
        assertEquals("ipv6", h2v6.optString("family"))
        assertTrue(ConnectionEngineRunner.V6_FIRST_BUDGET_MS.getValue("wireguard") < 180_000L)
        assertTrue(ConnectionEngineRunner.V6_FIRST_BUDGET_MS.getValue("h2") < 180_000L)
    }

    @Test fun peersRememberCarrierFamilyAndFingerprintAndOldEntriesReadAsH2() {
        CleanH2PeerMemory.remember(mci, "[2606:4700:d0::a]:2408", carrier = "wireguard", fingerprint = "chrome", now = 1_000)
        CleanH2PeerMemory.remember(mci, "162.159.198.2:443", carrier = "h3", fingerprint = "firefox", now = 2_000)
        CleanH2PeerMemory.remember(mci, "[2606:4700:103::2]:443", carrier = "h2", fingerprint = "chrome", now = 3_000)
        val peers = CleanH2PeerMemory.peers(mci, now = 4_000)
        val wg = peers.single { it.carrier == "wireguard" }
        assertEquals("ipv6", wg.family); assertEquals("WireGuard sends no TLS", "", wg.fingerprint)
        val h3 = peers.single { it.carrier == "h3" }
        assertEquals("ipv4", h3.family); assertEquals("firefox", h3.fingerprint)
        assertEquals("ipv6", peers.single { it.carrier == "h2" }.family)
        assertEquals(listOf("[2606:4700:103::2]:443"), CleanH2PeerMemory.peers(mci, now = 4_000, carrier = "h2").map { it.address })
        // Round trip through storage.
        assertEquals(peers, store.values.single().split(';').mapNotNull(CleanH2PeerMemory.Peer::decode))
        // An entry saved before carriers existed reads as H2.
        assertEquals("h2", CleanH2PeerMemory.Peer.decode("162.159.198.1:443,0,5,,0")!!.carrier)
        assertNull(CleanH2PeerMemory.Peer.decode("162.159.198.1:443,0,5,,0,quic"))
        // The cap is per carrier, and forgetting is per carrier.
        for (i in 1..5) CleanH2PeerMemory.remember(mci, "162.159.198.${10 + i}:443", carrier = "h2", now = 5_000L + i)
        assertEquals(CleanH2PeerMemory.MAX_PEERS, CleanH2PeerMemory.peers(mci, now = 6_000, carrier = "h2").size)
        assertEquals(1, CleanH2PeerMemory.peers(mci, now = 6_000, carrier = "wireguard").size)
        CleanH2PeerMemory.forget(mci, "162.159.198.2:443", "h2")
        assertEquals(1, CleanH2PeerMemory.peers(mci, now = 6_000, carrier = "h3").size)
        CleanH2PeerMemory.forget(mci, "162.159.198.2:443", "h3")
        assertTrue(CleanH2PeerMemory.peers(mci, now = 6_000, carrier = "h3").isEmpty())
    }

    @Test fun rememberedUdpEndpointsBecomeShortNoScanCandidates() {
        val variants = ConnectionEngineRunner.operatorVariants(ConnectionEngineRunner.candidates(AetherSidecar.profile("auto", "auto")), irancell = false, mci = true)
        val peers = listOf(
            CleanH2PeerMemory.Peer("[2606:4700:d0::a]:2408", at = 1, carrier = "wireguard"),
            CleanH2PeerMemory.Peer("162.159.192.7:2408", at = 1, carrier = "wireguard"),
            CleanH2PeerMemory.Peer("162.159.198.2:443", at = 1, carrier = "h3", fingerprint = "chrome"),
            CleanH2PeerMemory.Peer("162.159.198.9:443", at = 1, carrier = "h2"))
        val udp = ConnectionEngineRunner.cleanUdpCandidates(variants, peers, ipv4Only = false).map { ProfileExtras.read(it) }
        assertEquals(listOf("h3", "wireguard"), udp.map { it.optString("carrier") })
        assertEquals("162.159.198.2:443", udp[0].optString(AetherSidecar.CLEAN_PEER))
        assertEquals("[2606:4700:d0::a]:2408", udp[1].optString(AetherSidecar.CLEAN_PEER))
        assertEquals("ipv6", udp[1].optString("family"))
        assertFalse("never the IPv6-first probe as base", udp[1].optBoolean(ConnectionEngineRunner.V6_FIRST))
        // IPv4-only link: the IPv6 endpoint is left out.
        val v4 = ConnectionEngineRunner.cleanUdpCandidates(variants, peers, ipv4Only = true).map { ProfileExtras.read(it) }
        assertEquals("162.159.192.7:2408", v4.single { it.optString("carrier") == "wireguard" }.optString(AetherSidecar.CLEAN_PEER))
        // H2 peers only feed the H2 list, UDP peers only the UDP one.
        assertEquals(listOf("162.159.198.9:443"), ConnectionEngineRunner.cleanH2Candidates(variants, peers, false)
            .map { ProfileExtras.read(it).optString(AetherSidecar.CLEAN_H2_PEER) })
        // A user endpoint means no remembered one.
        val own = ConnectionEngineRunner.candidates(AetherSidecar.profile("auto", "auto", advanced = AetherSettings(endpoint = "162.159.192.1:2408")))
        assertTrue(ConnectionEngineRunner.cleanUdpCandidates(own, peers, false).isEmpty())
        // The engine dials the remembered endpoint with --peer only on H3 and WireGuard.
        val e = JSONObject().put(AetherSidecar.CLEAN_PEER, "162.159.192.7:2408")
        assertEquals("162.159.192.7:2408", AetherSidecar.cleanPeer(e, "wireguard", AetherSettings()))
        assertEquals("162.159.192.7:2408", AetherSidecar.cleanPeer(e, "h3", AetherSettings()))
        assertNull(AetherSidecar.cleanPeer(e, "h2", AetherSettings()))
        assertNull(AetherSidecar.cleanPeer(e, "wireguard", AetherSettings(endpoint = "162.159.192.1:2408")))
    }

    @Test fun peersThatWorkedWithThisFingerprintComeFirst() {
        val variants = ConnectionEngineRunner.candidates(AetherSidecar.profile("auto", "auto", advanced = AetherSettings(fingerprint = "firefox")))
        val peers = listOf(
            CleanH2PeerMemory.Peer("162.159.198.1:443", at = 1, carrier = "h2", fingerprint = "chrome"),
            CleanH2PeerMemory.Peer("162.159.198.2:443", at = 1, carrier = "h2", fingerprint = "firefox"))
        val clean = ConnectionEngineRunner.cleanH2Candidates(variants, peers, false).map { ProfileExtras.read(it) }
        assertEquals(listOf("162.159.198.2:443", "162.159.198.1:443"), clean.map { it.optString(AetherSidecar.CLEAN_H2_PEER) })
        assertTrue("the user's fingerprint is sent, not the remembered one", clean.all { AetherSettings.read(it).fingerprint == "firefox" })
    }

    @Test fun aetherLastConnectionFilesForEveryCarrier() {
        assertEquals("162.159.198.2:443", AetherSidecar.parseLastConnection("peer = \"162.159.198.2:443\"\ncarrier = \"masque-h3\"\n", "masque-h3"))
        assertEquals("[2606:4700:d0::a]:2408", AetherSidecar.parseLastConnection("peer = \"[2606:4700:d0::a]:2408\"\ncarrier = \"wireguard\"\n", "wireguard"))
        assertNull(AetherSidecar.parseLastConnection("peer = \"162.159.198.2:443\"\ncarrier = \"masque-h2\"\n", "wireguard"))
    }

    @Test fun serverlessAutoOrderFollowsTheFirewall() {
        fun variants(firewall: NetworkFirewalls.Firewall) = ConnectionEngineRunner.candidates(ServerlessSidecar.profile(), firewall = firewall)
            .map { ProfileExtras.read(it).optString("variant") }
        assertEquals(listOf("oob", "fragA", "fragB"), variants(NetworkFirewalls.Firewall.MCI))
        assertEquals(listOf("fragA", "fragB", "oob"), variants(NetworkFirewalls.Firewall.IRANCELL))
        assertEquals(listOf("fragA", "fragB", "oob"), variants(NetworkFirewalls.Firewall.OTHER))
        assertEquals(listOf("fragA", "fragB", "oob"), variants(NetworkFirewalls.Firewall.RIGHTEL))
        assertEquals(listOf("fragA", "fragB", "oob"), ConnectionEngineRunner.candidates(ServerlessSidecar.profile()).map { ProfileExtras.read(it).optString("variant") })
        // The order keeps the declared order through the runner's sort (no Aether ladder applies to Serverless).
        assertEquals(listOf("oob", "fragA", "fragB"), ConnectionEngineRunner.order(listOf("oob", "fragA", "fragB"), null, false, emptySet()))
        // A variant the user picked is never reordered or expanded.
        assertEquals(listOf("fragB"), ConnectionEngineRunner.candidates(ServerlessSidecar.profile("fragB"), firewall = NetworkFirewalls.Firewall.MCI)
            .map { ProfileExtras.read(it).optString("variant") })
        assertEquals(NetworkFirewalls.Firewall.MCI, NetworkFirewalls.detect(mci))
        assertEquals(NetworkFirewalls.Firewall.IRANCELL, NetworkFirewalls.detect(irancell))
    }
}
