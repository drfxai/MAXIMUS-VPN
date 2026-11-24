package com.example.vpn.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.ui.automation.AutomationUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutomationSchedulerTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    private fun infos(name: String): List<WorkInfo> = WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()

    @Test
    fun triggersEnqueueUniqueWorkWithoutANetworkConstraint() {
        AutomationScheduler.trigger(context, AutomationTrigger.CONNECT_FAILED)
        AutomationScheduler.trigger(context, AutomationTrigger.CONNECT_FAILED)
        val failed = infos(AutomationScheduler.uniqueName(AutomationTrigger.CONNECT_FAILED))
        assertEquals("KEEP: one pending run", 1, failed.size)
        assertEquals(WorkInfo.State.ENQUEUED, failed.single().state)
        assertTrue(AutomationScheduler.TAG in failed.single().tags)

        AutomationScheduler.trigger(context, AutomationTrigger.NETWORK_CHANGED)
        AutomationScheduler.trigger(context, AutomationTrigger.NETWORK_CHANGED)
        val changed = infos(AutomationScheduler.uniqueName(AutomationTrigger.NETWORK_CHANGED))
        assertEquals("REPLACE: the newer change wins", 1, changed.count { it.state == WorkInfo.State.ENQUEUED })

        // A DNS-only network never validates, so no task may wait for "connected".
        AutomationTrigger.entries.forEach { t ->
            assertEquals(NetworkType.NOT_REQUIRED, AutomationScheduler.constraints(t).requiredNetworkType)
        }
        assertTrue(AutomationScheduler.constraints(AutomationTrigger.DAILY).requiresBatteryNotLow())
        assertFalse(AutomationScheduler.constraints(AutomationTrigger.CONNECT_FAILED).requiresBatteryNotLow())
    }

    @Test
    fun dailyJobIsPeriodicAndCarriesItsTrigger() {
        AutomationScheduler.schedulePeriodic(context)
        AutomationScheduler.schedulePeriodic(context)
        val daily = infos(AutomationScheduler.PERIODIC)
        assertEquals(1, daily.size)
        assertEquals(WorkInfo.State.ENQUEUED, daily.single().state)
        assertTrue(AutomationScheduler.TAG in daily.single().tags)
    }

    @Test
    fun inputDataRoundTrips() {
        val data = AutomationScheduler.data(AutomationTrigger.MANUAL, setOf(AutomationTask.DNS_LADDER, AutomationTask.GEO_UPDATE))
        assertEquals(AutomationTrigger.MANUAL, AutomationScheduler.triggerOf(data))
        assertEquals(setOf(AutomationTask.DNS_LADDER, AutomationTask.GEO_UPDATE), AutomationScheduler.onlyOf(data))
        assertNull(AutomationScheduler.onlyOf(AutomationScheduler.data(AutomationTrigger.DAILY)))
        assertEquals(AutomationTrigger.DAILY, AutomationScheduler.triggerOf(androidx.work.Data.EMPTY))
        assertEquals(20L, AutomationScheduler.delaySeconds(AutomationTrigger.NETWORK_CHANGED))
        assertEquals(0L, AutomationScheduler.delaySeconds(AutomationTrigger.MANUAL))
    }

    @Test
    fun uiStateListsEveryTaskAndKeepsTotals() {
        val empty = AutomationUiState.from(emptyMap(), running = false, autoDrop = false)
        assertEquals(AutomationTask.entries.toList(), empty.rows.map { it.task })
        assertEquals(0L, empty.lastRunAt)
        val st = AutomationUiState.from(mapOf(
            AutomationTask.RESOLVER_SCAN to TaskStatus(AutomationTask.RESOLVER_SCAN, 500, TaskOutcome.OK, 9),
            AutomationTask.DNS_LADDER to TaskStatus(AutomationTask.DNS_LADDER, 700, TaskOutcome.FAILED, 0, detail = "x"),
            AutomationTask.GEO_UPDATE to TaskStatus(AutomationTask.GEO_UPDATE, 600, TaskOutcome.SKIPPED, skip = SkipReason.METERED)
        ), running = true, autoDrop = true)
        assertEquals(700L, st.lastRunAt)
        assertEquals(9, st.totalFound)
        assertEquals(SkipReason.METERED, st.rows.first { it.task == AutomationTask.GEO_UPDATE }.skip)
        assertTrue(st.running && st.autoDrop)
    }

    @Test
    fun startupStaysOffUnderRobolectric() {
        AutomationStartup.start(context)
        assertNull(com.example.vpn.dnsfirst.DnsLadderHook.lookup)
    }
}
