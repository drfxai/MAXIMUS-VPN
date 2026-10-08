package com.example

import com.example.data.model.ConnectionState
import com.example.data.model.ConnectionStatus
import com.example.data.model.ServerTestStatus
import com.example.data.model.VlessProfile
import com.example.vpn.ServerTester
import com.example.xray.RealDelayProbe
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * While the VPN's core runs, no real test is possible and an unprotected check would go through the
 * tunnel; a working server must not be reported as down because the current exit cannot reach it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerTestWhileConnectedTest {
    private val originalInvoker = RealDelayProbe.invoker

    @After fun restore() { RealDelayProbe.invoker = originalInvoker }

    private fun profile(name: String, host: String) = VlessProfile(
        id = name, name = name, address = host, port = 9, uuid = "00000000-0000-0000-0000-000000000001",
        transport = "ws", security = "tls", sni = "a.example", host = "a.example", path = "/"
    )

    @Test fun theServerInUseReportsTheTunnelLatencyAndOthersAreNotMeasured() {
        val inUse = profile("in-use", "203.0.113.1")
        val other = profile("other", "203.0.113.2")
        val connected = ConnectionState(status = ConnectionStatus.CONNECTED, activeProfile = inUse, pingMs = 420)

        assertEquals(ServerTestStatus.Available(420), ServerTester.whileTunnelRuns(inUse, connected).status)
        assertEquals(ServerTestStatus.Slow(1800), ServerTester.whileTunnelRuns(inUse, connected.copy(pingMs = 1800)).status)
        assertEquals(ServerTestStatus.Idle, ServerTester.whileTunnelRuns(other, connected).status)
        assertEquals(ServerTestStatus.Idle, ServerTester.whileTunnelRuns(inUse, connected.copy(pingMs = null)).status)
        assertEquals(ServerTestStatus.Idle, ServerTester.whileTunnelRuns(inUse, ConnectionState()).status)
    }

    @Test fun anUnprotectedCheckWhileTheCoreRunsIsNotReportedAsDown() = runBlocking {
        RealDelayProbe.invoker = { JSONObject().put("success", false).put("error", RealDelayProbe.CORE_RUNNING).toString() }
        val result = ServerTester.testServer(profile("p", "127.0.0.1"), timeoutMs = 1000)
        assertEquals(ServerTestStatus.Idle, result.status)
    }

    @Test fun theFailoverCheckOnTheOwnNetworkStillRuns() = runBlocking {
        RealDelayProbe.invoker = { JSONObject().put("success", false).put("error", RealDelayProbe.CORE_RUNNING).toString() }
        // With socket protection the check runs on the phone's own network, as before (port 9 is closed).
        val result = ServerTester.testServer(profile("p", "127.0.0.1"), timeoutMs = 1000, protectSocket = { true })
        assertTrue(result.status is ServerTestStatus.Unavailable)
    }
}
