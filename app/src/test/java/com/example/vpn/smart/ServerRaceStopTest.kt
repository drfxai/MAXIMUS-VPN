package com.example.vpn.smart

import com.example.data.model.VlessProfile
import com.example.xray.RealDelayProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * The race must stop the instant the attempt is cancelled. Probing is a blocking thread-pool call, so
 * without this guard a Stop pressed mid-race cannot break in and the connect lock stays held until every
 * remaining batch has been probed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerRaceStopTest {
    private fun node(id: String) = VlessProfile(
        id = id, name = "N$id", address = "203.0.113.$id", port = 443,
        uuid = "11111111-1111-1111-1111-111111111111", security = "reality", sni = "www.example.com",
        publicKey = "pbk$id", shortId = "ab", flow = "xtls-rprx-vision", fingerprint = "chrome"
    )

    private val candidates = listOf(node("1"), node("2"), node("3"))
    private val identity = { p: VlessProfile -> p }

    @Test fun doesNotProbeWhenAlreadyCancelled() {
        val probes = AtomicInteger(0)
        val race = ServerRace(probe = { p, _ -> probes.incrementAndGet(); p.map { RealDelayProbe.Outcome.Failed("blocked") } }, log = {})
        val winner = race.run(candidates, identity, timeoutSec = 1, shouldStop = { true })
        assertNull("a cancelled race returns no winner", winner)
        assertEquals("no batch is probed once the attempt is cancelled", 0, probes.get())
    }

    @Test fun probesNormallyWhenNotCancelled() {
        val probes = AtomicInteger(0)
        val race = ServerRace(probe = { p, _ -> probes.incrementAndGet(); p.map { RealDelayProbe.Outcome.Failed("blocked") } }, log = {})
        val winner = race.run(candidates, identity, timeoutSec = 1, shouldStop = { false })
        assertNull("none carried traffic, so there is no winner", winner)
        assertTrue("at least one batch is probed when the attempt is live", probes.get() >= 1)
    }
}
