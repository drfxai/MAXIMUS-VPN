package com.example.vpn.lab

import com.example.data.model.VlessProfile
import com.example.vpn.connections.BuiltinMethodMemory
import com.example.vpn.connections.ConnectionEngineRunner
import com.example.vpn.sidecar.AetherSidecar
import com.example.vpn.smart.NetworkCapabilityProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cloudflare blocked on the network (cf=0, the Irancell field case): WARP MASQUE, Matrix Transport and Aether
 * still run, but after everything else; a network where Cloudflare answered (cf=1) keeps its order.
 */
class CloudflareLastTest {
    private val open = NetworkCapabilityProfile(transport = "cellular", ipv4Available = true, ipv6Available = true,
        tcpAvailable = true, tlsAvailable = true, udpAvailable = true, cloudflareReachable = true,
        internationalOk = 2, internationalTried = 2, domesticReachable = true, measuredAt = 5L)
    private val cf0 = open.copy(cloudflareReachable = false)
    private val cut = open.copy(internationalOk = 0, tcpAvailable = false, tlsAvailable = false, cloudflareReachable = false)
    private fun reading(p: NetworkCapabilityProfile) = NetworkStateClassifier.classify(p, now = 1L)
    private fun plan(p: NetworkCapabilityProfile) = ExperimentPlanner.plan(reading(p), p, 15)
    private fun profile(id: String) = VlessProfile(id = id, name = id, address = "203.0.113.1", port = 443, uuid = "u")

    @Test fun onlyFamiliesThatDialCloudflareFromThePhoneGoLast() {
        assertEquals(setOf(PathFamily.MASQUE, PathFamily.MATRIX_H2, PathFamily.MATRIX_H3, PathFamily.MATRIX_WG, PathFamily.MATRIX_GOOL),
            ExperimentPlanner.cloudflareLast(cf0))
        // Psiphon over MASQUE/Matrix reaches Cloudflare through Psiphon, not over this network.
        assertFalse(PathFamily.PSIPHON_MASQUE in ExperimentPlanner.cloudflareLast(cf0))
        assertFalse(PathFamily.PSIPHON_MATRIX in ExperimentPlanner.cloudflareLast(cf0))
        // cf=1 or not measured: nothing moves.
        assertTrue(ExperimentPlanner.cloudflareLast(open).isEmpty())
        assertTrue(ExperimentPlanner.cloudflareLast(open.copy(cloudflareReachable = null)).isEmpty())
        assertTrue(ExperimentPlanner.cloudflareLast(null).isEmpty())
    }

    @Test fun anOrdinaryCf0PlanPutsMatrixLastButKeepsIt() {
        val p = plan(cf0)
        assertFalse(PathFamily.MATRIX_H2 in p.prefer)
        assertTrue(PathFamily.MATRIX_H2 in p.lowPrior)
        assertFalse(PathFamily.MATRIX_H2 in p.skip)
        assertTrue(PathFamily.PSIPHON in p.prefer)
        assertTrue(PathFamily.PSIPHON_MATRIX in p.prefer)
        assertTrue(p.reasons.any { it.contains("Cloudflare") })
        // The same network with Cloudflare answering keeps Matrix among the preferred engines.
        val q = plan(open)
        assertTrue(PathFamily.MATRIX_H2 in q.prefer)
        assertTrue(q.lowPrior.none { it in ExperimentPlanner.CLOUDFLARE_FAMILIES })
    }

