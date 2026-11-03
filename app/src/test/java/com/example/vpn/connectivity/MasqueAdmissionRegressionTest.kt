package com.example.vpn.connectivity

import com.example.vpn.sidecar.MasqueSidecar
import com.example.vpn.smart.NetworkCapabilityProfile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MasqueAdmissionRegressionTest {
    private val p = MasqueSidecar.profile()

    @Test fun masqueIsUdpEvenWithoutTheH3AlpnField() {
        assertEquals(TransportCapabilityEngine.Transport.UDP,
            TransportCapabilityEngine.transportOf(p))
    }

    @Test fun measuredBlockedUdpOrQuicNeverHammersMasque() {
        for (net in listOf(
            NetworkCapabilityProfile(udpAvailable = false, quicAvailable = true),
            NetworkCapabilityProfile(udpAvailable = true, quicAvailable = false)
        )) {
            val c = TransportCapabilityEngine.capabilities(net)
            assertEquals(TransportCapabilityEngine.Admission.SKIP,
                TransportCapabilityEngine.decide(p, c).admission)
        }
    }

    @Test fun missingCloudflarePathOnlyDeprioritizesNotPermanentlyBlocks() {
        val degraded = TransportCapabilityEngine.capabilities(NetworkCapabilityProfile(
            udpAvailable = true, quicAvailable = true, cloudflareReachable = false))
        assertEquals(TransportCapabilityEngine.Admission.DEPRIORITIZE,
            TransportCapabilityEngine.decide(p, degraded).admission)
        val ready = TransportCapabilityEngine.capabilities(NetworkCapabilityProfile(
            udpAvailable = true, quicAvailable = true, cloudflareReachable = true))
        assertEquals(TransportCapabilityEngine.Admission.ALLOW,
            TransportCapabilityEngine.decide(p, ready).admission)
    }
}
