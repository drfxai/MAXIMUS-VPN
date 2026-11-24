package com.example.vpn.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Build 171 report, Iranian cellular with Cloudflare's TLS filtered: what Aether should have fallen back to. */
class VerifiedWinnerBookTest {
    private var now = 1_795_459_885_000L
    private var stored: String? = null
    private fun book() = VerifiedWinnerBook({ stored }, { stored = it }, { now })
    private val cell = "cell:43235"

    private fun VerifiedWinnerBook.reportMinutes() {
        // 18:51Z chain 51 KB/s, 18:52Z Lantern 19 KB/s, 18:53Z Serverless 41 KB/s, 19:00Z Tor 10 KB/s.
        recordVerified(cell, "p-chain", "Chain"); recordRate(cell, "p-chain", 51 * 1024L, stalled = false)
        now += 60_000
        recordVerified(cell, "p-radiance", "Lantern"); recordRate(cell, "p-radiance", 19 * 1024L, stalled = false)
        now += 60_000
        recordVerified(cell, "p-serverless", "Serverless"); recordRate(cell, "p-serverless", 41 * 1024L, stalled = false)
        now += 7 * 60_000
        recordVerified(cell, "p-tor", "Tor"); recordRate(cell, "p-tor", 10 * 1024L, stalled = false)
    }

    @Test fun theFastestVerifiedPathOnThisNetworkWinsThenTheNextFastest() {
        val b = book()
        b.reportMinutes()
        now += 5 * 60_000
        assertEquals("p-chain", b.best(cell)?.profileId)
        assertEquals("Chain (51 KB/s)", b.best(cell)?.label())
        assertEquals("p-serverless", b.best(cell, exclude = setOf("p-chain"))?.profileId)
        assertEquals("p-radiance", b.best(cell, eligible = { it != "p-chain" && it != "p-serverless" })?.profileId)
        // Another network learns nothing from this one.
        assertNull(b.best("wifi:home"))
    }

    @Test fun withoutRatesTheMostRecentVerificationWins() {
        val b = book()
        b.recordVerified(cell, "a", "A")
        now += 1_000
        b.recordVerified(cell, "b", "B")
        assertEquals("b", b.best(cell)?.profileId)
        // A measured rate beats an unmeasured, more recent one.
        b.recordRate(cell, "a", 2_000, stalled = false)
        assertEquals("a", b.best(cell)?.profileId)
    }

    @Test fun aStalledDownloadOrAFailureDropsThePathAndAVerificationKeepsItsRate() {
        val b = book()
        b.reportMinutes()
        b.recordRate(cell, "p-chain", null, stalled = true)
        assertEquals("p-serverless", b.best(cell)?.profileId)
        b.forget(cell, "p-serverless")
        assertEquals("p-radiance", b.best(cell)?.profileId)
        // Verified again before its download check ran: the earlier rate still ranks it.
        b.recordVerified(cell, "p-tor", "Tor")
        assertEquals(10 * 1024L, b.best(cell, exclude = setOf("p-radiance"))?.bytesPerSecond)
    }

    @Test fun oldVerificationsExpireAndTheBookSurvivesARestart() {
        val b = book()
        b.reportMinutes()
        assertEquals("p-chain", book().best(cell)?.profileId)
        assertEquals(4, listOf("p-chain", "p-radiance", "p-serverless", "p-tor").count { id ->
            book().best(cell, eligible = { it == id }) != null })
        now += VerifiedWinnerBook.TTL_MS + 1
        assertNull(book().best(cell))
        // A shorter window can be asked for: only Tor was verified in the last three minutes.
        now -= VerifiedWinnerBook.TTL_MS
        assertEquals("p-tor", book().best(cell, maxAgeMs = 3 * 60_000L)?.profileId)
    }

    @Test fun clearForgetsEveryNetwork() {
        val b = book()
        b.reportMinutes()
        b.clear()
        assertNull(b.best(cell))
        assertNull(book().best(cell))
    }
}
