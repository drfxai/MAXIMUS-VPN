package com.example.vpn.verify

import com.example.vpn.diagnostics.LiveTunnelProbe
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 2026-11-25 (Irancell): Smart Connect picked Serverless Direct because www.gstatic.com answered, while YouTube and
 * Instagram failed TLS and Telegram timed out. A path must also reach a filtered site before Smart Connect keeps it.
 */
class FilteredSiteProbeTest {
    @Test fun oneAnswerFromAFilteredSitePasses() {
        val asked = mutableListOf<String>()
        val r = FilteredSiteProbe.probe { t ->
            asked += t.host
            if (t.host == "www.youtube.com") throw IOException("reset") else 200
        }
        assertTrue(r.passed)
        assertEquals(listOf("www.youtube.com", "web.telegram.org"), asked)
    }

    @Test fun noAnswerFromAnyIsBlocked() {
        val r = FilteredSiteProbe.probe { throw IOException("timeout") }
        assertEquals(FilteredSiteProbe.Verdict.BLOCKED, r.verdict)
        assertTrue(r.detail.contains("www.youtube.com") && r.detail.contains("web.telegram.org"))
    }

    @Test fun requiredForAutomaticPicksAndForTheIspExitOnly() {
        assertTrue(FilteredSiteProbe.required(smart = true, exact = false, ispExit = false))
        assertFalse(FilteredSiteProbe.required(smart = true, exact = true, ispExit = false))
        assertFalse(FilteredSiteProbe.required(smart = false, exact = true, ispExit = false))
        assertTrue(FilteredSiteProbe.required(smart = false, exact = true, ispExit = true))
    }

    @Test fun fetchTextReadsAStatusAndBodySoTheExitCanBeMeasuredThroughAnEnginePort() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("fl=1\nip=46.101.99.70\nloc=DE\nwarp=off\n"))
        server.start()
        try {
            val direct = Dialer { host, port, timeoutMs -> Socket().apply { connect(InetSocketAddress(host, port), timeoutMs) } }
            val (status, body) = TrafficCheck(direct, tls = false, stepTimeoutMs = 3_000)
                .fetchText(TrafficCheck.Target("127.0.0.1", server.port, "/cdn-cgi/trace"))
            assertEquals(200, status)
            val exit = LiveTunnelProbe.parseTrace(body)
            assertEquals("DE", exit.country)
            assertEquals("46.101.99.70", exit.ip)
        } finally {
            server.shutdown()
        }
    }
}
