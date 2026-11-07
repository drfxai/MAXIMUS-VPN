package com.example

import com.example.ui.panels.servers.ServersState
import org.junit.Assert.*
import org.junit.Test

class SmartManagerLogScopeTest {
    @Test fun delayedLogsNeverAppearUnderAnotherVps() {
        val log = "DNSTT logs" to listOf("service started")
        val result = ServersState(logs = log, logsServerId = "dubai")
        assertEquals(log, result.logsFor("dubai"))
        assertNull(result.logsFor("frankfurt"))
        assertNull(ServersState(logs = log).logsFor("dubai"))
        assertNull(result.copy(logs = null).logsFor("dubai"))
    }
}
