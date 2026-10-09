package com.example.vpn.lab

import com.example.vpn.smart.NetworkCapabilityProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ExperimentPlannerTest {
    private val base = NetworkCapabilityProfile(transport = "cellular", ipv4Available = true, ipv6Available = true, tcpAvailable = true,
        tlsAvailable = true, udpAvailable = true, internationalOk = 2, internationalTried = 2, domesticReachable = true)
    private fun reading(p: NetworkCapabilityProfile) = NetworkStateClassifier.classify(p, now = 1L)

    @Test fun automaticExperimentsWaitWhenNothingReachesAbroad() {
        assertNull(ExperimentPlanner.automaticRefusal(null))
        assertNull(ExperimentPlanner.automaticRefusal(reading(base)))
        val cut = base.copy(internationalOk = 0, tcpAvailable = false, tlsAvailable = false, udpAvailable = false)
        assertNotNull(ExperimentPlanner.automaticRefusal(reading(cut)))
        assertNotNull(ExperimentPlanner.automaticRefusal(reading(cut.copy(domesticReachable = false))))
        // TLS cut while TCP passes: fragment candidates may help, so experiments still run.
        assertNull(ExperimentPlanner.automaticRefusal(reading(base.copy(internationalOk = 0))))
    }

    @Test fun measuredFilteringDecidesWhatIsTriedFirst() {
        val gen = CandidateGenerator()
        val sni = gen.hypotheses(LabFailureCategory.SNI_INTERFERENCE_SUSPECTED, base.copy(sniFiltered = true)).map { it.strategy }
        assertEquals(listOf("fragment", "ech"), sni.take(2))
        val dns = gen.hypotheses(LabFailureCategory.DNS_TAMPERED, base.copy(dnsManipulated = true)).map { it.strategy }
        assertEquals("endpoint", dns.first())
        val noV6 = ExperimentPlanner.order(listOf(Hypothesis("address-family", ""), Hypothesis("alpn", "")), base.copy(ipv6Available = false))
        assertEquals(listOf("alpn"), noV6.map { it.strategy })
        // Nothing for a category no config change can fix.
        assertEquals(emptyList<Hypothesis>(), gen.hypotheses(LabFailureCategory.NO_INTERNATIONAL_EGRESS, base))
    }
}
