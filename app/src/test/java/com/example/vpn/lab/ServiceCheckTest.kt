package com.example.vpn.lab

import com.example.vpn.lab.ServiceCheck.Response
import com.example.vpn.lab.ServiceCheck.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServiceCheckTest {
    private val gemini = ServiceCheck.SERVICES.first { it.id == "gemini" }
    private fun judge(status: Int, url: String = gemini.url, body: String = "") =
        ServiceCheck.judge(gemini, Response(status, url, body, 120)).verdict

    @Test fun pageThatLoadsWorks() = assertEquals(Verdict.WORKS, judge(200, body = "<title>Gemini</title>"))

    @Test fun regionWordsInsideAWorkingPageDoNotCount() =
        assertEquals(Verdict.WORKS, judge(200, body = "<title>Gemini</title><script>msg='not available in your country'</script>"))

    @Test fun regionWordsInTheTitleAreARegionBlock() =
        assertEquals(Verdict.REGION_BLOCKED, judge(200, body = "<title>Gemini isn't available in your country</title>"))

    @Test fun regionWordsInAnErrorAnswerAreARegionBlock() =
        assertEquals(Verdict.REGION_BLOCKED, judge(403, body = "<p>unsupported_country</p>"))

    @Test fun status451IsARegionBlock() = assertEquals(Verdict.REGION_BLOCKED, judge(451))

    @Test fun redirectToSorryOrUnsupportedIsARegionBlock() {
        assertEquals(Verdict.REGION_BLOCKED, judge(200, url = "https://www.google.com/sorry/index"))
        assertEquals(Verdict.REGION_BLOCKED, judge(200, url = "https://ai.google.dev/unsupported-region"))
    }

    @Test fun filterPageIsFiltered() {
        assertEquals(Verdict.FILTERED, judge(200, url = "http://peyvandha.ir/0.htm"))
        assertEquals(Verdict.FILTERED, judge(200, url = "http://10.10.34.35/"))
        assertEquals(Verdict.FILTERED, judge(200, body = "<iframe src=\"http://peyvandha.ir\">"))
    }

    @Test fun cloudflareChallengeMeansReached() = assertEquals(Verdict.WORKS, judge(403, body = "<title>Just a moment...</title>"))

    @Test fun plain403IsRefused() = assertEquals(Verdict.REFUSED, judge(403, body = "denied"))

    @Test fun failedRequestIsNoConnection() =
        assertEquals(Verdict.NO_CONNECTION, ServiceCheck.judge(gemini, null, "timeout").verdict)

    @Test fun traceGivesExit() {
        val exit = ServiceCheck.parseTrace("fl=1\nip=203.0.113.9\nts=1\nloc=de\nwarp=off\n")!!
        assertEquals("203.0.113.9", exit.ip)
        assertEquals("DE", exit.country)
        assertNull(ServiceCheck.parseTrace("<html>blocked</html>"))
        assertNull(ServiceCheck.parseTrace(null))
    }

    @Test fun flagFromCountry() {
        assertEquals("🇩🇪", ServiceCheck.flag("DE"))
        assertEquals("", ServiceCheck.flag("X1"))
    }

    @Test fun runChecksEveryServiceAndStops() {
        val seen = mutableListOf<String>()
        val report = ServiceCheck.run(fetch = { url ->
            seen += url
            when {
                url == ServiceCheck.TRACE_URL -> Response(200, url, "ip=198.51.100.4\nloc=NL", 50)
                "youtube" in url -> throw java.io.IOException("reset")
                else -> Response(200, url, "<title>ok</title>", 80)
            }
        }, throughVpn = true, now = 7)
        assertEquals(ServiceCheck.SERVICES.size, report.results.size)
        assertEquals("NL", report.exit?.country)
        assertEquals(Verdict.NO_CONNECTION, report.results.first { it.service.id == "youtube" }.verdict)
        assertEquals(ServiceCheck.SERVICES.size + 1, seen.size)

        var n = 0
        val stopped = ServiceCheck.run(fetch = { Response(200, it, "", 1) }, throughVpn = false, cancelled = { n++ >= 2 })
        assertEquals(Verdict.NOT_RUN, stopped.results.last().verdict)
    }
}
