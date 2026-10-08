package com.example.vpn.connectivity

import com.example.data.model.VlessProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 9: backups on other failure domains, hysteresis, backoff and no oscillation. */
class SmartFailoverPolicyTest {
    private fun p(id: String, address: String, security: String = "reality", transport: String = "tcp", score: Double = 50.0) =
        VlessProfile(id = id, name = id, address = address, port = 443, uuid = "u", security = security, transport = transport,
            sni = "www.example.com", canonicalFingerprint = "fp-$id", overallScore = score)

    private var now = 1_000_000L
    private val policy = SmartFailoverPolicy { now }

    @Test fun backupsSitOnOtherFailureDomains() {
        val primary = p("p", "104.16.0.1", security = "tls", transport = "ws")
        val sameCdn = p("cf2", "104.16.0.2", security = "tls", transport = "ws", score = 99.0)
        val net1 = p("n1", "5.6.7.8", score = 80.0)
        val net1b = p("n1b", "5.6.9.9", score = 70.0)
        val net2 = p("n2", "9.9.1.1", score = 60.0)
        val plan = SmartFailoverPolicy.plan(primary, listOf(sameCdn, net1, net1b, net2), { it.overallScore })
        assertEquals("n1", plan.backupA!!.profile.id)
        assertEquals("n2", plan.backupB!!.profile.id)
        assertNotEquals(plan.backupA!!.failureDomain, plan.backupB!!.failureDomain)
    }

    @Test fun noBackupRatherThanOneThatFailsWithThePrimary() {
        val primary = p("p", "104.16.0.1", security = "tls", transport = "ws")
        val plan = SmartFailoverPolicy.plan(primary, listOf(p("cf2", "104.17.0.2", security = "tls", transport = "ws")), { it.overallScore })
        assertNull(plan.backupA)
        assertNull(plan.backupB)
    }

    @Test fun eachSwitchDoublesTheWaitUpToTheCap() {
        assertTrue(policy.mayFailover())
        policy.recordSwitch(p("a", "1.1.1.1"))
        assertEquals(SmartFailoverPolicy.BASE_COOLDOWN_MS, policy.cooldownMs())
        assertFalse(policy.mayFailover())
        now += SmartFailoverPolicy.BASE_COOLDOWN_MS
        assertTrue(policy.mayFailover())
        policy.recordSwitch(p("b", "2.2.2.2"))
        assertEquals(SmartFailoverPolicy.BASE_COOLDOWN_MS * 2, policy.cooldownMs())
        repeat(10) { now += 1_000; policy.recordSwitch(p("c$it", "3.3.3.$it")) }
        assertEquals(SmartFailoverPolicy.MAX_COOLDOWN_MS, policy.cooldownMs())
        now += SmartFailoverPolicy.WINDOW_MS + 1
        assertEquals(0L, policy.cooldownMs())
    }

    @Test fun aConfigLeftAfterFailingIsNotPickedAgainUntilItsPenaltyEnds() {
        val a = p("a", "1.1.1.1"); val b = p("b", "2.2.2.2")
        policy.recordSwitch(a)
        assertEquals(listOf(b), policy.withoutPenalized(listOf(a, b)))
        // Every candidate penalized: none is ruled out, so failover never ends with nothing.
        assertEquals(listOf(a), policy.withoutPenalized(listOf(a)))
        now += SmartFailoverPolicy.BASE_PENALTY_MS
        assertFalse(policy.isPenalized(a))
        policy.recordSwitch(a)
        now += SmartFailoverPolicy.BASE_PENALTY_MS
        assertTrue(policy.isPenalized(a)) // the second time it was left, the penalty doubled
    }

    @Test fun returningToThePrimaryNeedsSeveralPassesOverTimeAndAMinimumStay() {
        policy.recordSwitch(p("primary", "1.1.1.1"))
        assertFalse(policy.recordPrimaryCheck(true))
        now += 30_000; assertFalse(policy.recordPrimaryCheck(true))
        now += 30_000; assertFalse(policy.recordPrimaryCheck(true)) // three passes over a minute, but dwell not reached
        now += 60_000; assertTrue(policy.recordPrimaryCheck(true))
        // One failed check resets the count.
        policy.recordSwitch(null, failed = false)
        now += SmartFailoverPolicy.MIN_DWELL_MS
        assertFalse(policy.recordPrimaryCheck(true))
        assertFalse(policy.recordPrimaryCheck(false))
        assertFalse(policy.recordPrimaryCheck(true))
    }

    @Test fun failoverPrefersAnotherDomainThanTheFailedOne() {
        val failed = p("f", "104.16.0.1", security = "tls", transport = "ws")
        val same = p("s", "104.18.0.1", security = "tls", transport = "ws")
        val other = p("o", "8.8.4.4")
        assertEquals(listOf(other), SmartFailoverPolicy.preferOtherDomains(listOf(same, other), failed))
        assertEquals(listOf(same), SmartFailoverPolicy.preferOtherDomains(listOf(same), failed))
    }
}
