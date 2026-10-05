package com.example

import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.vpn.engine.ProtocolLinks
import com.example.vpn.smart.NetworkMemory
import com.example.vpn.smart.ServerRace
import com.example.vpn.smart.WatchPolicy
import com.example.vpn.stealth.ConnectionKind
import com.example.vpn.stealth.StealthPathFinder
import com.example.vpn.stealth.StealthVariants
import com.example.xray.RealDelayProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** War plan Phase 4: Smart Connect's server race, what each network taught, and the watchdog's timing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmartConnectRaceTest {
    private fun reality(id: String, host: String = "203.0.113.$id".take(20), score: Double = 50.0) = VlessProfile(
        id = id, name = "R$id", address = host, port = 443, uuid = "11111111-1111-1111-1111-111111111111",
        security = "reality", sni = "www.example.com", publicKey = "pbk$id", shortId = "ab", flow = "xtls-rprx-vision",
        fingerprint = "chrome", overallScore = score
    )

    private fun hy2(id: String, score: Double = 50.0) =
        ProtocolLinks.parseHysteria2("hysteria2://pw$id@198.51.100.$id:443?sni=h.example.com#H$id").copy(id = "h$id", overallScore = score)

    private fun cdn(id: String, score: Double = 50.0) = reality(id).copy(
        id = "c$id", security = "tls", transport = "ws", path = "/ws", publicKey = "", flow = "", address = "192.0.2.$id", overallScore = score
    )

    @Test fun theUsersServerLeadsAndEachBatchMixesKinds() {
        val pool = (1..4).map { reality("$it", score = 90.0 - it) } + hy2("1", 10.0) + cdn("1", 5.0)
        val ranked = ServerRace.rank(pool, first = reality("9"))
        assertEquals("9", ranked.first().id)
        // Plain score order would fill the first batch of five with REALITY; the race spreads the kinds.
        val firstBatch = ranked.take(RealDelayProbe.MAX_BATCH).map { ConnectionKind.of(it) }.toSet()
        assertEquals(setOf("REALITY", "QUIC", "CDN"), firstBatch)
    }

    @Test fun whatWorkedOnThisNetworkGoesFirstAndFailedOrUnsupportedServersAreLeftOut() {
        val pool = listOf(reality("1", score = 99.0), hy2("2", score = 1.0), reality("3").copy(id = "bridge-x"),
            reality("4").copy(protocolType = ProtocolType.TUIC))
        val ranked = ServerRace.rank(pool, workingKinds = listOf("QUIC"), exclude = setOf("1"))
        assertEquals(listOf("h2"), ranked.map { it.id })
    }

    @Test fun theRaceStopsAtTheFirstRoundWithAWorkingPathAndTriesTheBestServersInDisguise() {
        val pool = ServerRace.rank((1..8).map { reality("$it") })
        val rounds = mutableListOf<List<String>>()
        // A server-name filter: only split handshakes get through.
        val win = ServerRace(probe = { list, _ ->
            rounds += list.map { it.id }
            list.map { if (it.finalMask.contains("tlshello") && it.id == "2") RealDelayProbe.Outcome.Delay(300) else RealDelayProbe.Outcome.Failed("reset") }
        }, log = {}).run(pool, { it }, timeoutSec = 4)!!
        assertEquals("2", win.owner.id)
        assertEquals(StealthVariants.FRAGMENT, win.variantKey)
        // Servers 1 and 2 as saved and disguised, then server 3; nothing more once one works.
        assertEquals(listOf(listOf("1", "1", "2", "2", "3")), rounds)
    }

    @Test fun theWinnerIsTheKindLeastLikelyToBeCutNextNotTheFastest() {
        val failed = mutableListOf<String>()
        val win = ServerRace(probe = { list, _ ->
            list.map { if (it.protocolType == ProtocolType.HYSTERIA2) RealDelayProbe.Outcome.Delay(40) else if (it.id == "1") RealDelayProbe.Outcome.Delay(700) else RealDelayProbe.Outcome.Failed("reset") }
        }, log = {}).run(listOf(hy2("5"), reality("1"), reality("2")), { it }, timeoutSec = 4, onFailure = { failed += it.id })!!
        assertEquals("1", win.owner.id)
        assertTrue("2" in failed)
    }

    @Test fun kindsThatJustFailedOnThisNetworkGoLast() {
        val ranked = ServerRace.rank(listOf(reality("1", score = 99.0), hy2("2", score = 1.0)), failedKinds = setOf("REALITY"))
        assertEquals(listOf("h2", "1"), ranked.map { it.id })
        var stored: String? = null
        val memory = NetworkMemory({ stored }, { stored = it })
        memory.recordFailure("wifi", "REALITY", now = 1_000)
        assertEquals(setOf("REALITY"), NetworkMemory({ stored }, { stored = it }).recentFailures("wifi", now = 2_000))
        assertTrue(memory.recentFailures("wifi", now = 1_000 + NetworkMemory.FAILURE_MEMORY_MS).isEmpty())
        memory.recordSuccess("wifi", "REALITY", 300)
        assertTrue(memory.recentFailures("wifi", now = 2_000).isEmpty())
    }

    @Test fun aSecondRoundIsTriedAndAnUnresolvableServerIsSkipped() {
        val pool = (1..7).map { reality("$it") }
        val win = ServerRace(probe = { list, _ ->
            list.map { if (it.id == "7") RealDelayProbe.Outcome.Delay(100) else RealDelayProbe.Outcome.Failed("reset") }
        }, log = {}).run(pool, { if (it.id == "1") error("DNS") else it.copy(address = "10.0.0.${it.id}") }, timeoutSec = 4)!!
        assertEquals("7", win.owner.id)
        assertEquals("10.0.0.7", win.profile.address)
    }

    @Test fun noProbeMeansNoWinner() {
        val win = ServerRace(probe = { l, _ -> l.map { RealDelayProbe.Outcome.NotRun("core busy") } }, log = {})
            .run(listOf(reality("1"), reality("2")), { it }, timeoutSec = 4)
        assertNull(win)
    }

    @Test fun eachNetworkKeepsItsOwnLessonsAcrossRestarts() {
        var stored: String? = null
        val memory = NetworkMemory({ stored }, { stored = it })
        assertEquals(NetworkMemory.DEFAULT, memory.timeouts("cell:43235"))
        memory.variants("cell:43235")["r1"] = "fragment"
        memory.recordSuccess("cell:43235", "REALITY", 400)
        memory.recordSuccess("cell:43235", "QUIC", 400)
        memory.recordSuccess("wifi", "CDN", 2000)

        val reloaded = NetworkMemory({ stored }, { stored = it })
        assertEquals("fragment", reloaded.variants("cell:43235")["r1"])
        assertTrue(reloaded.variants("wifi").isEmpty())
        assertEquals(listOf("QUIC", "REALITY"), reloaded.workingKinds("cell:43235"))
        // A fast carrier gives up on dead paths sooner; a slow network gets more time.
        assertEquals(NetworkMemory.Timeouts(3, 4), reloaded.timeouts("cell:43235"))
        assertEquals(NetworkMemory.Timeouts(6, 7), reloaded.timeouts("wifi"))
    }

    @Test fun onlyTheMostRecentNetworksAreKept() {
        var stored: String? = null
        val memory = NetworkMemory({ stored }, { stored = it })
        repeat(NetworkMemory.MAX_NETWORKS + 2) { memory.recordSuccess("cell:$it", "REALITY", 500) }
        val reloaded = NetworkMemory({ stored }, { stored = it })
        assertTrue(reloaded.workingKinds("cell:0").isEmpty())
        assertEquals(listOf("REALITY"), reloaded.workingKinds("cell:${NetworkMemory.MAX_NETWORKS + 1}"))
    }

    @Test fun aDeadConnectionIsGivenUpInAboutEighteenSeconds() {
        var elapsed = 0L
        var failures = 0
        while (failures < WatchPolicy.FAILURES_TO_SWITCH) {
            elapsed += WatchPolicy.nextCheckDelayMs(failures)
            failures++
        }
        assertEquals(18_000L, elapsed)
    }

    @Test fun thePathFinderSaysWhenNothingWorkedAndUsesTheNetworksMemory() {
        val memory = mutableMapOf<String, String>()
        val dead = StealthPathFinder({ l, _ -> l.map { RealDelayProbe.Outcome.Failed("timeout") } }, log = {}, memory = memory)
            .choose(reality("1"), { it }, emptyList())
        assertTrue(dead.nothingWorked)
        val untested = StealthPathFinder({ l, _ -> l.map { RealDelayProbe.Outcome.NotRun("busy") } }, log = {}, memory = memory)
            .choose(reality("1"), { it }, emptyList())
        assertFalse(untested.nothingWorked)

        val timeouts = mutableListOf<Int>()
        StealthPathFinder({ l, t ->
            timeouts += t
            l.map { if (it.finalMask.contains("tlshello")) RealDelayProbe.Outcome.Delay(200) else RealDelayProbe.Outcome.Failed("reset") }
        }, log = {}, memory = memory, firstTimeoutSec = 3, alternateTimeoutSec = 4).choose(reality("1"), { it }, emptyList())
        assertEquals(listOf(3, 4), timeouts)
        assertTrue(memory.containsKey("1"))
        assertFalse(StealthPathFinder.memory.containsKey("1"))
    }
}
