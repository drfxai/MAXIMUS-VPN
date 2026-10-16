package com.example.vpn.sidecar

import com.example.vpn.engine.ConfigurationAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tor gets lyrebird's ready SOCKS ports instead of managing the bridge program itself. */
class PluggableTransportsTest {
    @Test fun readsTheManagedProxyProtocol() {
        assertEquals(PluggableTransports.Companion.Reply.Method("snowflake", 40123), PluggableTransports.parse("CMETHOD snowflake socks5 127.0.0.1:40123"))
        assertEquals(PluggableTransports.Companion.Reply.Done, PluggableTransports.parse("CMETHODS DONE"))
        assertTrue(PluggableTransports.parse("ENV-ERROR no TOR_PT_STATE_LOCATION") is PluggableTransports.Companion.Reply.Failed)
        // A port that is not on loopback is never handed to Tor.
        assertNull(PluggableTransports.parse("CMETHOD snowflake socks5 0.0.0.0:40123"))
        assertNull(PluggableTransports.parse("VERSION 1"))
        assertNull(PluggableTransports.parse("CMETHOD-ERROR meek_lite not supported"))
    }

    @Test fun torUsesTheReadyPortsAndManagesNoProcess() {
        val torrc = TorSidecar.torrcWithPorts(TorSidecar.SNOWFLAKE, 19050, mapOf("snowflake" to 40123)).lines()
        assertTrue(torrc.contains("ClientTransportPlugin snowflake socks5 127.0.0.1:40123"))
        assertFalse(torrc.any { " exec " in it })
        assertEquals(2, torrc.count { it.startsWith("Bridge snowflake ") })
        assertEquals(setOf("snowflake"), TorSidecar.transportsOf(TorSidecar.SNOWFLAKE))
    }

    @Test fun trojanPasswordsMayContainAnAt() {
        val p = ConfigurationAdapter.parseTrojanUri("trojan://p@ss@w0rd@jp.example.net:443/?security=tls&sni=jp.example.net#JP")!!
        assertEquals("jp.example.net", p.address)
        assertEquals(443, p.port)
        assertEquals("p@ss@w0rd", p.uuid)
    }
}
