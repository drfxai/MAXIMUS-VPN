package com.example.vpn.verify

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteProbeStepsTest {
    @Test fun multiHopEnginesAndChainsGetLongerStepsThanSingleProxies() {
        assertEquals(RouteProbeSteps.DEFAULT_MS, RouteProbeSteps.stepMs(emptyList(), dnsTunnel = false))
        assertEquals(RouteProbeSteps.DEFAULT_MS, RouteProbeSteps.stepMs(listOf("serverless"), dnsTunnel = false))
        assertEquals(RouteProbeSteps.DEFAULT_MS, RouteProbeSteps.stepMs(listOf("radiance"), dnsTunnel = false))
        assertEquals(RouteProbeSteps.MULTI_HOP_MS, RouteProbeSteps.stepMs(listOf("tor"), dnsTunnel = false))
        assertEquals(RouteProbeSteps.MULTI_HOP_MS, RouteProbeSteps.stepMs(listOf("psiphon"), dnsTunnel = false))
        assertEquals(RouteProbeSteps.MULTI_HOP_MS, RouteProbeSteps.stepMs(listOf("shirokhorshid"), dnsTunnel = false))
        assertEquals(RouteProbeSteps.MULTI_HOP_MS, RouteProbeSteps.stepMs(listOf("psiphon", "radiance"), dnsTunnel = false))
        assertEquals(RouteProbeSteps.DNS_TUNNEL_MS, RouteProbeSteps.stepMs(listOf("dns-tunnel"), dnsTunnel = true))
    }
}
