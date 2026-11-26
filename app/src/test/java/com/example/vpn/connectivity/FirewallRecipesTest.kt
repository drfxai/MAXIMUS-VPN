package com.example.vpn.connectivity

import com.example.data.model.VlessProfile
import com.example.panels.BpbFix
import com.example.vpn.connectivity.DerivedRecoveryCandidate.TestResult
import com.example.vpn.connectivity.NetworkFirewalls.Firewall
import com.example.vpn.diagnostics.FailureStage
import com.example.vpn.lab.CandidateMutationPolicy
import com.example.vpn.lab.ConfigRevival
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** patterniha's per-firewall CDN/Worker recipes: Irancell F&F (with and without a clean IP) and Hamrah-e-Aval clean IPv6. */
class FirewallRecipesTest {
    private fun cf(id: String, transport: String = "ws", ech: String = "") = VlessProfile(
        id = id, name = "CF $id", address = "$id.example.workers.dev", port = 443,
        uuid = "11111111-1111-1111-1111-111111111111", transport = transport, security = "tls",
        sni = "$id.example.workers.dev", host = "$id.example.workers.dev", path = "/vl?ed=2560",
        fingerprint = "chrome", canonicalFingerprint = "fp-$id", echConfigList = ech
    )

    private var now = RecoveryProfiles.REVIEWED_AT + 1_000
    private var saved: String? = null
    private val ledger = RecoveryLedger({ saved }, { saved = it })
    private val engine = BpbRecoveryEngine(ledger = ledger, clock = { now }, maxCandidates = ConfigRevival.RECIPES_PER_CONFIG)
    private val irancell = "cell:43235"
    private val mci = "cell:43211"

    private fun byId(id: String) = RecoveryProfiles.BUILT_IN.single { it.profileId == id }
    private val ff get() = byId(NetworkFirewalls.IRANCELL_FF_PROFILE)
    private val ffIp get() = byId(NetworkFirewalls.IRANCELL_FF_IP_PROFILE)
    private val v6 get() = byId(NetworkFirewalls.MCI_V6_CLEAN_PROFILE)

    private fun order(p: VlessProfile, network: String, ctx: RecoveryContext, endpoints: List<String> = emptyList()) =
        engine.generate(p, FailureStage.TLS_HANDSHAKE_FAILED, network, endpoints, ctx) { NetworkFirewalls.bias(ctx.firewall ?: Firewall.OTHER, it) }
            .map { it.recoveryProfileKey.substringBefore('@') }

    @Test fun irancellFfIsPatternihasRecipeWithAlpnByTransportAndEchRemoved() {
        val ws = ff.derive(cf("a"))!!
        assertEquals(BpbFix.FINAL_MASK_ORIGINAL, ws.finalMask)
        assertEquals("unsafe", ws.fingerprint)
        assertEquals(BpbFix.SEMI_PYTHON_CIPHER_SUITES, ws.cipherSuites)
        assertEquals("http/1.1", ws.alpn)
        assertEquals("http/1.1", ff.derive(cf("b", transport = "httpupgrade"))!!.alpn)
        // XHTTP, gRPC and h2 keep HTTP/2 offered instead of the forced HTTP/1.1 of the FIX BPB recipes.
        for (t in listOf("xhttp", "splithttp", "grpc", "h2")) assertEquals(t, "h2,http/1.1", ff.derive(cf("c", transport = t))!!.alpn)
        // The config's own ECH is removed, and the candidate says so.
        val withEch = cf("d", ech = "d.example.workers.dev+https://1.1.1.1/dns-query")
        val d = ff.derive(withEch)!!
        assertEquals("", d.echConfigList)
        assertTrue(RecoverySecurityGate.check(withEch, d).passed)
        assertTrue(CandidateMutationPolicy.check(withEch, d).allowed)
        // Identity, certificate checking and the address are untouched.
        assertEquals(withEch.sni, d.sni); assertEquals(withEch.host, d.host); assertEquals(withEch.address, d.address)
        assertEquals(withEch.uuid, d.uuid); assertFalse(d.allowInsecure)
        assertEquals(10, BpbFix.SEMI_PYTHON_CIPHER_SUITES.split(':').size)
    }

    @Test fun irancellRanksFfFirstThenTheCleanIpCopyAndSkipsTheDuplicateBpbRecipe() {
        val ctx = RecoveryContext(Firewall.IRANCELL)
        val keys = order(cf("e"), irancell, ctx)
        assertEquals(NetworkFirewalls.IRANCELL_FF_PROFILE, keys[0])
        assertEquals(NetworkFirewalls.IRANCELL_FF_IP_PROFILE, keys[1])
        assertEquals(NetworkFirewalls.IRANCELL_PROFILE, keys[2])
        assertFalse("F&F replaces it on Irancell", "bpb-fragment-original" in keys)
        assertTrue("UAC's split still fits in revival's recipes", IranHelloSplit.PROFILE_ID in keys)
        assertTrue(NetworkFirewalls.MCI_V6_CLEAN_PROFILE !in keys)
    }

