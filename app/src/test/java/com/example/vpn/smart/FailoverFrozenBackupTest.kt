package com.example.vpn.smart

import com.example.data.model.VlessProfile
import org.junit.Assert.assertEquals
import org.junit.Test

/** Build 150: "Backup plan: A REALITY on net:51.158.x.x/16" named a server on the /16 a frozen server sat in. */
class FailoverFrozenBackupTest {
    private fun p(id: String, address: String, security: String = "reality", transport: String = "tcp", sni: String = "") =
        VlessProfile(id = id, name = id, address = address, port = 443, uuid = "u", transport = transport,
            security = security, sni = sni, createdAt = 0L)

    private val frozenScaleway = p("fr-scw", "51.158.10.20")
    private val sameRange = p("nl-scw", "51.158.200.7")
    private val otherNet = p("de-hz", "5.75.1.2")
    private val cfA = p("cf-a", "104.16.1.1", security = "tls", transport = "ws", sni = "a.example.com")
    private val cfB = p("cf-b", "104.16.2.2", security = "tls", transport = "ws", sni = "b.example.com")
    private val all = listOf(frozenScaleway, sameRange, otherNet, cfA, cfB)

    @Test fun frozenServersAndTheirSlash16AreLeftOutOfTheBackupPool() {
        val pool = FailoverManager.withoutFrozen(listOf(frozenScaleway, sameRange, otherNet), setOf("fr-scw"), all)
        assertEquals(listOf("de-hz"), pool.map { it.id })
    }

    @Test fun aFrozenCdnConfigDoesNotTakeTheWholeCdnWithIt() {
        val pool = FailoverManager.withoutFrozen(listOf(cfA, cfB, otherNet), setOf("cf-a"), all)
        assertEquals(listOf("cf-b", "de-hz"), pool.map { it.id })
    }

    @Test fun nothingFrozenChangesNothing() {
        val pool = listOf(frozenScaleway, sameRange, otherNet)
        assertEquals(pool, FailoverManager.withoutFrozen(pool, emptySet(), all))
    }

    @Test fun everyCandidateFrozenLeavesAnEmptyPlan() {
        assertEquals(emptyList<VlessProfile>(), FailoverManager.withoutFrozen(listOf(sameRange), setOf("fr-scw"), all))
    }
}
