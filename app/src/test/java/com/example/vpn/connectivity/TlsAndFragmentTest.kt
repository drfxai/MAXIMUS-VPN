package com.example.vpn.connectivity

import com.example.data.model.VlessProfile
import com.example.vpn.connectivity.FragmentProfileEngine.Decision
import com.example.vpn.connectivity.FragmentProfileEngine.Stats
import com.example.vpn.connectivity.FragmentProfileEngine.Trial
import com.example.vpn.connectivity.TlsResilienceEngine.Engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 5: TLS / ECH honesty and bounded fragmentation that reverts when it hurts. */
class TlsAndFragmentTest {
    private val tls = VlessProfile(
        name = "t", address = "cdn.example.org", port = 443, uuid = "11111111-1111-1111-1111-111111111111",
        transport = "ws", security = "tls", sni = "cdn.example.org", fingerprint = "chrome"
    )

    @Test fun unsafeFingerprintKeepsCertificateChecksAndIsAccepted() {
        val a = TlsResilienceEngine.assess(tls.copy(fingerprint = "unsafe"), Engine.XRAY)
        assertTrue(a.accepted)
        assertTrue(a.notes.any { it.contains("certificate still verified") })
    }

    @Test fun insecureCertificateOrUnknownFingerprintIsRefused() {
        assertFalse(TlsResilienceEngine.assess(tls.copy(allowInsecure = true), Engine.XRAY).accepted)
        assertFalse(TlsResilienceEngine.assess(tls.copy(fingerprint = "totally-made-up"), Engine.XRAY).accepted)
        assertFalse(TlsResilienceEngine.assess(tls.copy(cipherSuites = "TLS_RSA_WITH_NULL_SHA"), Engine.XRAY).accepted)
    }

    @Test fun echIsNeverFakedOnAnEngineWithoutIt() {
        val ech = tls.copy(echConfigList = "cdn.example.org+https://1.1.1.1/dns-query")
        assertTrue(TlsResilienceEngine.assess(ech, Engine.XRAY).accepted)
        assertEquals("ECH was requested but this engine cannot do it", TlsResilienceEngine.assess(ech, Engine.KOTLIN).reason)
        assertFalse(TlsResilienceEngine.echSupported(Engine.SIDECAR))
    }

    @Test fun unboundedFragmentMaskIsRefused() {
        val bad = tls.copy(finalMask = """{"tcp":[{"type":"fragment","settings":{"packets":"tlshello","length":"1-99999","delay":"0"}}]}""")
        assertFalse(TlsResilienceEngine.assess(bad, Engine.XRAY).accepted)
        FragmentProfileEngine.PROFILES.forEach { p ->
            assertTrue(p.key, RecoverySecurityGate.maskProblem(p.parameters.getValue("finalMask")) == null)
        }
    }

    @Test fun fragmentationThatHurtsIsReverted() {
        assertEquals(Decision.UNDECIDED, FragmentProfileEngine.compare(Stats(3, 3, 200), Stats(1, 1, 220)))
        assertEquals(Decision.REVERT, FragmentProfileEngine.compare(Stats(3, 3, 200), Stats(3, 0, null)))
        assertEquals(Decision.REVERT, FragmentProfileEngine.compare(Stats(4, 4, 200), Stats(4, 2, 210)))
        assertEquals(Decision.REVERT, FragmentProfileEngine.compare(Stats(4, 4, 200), Stats(4, 4, 400)))
        assertEquals(Decision.KEEP, FragmentProfileEngine.compare(Stats(4, 0, null), Stats(4, 3, 400)))
        assertEquals(Decision.KEEP, FragmentProfileEngine.compare(Stats(4, 2, 300), Stats(4, 4, 320)))
    }

    @Test fun trialsArePerNetworkPersistedAndExpire() {
        var saved: String? = null
        var now = 1_000_000L
        val e = FragmentProfileEngine({ saved }, { saved = it }, { now })
        val key = "bpb-fragment@v1"
        repeat(2) {
            e.record(Trial(FragmentProfileEngine.PLAIN, "wifi", now, false, false))
            e.record(Trial(key, "wifi", now, true, true, 300))
        }
        assertEquals(Decision.KEEP, e.decide(key, "wifi"))
        assertEquals(Decision.UNDECIDED, e.decide(key, "cellular"))
        assertEquals(Decision.KEEP, FragmentProfileEngine({ saved }, { saved = it }, { now }).decide(key, "wifi"))
        now += FragmentProfileEngine.TTL_MS + 1
        assertEquals(Decision.UNDECIDED, e.decide(key, "wifi"))
    }

    @Test fun revertedFragmentProfilesAreNotOfferedForRecovery() {
        var saved: String? = null
        val bpb = tls.copy(id = "p", canonicalFingerprint = "fp")
        val engine = BpbRecoveryEngine(ledger = RecoveryLedger({ saved }, { saved = it }), clock = { RecoveryProfiles.REVIEWED_AT + 1 },
            maxCandidates = 20, fragmentReverted = { _, n -> n == "wifi" })
        val onWifi = engine.generate(bpb, com.example.vpn.diagnostics.FailureStage.TLS_HANDSHAKE_FAILED, "wifi")
        val onCell = engine.generate(bpb, com.example.vpn.diagnostics.FailureStage.TLS_HANDSHAKE_FAILED, "cellular")
        assertTrue(onWifi.none { RecoveryProfile.Strategy.FRAGMENT in RecoveryProfiles.byKey(it.recoveryProfileKey)!!.networkConditions })
        assertTrue(onCell.any { RecoveryProfile.Strategy.FRAGMENT in RecoveryProfiles.byKey(it.recoveryProfileKey)!!.networkConditions })
    }
}
