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
import java.net.InetAddress

/** Recipes limited to a firewall type, IPv6 or the fork; fallback endpoints; ECH clearing recorded in the ledger. */
class RecoveryContextTest {
    private val cf = VlessProfile(
        id = "c1", name = "CF", address = "edge.example.workers.dev", port = 443,
        uuid = "11111111-1111-1111-1111-111111111111", transport = "ws", security = "tls",
        sni = "edge.example.workers.dev", host = "edge.example.workers.dev", path = "/vl?ed=2560",
        fingerprint = "chrome", canonicalFingerprint = "fp-c1"
    )
    private var now = RecoveryProfiles.REVIEWED_AT + 1_000
    private var saved: String? = null
    private val ledger = RecoveryLedger({ saved }, { saved = it })

    private fun rp(
        id: String, params: Map<String, String>, family: String = "any",
        firewalls: Set<NetworkFirewalls.Firewall> = emptySet(), ipv6: Boolean = false, fork: Boolean = false,
        defaults: List<String> = emptyList(), strategy: RecoveryProfile.Strategy = RecoveryProfile.Strategy.FINGERPRINT
    ) = RecoveryProfile(id, 1, RecoveryProfiles.REVIEWED_AT, RecoveryProfiles.REVIEWED_AT + RecoveryProfiles.REVIEW_PERIOD_MS,
        addressFamily = family, networkConditions = setOf(strategy), parameters = params, firewalls = firewalls,
        requiresIpv6 = ipv6, requiresFork = fork, defaultEndpoints = defaults)

    private val gatedV6 = rp("v6-only-mci", mapOf("fingerprint" to "firefox", "address" to RecoveryProfile.ENDPOINT), family = "ipv6",
        firewalls = setOf(NetworkFirewalls.Firewall.MCI), ipv6 = true, defaults = listOf("2a06:98c1:3101::6812:202f"),
        strategy = RecoveryProfile.Strategy.ALT_ENDPOINT_V6)
    private val forkOnly = rp("fork-only", mapOf("fingerprint" to "safari"), fork = true)
    private val open = rp("open", mapOf("fingerprint" to "edge"))

    private fun engine(vararg p: RecoveryProfile) = BpbRecoveryEngine(profiles = p.toList(), ledger = ledger, clock = { now }, maxCandidates = 20)

    @Test fun gatedRecipesNeedEveryConditionKnownAndMet() {
        val e = engine(gatedV6, forkOnly, open)
        fun keys(ctx: RecoveryContext) = e.generate(cf, FailureStage.TLS_HANDSHAKE_FAILED, "cell:43211", context = ctx)
            .map { it.recoveryProfileKey.substringBefore('@') }.toSet()
        assertEquals(setOf("open"), keys(RecoveryContext.UNKNOWN))
        assertEquals("IPv6 not known", setOf("open"), keys(RecoveryContext(NetworkFirewalls.Firewall.MCI, ipv6 = null)))
        assertEquals("no IPv6", setOf("open"), keys(RecoveryContext(NetworkFirewalls.Firewall.MCI, ipv6 = false)))
        assertEquals("another firewall", setOf("open"), keys(RecoveryContext(NetworkFirewalls.Firewall.IRANCELL, ipv6 = true)))
        assertEquals(setOf("open", "v6-only-mci"), keys(RecoveryContext(NetworkFirewalls.Firewall.MCI, ipv6 = true)))
        assertEquals(setOf("open", "fork-only"), keys(RecoveryContext(forkAvailable = true)))
        assertTrue(gatedV6.gated && forkOnly.gated && !open.gated)
    }

    @Test fun anEndpointRecipeFallsBackToItsDefaultAddressOnlyWhenNothingIsValidated() {
        val e = engine(gatedV6)
        val ctx = RecoveryContext(NetworkFirewalls.Firewall.MCI, ipv6 = true)
        val fallback = e.generate(cf, FailureStage.TLS_HANDSHAKE_FAILED, "cell:43211", context = ctx).single()
        assertEquals("2a06:98c1:3101::6812:202f", fallback.endpoint)
        assertEquals("2a06:98c1:3101::6812:202f", fallback.profile.address)
        assertEquals("the certificate is still checked against the config's name", cf.sni, fallback.profile.sni)
        assertTrue(fallback.security.passed)
        val validated = e.generate(cf, FailureStage.TLS_HANDSHAKE_FAILED, "cell:43211", endpoints = listOf("2606:4700::6810:1", "104.16.1.1"), context = ctx)
        assertEquals(listOf("2606:4700::6810:1"), validated.map { it.endpoint })
        // A default address that refused the certificate here is quarantined like any other.
        ledger.markIntercepted("2a06:98c1:3101::6812:202f", "cell:43211", now)
        assertTrue(e.generate(cf, FailureStage.TLS_HANDSHAKE_FAILED, "cell:43211", context = ctx).isEmpty())
    }

