package com.example.vpn.connectivity

import com.example.vpn.connectivity.FirewallBehaviour.Outcome
import com.example.vpn.connectivity.FirewallBehaviour.Outcome.ANSWERED
import com.example.vpn.connectivity.FirewallBehaviour.Outcome.CUT
import com.example.vpn.connectivity.FirewallBehaviour.Outcome.NOT_RUN
import com.example.vpn.connectivity.FirewallBehaviour.Outcome.UNREACHABLE
import com.example.vpn.connectivity.FirewallBehaviour.Probe
import com.example.vpn.connectivity.FirewallBehaviour.Verdict
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/** Firewall type by behaviour: the classifier, the per-network store, the hello bytes and how detection uses them. */
class FirewallBehaviourTest {
    @After fun reset() { NetworkFirewalls.behaviour = null }

    private fun round(chrome4: Outcome, split4: Outcome, chrome6: Outcome) =
        FirewallBehaviour.classify(mapOf(Probe.V4_CHROME to chrome4, Probe.V4_SPLIT to split4, Probe.V6_CHROME to chrome6))

    @Test fun irancellPattern_chromeCutSplitPasses() {
        for (v6 in listOf(NOT_RUN, CUT, UNREACHABLE)) {
            val c = round(CUT, ANSWERED, v6)
            assertEquals(Verdict.IRANCELL_LIKE, c.verdict)
            assertTrue(c.confident)
        }
    }

    @Test fun mciPattern_v4CutInEveryShape_v6Passes() {
        val c = round(CUT, CUT, ANSWERED)
        assertEquals(Verdict.MCI_LIKE, c.verdict)
        assertTrue(c.confident)
    }

    @Test fun openNetwork_isUnknown() {
        for (split in Outcome.entries) for (v6 in Outcome.entries) {
            val c = round(ANSWERED, split, v6)
            assertEquals(Verdict.UNKNOWN, c.verdict)
            assertFalse(c.confident)
        }
    }

    @Test fun ambiguousRounds_areNotConfident() {
        // No positive control on the same moment: a dead link or a Cloudflare outage must not decide anything.
        assertEquals(Verdict.UNKNOWN, round(CUT, CUT, NOT_RUN).verdict)
        assertEquals(Verdict.UNKNOWN, round(CUT, CUT, CUT).verdict)
        assertEquals(Verdict.UNKNOWN, round(UNREACHABLE, UNREACHABLE, ANSWERED).verdict)
        assertEquals(Verdict.UNKNOWN, round(UNREACHABLE, ANSWERED, NOT_RUN).verdict)
        assertEquals(Verdict.UNKNOWN, round(NOT_RUN, NOT_RUN, ANSWERED).verdict)
        // Split passes on v4 but Chrome passes on v6 too: mixed, keep the network code.
        assertEquals(Verdict.UNKNOWN, round(CUT, ANSWERED, ANSWERED).verdict)
        // TCP never opened for the split hello: no evidence about its shape.
        assertEquals(Verdict.UNKNOWN, round(CUT, UNREACHABLE, ANSWERED).verdict)
        assertFalse(round(CUT, CUT, NOT_RUN).confident)
    }

    @Test fun everyPossibleRound_confidentOnlyWithAnAnswer() {
        for (a in Outcome.entries) for (b in Outcome.entries) for (c in Outcome.entries) {
            val r = round(a, b, c)
            if (r.confident) {
                assertTrue("a confident round holds an answered handshake", ANSWERED in listOf(a, b, c))
                assertTrue("and a cut one", CUT in listOf(a, b, c))
                assertTrue(r.verdict != Verdict.UNKNOWN)
            } else assertEquals(Verdict.UNKNOWN, r.verdict)
        }
    }

    private class Clock(var now: Long)

    private fun entry(identity: String, coarse: String, at: Long, verdict: Verdict, confident: Boolean = true) =
        FirewallBehaviour.Entry(identity, coarse, at, verdict, confident, "test",
            mapOf(Probe.V4_CHROME to CUT, Probe.V4_SPLIT to ANSWERED, Probe.V6_CHROME to NOT_RUN))

    @Test fun store_appliesOnlyConfidentFreshSameNetwork() {
        val clock = Clock(1_000_000)
        var saved: String? = null
        val store = FirewallBehaviour.Store({ saved }, { saved = it }, { clock.now })
        assertTrue(store.due("wifi:aaaa"))
        store.record(entry("wifi:aaaa", "wifi", clock.now, Verdict.IRANCELL_LIKE))
        assertFalse(store.due("wifi:aaaa"))
        assertTrue("another network needs its own round", store.due("wifi:bbbb"))
        assertEquals(NetworkFirewalls.Firewall.IRANCELL, store.confident("wifi:aaaa"))
        assertEquals("the coarse key other code passes matches too", NetworkFirewalls.Firewall.IRANCELL, store.confident("wifi"))
        assertNull(store.confident("cell:43211"))
        clock.now += FirewallBehaviour.TTL_MS
        assertNull("expired after a day", store.confident("wifi"))
        assertTrue(store.due("wifi:aaaa"))
    }

