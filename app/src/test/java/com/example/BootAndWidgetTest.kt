package com.example

import com.example.data.model.ConnectionState
import com.example.data.model.ConnectionStatus
import com.example.vpn.boot.BootPolicy
import com.example.vpn.boot.BootPolicy.Decision
import com.example.vpn.widget.WidgetLabels
import org.junit.Assert.assertEquals
import org.junit.Test

class BootAndWidgetTest {

    @Test
    fun bootConnectsOnlyWhenEnabledPermittedAndNotAlreadyHandled() {
        assertEquals(Decision.OFF, BootPolicy.decide(false, true, false, false))
        assertEquals(Decision.CONNECT, BootPolicy.decide(true, true, false, false))
        assertEquals(Decision.NEEDS_PERMISSION, BootPolicy.decide(true, false, false, false))
        assertEquals(Decision.ALWAYS_ON_HANDLES_IT, BootPolicy.decide(true, true, true, false))
        assertEquals(Decision.ALREADY_RUNNING, BootPolicy.decide(true, true, false, true))
    }

    @Test
    fun widgetLabelsFollowTheConnection() {
        assertEquals("Connect", WidgetLabels.of(ConnectionState()).action)
        assertEquals("Disconnected", WidgetLabels.of(ConnectionState()).status)
        val connected = WidgetLabels.of(ConnectionState(status = ConnectionStatus.CONNECTED))
        assertEquals("Connected", connected.status)
        assertEquals("Disconnect", connected.action)
        assertEquals("Connecting…", WidgetLabels.of(ConnectionState(status = ConnectionStatus.CONNECTING)).status)
    }
}