    @Test fun defaultEndpointsMustBePublicAddressesOfTheProfilesFamily() {
        assertTrue(runCatching { rp("x", mapOf("address" to RecoveryProfile.ENDPOINT), family = "ipv6", defaults = listOf("10.0.0.1")) }.isFailure)
        assertTrue(runCatching { rp("y", mapOf("address" to RecoveryProfile.ENDPOINT), family = "ipv6", defaults = listOf("188.114.98.0")) }.isFailure)
        assertTrue(runCatching { rp("z", mapOf("fingerprint" to "chrome"), defaults = listOf("188.114.98.0")) }.isFailure)
        assertNotNull(rp("ok", mapOf("address" to RecoveryProfile.ENDPOINT), family = "ipv4", defaults = listOf("188.114.98.0")))
    }

    @Test fun activeRecoveryIsNotReplayedWhereItsGateNoLongerHolds() {
        val e = engine(gatedV6)
        val mciV6 = RecoveryContext(NetworkFirewalls.Firewall.MCI, ipv6 = true)
        val c = e.generate(cf, FailureStage.TLS_HANDSHAKE_FAILED, "cell:43211", context = mciV6)
        e.test(c) { ps -> ps.map { TestResult.Passed(200) } }
        assertNotNull(e.active(cf, "cell:43211", context = mciV6))
        assertNull("the network lost IPv6", e.active(cf, "cell:43211", context = mciV6.copy(ipv6 = false)))
        assertNotNull("callers without a context keep the old behaviour", e.active(cf, "cell:43211"))
    }

    @Test fun aRecipeMayClearEchAndTheLedgerRecordsIt() {
        val clear = rp("no-ech", mapOf("echConfigList" to "", "fingerprint" to "chrome"))
        val withEch = cf.copy(echConfigList = "edge.example.workers.dev+https://1.1.1.1/dns-query", fingerprint = "firefox")
        val d = clear.derive(withEch)!!
        assertEquals("", d.echConfigList)
        assertTrue(RecoverySecurityGate.check(withEch, d).passed)
        val e = engine(clear)
        val c = e.generate(withEch, FailureStage.TLS_HANDSHAKE_FAILED, "wifi").single()
        assertTrue(c.clearsEch)
        assertTrue(c.transformation.any { it.field == "echConfigList" && it.to.isEmpty() })
        assertFalse(ledger.clearedEch(withEch.effectiveFingerprint, "wifi"))
        e.test(listOf(c)) { ps -> ps.map { TestResult.Passed(150) } }
        assertTrue(ledger.clearedEch(withEch.effectiveFingerprint, "wifi"))
        // Survives a restart.
        assertTrue(RecoveryLedger({ saved }, { saved = it }).clearedEch(withEch.effectiveFingerprint, "wifi"))
        // A config without ECH: clearing is not a change and not recorded.
        val plain = clear.derive(cf.copy(fingerprint = "firefox"))!!
        assertEquals("", plain.echConfigList)
        assertFalse(e.generate(cf.copy(fingerprint = "firefox", canonicalFingerprint = "fp-plain"), FailureStage.TLS_HANDSHAKE_FAILED, "wifi").single().clearsEch)
    }

    @Test fun globalIpv6IsToldApartFromLocalAddresses() {
        fun a(s: String) = InetAddress.getByName(s)
        assertFalse(RecoveryContext.hasGlobalIpv6(emptyList()))
        assertFalse(RecoveryContext.hasGlobalIpv6(listOf(a("10.20.30.40"))))
        assertFalse(RecoveryContext.hasGlobalIpv6(listOf(a("fe80::1"), a("fd12:3456::1"), a("::1"), a("2001:db8::5"), a("fec0::2"))))
        assertTrue(RecoveryContext.hasGlobalIpv6(listOf(a("10.20.30.40"), a("2a01:5ec0:1000::12"))))
    }
}