    @Test fun store_unknownOrUnconfidentNeverApplies() {
        val store = FirewallBehaviour.Store({ null }, { }, { 5L })
        store.record(entry("cell:43235", "cell:43235", 5L, Verdict.MCI_LIKE, confident = false))
        assertNull(store.confident("cell:43235"))
        store.record(entry("cell:43235", "cell:43235", 5L, Verdict.UNKNOWN, confident = true))
        assertNull(store.confident("cell:43235"))
    }

    @Test fun store_networkChangeForgetsAndPersists() {
        var saved: String? = null
        val store = FirewallBehaviour.Store({ saved }, { saved = it }, { 10L })
        store.record(entry("wifi:aaaa", "wifi", 10L, Verdict.MCI_LIKE))
        val reloaded = FirewallBehaviour.Store({ saved }, { saved = it }, { 10L })
        assertEquals(NetworkFirewalls.Firewall.MCI, reloaded.confident("wifi"))
        assertEquals(CUT, reloaded.latest!!.outcomes[Probe.V4_CHROME])
        reloaded.onNetwork("wifi:aaaa")
        assertEquals(NetworkFirewalls.Firewall.MCI, reloaded.confident("wifi"))
        // Another Wi-Fi shares the coarse key "wifi": the old result must not follow the phone there.
        reloaded.onNetwork("wifi:bbbb")
        assertNull(reloaded.confident("wifi"))
        assertNull(reloaded.latest)
        assertNull(FirewallBehaviour.Store({ saved }, { }, { 10L }).latest)
    }

    @Test fun detection_behaviourOverridesCodeOnlyWhenSet() {
        assertEquals(NetworkFirewalls.Firewall.OTHER, NetworkFirewalls.detect("wifi"))
        assertEquals(NetworkFirewalls.Firewall.MCI, NetworkFirewalls.detect("cell:43211"))
        val store = FirewallBehaviour.Store({ null }, { }, { 7L })
        NetworkFirewalls.behaviour = store::confident
        assertEquals("no round yet: network code", NetworkFirewalls.Firewall.MCI, NetworkFirewalls.detect("cell:43211"))
        assertEquals(NetworkFirewalls.Source.NETWORK_CODE, NetworkFirewalls.detection("cell:43211").source)
        assertEquals(NetworkFirewalls.Source.NONE, NetworkFirewalls.detection("wifi").source)
        // patterniha: in some regions a Hamrah-e-Aval SIM sits behind the Irancell firewall.
        store.record(entry("cell:43211", "cell:43211", 7L, Verdict.IRANCELL_LIKE))
        val d = NetworkFirewalls.detection("cell:43211")
        assertEquals(NetworkFirewalls.Firewall.IRANCELL, d.firewall)
        assertEquals(NetworkFirewalls.Source.BEHAVIOUR, d.source)
        assertEquals(NetworkFirewalls.Firewall.MCI, d.byCode)
        // Wi-Fi on a fixed ISP that filters like Hamrah-e-Aval.
        store.record(entry("wifi:cccc", "wifi", 7L, Verdict.MCI_LIKE))
        assertEquals(NetworkFirewalls.Firewall.MCI, NetworkFirewalls.detect("wifi"))
        assertEquals(NetworkFirewalls.Firewall.MCI, NetworkFirewalls.detect("wifi:cccc"))
        assertEquals("the other SIM's network keeps its code", NetworkFirewalls.Firewall.IRANCELL, NetworkFirewalls.detect("cell:43235"))
        // An unconfident round falls back to the network code.
        store.record(entry("cell:43211", "cell:43211", 7L, Verdict.IRANCELL_LIKE, confident = false))
        assertEquals(NetworkFirewalls.Firewall.MCI, NetworkFirewalls.detect("cell:43211"))
    }

    @Test fun detection_survivesAFailingHook() {
        NetworkFirewalls.behaviour = { error("boom") }
        assertEquals(NetworkFirewalls.Firewall.IRANCELL, NetworkFirewalls.detect("cell:43235"))
    }

