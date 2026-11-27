package com.example.vpn.connections

import com.example.vpn.sidecar.AetherSidecar
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Build 181 3.2: MASQUE SNI pre-check (≤10 s), pins kept, failed names skipped per network. */
class AetherSniProbeTest {
    private val store = mutableMapOf<String, String>()

    @Before fun setUp() {
        store.clear()
        BuiltinMethodMemory.read = { store[it] }
        BuiltinMethodMemory.write = { k, v -> if (v == null) store.remove(k) else store[k] = v }
    }

    @After fun tearDown() {
        BuiltinMethodMemory.read = { null }
        BuiltinMethodMemory.write = { _, _ -> }
        AetherSniProbe.handshake = AetherSniProbe::probe
    }

    private fun result(c: AetherSniProbe.Candidate, o: AetherSniProbe.Outcome) = AetherSniProbe.Result(c, o, 10)

    @Test fun candidatesCoverIrancellNamesAndZeroTrust() {
        val c = AetherSniProbe.candidates("abcdefghijkl.com")
        assertTrue(c.any { it.sni == "www.speedtest.net" && it.host == "162.159.198.1" })
        assertTrue(c.any { it.sni == "x.cloudflare.com" && it.host == "162.159.192.1" })
        assertTrue(c.any { it.zeroTrust && it.host.startsWith("162.159.197.") })
        assertTrue(c.none { it.sni.endsWith("cloudflareclient.com") })
        assertTrue(AetherSniProbe.validName(AetherSniProbe.randomName()))
    }

    @Test fun pinnedBeatsAnsweredAndZeroTrustIsNeverPicked() {
        val c = AetherSniProbe.candidates("abcdefghijkl.com")
        val speed = c.first { it.sni == "www.speedtest.net" }
        val xcf = c.first { it.sni == "x.cloudflare.com" }
        val zt = c.first { it.zeroTrust }
        val results = c.map { result(it, AetherSniProbe.Outcome.DROPPED) }.toMutableList()
        results.replaceAll { if (it.candidate == speed) result(speed, AetherSniProbe.Outcome.OTHER_CERT) else it }
        assertEquals(speed, AetherSniProbe.choose(results, c))
        results.replaceAll { if (it.candidate == xcf) result(xcf, AetherSniProbe.Outcome.PINNED) else it }
        assertEquals(xcf, AetherSniProbe.choose(results, c))
        val onlyZt = c.map { result(it, if (it == zt) AetherSniProbe.Outcome.PINNED else AetherSniProbe.Outcome.DROPPED) }
        assertNull(AetherSniProbe.choose(onlyZt, c))
    }

    @Test fun failedNamesAreSkippedAndRandomCountsAsOne() {
        val c = AetherSniProbe.candidates("abcdefghijkl.com")
        val all = c.map { result(it, AetherSniProbe.Outcome.OTHER_CERT) }
        assertEquals("abcdefghijkl.com", AetherSniProbe.choose(all, c)!!.sni)
        AetherSniProbe.recordFailure("cell", "abcdefghijkl.com")
        assertEquals(setOf(AetherSniProbe.RANDOM), AetherSniProbe.failed("cell"))
        assertEquals("www.speedtest.net", AetherSniProbe.choose(all, c, AetherSniProbe.failed("cell"))!!.sni)
        AetherSniProbe.recordSuccess("cell", "www.speedtest.net")
        assertEquals("www.speedtest.net", AetherSniProbe.remembered("cell"))
        assertTrue(AetherSniProbe.failed("cell").isEmpty())
    }

    @Test fun precheckLogsEveryProbeAndStaysWithinItsWindow() {
        AetherSniProbe.handshake = { c, _ ->
            if (c.sni == "x.cloudflare.com") result(c, AetherSniProbe.Outcome.ALERT)
            else { Thread.sleep(5_000); result(c, AetherSniProbe.Outcome.DROPPED) }
        }
        val lines = mutableListOf<String>()
        val started = System.currentTimeMillis()
        val pick = AetherSniProbe.precheck("cell", timeoutMs = 1_000) { lines += it }
        assertTrue(System.currentTimeMillis() - started < 4_000)
        assertEquals("x.cloudflare.com", pick)
        assertTrue(lines.any { it.contains("SNI x.cloudflare.com @ 162.159.192.x:443") })
        assertTrue(lines.any { it.contains("Zero Trust range") })
        // not remembered until Aether carried traffic with it
        assertNull(AetherSniProbe.remembered("cell"))
    }

    @Test fun pinsAreAethersMasquePins() {
        assertEquals(3, AetherSniProbe.MASQUE_PINS.size)
        assertTrue(AetherSniProbe.pinned("EB591B36AB26BA617E98371918C10BCDEAE3742DB6E76543F94BE524DCE1D555"))
        assertFalse(AetherSniProbe.pinned("d692dbb706e05d36c24d70efee67ffcf70098d6f8bcc42acf155c73d61a72fe3"))
    }

    @Test fun pickedNameReachesOnlyMasqueCarriers() {
        val h2 = JSONObject().put(AetherSidecar.MASQUE_SNI, "www.speedtest.net")
        assertEquals("www.speedtest.net", AetherSidecar.masqueSni(h2, "h2"))
        assertNull(AetherSidecar.masqueSni(h2, "wireguard"))
        assertNull(AetherSidecar.masqueSni(JSONObject().put(AetherSidecar.MASQUE_SNI, "1.1.1.1"), "h2"))
        assertTrue(ConnectionEngineRunner.masqueSniPrecheck(AetherSidecar.ID, false, true, false, "auto"))
        assertFalse(ConnectionEngineRunner.masqueSniPrecheck(AetherSidecar.ID, false, false, false, "auto"))
        assertFalse(ConnectionEngineRunner.masqueSniPrecheck(AetherSidecar.ID, true, true, true, "auto"))
        assertFalse(ConnectionEngineRunner.masqueSniPrecheck(AetherSidecar.ID, false, true, true, "wireguard"))
    }
}
