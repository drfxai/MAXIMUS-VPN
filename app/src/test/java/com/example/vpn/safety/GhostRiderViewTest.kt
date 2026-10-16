package com.example.vpn.safety

import com.example.data.model.ConnectionState
import com.example.data.model.ConnectionStatus
import com.example.data.model.VlessProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The dashboard model is built from real connection state, so it never claims a Tor circuit that isn't live. */
class GhostRiderViewTest {
    private fun profile(name: String) = VlessProfile(name = name, address = "1.2.3.4", port = 443, uuid = "u")

    @Test fun notConnectedShowsOnlyTheDeviceAndNoTor() {
        val m = GhostRiderView.of(ConnectionState(status = ConnectionStatus.DISCONNECTED), null)
        assertFalse(m.connected)
        assertFalse(m.onTor)
        assertEquals("Not connected", m.heroTitle)
        assertEquals(1, m.hops.size)
        assertEquals(GhostRiderView.Role.DEVICE, m.hops.first().role)
    }

    @Test fun connectedToAPlainServerIsNotClaimedAsTor() {
        val state = ConnectionState(
            status = ConnectionStatus.CONNECTED, activeProfile = profile("DE · REALITY"),
            activeEngineName = "xray", exitCountryCode = "DE", vpnIp = "104.18.1.1"
        )
        val m = GhostRiderView.of(state, null)
        assertTrue(m.connected)
        assertFalse(m.onTor)
        assertEquals("Protected", m.heroTitle)
        assertEquals(2, m.hops.size) // device + exit server
        assertEquals(GhostRiderView.Role.EXIT, m.hops.last().role)
        assertTrue(m.exitLine.contains("DE"))
    }

    @Test fun connectedThroughTheTorEngineReadsAsTorExit() {
        val state = ConnectionState(status = ConnectionStatus.CONNECTED, activeEngineName = "Tor")
        val m = GhostRiderView.of(state, null)
        assertTrue(m.onTor)
        assertEquals("Protected · Tor exit", m.heroTitle)
        assertEquals(GhostRiderView.Role.EXIT, m.hops.last().role)
        assertEquals("Tor", m.hops.last().label)
    }

    @Test fun aPsiphonTorChainShowsEveryHopEndingInTor() {
        val state = ConnectionState(status = ConnectionStatus.CONNECTED, activeProfile = profile("Psiphon → Tor"))
        val m = GhostRiderView.of(state, listOf("psiphon", "tor"))
        assertTrue(m.onTor)
        assertEquals("Protected · Tor exit", m.heroTitle)
        assertEquals(3, m.hops.size) // device + psiphon entry + tor exit
        assertEquals("Psiphon", m.hops[1].label)
        assertEquals(GhostRiderView.Role.ENTRY, m.hops[1].role)
        assertEquals("Tor", m.hops[2].label)
        assertEquals(GhostRiderView.Role.EXIT, m.hops[2].role)
    }

    @Test fun protectionsAreAlwaysOnForTheMode() {
        val m = GhostRiderView.of(ConnectionState(status = ConnectionStatus.CONNECTED, activeProfile = profile("x")), null)
        assertTrue(m.killSwitch && m.failsClosed && m.leakBlocked)
    }
}
