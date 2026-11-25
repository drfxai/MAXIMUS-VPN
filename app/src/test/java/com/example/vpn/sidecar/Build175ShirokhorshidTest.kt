package com.example.vpn.sidecar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Build 175: the timeout reason says why nothing was dialed when the core knew no server at all. */
class Build175ShirokhorshidTest {
    @Test fun anEmptyServerListIsNamedInTheTimeoutReason() {
        val progress = PsiphonSidecar.StartupProgress()
        progress.observe("""{"noticeType":"NetworkID","data":{"ID":"MOBILE-43235-direct"}}""")
        progress.observe("""{"noticeType":"ListeningSocksProxyPort","data":{"port":35107}}""")
        progress.observe("""{"noticeType":"AvailableEgressRegions","data":{"regions":[]}}""")
        assertTrue(progress.noServersKnown)
        val failure = progress.failureAfter(120_000, label = "Shir o Khorshid")
        assertTrue(failure, failure.contains("no Psiphon servers known yet"))
        // Once a server is dialed the reason is about dialing again.
        progress.observe("""{"noticeType":"ConnectingServer","data":{"protocol":"OSSH"}}""")
        assertFalse(progress.noServersKnown)
        assertTrue(progress.failureAfter(120_000).contains("server dialing did not establish a tunnel"))
    }

    @Test fun regionsListedIsNotAnEmptyServerList() {
        val progress = PsiphonSidecar.StartupProgress()
        progress.observe("""{"noticeType":"AvailableEgressRegions","data":{"regions":["DE","NL"]}}""")
        assertFalse(progress.noServersKnown)
        val fresh = PsiphonSidecar.StartupProgress()
        assertFalse("nothing heard yet", fresh.noServersKnown)
        assertTrue(fresh.failureAfter(60_000).contains("discovery"))
    }
}
