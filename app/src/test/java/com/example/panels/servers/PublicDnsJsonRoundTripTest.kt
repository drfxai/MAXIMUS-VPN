package com.example.panels.servers

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PublicDnsJsonRoundTripTest {
    @Test fun emptyPublicInventorySurvivesEncryptedStoreEncoding() {
        val server = ManagedServer(
            name = "VPS", host = "198.51.100.20", location = ServerLocation.ABROAD,
            facts = ServerFacts(
                socketInspectionAvailable = true, udpPorts = setOf(53),
                tcpPorts = setOf(22, 53), publicUdpPorts = emptySet(),
                publicTcpPorts = setOf(22)
            )
        )
        val stored = ServerJson.decode(ServerJson.encode(listOf(server))).single()
        assertEquals(emptySet<Int>(), stored.facts?.publicUdpPorts)
        assertEquals(setOf(22), stored.facts?.publicTcpPorts)
        assertEquals(setOf(53), stored.facts?.udpPorts)
    }

    @Test fun olderSavedServersDoNotMagicallyClaimPublicPortsAreFree() {
        val server = ManagedServer(
            name = "legacy", host = "198.51.100.21", location = ServerLocation.ABROAD,
            facts = ServerFacts(socketInspectionAvailable = true, udpPorts = setOf(53))
        )
        val restored = ServerJson.decode(ServerJson.encode(listOf(server))).single()
        assertNull(restored.facts?.publicUdpPorts)
        assertTrue(53 in (restored.facts?.udpPorts ?: emptySet()))
    }
}
