package com.example.vpn.connectivity

import com.example.data.model.VlessProfile
import com.example.vpn.connectivity.DerivedRecoveryCandidate.TestResult
import com.example.vpn.diagnostics.FailureStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 4 test matrix: BPB recovery with immutable originals, a security gate and rollback. */
class BpbRecoveryEngineTest {
    private val bpb = VlessProfile(
        id = "p1", name = "BPB 1", address = "worker.example.workers.dev", port = 443,
        uuid = "11111111-1111-1111-1111-111111111111", transport = "ws", security = "tls",
        sni = "worker.example.workers.dev", host = "worker.example.workers.dev", path = "/vl/abc?ed=2560",
        fingerprint = "chrome", canonicalFingerprint = "fp-bpb"
    )
    private var now = RecoveryProfiles.REVIEWED_AT + 1_000
    private var saved: String? = null
    private val ledger = RecoveryLedger({ saved }, { saved = it })
    private val engine = BpbRecoveryEngine(ledger = ledger, clock = { now })
    private val net = "wifi:v4=1,v6=0"

    @Test fun healthyOrUnrecoverableFailuresGetNoCandidates() {
        assertTrue(engine.generate(bpb, FailureStage.CERTIFICATE_VALIDATION_FAILED, net).isEmpty())
        assertTrue(engine.generate(bpb, FailureStage.PROXY_AUTH_FAILED, net).isEmpty())
        assertTrue(engine.generate(bpb, FailureStage.PROXY_HANDSHAKE_FAILED, net).isEmpty())
        assertTrue(engine.generate(bpb, FailureStage.SECURITY_REJECTED, net).isEmpty())
        assertTrue(engine.generate(bpb.copy(security = "reality"), FailureStage.TLS_HANDSHAKE_FAILED, net).isEmpty())
    }

    @Test fun degradedBpbGetsBoundedSafeCandidatesAndTheOriginalStaysUntouched() {
        val before = bpb.copy()
        val cs = engine.generate(bpb, FailureStage.TLS_HANDSHAKE_FAILED, net)
        assertTrue(cs.isNotEmpty())
        assertTrue(cs.size <= engine.maxCandidates)
        assertEquals(before, bpb)
        cs.forEach { c ->
            assertEquals("fp-bpb", c.parentFingerprint)
            assertEquals(bpb.id, c.profile.id)
            assertTrue(c.transformation.isNotEmpty())
            assertTrue(c.transformation.all { it.field in RecoveryProfile.ALLOWED_FIELDS })
            assertTrue(c.security.passed)
            assertEquals(bpb.sni, c.profile.sni)
            assertEquals(bpb.uuid, c.profile.uuid)
            assertTrue(c.expiresAt > now)
            assertEquals(net, c.networkContext)
        }
    }

    @Test fun blockedEndpointUsesOnlyValidatedAlternativeAddresses() {
        val cs = engine.generate(bpb, FailureStage.TCP_CONNECT_FAILED, net, endpoints = listOf("104.16.10.20", "2606:4700::6810:1"))
        assertEquals(setOf("endpoint-ipv4@v1", "endpoint-ipv6@v1"), cs.map { it.recoveryProfileKey }.toSet())
        val v6 = cs.single { it.endpoint == "2606:4700::6810:1" }
        assertEquals(listOf(FieldChange("address", bpb.address, "2606:4700::6810:1")), v6.transformation)
        // The certificate is still checked against the unchanged name.
        assertEquals(bpb.sni, v6.profile.sni)
        assertTrue(engine.generate(bpb, FailureStage.TCP_CONNECT_FAILED, net).isEmpty())
    }

    @Test fun privateEndpointsAndInsecureChangesAreRefusedByTheGate() {
        val toPrivate = bpb.copy(address = "10.10.34.35")
        assertFalse(RecoverySecurityGate.check(bpb, toPrivate).passed)
        assertFalse(RecoverySecurityGate.check(bpb, bpb.copy(allowInsecure = true)).passed)
        assertFalse(RecoverySecurityGate.check(bpb, bpb.copy(security = "none")).passed)
        assertFalse(RecoverySecurityGate.check(bpb, bpb.copy(sni = "other.example")).passed)
        val pinned = bpb.copy(pinnedPeerCertSha256 = "ab".repeat(32))
        assertFalse(RecoverySecurityGate.check(pinned, pinned.copy(pinnedPeerCertSha256 = "")).passed)
        assertFalse(RecoverySecurityGate.check(bpb, bpb.copy(fingerprint = "hellogolang")).passed)
        assertFalse(RecoverySecurityGate.check(bpb, bpb.copy(cipherSuites = "TLS_RSA_WITH_RC4_128_SHA")).passed)
        assertFalse(RecoverySecurityGate.check(bpb, bpb.copy(echConfigList = "worker.example.workers.dev+https://dns.evil/dns-query")).passed)
        assertFalse(RecoverySecurityGate.check(bpb, bpb.copy(finalMask = """{"tcp":[{"type":"fragment","settings":{"lengths":["100000"]}}]}""")).passed)
        assertFalse(RecoverySecurityGate.check(bpb, bpb.copy(finalMask = """{"tcp":[{"type":"exec","settings":{}}]}""")).passed)
        // "unsafe" keeps certificate verification in Xray v26.9.9, so it is allowed for TLS.
        assertTrue(RecoverySecurityGate.check(bpb, bpb.copy(fingerprint = "unsafe")).passed)
    }

