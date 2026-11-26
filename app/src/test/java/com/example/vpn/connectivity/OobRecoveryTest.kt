package com.example.vpn.connectivity

import com.example.data.model.VlessProfile
import com.example.vpn.connectivity.DerivedRecoveryCandidate.TestResult
import com.example.vpn.connectivity.NetworkFirewalls.Firewall
import com.example.vpn.diagnostics.FailureStage
import com.example.vpn.engine.PatternihaFields
import com.example.vpn.engine.ProfileExtras
import com.example.vpn.lab.ConfigRevival
import com.example.vpn.sidecar.ForkXraySidecar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** patterniha's oob6 recipe: Hamrah-e-Aval-type firewall + IPv6 + the fork only, tested and run on the fork. */
class OobRecoveryTest {
    private fun cf(id: String, extras: String = "") = VlessProfile(
        id = id, name = "CF $id", address = "$id.example.pages.dev", port = 443,
        uuid = "11111111-1111-1111-1111-111111111111", transport = "ws", security = "tls",
        sni = "$id.example.pages.dev", host = "$id.example.pages.dev", path = "/vl?ed=2560",
        fingerprint = "firefox", canonicalFingerprint = "fp-$id", extraSettings = extras
    )

    private var now = RecoveryProfiles.REVIEWED_AT + 1_000
    private var saved: String? = null
    private val ledger = RecoveryLedger({ saved }, { saved = it })
    private val engine = BpbRecoveryEngine(ledger = ledger, clock = { now }, maxCandidates = ConfigRevival.RECIPES_PER_CONFIG)
    private val mci = "cell:43211"
    private val ready = RecoveryContext(Firewall.MCI, ipv6 = true, forkAvailable = true)
    private val oob get() = RecoveryProfiles.BUILT_IN.single { it.profileId == NetworkFirewalls.MCI_V6_OOB_PROFILE }

    private fun oobCandidates(p: VlessProfile, ctx: RecoveryContext, endpoints: List<String> = emptyList()) =
        engine.generate(p, FailureStage.TLS_HANDSHAKE_FAILED, mci, endpoints, ctx) { NetworkFirewalls.bias(ctx.firewall ?: Firewall.OTHER, it) }
            .filter { it.recoveryProfileKey.startsWith(NetworkFirewalls.MCI_V6_OOB_PROFILE + "@") }

    @Test fun theRecipeUsesTheForksIpv6OnlyOobFormAndACloudflareIpv6Edge() {
        assertEquals("oob6.0", RecoveryProfiles.MCI_V6_OOB_DIAL_MODE)
        assertTrue(PatternihaFields.validDialMode(RecoveryProfiles.MCI_V6_OOB_DIAL_MODE))
        assertTrue("dialMode" in RecoveryProfile.ALLOWED_FIELDS)
        val c = oobCandidates(cf("a"), ready).single()
        assertEquals(RecoveryProfiles.MCI_V6_OOB_IPV6, c.profile.address)
        assertEquals("oob6.0", PatternihaFields.dialMode(c.profile))
        assertEquals("chrome", c.profile.fingerprint)
        assertEquals("", c.profile.finalMask)
        assertEquals("", c.profile.cipherSuites)
        assertTrue(c.transformation.contains(FieldChange("dialMode", "", "oob6.0")))
        assertTrue(c.security.passed)
        // Identity untouched; the candidate needs the fork (stock Xray would drop dialMode).
        assertEquals(cf("a").sni, c.profile.sni); assertEquals(cf("a").uuid, c.profile.uuid); assertFalse(c.profile.allowInsecure)
        assertTrue(ForkXraySidecar.applies(c.profile))
        assertEquals(listOf("dialMode"), ForkXraySidecar.forkFields(c.profile))
        // A validated Cloudflare IPv6 edge is preferred to the fallback one.
        assertEquals(listOf("2606:4700:3030::6815:1"), oobCandidates(cf("a"), ready, listOf("2606:4700:3030::6815:1", "104.16.1.1")).map { it.endpoint })
    }

    @Test fun strictlyGatedOnHamrahEAvalIpv6AndTheFork() {
        val p = cf("b")
        assertEquals(1, oobCandidates(p, ready).size)
        for (ctx in listOf(
            ready.copy(forkAvailable = false), ready.copy(ipv6 = false), ready.copy(ipv6 = null),
            ready.copy(firewall = Firewall.IRANCELL), ready.copy(firewall = Firewall.OTHER), ready.copy(firewall = null),
            RecoveryContext.UNKNOWN
        )) assertTrue(ctx.toString(), oobCandidates(p, ctx).isEmpty())
        assertTrue(oob.requiresFork && oob.requiresIpv6 && oob.firewalls == setOf(Firewall.MCI))
        // On Hamrah-e-Aval it follows UAC's split and comes before the ECH recipes.
        val order = engine.generate(p, FailureStage.TLS_HANDSHAKE_FAILED, mci, emptyList(), ready) { NetworkFirewalls.bias(Firewall.MCI, it) }
            .map { it.recoveryProfileKey.substringBefore('@') }
        val i = order.indexOf(NetworkFirewalls.MCI_V6_OOB_PROFILE)
        assertTrue(order.toString(), i > order.indexOf(IranHelloSplit.PROFILE_ID))
        assertTrue(order.toString(), i < order.indexOf("ech-cloudflare"))
        // A recovered oob copy is not replayed once the network lost IPv6 or the fork.
        engine.test(oobCandidates(p, ready)) { ps -> ps.map { TestResult.Passed(300) } }
        assertNotNull(engine.active(p, mci, context = ready))
        assertNull(engine.active(p, mci, context = ready.copy(ipv6 = false)))
        assertNull(engine.active(p, mci, context = ready.copy(forkAvailable = false)))
    }

