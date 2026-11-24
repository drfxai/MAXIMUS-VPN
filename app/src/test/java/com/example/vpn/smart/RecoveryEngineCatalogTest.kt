package com.example.vpn.smart

import com.example.vpn.connectivity.PathRef
import com.example.vpn.lab.ExperimentPlanner
import com.example.vpn.lab.PathFamily
import com.example.vpn.sidecar.ChainRunner
import com.example.vpn.sidecar.TorSidecar
import com.example.vpn.sidecar.PsiphonSidecar
import org.junit.Assert.*
import org.junit.Test

class RecoveryEngineCatalogTest {
    @Test fun privateWebTunnelAndSnowflakeFallbackAreBothKept() {
        val privateBridge = TorSidecar.profile("webtunnel 192.0.2.10:443 AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA url=https://bridge.example/path ver=0.0.1")
        val candidates = RecoveryEngineCatalog.candidates(listOf(privateBridge), seedBridges = emptyList()) { true }
        assertTrue(candidates.any { it.id == privateBridge.id })
        assertTrue(candidates.any { it.id == "recovery-tor-snowflake" })
        assertEquals(candidates.size, candidates.map { PathRef.of(it) }.distinct().size)
    }

    @Test fun savedSnowflakeWinsItsDuplicateWithoutHidingOtherEngines() {
        val saved = TorSidecar.profile()
        val candidates = RecoveryEngineCatalog.candidates(listOf(saved), seedBridges = emptyList()) { true }
        assertTrue(candidates.any { it.id == saved.id })
        assertFalse(candidates.any { it.id == "recovery-tor-snowflake" })
        assertEquals(12, candidates.size)
    }

    @Test fun availabilityAndSecurityAreGates() {
        val insecure = TorSidecar.profile().copy(allowInsecure = true)
        val candidates = RecoveryEngineCatalog.candidates(listOf(insecure), seedBridges = emptyList()) { it.id == "recovery-masque" }
        assertEquals(listOf("recovery-masque"), candidates.map { it.id })
        assertTrue(RecoveryEngineCatalog.candidates(emptyList(), seedBridges = emptyList()) { false }.isEmpty())
    }

    @Test fun radianceIsAnIndependentRecoveryFamily() {
        val candidates = RecoveryEngineCatalog.candidates(emptyList(), seedBridges = emptyList()) { true }
        val profile = candidates.single { it.id == "recovery-radiance" }
        assertEquals(PathFamily.RADIANCE, PathFamily.of(profile, "radiance"))
        assertTrue(PathFamily.RADIANCE in ExperimentPlanner.emergencyOrder(null))
    }

    @Test fun psiphonCanUseRadianceAsItsEntryWithoutReplacingDirectRadiance() {
        val candidates = RecoveryEngineCatalog.candidates(emptyList(), seedBridges = emptyList()) { true }
        val chained = candidates.single { it.id == "recovery-psiphon-radiance" }
        val chain = checkNotNull(ChainRunner.readChain(chained))
        assertEquals(listOf("psiphon", "radiance"), chain.hops.map { it.engineId })
        assertEquals(PathFamily.PSIPHON_RADIANCE, PathFamily.of(chained, "chain", "psiphon,radiance"))
        assertTrue(candidates.any { it.id == "recovery-radiance" })
    }

    @Test fun psiphonTransportFollowsTheCurrentNetwork() {
        val normal = RecoveryEngineCatalog.candidates(emptyList(), seedBridges = emptyList()) { true }
        assertFalse(PsiphonSidecar.tcpOnly(normal.single { it.id == "recovery-psiphon" }))
        val constrained = RecoveryEngineCatalog.candidates(emptyList(), seedBridges = emptyList(), tcpOnly = true) { true }
        assertTrue(PsiphonSidecar.tcpOnly(constrained.single { it.id == "recovery-psiphon-tcp" }))
    }

