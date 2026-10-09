package com.example

import com.example.vpn.diagnostics.FailureStage
import com.example.vpn.hub.ConnectivityMeasurement
import com.example.vpn.hub.ConnectivityMeasurement.Kind
import com.example.vpn.hub.FreeConfigEvidenceStore
import com.example.vpn.hub.FreeConfigLifecycle
import com.example.vpn.hub.FreeConfigLifecycleRules
import com.example.vpn.hub.FreeConfigScore
import com.example.vpn.hub.LocalEvidence
import com.example.vpn.smart.NetworkCapabilityProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 1: the phone's own evidence for free configs, kept apart from the list builder's global checks. */
class FreeConfigEvidenceTest {
    private fun ok(t: Long, ms: Long = 200, kind: Kind = Kind.TEST) = ConnectivityMeasurement(t, kind, true, rttMs = ms)
    private fun fail(t: Long, stage: FailureStage = FailureStage.TIMEOUT) = ConnectivityMeasurement(t, Kind.TEST, false, failureStage = stage)

    @Test fun aConfigStartsGloballyVerifiedAndEarnsVerificationHereOnlyByCarryingTraffic() {
        var e = LocalEvidence("fp")
        assertEquals(FreeConfigLifecycle.GLOBAL_VERIFIED, e.lifecycle)
        e = FreeConfigLifecycleRules.apply(e, ok(1))
        assertEquals(FreeConfigLifecycle.LOCAL_PROBATION, e.lifecycle)
        e = FreeConfigLifecycleRules.apply(e, ok(2))
        assertEquals(FreeConfigLifecycle.LOCAL_PROBATION, e.lifecycle)
        e = FreeConfigLifecycleRules.apply(e, ok(3))
        assertEquals(FreeConfigLifecycle.LOCAL_NETWORK_VERIFIED, e.lifecycle)
    }

    @Test fun aVerifiedVpnSessionCountsMoreThanAPing() {
        var e = FreeConfigLifecycleRules.apply(LocalEvidence("fp"), ok(1, kind = Kind.CONNECTION))
        e = FreeConfigLifecycleRules.apply(e, ok(2))
        assertEquals(FreeConfigLifecycle.LOCAL_NETWORK_VERIFIED, e.lifecycle)
        assertEquals(1, e.verifiedConnections)
    }

    @Test fun failuresDegradeThenKill() {
        var e = LocalEvidence("fp")
        repeat(3) { e = FreeConfigLifecycleRules.apply(e, ok(it.toLong())) }
        e = FreeConfigLifecycleRules.apply(e, fail(10))
        assertEquals(FreeConfigLifecycle.LOCAL_NETWORK_VERIFIED, FreeConfigLifecycleRules.lifecycleOf(e.copy(consecutiveFailures = 0), 10))
        e = FreeConfigLifecycleRules.apply(e, fail(11, FailureStage.TLS_HANDSHAKE_FAILED))
        assertEquals(FreeConfigLifecycle.DEGRADED, e.lifecycle)
        assertEquals(FailureStage.TLS_HANDSHAKE_FAILED, e.lastFailureStage)
        val day = FreeConfigLifecycleRules.DEAD_AFTER_MS
        e = FreeConfigLifecycleRules.apply(e, fail(day + 12))
        e = FreeConfigLifecycleRules.apply(e, fail(day + 13))
        assertEquals(FreeConfigLifecycle.DEAD, e.lifecycle)
        // Its earlier successes still count, so one new success restores it.
        e = FreeConfigLifecycleRules.apply(e, ok(day + 14))
        assertEquals(FreeConfigLifecycle.LOCAL_NETWORK_VERIFIED, e.lifecycle)
    }

    @Test fun neverWorkedHereIsDeadAfterTwoFailures() {
        var e = FreeConfigLifecycleRules.apply(LocalEvidence("fp"), fail(1))
        assertEquals(FreeConfigLifecycle.GLOBAL_VERIFIED, e.lifecycle)
        e = FreeConfigLifecycleRules.apply(e, fail(2))
        assertEquals(FreeConfigLifecycle.DEAD, e.lifecycle)
    }

