package com.example.vpn.smartsni

import com.example.vpn.connections.AetherSniProbe
import com.example.vpn.connections.BuiltinMethodMemory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Build 181 Phase 5 (A): Smart SNI pool, bounded probe, per-network memory, rotation and cooldown, manual override. */
class SmartSniTest {
    private val store = mutableMapOf<String, String>()
    private val settings = mutableMapOf<String, String>()

    @Before fun setUp() {
        store.clear(); settings.clear()
        BuiltinMethodMemory.read = { store[it] }
        BuiltinMethodMemory.write = { k, v -> if (v == null) store.remove(k) else store[k] = v }
        SmartSniSettings.read = { settings[it] }
        SmartSniSettings.write = { k, v -> if (v == null) settings.remove(k) else settings[k] = v }
    }

    @After fun tearDown() {
        BuiltinMethodMemory.read = { null }
        BuiltinMethodMemory.write = { _, _ -> }
        SmartSniSettings.read = { null }
        SmartSniSettings.write = { _, _ -> }
        SmartSni.check = SmartSni::liveCheck
        AetherSniProbe.handshake = AetherSniProbe::probe
    }

    private fun p(name: String, v: SmartSni.Verdict, ms: Long = 50) = SmartSni.Probe(name, v, ms)

    @Test fun poolHasLearnedNamesNoBlockedNamesAndNoAddresses() {
        assertTrue(SmartSni.POOL.containsAll(listOf("www.speedtest.net", "x.cloudflare.com", "cloudflare-ech.com")))
        assertTrue(SmartSni.POOL.none { it.endsWith("cloudflareclient.com") })
        assertTrue(SmartSni.POOL.all { SmartSni.validName(it) })
        assertFalse(SmartSni.validName("1.1.1.1"))
        assertFalse(SmartSni.validName("*.example.com"))
        assertTrue(SmartSni.validName(SmartSni.randomName()))
    }

    @Test fun passedBeatsReachedAndDroppedIsNotRemembered() {
        SmartSni.record("cell:43235", listOf(p("www.speedtest.net", SmartSni.Verdict.REACHED, 20),
            p("x.cloudflare.com", SmartSni.Verdict.PASSED, 90), p("cdnjs.cloudflare.com", SmartSni.Verdict.DROPPED)))
        assertEquals(listOf("x.cloudflare.com", "www.speedtest.net"), SmartSni.ranked("cell:43235"))
        assertEquals("x.cloudflare.com", SmartSni.pick("cell:43235"))
        assertTrue(SmartSni.passedHere("cell:43235", "x.cloudflare.com"))
        assertFalse(SmartSni.passedHere("cell:43235", "www.speedtest.net"))
    }

    @Test fun memoryIsPerNetwork() {
        SmartSni.record("cell:43235", listOf(p("www.speedtest.net", SmartSni.Verdict.PASSED)))
        SmartSni.record("cell:43211", listOf(p("x.cloudflare.com", SmartSni.Verdict.PASSED)))
        assertEquals("www.speedtest.net", SmartSni.pick("cell:43235"))
        assertEquals("x.cloudflare.com", SmartSni.pick("cell:43211"))
        assertNull(SmartSni.pick("wifi:abc"))
    }

    @Test fun rotationPutsBlockedNameOnSixHourCooldown() {
        val t0 = 1_000_000L
        SmartSni.record("cell", listOf(p("x.cloudflare.com", SmartSni.Verdict.PASSED, 10), p("www.speedtest.net", SmartSni.Verdict.PASSED, 30),
            p("cdnjs.cloudflare.com", SmartSni.Verdict.REACHED, 5)), now = t0)
        assertEquals("x.cloudflare.com", SmartSni.pick("cell", t0))
        assertEquals("www.speedtest.net", SmartSni.rotate("cell", "x.cloudflare.com", t0 + 1))
        assertEquals("www.speedtest.net", SmartSni.pick("cell", t0 + 2))
        assertTrue("x.cloudflare.com" in SmartSni.cooling("cell", t0 + 2))
        assertEquals("cdnjs.cloudflare.com", SmartSni.rotate("cell", "www.speedtest.net", t0 + 3))
        assertNull(SmartSni.rotate("cell", "cdnjs.cloudflare.com", t0 + 4))
        // the cooldown ends after 6 h (the probe memory too: probe again then)
        assertFalse("x.cloudflare.com" in SmartSni.cooling("cell", t0 + 1 + SmartSni.COOLDOWN_MS))
    }