    @Test fun defaultIdentitiesStayStableBetweenSearches() {
        val a = RecoveryEngineCatalog.candidates(emptyList(), seedBridges = emptyList()) { true }
        val b = RecoveryEngineCatalog.candidates(emptyList(), seedBridges = emptyList()) { true }
        assertEquals(a.map { it.id }, b.map { it.id })
        assertEquals(a.map { PathRef.of(it) }, b.map { PathRef.of(it) })
        val chain = ChainRunner.readChain(a.first { it.id == "recovery-psiphon-masque" })!!
        assertEquals(listOf("psiphon", "masque"), chain.hops.map { it.engineId })
    }

    @Test fun shirokhorshidFollowsDirectPsiphonAndLeadsWhereDirectPsiphonFailed() {
        fun ids(failed: Boolean) = RecoveryEngineCatalog.candidates(emptyList(), seedBridges = emptyList(),
            psiphonDirectFailed = failed) { true }.map { it.id }
        val normal = ids(false)
        assertEquals(normal.indexOf("recovery-psiphon") + 1, normal.indexOf(RecoveryEngineCatalog.SHIROKHORSHID_ID))
        val failed = ids(true)
        assertEquals(failed.indexOf("recovery-psiphon") - 1, failed.indexOf(RecoveryEngineCatalog.SHIROKHORSHID_ID))
        assertEquals(normal.toSet(), failed.toSet())
        val profile = RecoveryEngineCatalog.candidates(emptyList(), seedBridges = emptyList()) { true }
            .single { it.id == RecoveryEngineCatalog.SHIROKHORSHID_ID }
        assertEquals(PathFamily.PSIPHON, PathFamily.of(profile, "shirokhorshid"))
        // Both Psiphon candidates share a family, so the planner takes them in this order.
        val pool = com.example.vpn.lab.FullAnalysis.select(
            RecoveryEngineCatalog.candidates(emptyList(), seedBridges = emptyList(), psiphonDirectFailed = true) { true }
                .filter { it.id == "recovery-psiphon" || it.id == RecoveryEngineCatalog.SHIROKHORSHID_ID }
                .map { it to PathFamily.PSIPHON },
            ExperimentPlanner.plan(null, null, 4))
        assertEquals(listOf(RecoveryEngineCatalog.SHIROKHORSHID_ID, "recovery-psiphon"), pool.map { it.profile.id })
    }

    @Test fun masqueIsNotMisclassifiedAsMihomo() {
        val p = RecoveryEngineCatalog.candidates(emptyList(), seedBridges = emptyList()) { true }.first { it.id == "recovery-masque" }
        assertEquals(PathFamily.MASQUE, PathFamily.of(p, "masque"))
        assertTrue(PathFamily.MASQUE.udp)
        assertEquals(PathFamily.PSIPHON_MASQUE, PathFamily.of(p, "chain", "psiphon,masque"))
    }

    @Test fun measuredUdpFailureSkipsBothMasquePathsButKeepsTcpBridges() {
        val net = NetworkCapabilityProfile(udpAvailable = false, quicAvailable = false, tcpAvailable = true, tlsAvailable = true)
        val plan = ExperimentPlanner.plan(com.example.vpn.lab.NetworkStateClassifier.classify(net), net, 10)
        // The emergency order must never prefer a UDP carrier when UDP has no answer.
        val order = ExperimentPlanner.emergencyOrder(net)
        assertFalse(PathFamily.MASQUE in order)
        assertFalse(PathFamily.PSIPHON_MASQUE in order)
        assertTrue(PathFamily.TOR_WEBTUNNEL in order)
        assertFalse(PathFamily.TOR_WEBTUNNEL in plan.skip)
        assertTrue(PathFamily.MASQUE in plan.skip)
        assertTrue(PathFamily.PSIPHON_MASQUE in plan.skip)
    }
    @Test fun publicWebTunnelSeedsAreSeparateCandidatesAndNeverMarkThemselvesVerified() {
        val lines = listOf("webtunnel 192.0.2.3:443 AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA url=https://bridge.example/path ver=0.0.1")
        val candidates = RecoveryEngineCatalog.candidates(emptyList(), seedBridges = lines) { true }
        val seed = candidates.single { it.id == "recovery-webtunnel-0" }
        assertEquals(lines, TorSidecar.bridges(seed))
        assertNull(seed.lastLatencyMs)
        assertTrue(candidates.any { it.id == "recovery-tor-snowflake" })
    }

}
