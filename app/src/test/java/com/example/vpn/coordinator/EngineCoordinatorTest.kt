package com.example.vpn.coordinator

import com.example.vpn.connectivity.TransportCapabilityEngine.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineCoordinatorTest {

    private fun c(id: String, t: Transport, engine: String = "xray") =
        EngineCoordinator.Candidate(id, id, t, engine)

    @Test fun ordersByRecommendedTransport() {
        val plan = EngineCoordinator.plan(
            recommended = listOf(Transport.TCP_TLS, Transport.UDP),
            candidates = listOf(c("a", Transport.UDP), c("b", Transport.TCP_TLS))
        )
        assertEquals(listOf("b", "a"), plan.ordered.map { it.id })
    }

    @Test fun nonRecommendedTransportGoesLast() {
        val plan = EngineCoordinator.plan(
            recommended = listOf(Transport.TCP_TLS),
            candidates = listOf(c("plain", Transport.TCP_PLAIN), c("tls", Transport.TCP_TLS))
        )
        assertEquals(listOf("tls", "plain"), plan.ordered.map { it.id })
        assertTrue(plan.ordered.last().reason.contains("not in the recommended order"))
    }

    @Test fun tiesKeepGivenOrder() {
        val plan = EngineCoordinator.plan(
            recommended = listOf(Transport.TCP_TLS),
            candidates = listOf(c("first", Transport.TCP_TLS), c("second", Transport.TCP_TLS))
        )
        assertEquals(listOf("first", "second"), plan.ordered.map { it.id })
    }

    @Test fun skipsEnginesThatAreCoolingDown() {
        val plan = EngineCoordinator.plan(
            recommended = listOf(Transport.UDP),
            candidates = listOf(c("ok", Transport.UDP, "xray"), c("broken", Transport.UDP, "tor")),
            allows = { it != "tor" }
        )
        assertEquals(listOf("ok"), plan.ordered.map { it.id })
        assertEquals(listOf("broken"), plan.skipped.map { it.id })
        assertTrue(plan.skipped.first().reason.contains("cooling down"))
    }

    @Test fun recommendedReasonNamesThePosition() {
        val plan = EngineCoordinator.plan(
            recommended = listOf(Transport.TCP_TLS, Transport.UDP),
            candidates = listOf(c("a", Transport.UDP))
        )
        assertTrue(plan.ordered.first().reason.contains("#2"))
    }

    @Test fun emptyCandidatesGivesEmptyPlan() {
        val plan = EngineCoordinator.plan(listOf(Transport.UDP), emptyList())
        assertTrue(plan.ordered.isEmpty())
        assertTrue(plan.skipped.isEmpty())
    }
}