    @Test fun theCleanIpCopyUsesAValidatedEdgeElsePatternihasAddress() {
        val ctx = RecoveryContext(Firewall.IRANCELL)
        val p = cf("f")
        fun ipCandidates(endpoints: List<String>) = engine.generate(p, FailureStage.TLS_HANDSHAKE_FAILED, irancell, endpoints, ctx)
            .filter { it.recoveryProfileKey.startsWith(NetworkFirewalls.IRANCELL_FF_IP_PROFILE + "@") }
        assertEquals(listOf(RecoveryProfiles.IRANCELL_FF_CLEAN_IPV4), ipCandidates(emptyList()).map { it.endpoint })
        assertEquals(listOf("188.114.97.3"), ipCandidates(listOf("188.114.97.3", "2606:4700::1")).map { it.endpoint })
        val c = ipCandidates(emptyList()).single()
        assertTrue(c.security.passed)
        assertEquals(p.sni, c.profile.sni)
        assertEquals(BpbFix.SEMI_PYTHON_CIPHER_SUITES, c.profile.cipherSuites)
        // Only on Irancell-type networks.
        assertTrue(engine.generate(p, FailureStage.TLS_HANDSHAKE_FAILED, "wifi:home", emptyList(), RecoveryContext(Firewall.OTHER))
            .none { it.recoveryProfileKey.startsWith(NetworkFirewalls.IRANCELL_FF_IP_PROFILE + "@") })
        assertTrue(ffIp.gated && !ff.gated)
    }

    @Test fun ffIsSkippedOnHamrahEAvalAndNotPromotedOnRightel() {
        assertNull(NetworkFirewalls.bias(Firewall.MCI, ff))
        assertTrue(NetworkFirewalls.bias(Firewall.RIGHTEL, ff)!! < 0)
        assertEquals(0.0, NetworkFirewalls.bias(Firewall.OTHER, ff)!!, 1e-9)
    }

    @Test fun mciV6CleanIsChromeOverCloudflareIpv6OnlyOnHamrahEAvalWithIpv6() {
        val p = cf("g").copy(fingerprint = "firefox", finalMask = BpbFix.FINAL_MASK, cipherSuites = BpbFix.CIPHER_SUITES,
            echConfigList = "g.example.workers.dev+https://1.1.1.1/dns-query")
        val withV6 = RecoveryContext(Firewall.MCI, ipv6 = true)
        val keys = order(p, mci, withV6)
        assertEquals(NetworkFirewalls.MCI_V6_CLEAN_PROFILE, keys.first())
        val c = engine.generate(p, FailureStage.TLS_HANDSHAKE_FAILED, mci, emptyList(), withV6)
            .first { it.recoveryProfileKey.startsWith(NetworkFirewalls.MCI_V6_CLEAN_PROFILE + "@") }
        assertEquals(RecoveryProfiles.MCI_V6_CLEAN_IPV6, c.profile.address)
        assertEquals("chrome", c.profile.fingerprint)
        assertEquals("", c.profile.finalMask)
        assertEquals("", c.profile.cipherSuites)
        assertEquals("", c.profile.echConfigList)
        assertTrue(c.clearsEch)
        assertTrue(c.security.passed)
        assertEquals(p.sni, c.profile.sni); assertEquals(p.host, c.profile.host)
        // A validated Cloudflare IPv6 edge is preferred to the fallback.
        val validated = engine.generate(p, FailureStage.TLS_HANDSHAKE_FAILED, mci, listOf("2606:4700:3030::6815:1"), withV6)
            .first { it.recoveryProfileKey.startsWith(NetworkFirewalls.MCI_V6_CLEAN_PROFILE + "@") }
        assertEquals("2606:4700:3030::6815:1", validated.endpoint)
        // Not without IPv6, not when IPv6 is unknown, not on other firewalls.
        for (ctx in listOf(RecoveryContext(Firewall.MCI, ipv6 = false), RecoveryContext(Firewall.MCI), RecoveryContext(Firewall.IRANCELL, ipv6 = true),
            RecoveryContext(Firewall.OTHER, ipv6 = true))) {
            assertTrue(ctx.toString(), NetworkFirewalls.MCI_V6_CLEAN_PROFILE !in order(p, mci, ctx))
        }
        assertTrue(v6.requiresIpv6 && Firewall.MCI in v6.firewalls)
    }

    @Test fun aRevivalThatWonByRemovingEchSaysSoAndTheLedgerKeepsIt() {
        val dead = cf("h", ech = "h.example.workers.dev+https://1.1.1.1/dns-query")
        val r = ConfigRevival(engine, probe = { ps ->
            ps.map { p -> if (p.cipherSuites == BpbFix.SEMI_PYTHON_CIPHER_SUITES && p.address == dead.address) TestResult.Passed(250) else TestResult.Failed("timeout") }
        })
        val res = r.run(listOf(dead), irancell).results.single()
        assertEquals(ConfigRevival.Outcome.REVIVED, res.outcome)
        assertEquals("${NetworkFirewalls.IRANCELL_FF_PROFILE}@v1", res.recipe)
        assertTrue(res.detail.contains(ConfigRevival.ECH_REMOVED))
        assertTrue(ledger.clearedEch(dead.effectiveFingerprint, irancell))
        // The saved config keeps its ECH; only the recovered copy drops it.
        assertEquals("h.example.workers.dev+https://1.1.1.1/dns-query", dead.echConfigList)
        assertEquals("", engine.active(dead, irancell)!!.profile.echConfigList)
    }

    @Test fun labExperimentsUseFfButNotTheGatedRecipes() {
        val built = com.example.vpn.lab.CandidateGenerator(clock = { now }).generate("E", cf("k"),
            com.example.vpn.lab.LabFailureCategory.TLS_HANDSHAKE_FAILED, null, endpoints = listOf("188.114.97.3"), max = 50)
        val ids = built.map { it.candidate.mutationProfileId.substringBefore('@') }
        assertTrue(NetworkFirewalls.IRANCELL_FF_IP_PROFILE !in ids)
        assertTrue(NetworkFirewalls.MCI_V6_CLEAN_PROFILE !in ids)
    }
}