    @Test fun quarantinedStaysQuarantined() {
        val q = LocalEvidence("fp", lifecycle = FreeConfigLifecycle.QUARANTINED)
        assertEquals(FreeConfigLifecycle.QUARANTINED, FreeConfigLifecycleRules.apply(q, ok(1)).lifecycle)
    }

    @Test fun noLabelClaimsTheConfigWorksInIran() {
        FreeConfigLifecycle.values().forEach { assertFalse(it.label, it.label.contains("works in iran", ignoreCase = true)) }
        assertTrue(FreeConfigLifecycle.GLOBAL_VERIFIED.label.contains("outside Iran"))
    }

    @Test fun theStoreSurvivesARestartAndStaysBounded() {
        var saved: String? = null
        val store = FreeConfigEvidenceStore({ saved }, { saved = it })
        repeat(FreeConfigEvidenceStore.MAX_ENTRIES + 5) { store.record("fp$it", ok(it.toLong())) }
        assertEquals(FreeConfigEvidenceStore.MAX_ENTRIES, store.all().size)
        assertNull(store.get("fp0"))
        val reloaded = FreeConfigEvidenceStore({ saved }, { saved = it })
        assertEquals(1, reloaded.get("fp${FreeConfigEvidenceStore.MAX_ENTRIES + 4}")?.successes)
        reloaded.forget(listOf("fp${FreeConfigEvidenceStore.MAX_ENTRIES + 4}"))
        assertNull(reloaded.get("fp${FreeConfigEvidenceStore.MAX_ENTRIES + 4}"))
        // Nothing but fingerprints and measurements is stored.
        assertFalse(saved!!.contains("vless://"))
    }

    @Test fun aStable250msConfigScoresAboveAnUnstable80msOne() {
        val stable = LocalEvidence("a", attempts = 6, successes = 6, rttMs = listOf(250, 248, 252, 251, 249, 250))
        val unstable = LocalEvidence("b", attempts = 6, successes = 3, rttMs = listOf(80, 700, 90))
        assertTrue(FreeConfigScore.of(stable, "reality").score > FreeConfigScore.of(unstable, "reality").score)
        assertEquals(FreeConfigScore.WEIGHTS.keys, FreeConfigScore.of(stable, "tls").reasons.keys)
        assertEquals(FreeConfigScore.of(stable, "tls"), FreeConfigScore.of(stable, "tls"))
    }

    @Test fun failureStagesComeFromExceptionsAndTexts() {
        assertEquals(FailureStage.DNS_RESOLUTION_FAILED, FailureStage.of(java.net.UnknownHostException("x")))
        assertEquals(FailureStage.TCP_CONNECT_FAILED, FailureStage.of(RuntimeException(java.net.ConnectException("refused"))))
        assertEquals(FailureStage.TLS_HANDSHAKE_FAILED, FailureStage.of(javax.net.ssl.SSLHandshakeException("bad cert")))
        assertEquals(FailureStage.TIMEOUT, FailureStage.of(java.net.SocketTimeoutException()))
        assertEquals(FailureStage.TIMEOUT, FailureStage.fromText("context deadline exceeded"))
        assertEquals(FailureStage.HTTP_STATUS_INVALID, FailureStage.fromText("Probe returned HTTP 503"))
        assertEquals(FailureStage.UNKNOWN, FailureStage.fromText(null))
    }

    @Test fun networkProfilesAreAnonymousBucketsAndRoundTrip() {
        val p = NetworkCapabilityProfile(transport = "cellular", ipv4Available = true, ipv6Available = false, udpAvailable = null,
            tlsAvailable = true, cloudflareReachable = false, dnsWorking = true, carrierCode = "43211", measuredAt = 5)
        assertEquals("cellular:v4=1,v6=0,udp=?,tls=1,cf=0,dns=1", p.key())
        assertFalse(p.key().contains("43211"))
        assertEquals(p, NetworkCapabilityProfile.fromJson(JSONObject(p.toJson().toString())))
        assertTrue(p.observations().contains("UDP (direct DNS abroad): not measured"))
    }
}