    // ---- hello bytes ----

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)

    /** Extension types of one ClientHello handshake message. */
    private fun extensions(hs: ByteArray): List<Pair<Int, ByteArray>> {
        assertEquals(1, hs[0].toInt())
        val len = ((hs[1].toInt() and 0xff) shl 16) or u16(hs, 2)
        assertEquals(hs.size - 4, len)
        var p = 4 + 2 + 32
        p += 1 + (hs[p].toInt() and 0xff)
        p += 2 + u16(hs, p)
        p += 1 + (hs[p].toInt() and 0xff)
        val end = p + 2 + u16(hs, p)
        assertEquals(hs.size, end)
        p += 2
        val out = mutableListOf<Pair<Int, ByteArray>>()
        while (p < end) {
            val t = u16(hs, p); val l = u16(hs, p + 2)
            out += t to hs.copyOfRange(p + 4, p + 4 + l)
            p += 4 + l
        }
        assertEquals(end, p)
        return out
    }

    /** The handshake bytes carried by a list of TLS records, checking each record header. */
    private fun unwrap(records: List<ByteArray>): ByteArray = records.fold(ByteArray(0)) { acc, r ->
        assertEquals(22, r[0].toInt())
        assertEquals(0x0301, u16(r, 1))
        assertEquals(r.size - 5, u16(r, 3))
        acc + r.copyOfRange(5, r.size)
    }

    private fun isGrease(t: Int) = (t and 0x0f0f) == 0x0a0a && (t ushr 8) == (t and 0xff)

    @Test fun chromeHello_isBigWithGreasePqAndServerName() {
        repeat(20) { seed ->
            val random = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(seed.toLong()) }
            val record = FirewallBehaviour.chromeHello("abcdefghij.workers.dev", random)
            assertTrue("Chrome-sized: ${record.size}", record.size in 1_600..2_100)
            val exts = extensions(unwrap(listOf(record)))
            val types = exts.map { it.first }
            assertTrue(isGrease(types.first()) && isGrease(types.last()))
            assertTrue("GREASE extension types differ", types.first() != types.last())
            assertTrue(0x44cd in types && 0xfe0d in types && 0x001b in types)
            val keyShare = exts.single { it.first == 0x0033 }.second
            assertEquals(keyShare.size - 2, u16(keyShare, 0))
            assertTrue("X25519MLKEM768 share", u16(keyShare, 2 + 4 + 1) == 0x11ec)
            assertEquals(1216, u16(keyShare, 2 + 4 + 1 + 2))
            val sni = String(exts.single { it.first == 0 }.second.copyOfRange(5, 5 + 22), Charsets.US_ASCII)
            assertEquals("abcdefghij.workers.dev", sni)
        }
    }

    @Test fun mlKemKey_coefficientsBelowQ() {
        val key = FirewallBehaviour.mlKem768Key(SecureRandom())
        assertEquals(1184, key.size)
        for (i in 0 until 384) {
            val b0 = key[3 * i].toInt() and 0xff; val b1 = key[3 * i + 1].toInt() and 0xff; val b2 = key[3 * i + 2].toInt() and 0xff
            assertTrue((b0 or ((b1 and 0x0f) shl 8)) < 3329)
            assertTrue(((b1 ushr 4) or (b2 shl 4)) < 3329)
        }
    }

    @Test fun splitSmallHello_isSemiPythonAndSplitIntoTwoRecords() {
        val records = FirewallBehaviour.splitSmallHello("abcdefghij.workers.dev", firstRecord = 6)
        assertEquals(2, records.size)
        assertEquals(6 + 5, records[0].size)
        val hs = unwrap(records)
        assertTrue("small: ${hs.size}", hs.size in 180..320)
        val exts = extensions(hs)
        assertTrue("no GREASE", exts.none { isGrease(it.first) })
        // Cipher suites: the 3 TLS 1.3 suites and the 10 semi-python ECDHE suites, no GREASE.
        val sessionLen = hs[4 + 2 + 32].toInt() and 0xff
        val cs = 4 + 2 + 32 + 1 + sessionLen
        assertEquals(13 * 2, u16(hs, cs))
        assertEquals(0x1302, u16(hs, cs + 2))
        assertEquals(listOf("http/1.1"), listOf(String(exts.single { it.first == 0x0010 }.second.copyOfRange(3, 11))))
    }

    @Test fun records_splitOnlyInsideTheMessage() {
        val hs = ByteArray(100) { it.toByte() }
        assertEquals(1, FirewallBehaviour.records(hs, 0).size)
        assertEquals(1, FirewallBehaviour.records(hs, 100).size)
        assertEquals(2, FirewallBehaviour.records(hs, 32).size)
        assertTrue(unwrap(FirewallBehaviour.records(hs, 32)).contentEquals(hs))
    }

    @Test fun readReply_tellsTlsFromInterference() {
        assertEquals(ANSWERED, FirewallBehaviour.readReply(byteArrayOf(22, 3, 3), 3))
        assertEquals("a TLS alert is still the server", ANSWERED, FirewallBehaviour.readReply(byteArrayOf(21, 3, 1), 3))
        assertNull("one byte: not yet", FirewallBehaviour.readReply(byteArrayOf(22, 0, 0), 1))
        assertNull(FirewallBehaviour.readReply(ByteArray(3), 0))
        assertEquals("an HTTP block page", CUT, FirewallBehaviour.readReply("HTT".toByteArray(), 3))
        assertEquals(CUT, FirewallBehaviour.readReply(byteArrayOf(22, 9, 9), 3))
    }

    @Test fun probeName_isAnUnlistedWorkersName() {
        val n = FirewallBehaviour.probeServerName()
        assertTrue(Regex("[a-z]{10}\\.workers\\.dev").matches(n))
    }
}