    @Test fun successMakesANameCurrentAndClearsItsCooldown() {
        SmartSni.record("cell", listOf(p("x.cloudflare.com", SmartSni.Verdict.PASSED, 10), p("www.speedtest.net", SmartSni.Verdict.PASSED, 30)))
        SmartSni.rotate("cell", "x.cloudflare.com")
        SmartSni.recordSuccess("cell", "x.cloudflare.com")
        assertEquals("x.cloudflare.com", SmartSni.pick("cell"))
        assertFalse("x.cloudflare.com" in SmartSni.cooling("cell"))
    }

    @Test fun manualNameWinsAndIsNeverRotatedAway() {
        SmartSni.record("cell", listOf(p("x.cloudflare.com", SmartSni.Verdict.PASSED)))
        assertEquals("not a valid domain name", SmartSniSettings.setManual(SmartSniSettings.MANUAL_SNI, "1.2.3.4"))
        assertNull(SmartSniSettings.setManual(SmartSniSettings.MANUAL_SNI, "My.Example.org"))
        assertEquals("my.example.org", SmartSni.pick("cell"))
        assertEquals("my.example.org", SmartSni.rotate("cell", "my.example.org"))
        assertEquals("my.example.org (manual)", SmartSni.status("cell"))
        SmartSniSettings.setManual(SmartSniSettings.MANUAL_SNI, "")
        assertEquals("x.cloudflare.com", SmartSni.pick("cell"))
    }

    @Test fun smartSniOffPicksNothing() {
        SmartSni.record("cell", listOf(p("x.cloudflare.com", SmartSni.Verdict.PASSED)))
        SmartSniSettings.setFlag(SmartSniSettings.SMART_SNI, false)
        assertNull(SmartSni.pick("cell"))
        assertEquals("off", SmartSni.status("cell"))
        assertNull(AetherSniProbe.precheck("cell") {})
    }

    @Test fun probeRunsThreeAtATimeWithinItsBudget() {
        val running = AtomicInteger(); val peak = AtomicInteger()
        SmartSni.check = { n, _, _ ->
            val now = running.incrementAndGet(); peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            try { Thread.sleep(400) } catch (_: InterruptedException) {}
            running.decrementAndGet()
            SmartSni.Probe(n, SmartSni.Verdict.PASSED, 400)
        }
        val started = System.currentTimeMillis()
        val lines = mutableListOf<String>()
        val r = SmartSni.probe("cell", SmartSni.POOL, listOf("104.16.1.1", "172.64.1.1"), budgetMs = 1_000) { lines += it }
        val took = System.currentTimeMillis() - started
        assertTrue("took $took ms", took < 2_500)
        assertTrue(peak.get() <= SmartSni.PARALLEL)
        assertEquals(SmartSni.POOL.size, r.size)
        assertTrue(r.any { it.verdict == SmartSni.Verdict.SKIPPED || it.verdict == SmartSni.Verdict.DROPPED })
        // names only in logs, never the addresses
        assertTrue(lines.none { it.contains("104.16.") || it.contains("172.64.") })
    }

    @Test fun aetherPrecheckRotatesThroughSmartSniCooldown() {
        AetherSniProbe.recordFailure("cell", "www.speedtest.net")
        assertTrue("www.speedtest.net" in SmartSni.cooling("cell"))
        assertTrue("www.speedtest.net" in AetherSniProbe.failed("cell"))
        val c = AetherSniProbe.candidates("abcdefghijkl.com", learned = listOf("cdnjs.cloudflare.com", "www.speedtest.net"))
        assertTrue(c.any { it.sni == "cdnjs.cloudflare.com" })
        assertEquals(1, c.count { it.sni == "www.speedtest.net" })
    }
}