    @Test fun anEmergencyCf0PlanTriesMasqueAndMatrixAfterTheOtherRecoveryFamilies() {
        val p = plan(cut)
        assertEquals(ExperimentPlanner.Mode.EMERGENCY_RECOVERY, p.mode)
        ExperimentPlanner.CLOUDFLARE_FAMILIES.forEach {
            assertFalse(it.name, it in p.prefer)
            assertTrue(it.name, it in p.lowPrior)
            assertFalse(it.name, it in p.skip)
        }
        assertTrue(PathFamily.PSIPHON_MASQUE in p.prefer)
        val picks = FullAnalysis.select(listOf(
            profile("masque") to PathFamily.MASQUE,
            profile("matrix") to PathFamily.MATRIX_H2,
            profile("psiphon") to PathFamily.PSIPHON,
            profile("tor") to PathFamily.TOR_OBFS4), p)
        assertEquals(listOf("psiphon", "tor"), picks.take(2).map { it.profile.id })
        assertEquals(setOf("masque", "matrix"), picks.drop(2).map { it.profile.id }.toSet())
    }

    @Test fun theParallelRoundLeavesCloudflareCandidatesOutOnlyOnCf0() {
        assertTrue(ExperimentPlanner.cloudflareDeferred(cf0, PathFamily.MASQUE, "masque"))
        assertTrue(ExperimentPlanner.cloudflareDeferred(cf0, PathFamily.MATRIX_H3, "matrix-transport"))
        // A saved Aether profile, whatever family its fields map to.
        assertTrue(ExperimentPlanner.cloudflareDeferred(cf0, PathFamily.MIHOMO, AetherSidecar.ID))
        assertFalse(ExperimentPlanner.cloudflareDeferred(cf0, PathFamily.PSIPHON, "psiphon"))
        assertFalse(ExperimentPlanner.cloudflareDeferred(cf0, PathFamily.PSIPHON_MASQUE, "chain"))
        assertFalse(ExperimentPlanner.cloudflareDeferred(open, PathFamily.MASQUE, "masque"))
        assertFalse(ExperimentPlanner.cloudflareDeferred(null, PathFamily.MASQUE, AetherSidecar.ID))
    }

    @Test fun aetherCountsAsVerifiedHereOnlyForItsOwnNetworkAndOptionsWithinTheTtl() {
        val m = BuiltinMethodMemory
        val previousRead = m.read
        val previousWrite = m.write
        val values = mutableMapOf<String, String>()
        try {
            m.read = { values[it] }
            m.write = { k, v -> if (v == null) values.remove(k) else values[k] = v }
            val aether = AetherSidecar.profile("auto", "auto")
            val dns = "https://8.8.8.8/dns-query"
            assertFalse(ConnectionEngineRunner.aetherVerifiedHere("cell:43235", aether, dns, now = 2_000))
            m.record(ConnectionEngineRunner.methodScope("cell:43235", AetherSidecar.ID, aether, dns), "wireguard", now = 1_000)
            assertTrue(ConnectionEngineRunner.aetherVerifiedHere("cell:43235", aether, dns, now = 2_000))
            // Asking never clears what the engine will try first.
            assertTrue(m.verifiedRecently(ConnectionEngineRunner.methodScope("cell:43235", AetherSidecar.ID, aether, dns), now = 2_000))
            // Another network, other DNS, or an expired entry is not evidence here.
            assertFalse(ConnectionEngineRunner.aetherVerifiedHere("cell:43211", aether, dns, now = 2_000))
            assertFalse(ConnectionEngineRunner.aetherVerifiedHere("cell:43235", aether, "https://1.1.1.1/dns-query", now = 2_000))
            assertFalse(ConnectionEngineRunner.aetherVerifiedHere("cell:43235", aether, dns, now = 1_000 + m.TTL_MS))
            assertFalse(ConnectionEngineRunner.aetherVerifiedHere(null, aether, dns, now = 2_000))
            // Not Aether, or a carrier the user chose: never counted.
            assertFalse(ConnectionEngineRunner.aetherVerifiedHere("cell:43235", profile("x"), dns, now = 2_000))
            assertFalse(ConnectionEngineRunner.aetherVerifiedHere("cell:43235", AetherSidecar.profile("h3", "auto"), dns, now = 2_000))
            assertFalse(m.verifiedRecently(null, now = 2_000))
        } finally {
            m.read = previousRead
            m.write = previousWrite
        }
    }
}
