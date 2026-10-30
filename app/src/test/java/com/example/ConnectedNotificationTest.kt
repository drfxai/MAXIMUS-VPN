package com.example

import com.example.vpn.ConnectedNotification
import org.junit.Assert.assertEquals
import org.junit.Test

/** The connected notification reads like "↓ 152 KB/s ↑ 30 KB/s" and "Session: 88.4 MB down · 2.0 MB up". */
class ConnectedNotificationTest {

    @Test fun sizesStayShort() {
        assertEquals("0 B", ConnectedNotification.size(0))
        assertEquals("0 B", ConnectedNotification.size(-5))
        assertEquals("89 B", ConnectedNotification.size(89))
        assertEquals("1.0 KB", ConnectedNotification.size(1024))
        assertEquals("8.4 KB", ConnectedNotification.size(8_602))
        assertEquals("152 KB", ConnectedNotification.size(152 * 1024 + 300))
        assertEquals("2.0 MB", ConnectedNotification.size(2L * 1024 * 1024))
        assertEquals("88.4 MB", ConnectedNotification.size((88.4 * 1024 * 1024).toLong()))
        assertEquals("512 MB", ConnectedNotification.size(512L * 1024 * 1024))
        assertEquals("1.5 GB", ConnectedNotification.size(1536L * 1024 * 1024))
    }

    @Test fun linesMatchTheLayout() {
        assertEquals("↓ 89 B/s ↑ 370 B/s", ConnectedNotification.speedLine(89, 370))
        assertEquals("Session: 88.4 MB down · 2.0 MB up",
            ConnectedNotification.sessionLine((88.4 * 1024 * 1024).toLong(), 2L * 1024 * 1024))
        assertEquals("↓ 152 KB/s ↑ 30 KB/s\nSession: 2.0 MB down · 1.0 KB up",
            ConnectedNotification.expandedText(152 * 1024, 30 * 1024, 2L * 1024 * 1024, 1024))
    }

    @Test fun coreNamesTheEngineCarryingTraffic() {
        assertEquals("Xray", ConnectedNotification.coreLabel(kotlinTunnel = false, sidecarId = null, chain = false))
        assertEquals("Maximus", ConnectedNotification.coreLabel(kotlinTunnel = true, sidecarId = null, chain = false))
        assertEquals("Tor", ConnectedNotification.coreLabel(kotlinTunnel = false, sidecarId = "tor", chain = false))
        assertEquals("DNS tunnel", ConnectedNotification.coreLabel(kotlinTunnel = false, sidecarId = "dns-tunnel", chain = false))
        assertEquals("Newengine", ConnectedNotification.coreLabel(kotlinTunnel = false, sidecarId = "newengine", chain = false))
        assertEquals("Engine chain", ConnectedNotification.coreLabel(kotlinTunnel = false, sidecarId = "tor", chain = true))
    }
}
