package com.example.vpn.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitTunnelTest {
    private val self = "com.example"
    private val all: (String) -> Boolean = { true }

    @Test
    fun defaultRulesKeepTodaysBehaviour() {
        val inside = SplitTunnelPlan.of(SplitTunnel(), self, excludeSelf = false, isInstalled = all)
        assertTrue(inside.allowed.isEmpty())
        assertTrue(inside.disallowed.isEmpty())
        val outside = SplitTunnelPlan.of(SplitTunnel(), self, excludeSelf = true, isInstalled = all)
        assertEquals(listOf(self), outside.disallowed)
        assertTrue(outside.allowed.isEmpty())
    }

    @Test
    fun exceptLeavesChosenAppsOutAndNeverMixesLists() {
        val rules = SplitTunnel(SplitTunnelMode.EXCEPT, setOf("ir.bank", "com.other", self))
        val plan = SplitTunnelPlan.of(rules, self, excludeSelf = true, isInstalled = all)
        assertTrue(plan.allowed.isEmpty())
        assertEquals(listOf("com.other", "ir.bank", self), plan.disallowed)
        val inTunnel = SplitTunnelPlan.of(rules, self, excludeSelf = false, isInstalled = all)
        assertEquals(listOf("com.other", "ir.bank"), inTunnel.disallowed)
    }

    @Test
    fun onlyKeepsTheAppInsideUnlessASeparateEngineRuns() {
        val rules = SplitTunnel(SplitTunnelMode.ONLY, setOf("org.telegram.messenger"))
        val inProcess = SplitTunnelPlan.of(rules, self, excludeSelf = false, isInstalled = all)
        assertEquals(listOf("org.telegram.messenger", self), inProcess.allowed)
        assertTrue(inProcess.disallowed.isEmpty())
        val sidecar = SplitTunnelPlan.of(rules, self, excludeSelf = true, isInstalled = all)
        assertEquals(listOf("org.telegram.messenger"), sidecar.allowed)
        assertTrue(sidecar.disallowed.isEmpty())
    }

    @Test
    fun onlyWithNothingInstalledRoutesEverything() {
        val rules = SplitTunnel(SplitTunnelMode.ONLY, setOf("gone.app"))
        val plan = SplitTunnelPlan.of(rules, self, excludeSelf = true) { false }
        assertTrue(plan.allowed.isEmpty())
        assertEquals(listOf(self), plan.disallowed)
    }

    @Test
    fun uninstalledAppsAreDropped() {
        val rules = SplitTunnel(SplitTunnelMode.EXCEPT, setOf("a.present", "b.gone"))
        val plan = SplitTunnelPlan.of(rules, self, excludeSelf = false) { it == "a.present" }
        assertEquals(listOf("a.present"), plan.disallowed)
    }

    @Test
    fun encodeDecodeRoundTripAndBadInput() {
        val rules = SplitTunnel(SplitTunnelMode.EXCEPT, setOf("b", "a"))
        assertEquals(rules, SplitTunnel.decode(rules.encode()))
        assertEquals(SplitTunnel(), SplitTunnel.decode("not json"))
        assertEquals(SplitTunnel(), SplitTunnel.decode(null))
        assertEquals(SplitTunnelMode.ALL, SplitTunnel.decode("""{"mode":"WHAT","packages":["x"]}""").mode)
    }

    @Test
    fun validationAndSummary() {
        assertEquals("Every app on this phone", SplitTunnel().summary())
        assertTrue(SplitTunnel(SplitTunnelMode.ONLY, setOf(self)).validationError(self) != null)
        assertNull(SplitTunnel(SplitTunnelMode.ONLY, setOf("x")).validationError(self))
        assertEquals("Every app except 2", SplitTunnel(SplitTunnelMode.EXCEPT, setOf("a", "b")).summary())
        assertEquals("1 app only", SplitTunnel(SplitTunnelMode.ONLY, setOf("a")).summary())
    }

    @Test
    fun iranianSuggestionsMatchByPackageOnly() {
        val apps = listOf(
            InstalledApp("ir.mci.ecareapp", "Hamrah Man", system = false),
            InstalledApp("com.myirancell", "MyIrancell", system = false),
            InstalledApp("cab.snapp.passenger", "Snapp", system = false),
            InstalledApp("org.telegram.messenger", "تلگرام", system = false),
            InstalledApp("ir.vendor.preinstalled", "Preinstalled", system = true),
            InstalledApp("com.irrelevant.app", "Irrelevant", system = false),
        )
        assertEquals(setOf("ir.mci.ecareapp", "com.myirancell", "cab.snapp.passenger"), IranianApps.suggest(apps))
        assertFalse(IranianApps.matches("com.irrelevant.app"))
    }
}
