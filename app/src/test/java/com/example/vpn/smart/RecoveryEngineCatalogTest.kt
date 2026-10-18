package com.example.vpn.smart

import com.example.vpn.connectivity.PathRef
import com.example.vpn.lab.ExperimentPlanner
import com.example.vpn.lab.PathFamily
import com.example.vpn.sidecar.ChainRunner
import com.example.vpn.sidecar.TorSidecar
import org.junit.Assert.*
import org.junit.Test

class RecoveryEngineCatalogTest {
    @Test fun privateWebTunnelAndSnowflakeFallbackAreBothKept() {
        val privateBridge = TorSidecar.profile("webtunnel 192.0.2.10:443 AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA url=https://bridge.example/path ver=0.0.1")
        val candidates = RecoveryEngineCatalog.candidates(listOf(privateBridge)) { true }
        assertTrue(candidates.any { it.id == privateBridge.id })
        assertTrue(candidates.any { it.id == "recovery-tor-snowflake" })
        assertEquals(candidates.size, candidates.map { PathRef.of(it) }.distinct().size)
    }

    @Test fun savedSnowflakeWinsItsDuplicateWithoutHidingOtherEngines() {
        val saved = TorSidecar.profile()
        val candidates = RecoveryEngineCatalog.candidates(listOf(saved)) { true }
        assertTrue(candidates.any { it.id == saved.id })
        assertFalse(candidates.any { it.id == "recovery-tor-snowflake" })
        assertEquals(4, candidates.size)
    }

    @Test fun availabilityAndSecurityAreGates() {
        val insecure = TorSidecar.profile().copy(allowInsecure = true)
        val candidates = RecoveryEngineCatalog.candidates(listOf(insecure)) { it.id == "recovery-masque" }
        assertEquals(listOf("recovery-masque"), candidates.map { it.id })
        assertTrue(RecoveryEngineCatalog.candidates(emptyList()) { false }.isEmpty())
    }

    @Test fun defaultIdentitiesStayStableBetweenSearches() {
        val a = RecoveryEngineCatalog.candidates(emptyList()) { true }
        val b = RecoveryEngineCatalog.candidates(emptyList()) { true }
        assertEquals(a.map { it.id }, b.map { it.id })
        assertEquals(a.map { PathRef.of(it) }, b.map { PathRef.of(it) })
        val chain = ChainRunner.readChain(a.first { it.id == "recovery-psiphon-masque" })!!
        assertEquals(listOf("psiphon", "masque"), chain.hops.map { it.engineId })
    }

    @Test fun masqueIsNotMisclassifiedAsMihomo() {
        val p = RecoveryEngineCatalog.candidates(emptyList()) { true }.first { it.id == "recovery-masque" }
        assertEquals(PathFamily.MASQUE, PathFamily.of(p, "masque"))
        assertTrue(PathFamily.MASQUE.udp)
        assertEquals(PathFamily.PSIPHON_MASQUE, PathFamily.of(p, "chain", "psiphon,masque"))
    }

    @Test fun measuredUdpFailureSkipsBothMasquePathsButKeepsTcpBridges() {
        val net = NetworkCapabilityProfile(udpAvailable = false, quicAvailable = false)
        val plan = ExperimentPlanner.plan(null, net, 10)
        // The emergency order must never prefer a UDP carrier when UDP has no answer.
        val order = ExperimentPlanner.emergencyOrder(net)
        assertFalse(PathFamily.MASQUE in order)
        assertFalse(PathFamily.PSIPHON_MASQUE in order)
        assertTrue(PathFamily.TOR_WEBTUNNEL in order)
        assertFalse(PathFamily.TOR_WEBTUNNEL in plan.skip)
    }
}
