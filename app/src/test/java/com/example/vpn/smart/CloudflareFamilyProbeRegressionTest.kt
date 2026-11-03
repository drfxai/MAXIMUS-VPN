package com.example.vpn.smart

import com.example.vpn.smart.NetworkCapabilityDetector as Detector
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudflareFamilyProbeRegressionTest {
    @Test fun ipv4AndIpv6EvidenceAreIndependent() {
        assertTrue(Detector.cloudflareFamilyReachability(true, false, true, true) == true)
        assertTrue(Detector.cloudflareFamilyReachability(false, true, true, true) == true)
        assertEquals(false, Detector.cloudflareFamilyReachability(false, false, true, true))
        assertEquals(false, Detector.cloudflareFamilyReachability(false, null, true, false))
        assertNull(Detector.cloudflareFamilyReachability(false, null, true, true))
        assertNull(Detector.cloudflareFamilyReachability(null, null, false, false))
        assertNull(Detector.cloudflareFamilyReachability(null, false, true, true))
    }

    @Test fun onlyCompletedTlsIsAConfirmedCloudflarePath() {
        assertEquals(true, Detector.verifiedCloudflareTls(Detector.TlsOutcome.COMPLETED))
        assertEquals(false, Detector.verifiedCloudflareTls(Detector.TlsOutcome.SERVER_ANSWERED))
        assertEquals(false, Detector.verifiedCloudflareTls(Detector.TlsOutcome.INTERFERED))
        assertNull(Detector.verifiedCloudflareTls(Detector.TlsOutcome.NOT_RUN))
        assertNull(Detector.verifiedCloudflareTls(null))
    }

    @Test fun measuredFamiliesSurviveNetworkProfileJsonRoundTrip() {
        val original = NetworkCapabilityProfile(
            transport = "cellular", ipv4Available = true, ipv6Available = true,
            cloudflareIpv4Tls = true, cloudflareIpv6Tls = false,
            cloudflareReachable = true, carrierCode = "43235"
        )
        val restored = NetworkCapabilityProfile.fromJson(original.toJson())
        assertEquals(original.cloudflareIpv4Tls, restored.cloudflareIpv4Tls)
        assertEquals(original.cloudflareIpv6Tls, restored.cloudflareIpv6Tls)
        assertEquals(original.cloudflareReachable, restored.cloudflareReachable)
        assertTrue(restored.observations().any { it == "Cloudflare IPv6 TLS: no" })
        assertNull(NetworkCapabilityProfile.fromJson(
            org.json.JSONObject("""{"transport":"wifi"}""")).cloudflareIpv4Tls)
    }
}
