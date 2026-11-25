package com.example.vpn.automation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build 175 report (Irancell cell:43235): the upkeep never measured anything. While connected (Serverless, the app left
 * out of its own VPN) NETWORK_CHANGED and VPN_STOPPED were skipped with VPN_ACTIVE; and the VPN_STOPPED run 30 s after
 * each Disconnect found the user's next connect already starting and skipped everything, with no retry.
 */
class Build175UpkeepTest {
    private val now = 10_000_000_000L

    private fun env(vpn: Boolean = true, tunnel: Boolean = false) =
        AutomationEnv("cell:43235", vpn, tunnel, metered = true, now = now, hasDnsTunnels = true, hasFronted = true, hasRevivable = true)

    private fun plan(trigger: AutomationTrigger, e: AutomationEnv) =
        AutomationPolicy.plan(trigger, e) { _, _ -> 0L }.associate { it.task to it.skip }

    @Test fun networkOnlyTasksRunBesideATunnelTheAppIsLeftOutOf() {
        val beside = env(tunnel = true).copy(appOutsideTunnel = true)
        for (trigger in listOf(AutomationTrigger.NETWORK_CHANGED, AutomationTrigger.VPN_STOPPED, AutomationTrigger.DAILY)) {
            val p = plan(trigger, beside)
            assertNull("$trigger", p[AutomationTask.RESOLVER_SCAN])
            assertNull("$trigger", p[AutomationTask.CLEAN_IP_SCAN])
            // These start engine programs: they wait for a stop.
            assertEquals("$trigger", SkipReason.VPN_ACTIVE, p[AutomationTask.DNS_LADDER])
            assertEquals("$trigger", SkipReason.VPN_ACTIVE, p[AutomationTask.CONFIG_REVIVAL])
        }
        // The app inside its own tunnel (in-process Xray): its sockets would measure the tunnel.
        assertEquals(SkipReason.VPN_ACTIVE, plan(AutomationTrigger.NETWORK_CHANGED, env(tunnel = true))[AutomationTask.RESOLVER_SCAN])
        // Another app's VPN, or a connect under way: no.
        assertEquals(SkipReason.VPN_ACTIVE, plan(AutomationTrigger.NETWORK_CHANGED, env())[AutomationTask.RESOLVER_SCAN])
        assertEquals(SkipReason.VPN_ACTIVE,
            plan(AutomationTrigger.VPN_STOPPED, env().copy(ownConnecting = true))[AutomationTask.CLEAN_IP_SCAN])
        // "Outside the tunnel" without a tunnel means nothing.
        assertEquals(SkipReason.VPN_ACTIVE,
            plan(AutomationTrigger.NETWORK_CHANGED, env().copy(appOutsideTunnel = true))[AutomationTask.RESOLVER_SCAN])
    }

    @Test fun aRunThatMeetsThisAppsOwnConnectTriesAgainLater() {
        val skipped = listOf(SkipReason.VPN_ACTIVE, SkipReason.VPN_ACTIVE)
        val connecting = env().copy(ownConnecting = true)
        assertTrue(AutomationPolicy.retryLater(AutomationTrigger.VPN_STOPPED, connecting, skipped, ranAny = false, attempt = 0, maxRetries = 3))
        assertTrue(AutomationPolicy.retryLater(AutomationTrigger.NETWORK_CHANGED, connecting, skipped, ranAny = false, attempt = 2, maxRetries = 3))
        assertFalse("retries are bounded",
            AutomationPolicy.retryLater(AutomationTrigger.VPN_STOPPED, connecting, skipped, ranAny = false, attempt = 3, maxRetries = 3))
        assertFalse("another app's VPN: no point waiting",
            AutomationPolicy.retryLater(AutomationTrigger.VPN_STOPPED, env(), skipped, ranAny = false, attempt = 0, maxRetries = 3))
        assertFalse("nothing was held back",
            AutomationPolicy.retryLater(AutomationTrigger.VPN_STOPPED, connecting, listOf(SkipReason.FRESH), ranAny = true, attempt = 0, maxRetries = 3))
        // The failed-connect rule is unchanged.
        assertTrue(AutomationPolicy.retryLater(AutomationTrigger.CONNECT_FAILED, env(), skipped, ranAny = false, attempt = 0, maxRetries = 3))
        assertFalse(AutomationPolicy.retryLater(AutomationTrigger.CONNECT_FAILED, env(), skipped + SkipReason.FRESH, ranAny = false, attempt = 0, maxRetries = 3))
    }

    @Test fun theReportSaysWhoseVpnItWas() {
        assertEquals("none", AutomationPolicy.vpnLabel(env(vpn = false)))
        assertEquals("tunnel", AutomationPolicy.vpnLabel(env(tunnel = true)))
        assertEquals("tunnel-app-outside", AutomationPolicy.vpnLabel(env(tunnel = true).copy(appOutsideTunnel = true)))
        assertEquals("own-block", AutomationPolicy.vpnLabel(env().copy(ownBlockOnly = true)))
        assertEquals("own-connect", AutomationPolicy.vpnLabel(env().copy(ownConnecting = true)))
        assertEquals("other", AutomationPolicy.vpnLabel(env()))
    }

    @Test fun aFailedTaskKeepsItsReason() = runBlocking {
        val bodies = mapOf(
            AutomationTask.GEO_UPDATE to TaskBody { throw IllegalStateException("HTTP 404 downloading geoip-lite.dat") },
            AutomationTask.RESOLVER_SCAN to TaskBody { TaskResult(0, "nothing answered", ok = false) }
        )
        val summary = AutomationRunner(AutomationStatusBook(), bodies) { now }.run(AutomationTrigger.MANUAL, env(vpn = false))
        assertEquals("HTTP 404 downloading geoip-lite.dat", summary.failures[AutomationTask.GEO_UPDATE])
        assertEquals("nothing answered", summary.failures[AutomationTask.RESOLVER_SCAN])
    }
}