    @Test fun eachCandidateIsGatedAndRefusedOnesAreNeverTested() {
        val bad = engine.generate(bpb, FailureStage.TIMEOUT, net).first().let {
            it.copy(profile = it.profile.copy(allowInsecure = true), security = RecoverySecurityGate.check(bpb, it.profile.copy(allowInsecure = true)))
        }
        var probed = 0
        val out = engine.test(listOf(bad)) { ps -> probed += ps.size; ps.map { TestResult.Passed(10) } }
        assertEquals(0, probed)
        assertNull(out.single().test)
        assertFalse(out.single().security.passed)
    }

    @Test fun eChCandidateUsesTheServersOwnNameAndAnIpResolver() {
        val ech = engine.generate(bpb, FailureStage.TLS_HANDSHAKE_FAILED, net).firstOrNull { it.recoveryProfileKey == "ech-doh@v1" }
            ?: BpbRecoveryEngine(ledger = ledger, clock = { now }, maxCandidates = 20).generate(bpb, FailureStage.TLS_HANDSHAKE_FAILED, net)
                .single { it.recoveryProfileKey == "ech-doh@v1" }
        assertEquals("worker.example.workers.dev+https://1.1.1.1/dns-query", ech.profile.echConfigList)
        assertTrue(ech.security.passed)
        // An IP-only server cannot use ECH: no candidate.
        val ipOnly = bpb.copy(sni = "", host = "", address = "104.16.10.20")
        assertTrue(RecoveryProfiles.byKey("ech-doh@v1")!!.derive(ipOnly) == null)
    }

    @Test fun successfulRecoveryBecomesActiveAndRollsBackAfterInstability() {
        val cs = engine.generate(bpb, FailureStage.TLS_HANDSHAKE_FAILED, net)
        var n = 0
        val tested = engine.test(cs) { ps -> ps.map { if (n++ == 0) TestResult.Passed(180) else TestResult.Failed("timeout") } }
        val winner = tested.first()
        assertTrue(winner.test is TestResult.Passed)
        val active = engine.active(bpb, net)
        assertNotNull(active)
        assertEquals(winner.recoveryProfileKey, active!!.recoveryProfileKey)
        // Not active on another network.
        assertNull(engine.active(bpb, "cellular:v4=1"))
        // Works, then fails twice in a row: withdrawn, the original is used again.
        engine.recordOutcome(active, success = false)
        assertNotNull(engine.active(bpb, net))
        engine.recordOutcome(active, success = false)
        assertNull(engine.active(bpb, net))
        assertTrue(ledger.isWithdrawn("fp-bpb", winner.recoveryProfileKey, null, net))
        assertFalse(engine.generate(bpb, FailureStage.TLS_HANDSHAKE_FAILED, net).any { it.recoveryProfileKey == winner.recoveryProfileKey })
    }

    @Test fun aProfileThatNeverWorksHereStopsBeingOffered() {
        val key = "fragment-tlshello@v1"
        repeat(RecoveryLedger.WORSE_AFTER) { i ->
            val c = BpbRecoveryEngine(ledger = ledger, clock = { now }, maxCandidates = 20)
                .generate(bpb.copy(canonicalFingerprint = "fp$i"), FailureStage.TIMEOUT, net).single { it.recoveryProfileKey == key }
            engine.recordOutcome(c, success = false)
        }
        assertTrue(ledger.profileIsWorse(key, net))
        assertFalse(BpbRecoveryEngine(ledger = ledger, clock = { now }, maxCandidates = 20).generate(bpb, FailureStage.TIMEOUT, net).any { it.recoveryProfileKey == key })
    }

    @Test fun expiredProfilesAndCandidatesAreNotUsed() {
        val cs = engine.generate(bpb, FailureStage.TLS_HANDSHAKE_FAILED, net)
        engine.test(cs) { ps -> ps.map { TestResult.Passed(100) } }
        assertNotNull(engine.active(bpb, net))
        now += RecoveryProfiles.REVIEW_PERIOD_MS
        assertNull(engine.active(bpb, net))
        assertTrue(engine.generate(bpb, FailureStage.TLS_HANDSHAKE_FAILED, net).isEmpty())
    }

    @Test fun theLedgerSurvivesARestartAndHoldsNoCredential() {
        val cs = engine.generate(bpb, FailureStage.TLS_HANDSHAKE_FAILED, net)
        engine.test(cs.take(1)) { ps -> ps.map { TestResult.Passed(90) } }
        val restored = BpbRecoveryEngine(ledger = RecoveryLedger({ saved }, { saved = it }), clock = { now })
        assertNotNull(restored.active(bpb, net))
        assertFalse(saved!!.contains(bpb.uuid))
        assertFalse(saved!!.contains(bpb.address))
    }

    @Test fun recoveryProfilesCannotTouchIdentityFields() {
        val bad = runCatching {
            RecoveryProfile("x", 1, 0, 1, parameters = mapOf("sni" to "evil.example"))
        }
        assertTrue(bad.isFailure)
        assertTrue(RecoveryProfiles.BUILT_IN.all { it.expiresAt - it.createdAt <= RecoveryProfiles.REVIEW_PERIOD_MS })
        assertEquals(RecoveryProfiles.BUILT_IN.size, RecoveryProfiles.BUILT_IN.map { it.key }.toSet().size)
    }
}
