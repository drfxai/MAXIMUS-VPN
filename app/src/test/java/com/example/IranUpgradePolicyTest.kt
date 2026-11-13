package com.example

import com.example.data.model.AppSettings
import com.example.data.model.ServerTestStatus
import com.example.data.model.VlessProfile
import com.example.ui.home.LeakProtectionAdvice
import com.example.vpn.connections.ConnectionEngineRunner
import com.example.vpn.health.NodeHealth
import com.example.vpn.safety.MaximusVpnSupervisor.Lockdown
import com.example.vpn.safety.PowerButtonPolicy
import org.junit.Assert.*
import org.junit.Test

class IranUpgradePolicyTest {
    @Test fun powerButtonFallsBackByDefaultAndCanBePinned() {
        assertFalse(PowerButtonPolicy.exact(AppSettings()))
        assertTrue(PowerButtonPolicy.exact(AppSettings(powerButtonFallback = false)))
        // A non-exact power-button session lets Aether's failed carriers hand over (failover on by default).
        assertTrue(ConnectionEngineRunner.mayRecoverAether(aether = true, smart = false, autoFailover = AppSettings().autoFailoverEnabled,
            exact = PowerButtonPolicy.exact(AppSettings()), exitLocked = false, force = false, alreadyEscalated = false))
        assertFalse(ConnectionEngineRunner.mayRecoverAether(aether = true, smart = false, autoFailover = true,
            exact = PowerButtonPolicy.exact(AppSettings(powerButtonFallback = false)), exitLocked = false, force = false, alreadyEscalated = false))
    }

    @Test fun leakAdviceOnlyWhenConnectedAndLockdownMeasuredOff() {
        assertTrue(LeakProtectionAdvice.shouldShow(Lockdown.OFF, connected = true, snoozedUntil = 0, now = 10))
        assertTrue(LeakProtectionAdvice.shouldShow(Lockdown.ALWAYS_ON_ONLY, connected = true, snoozedUntil = 0, now = 10))
        assertFalse(LeakProtectionAdvice.shouldShow(Lockdown.ON, connected = true, snoozedUntil = 0, now = 10))
        assertFalse("Android 9 cannot tell; never nag on a guess", LeakProtectionAdvice.shouldShow(Lockdown.UNKNOWN, true, 0, 10))
        assertFalse(LeakProtectionAdvice.shouldShow(Lockdown.OFF, connected = false, snoozedUntil = 0, now = 10))
        assertFalse(LeakProtectionAdvice.shouldShow(Lockdown.OFF, connected = true, snoozedUntil = 20, now = 10))
        assertTrue(LeakProtectionAdvice.message(Lockdown.ALWAYS_ON_ONLY).contains("Block connections without VPN"))
    }

    @Test fun pingAllBatchesAndCleansOnlyProvenDeadSubscriptionNodes() {
        val sub = "https://example.com/sub"
        val nodes = (1..12).map { VlessProfile(id = "n$it", name = "n$it", address = "1.1.1.$it", port = 443, uuid = "u", subscriptionUrl = sub) }
        assertEquals(listOf(5, 5, 2), NodeHealth.batches(nodes).map { it.size })
        val manual = VlessProfile(id = "manual", name = "mine", address = "2.2.2.2", port = 443, uuid = "u")
        val favourite = nodes[1].copy(isFavorite = true)
        val all = listOf(nodes[0], favourite, nodes[2], nodes[3], nodes[4], manual)
        val results = mapOf(
            "n1" to ServerTestStatus.Unavailable("no traffic"),
            "n2" to ServerTestStatus.Unavailable("no traffic"),
            "n3" to ServerTestStatus.Available(120),
            "n4" to ServerTestStatus.Idle,
            "n5" to ServerTestStatus.InvalidConfig("bad"),
            "manual" to ServerTestStatus.Unavailable("no traffic"))
        assertEquals(listOf("n1", "n5"), NodeHealth.unreachable(all, results, selectedId = null).map { it.id })
        assertEquals(listOf("n5"), NodeHealth.unreachable(all, results, selectedId = "n1").map { it.id })
        assertTrue(NodeHealth.unreachable(all, emptyMap(), null).isEmpty())
    }
}
