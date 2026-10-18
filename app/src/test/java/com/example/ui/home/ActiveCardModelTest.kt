package com.example.ui.home

import com.example.data.model.ConnectionState
import com.example.data.model.ConnectionStatus
import com.example.data.model.VlessProfile
import com.example.vpn.diagnostics.LiveTunnelProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The home card shows the measured exit, never the server's own address or a made-up value. */
class ActiveCardModelTest {
    private val vless15 = VlessProfile(name = "US · VLESS 15 · YT TG X", address = "188.114.97.6", port = 443,
        uuid = "00000000-0000-0000-0000-000000000000", countryCode = "DE", lastLatencyMs = 777)

    @Test fun offShowsTheConfigSelectedInNodesWithNothingMeasured() {
        val m = ActiveCardModel.of(ConnectionState(), vless15)!!
        assertFalse(m.live)
        assertEquals("Selected in Nodes", m.label)
        assertEquals("US · VLESS 15 · YT TG X", m.title)
        assertEquals("Last test 777 ms", m.subtitle)
        assertEquals(ActiveCardModel.DASH, m.ping)
        assertEquals(ActiveCardModel.DASH, m.download)
        assertEquals("Off", m.status)
    }

    @Test fun connectedShowsTheExitCountryAndIpFromTheTunnelCheck() {
        val state = ConnectionState(status = ConnectionStatus.CONNECTED, activeProfile = vless15, exitCountryCode = "US",
            exitIp = "104.28.214.67", pingMs = 692, downloadSpeedBps = 1_300_000, uploadSpeedBps = 190_000)
        val m = ActiveCardModel.of(state, null)!!
        assertTrue(m.live)
        assertEquals("United States", m.title)
        assertEquals("🇺🇸", m.flag)
        assertEquals("104.28.214.67", m.exitIp)
        assertEquals("692 ms", m.ping)
        assertEquals("Verified", m.status)
        assertEquals("1.2 MB/s", m.download)
        assertEquals("185.5 KB/s", m.upload)
        assertEquals("US · VLESS 15 · YT TG X", m.configName)
    }

    @Test fun anUnmeasuredExitIsSaidSoAndTheServersCountryIsNotUsed() {
        val state = ConnectionState(status = ConnectionStatus.TUNNEL_STARTED_CONNECTIVITY_UNVERIFIED, activeProfile = vless15)
        val m = ActiveCardModel.of(state, null)!!
        assertEquals("Exit not measured yet", m.title)
        assertEquals("🌐", m.flag)
        assertEquals("Not verified", m.status)
        assertEquals(ActiveCardModel.DASH, m.exitIp)
        assertEquals(ActiveCardModel.DASH, m.download)
    }

    @Test fun nothingSelectedAndNoTunnelShowsNoCard() {
        assertNull(ActiveCardModel.of(ConnectionState(), null))
    }

    @Test fun theTracePageGivesCountryAndIpAndRejectsAnythingElse() {
        val e = LiveTunnelProbe.parseTrace("fl=1\nh=www.cloudflare.com\nip=104.28.214.67\nts=1\nloc=US\nwarp=off\n")
        assertEquals("US", e.country)
        assertEquals("104.28.214.67", e.ip)
        assertEquals("2a09:bac1:1::42", LiveTunnelProbe.parseTrace("ip=2a09:bac1:1::42\nloc=NL").ip)
        val bad = LiveTunnelProbe.parseTrace("ip=<script>\nloc=usa")
        assertNull(bad.ip)
        assertNull(bad.country)
    }

    @Test fun signalBarsFollowThePingAndStayEmptyWhenUnmeasured() {
        assertEquals(0, ActiveCardModel.signalBars(null))
        assertEquals(4, ActiveCardModel.signalBars(120))
        assertEquals(3, ActiveCardModel.signalBars(692))
        assertEquals(2, ActiveCardModel.signalBars(1499))
        assertEquals(1, ActiveCardModel.signalBars(2400))
        val off = ActiveCardModel.of(ConnectionState(), vless15)!!
        assertEquals(0, off.bars)
        assertEquals("Selected in Nodes · last test 777 ms", ActiveCardModel.offLine(off))
    }
}