    @Test fun theGateAcceptsOnlyAValidDialModeAndNoOtherExtrasChange() {
        val p = cf("c", extras = """{"pattSessionResumption":true}""")
        val withOob = ProfileExtras.with(p, ProfileExtras.PATT_DIAL_MODE, "oob6.0")
        assertTrue(RecoverySecurityGate.check(p, withOob).passed)
        assertFalse(RecoverySecurityGate.check(p, ProfileExtras.with(p, ProfileExtras.PATT_DIAL_MODE, "exec.rm")).passed)
        assertFalse(RecoverySecurityGate.check(p, ProfileExtras.with(p, ProfileExtras.PATT_DIAL_MODE, "oob.0;oob6.0")).passed)
        assertFalse("another extra may not change", RecoverySecurityGate.check(p, ProfileExtras.with(withOob, "pattSessionResumption", false)).passed)
        // The other extras survive the recipe.
        val d = oob.derive(p, "2a06:98c1:3120::7")!!
        assertTrue(ProfileExtras.read(d).optBoolean("pattSessionResumption"))
        // A profile may only set dialMode when it requires the fork, and only a value the fork parses.
        fun bad(params: Map<String, String>, fork: Boolean) = runCatching {
            RecoveryProfile("x", 1, 0, 1, parameters = params, requiresFork = fork)
        }.isFailure
        assertTrue(bad(mapOf("dialMode" to "oob6.0"), fork = false))
        assertTrue(bad(mapOf("dialMode" to "oob6.999"), fork = true))
        assertFalse(bad(mapOf("dialMode" to "oob6.0"), fork = true))
    }

    @Test fun forkCandidatesAreProbedOnTheForkAndTheRestOnStockInOrder() {
        val a = cf("p1"); val b = ProfileExtras.with(cf("p2"), ProfileExtras.PATT_DIAL_MODE, "oob6.0"); val c = cf("p3")
        val stockSeen = mutableListOf<String>(); val forkSeen = mutableListOf<String>()
        val out = ForkAwareProbe.measure(listOf(a, b, c), needsFork = { ForkXraySidecar.applies(it) },
            stock = { ps -> stockSeen += ps.map { it.id }; ps.map { "stock:${it.id}" } },
            fork = { p -> forkSeen += p.id; "fork:${p.id}" }, missing = "none")
        assertEquals(listOf("stock:p1", "fork:p2", "stock:p3"), out)
        assertEquals(listOf("p1", "p3"), stockSeen)
        assertEquals(listOf("p2"), forkSeen)
        assertEquals(listOf("fork:p2"), ForkAwareProbe.measure(listOf(b), { true }, { error("no stock batch") }, { "fork:${it.id}" }, "none"))
        assertTrue(ForkAwareProbe.testResult(true, false, 120, null) is TestResult.Passed)
        assertTrue(ForkAwareProbe.testResult(false, true, null, "missing") is TestResult.NotRun)
        assertTrue(ForkAwareProbe.testResult(false, false, null, null) is TestResult.Failed)
    }

    @Test fun revivalRunsTheOobCandidateThroughTheForkProbeOnly() {
        val dead = cf("r")
        val probedOnFork = mutableListOf<VlessProfile>()
        val revival = ConfigRevival(engine, context = { ready }, probe = { ps ->
            ForkAwareProbe.measure(ps, { ForkXraySidecar.applies(it) },
                stock = { plain -> plain.map { TestResult.Failed("timeout") } },
                fork = { p -> probedOnFork += p; TestResult.Passed(400) }, missing = TestResult.NotRun("no result"))
        })
        val res = revival.run(listOf(dead), mci).results.single()
        assertEquals(ConfigRevival.Outcome.REVIVED, res.outcome)
        assertEquals("${NetworkFirewalls.MCI_V6_OOB_PROFILE}@v1", res.recipe)
        assertEquals(listOf("oob6.0"), probedOnFork.map { PatternihaFields.dialMode(it) })
        assertTrue(res.detail.startsWith("oob"))
        // Without IPv6 the same run never builds it.
        val none = ConfigRevival(BpbRecoveryEngine(ledger = RecoveryLedger({ null }, { }), clock = { now }, maxCandidates = 10),
            context = { ready.copy(ipv6 = false) }, probe = { ps -> ps.map { p ->
                assertNull(PatternihaFields.dialMode(p)); TestResult.Failed("timeout") } })
        assertEquals(ConfigRevival.Outcome.NOT_REVIVED, none.run(listOf(cf("s")), mci).results.single().outcome)
    }
}
