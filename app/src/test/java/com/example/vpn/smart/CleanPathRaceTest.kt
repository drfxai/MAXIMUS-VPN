package com.example.vpn.smart

import com.example.data.model.VlessProfile
import com.example.xray.RealDelayProbe
import org.junit.Assert.*
import org.junit.Test

class CleanPathRaceTest {
    private val parent = VlessProfile(id = "p", name = "p", address = "origin.example", port = 8443,
        uuid = "secret", security = "tls", transport = "ws", path = "/unchanged")

    @Test fun aFastEdgeThatFailsTheConfigCannotHideAWorkingAlternative() {
        var round = 0
        val win = CleanPathRace.select(parent, listOf("192.0.2.1", "192.0.2.2"), probe = { paths ->
            round++
            paths.map { if (it.address == "192.0.2.1") RealDelayProbe.Outcome.Failed("no origin") else RealDelayProbe.Outcome.Delay(80) }
        })!!
        assertEquals(2, round)
        assertEquals("192.0.2.2", win.profile.address)
        assertEquals(parent.port, win.profile.port)
        assertEquals(parent.uuid, win.profile.uuid)
        assertEquals(parent.path, win.profile.path)
        assertEquals("origin.example", win.profile.sni)
        assertEquals("origin.example", win.profile.host)
    }

    @Test fun onePassThenFailureIsNotAVerifiedCleanPath() {
        var round = 0
        assertNull(CleanPathRace.select(parent, listOf("192.0.2.1"), probe = {
            if (++round == 1) listOf(RealDelayProbe.Outcome.Delay(10)) else listOf(RealDelayProbe.Outcome.Failed("stalled"))
        }))
    }

    @Test fun cancellationAndUnmeasuredResultsNeverVerifyAPath() {
        assertNull(CleanPathRace.select(parent, listOf("192.0.2.1"), probe = { error("must not probe") }, cancelled = { true }))
        assertNull(CleanPathRace.select(parent, listOf("192.0.2.1"), probe = { listOf(RealDelayProbe.Outcome.NotRun("busy")) }))
        assertNull(CleanPathRace.select(parent.copy(security = "reality"), listOf("192.0.2.1"), probe = { error("not CDN") }))
    }
    @Test fun staleCachedBatchCannotHideFreshFifthCandidate() {
        var calls = 0
        val win = CleanPathRace.select(parent, (1..5).map { "192.0.2.$it" }, probe = { profiles ->
            calls++
            assertTrue(profiles.size <= 4)
            profiles.map { if (it.address.endsWith(".5")) RealDelayProbe.Outcome.Delay(30) else RealDelayProbe.Outcome.Failed("stale") }
        })
        assertEquals("192.0.2.5", win?.profile?.address)
        assertEquals(3, calls)
    }

    @Test fun networkChangeAfterProbeDiscardsEvenSuccessfulResults() {
        var changed = false
        assertNull(CleanPathRace.select(parent, listOf("192.0.2.1"), probe = {
            changed = true
            listOf(RealDelayProbe.Outcome.Delay(1))
        }, cancelled = { changed }))
    }
}
