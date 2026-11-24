package com.example.vpn.automation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationRunnerTest {
    private var now = 10_000_000_000L
    private val hour = 3_600_000L

    private fun env(vpn: Boolean = false, tunnel: Boolean = false, metered: Boolean = false, network: String = "cell:43235") =
        AutomationEnv(network, vpn, tunnel, metered, now, hasDnsTunnels = true, hasFronted = true, hasRevivable = true)

    private fun plan(trigger: AutomationTrigger, e: AutomationEnv, ok: Map<Pair<AutomationTask, String>, Long> = emptyMap()) =
        AutomationPolicy.plan(trigger, e) { t, n -> ok[t to n] ?: 0L }.associate { it.task to it.skip }

    @Test
    fun networkChangeRunsEveryMeasurementButNotTheDownload() {
        val p = plan(AutomationTrigger.NETWORK_CHANGED, env())
        assertNull(p[AutomationTask.RESOLVER_SCAN]); assertNull(p[AutomationTask.DNS_LADDER])
        assertNull(p[AutomationTask.CLEAN_IP_SCAN]); assertNull(p[AutomationTask.CONFIG_REVIVAL])
        assertEquals(SkipReason.NOT_THIS_TRIGGER, p[AutomationTask.GEO_UPDATE])
    }

    @Test
    fun aVpnBlocksMeasuringButTheTunnelCarriesTheDownload() {
        val p = plan(AutomationTrigger.DAILY, env(vpn = true, tunnel = true, metered = true))
        assertEquals(SkipReason.VPN_ACTIVE, p[AutomationTask.RESOLVER_SCAN])
        assertEquals(SkipReason.VPN_ACTIVE, p[AutomationTask.CONFIG_REVIVAL])
        assertNull("through the tunnel even on mobile data", p[AutomationTask.GEO_UPDATE])
        assertEquals(SkipReason.VPN_ACTIVE, plan(AutomationTrigger.DAILY, env(vpn = true))[AutomationTask.GEO_UPDATE])
        assertEquals(SkipReason.METERED, plan(AutomationTrigger.DAILY, env(metered = true))[AutomationTask.GEO_UPDATE])
        assertNull("the user asked", plan(AutomationTrigger.MANUAL, env(metered = true))[AutomationTask.GEO_UPDATE])
        assertEquals(SkipReason.NEEDS_TUNNEL, plan(AutomationTrigger.MANUAL, env().copy(geoNeedsTunnel = true))[AutomationTask.GEO_UPDATE])
        assertNull(plan(AutomationTrigger.CONNECTED, env(vpn = true, tunnel = true).copy(geoNeedsTunnel = true))[AutomationTask.GEO_UPDATE])
    }

    @Test
    fun freshnessIsPerNetworkAndShorterAfterAFailedConnect() {
        val ok = mapOf((AutomationTask.RESOLVER_SCAN to "cell:43235") to now - 2 * hour, (AutomationTask.GEO_UPDATE to "*") to now - hour)
        assertEquals(SkipReason.FRESH, plan(AutomationTrigger.NETWORK_CHANGED, env(), ok)[AutomationTask.RESOLVER_SCAN])
        assertNull("another network has no scan", plan(AutomationTrigger.NETWORK_CHANGED, env(network = "wifi:abc"), ok)[AutomationTask.RESOLVER_SCAN])
        assertNull("a failed connect rescans after 30 min", plan(AutomationTrigger.CONNECT_FAILED, env(), ok)[AutomationTask.RESOLVER_SCAN])
        assertEquals(SkipReason.FRESH, plan(AutomationTrigger.CONNECTED, env(vpn = true, tunnel = true), ok)[AutomationTask.GEO_UPDATE])
        assertNull(plan(AutomationTrigger.MANUAL, env(), ok)[AutomationTask.RESOLVER_SCAN])
    }

    @Test
    fun nothingToDoWithoutMatchingConfigs() {
        val e = env().copy(hasDnsTunnels = false, hasFronted = false, hasRevivable = false)
        val p = plan(AutomationTrigger.DAILY, e)
        assertEquals(SkipReason.NOTHING_TO_DO, p[AutomationTask.DNS_LADDER])
        assertEquals(SkipReason.NOTHING_TO_DO, p[AutomationTask.CLEAN_IP_SCAN])
        assertEquals(SkipReason.NOTHING_TO_DO, p[AutomationTask.CONFIG_REVIVAL])
        assertNull(p[AutomationTask.RESOLVER_SCAN])
    }

    @Test
    fun runnerRecordsResultsFailuresAndSkips() = runBlocking {
        val saved = arrayOf<String?>(null)
        val book = AutomationStatusBook({ saved[0] }, { saved[0] = it })
        val order = mutableListOf<AutomationTask>()
        val runner = AutomationRunner(book, mapOf(
            AutomationTask.RESOLVER_SCAN to TaskBody { order += AutomationTask.RESOLVER_SCAN; TaskResult(9, "9 honest resolvers") },
            AutomationTask.DNS_LADDER to TaskBody { order += AutomationTask.DNS_LADDER; error("engine missing") },
            AutomationTask.CLEAN_IP_SCAN to TaskBody { order += AutomationTask.CLEAN_IP_SCAN; TaskResult(0, "none", ok = true) },
            AutomationTask.GEO_UPDATE to TaskBody { order += AutomationTask.GEO_UPDATE; TaskResult(1) }
        ), clock = { now })
        val s = runner.run(AutomationTrigger.DAILY, env(metered = true))
        assertEquals(listOf(AutomationTask.RESOLVER_SCAN, AutomationTask.DNS_LADDER, AutomationTask.CLEAN_IP_SCAN), order)
        assertEquals(listOf(AutomationTask.DNS_LADDER), s.failed)
        assertEquals(SkipReason.NOTHING_TO_DO, s.skipped[AutomationTask.CONFIG_REVIVAL])
        assertEquals(SkipReason.METERED, s.skipped[AutomationTask.GEO_UPDATE])
        val st = AutomationStatusBook({ saved[0] }, {}).current()
        assertEquals(9, st.getValue(AutomationTask.RESOLVER_SCAN).found)
        assertEquals(TaskOutcome.OK, st.getValue(AutomationTask.RESOLVER_SCAN).outcome)
        assertEquals("engine missing", st.getValue(AutomationTask.DNS_LADDER).detail)
        assertEquals(TaskOutcome.FAILED, st.getValue(AutomationTask.DNS_LADDER).outcome)
        assertEquals(SkipReason.METERED, st.getValue(AutomationTask.GEO_UPDATE).skip)
        assertEquals(now, book.lastOk(AutomationTask.RESOLVER_SCAN, "cell:43235"))
        assertEquals("a failure is not a success", 0L, book.lastOk(AutomationTask.DNS_LADDER, "cell:43235"))
        // A second run an hour later skips the fresh scan but keeps its numbers on show.
        now += hour
        val again = runner.run(AutomationTrigger.NETWORK_CHANGED, env())
        assertEquals(SkipReason.FRESH, again.skipped[AutomationTask.RESOLVER_SCAN])
        val shown = book.current().getValue(AutomationTask.RESOLVER_SCAN)
        assertEquals(9, shown.found); assertEquals(TaskOutcome.OK, shown.outcome); assertEquals(SkipReason.FRESH, shown.skip)
    }

    @Test
    fun vpnBlockedRunAsksForARetry() = runBlocking {
        val runner = AutomationRunner(AutomationStatusBook(), AutomationTask.entries.associateWith { TaskBody { TaskResult(1) } }, { now })
        assertTrue(runner.run(AutomationTrigger.CONNECT_FAILED, env(vpn = true)).blockedByVpn)
        assertFalse(runner.run(AutomationTrigger.CONNECT_FAILED, env()).blockedByVpn)
    }

    @Test
    fun onlyLimitsTheTasks() = runBlocking {
        val ran = mutableListOf<AutomationTask>()
        val runner = AutomationRunner(AutomationStatusBook(), AutomationTask.entries.associateWith { t -> TaskBody { ran += t; TaskResult(1) } }, { now })
        runner.run(AutomationTrigger.MANUAL, env(), only = setOf(AutomationTask.DNS_LADDER))
        assertEquals(listOf(AutomationTask.DNS_LADDER), ran)
    }

    @Test
    fun configHealthDropsOnlyLongDeadSubscriptionConfigs() {
        val saved = arrayOf<String?>(null)
        val book = ConfigHealthBook({ saved[0] }, { saved[0] = it }, { now })
        val candidates = listOf(
            ConfigHealthBook.Candidate("sub", fromSubscription = true, favorite = false),
            ConfigHealthBook.Candidate("mine", fromSubscription = false, favorite = false),
            ConfigHealthBook.Candidate("fav", fromSubscription = true, favorite = true),
            ConfigHealthBook.Candidate("back", fromSubscription = true, favorite = false)
        )
        repeat(3) { i ->
            book.record("cell:1", mapOf("sub" to false, "mine" to false, "fav" to false, "back" to if (i == 1) true else false, "untested" to null))
            assertTrue("never before 48 h", book.droppable(candidates).isEmpty())
            now += 20 * hour
        }
        assertEquals(listOf("sub"), book.droppable(candidates))
        assertEquals(3, ConfigHealthBook({ saved[0] }, {}, { now }).strikes("sub"))
        assertEquals(0, book.strikes("untested"))
        book.forget(listOf("sub"))
        assertTrue(book.droppable(candidates).isEmpty())
    }
}
