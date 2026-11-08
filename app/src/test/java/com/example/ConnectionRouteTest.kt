package com.example

import com.example.data.model.ConnectionRoute
import com.example.vpn.sidecar.AetherSidecar
import com.example.vpn.sidecar.ServerlessSidecar
import com.example.vpn.sidecar.SidecarProcess
import org.junit.Assert.*
import org.junit.Test

class ConnectionRouteTest {
    @Test fun resolvedDirectMethodIsVisibleEvenWithCustomAlias() {
        assertEquals("Direct Serverless · OOB", ConnectionRoute.label(ServerlessSidecar.profile("oob").copy(name = "Dr")))
        assertEquals("Direct Serverless · Fragment A", ConnectionRoute.label(ServerlessSidecar.profile("fragA")))
    }
    @Test fun autoDoesNotInventAWireGuardRoute() {
        assertEquals("Aether WARP · transport not reported", ConnectionRoute.label(AetherSidecar.profile()))
        assertEquals("Aether WARP · MASQUE HTTP/2", ConnectionRoute.label(AetherSidecar.profile("h2")))
        assertEquals("Aether WARP · WireGuard", ConnectionRoute.label(AetherSidecar.profile("wireguard")))
    }
    @Test fun multilineCauseIsRecognizedWithoutErrorPrefix() {
        assertTrue(SidecarProcess.isFailureLine("Caused by: deadline has elapsed"))
        assertEquals("network operation timed out", SidecarProcess.failureCategory("Caused by: deadline has elapsed"))
        assertEquals("network operation timed out", SidecarProcess.preferFailure("network operation timed out", "registration failed"))
        assertFalse(SidecarProcess.isFailureLine("TLS verification enabled"))
    }
}
