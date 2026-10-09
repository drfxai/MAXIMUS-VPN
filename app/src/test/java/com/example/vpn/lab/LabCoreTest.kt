package com.example.vpn.lab

import com.example.data.model.VlessProfile
import com.example.vpn.connectivity.HealthReport
import com.example.vpn.connectivity.ProbeResult
import com.example.vpn.connectivity.ProbeStep
import com.example.vpn.connectivity.ProbeVerdict
import com.example.vpn.connectivity.RecoveryProfiles
import com.example.vpn.diagnostics.FailureStage
import com.example.vpn.smart.NetworkCapabilityProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** LAB deterministic core: taxonomy, allowlist, candidates, experiments, promotion, memory, sessions. */
class LabCoreTest {
    private var now = RecoveryProfiles.REVIEWED_AT + 1_000
    private val parent = VlessProfile(
        id = "p1", name = "BPB 1", address = "worker.example.workers.dev", port = 443,
        uuid = "11111111-1111-1111-1111-111111111111", transport = "ws", security = "tls",
        sni = "worker.example.workers.dev", host = "worker.example.workers.dev", path = "/vl/abc?ed=2560",
        fingerprint = "chrome", canonicalFingerprint = "fp-bpb"
    )
    private val net = NetworkCapabilityProfile(transport = "cellular", ipv4Available = true, ipv6Available = false, udpAvailable = true, measuredAt = now)
    private val context = NetworkContext("NS-1", "cell:43235", "IPv4", now, "Irancell")

    // ---- Taxonomy ----

    @Test fun failureCategoriesAreRefinedByMeasurements() {
        assertEquals(LabFailureCategory.TLS_HANDSHAKE_FAILED, FailureClassifier.classify(FailureStage.TLS_HANDSHAKE_FAILED))
        assertEquals(LabFailureCategory.AUTHENTICATION_FAILED, FailureClassifier.classify(FailureStage.PROXY_AUTH_FAILED))
        assertEquals(LabFailureCategory.IPV6_PATH_FAILED, FailureClassifier.classify(FailureStage.TCP_CONNECT_FAILED, net, endpointFamily = "ipv6"))
        assertEquals(LabFailureCategory.UDP_UNAVAILABLE, FailureClassifier.classify(FailureStage.TIMEOUT, net.copy(udpAvailable = false), udpTransport = true))
        assertEquals(LabFailureCategory.QUIC_UNAVAILABLE, FailureClassifier.classify(FailureStage.TIMEOUT, net.copy(quicAvailable = false), usesQuic = true))
        assertEquals(LabFailureCategory.HTTP_CONNECTIVITY_FAILED, FailureClassifier.classify(FailureStage.HTTP_STATUS_INVALID))
        FailureStage.entries.forEach { assertNotNull(FailureClassifier.of(it)) }
    }

    @Test fun observationsAndAssessmentsStaySeparate() {
        val r = ProbeResult(ProbeStep.TLS_HANDSHAKE, 0, 40, false, FailureStage.TLS_HANDSHAKE_FAILED)
        val report = HealthReport("s", listOf(r), ProbeVerdict.BLOCKED, ProbeStep.TLS_HANDSHAKE, FailureStage.TLS_HANDSHAKE_FAILED)
        val o = FailureClassifier.observe(report, now)
        assertTrue(o.text.contains("TLS_HANDSHAKE failed"))
        assertEquals(LabFailureCategory.TLS_HANDSHAKE_FAILED, o.category)
        val a = FailureClassifier.assess(o.category)
        assertEquals(Assessment.Source.DETERMINISTIC_RULE, a.by)
        assertTrue(a.text.contains("possible"))
        assertTrue(a.confidence < 1.0)
    }

    // ---- Allowlist ----

    @Test fun onlyApprovedFieldsAndValuesMayChange() {
        assertTrue(CandidateMutationPolicy.check(parent, parent.copy(fingerprint = "firefox")).allowed)
        assertTrue(CandidateMutationPolicy.check(parent, parent.copy(alpn = "http/1.1")).allowed)
        assertFalse(CandidateMutationPolicy.check(parent, parent.copy(fingerprint = "evil")).allowed)
        assertFalse(CandidateMutationPolicy.check(parent, parent.copy(alpn = "h3,weird")).allowed)
        assertFalse(CandidateMutationPolicy.check(parent, parent.copy(finalMask = """{"tcp":[{"type":"fragment","settings":{"length":"1-2"}}]}""")).allowed)
        assertFalse("identity", CandidateMutationPolicy.check(parent, parent.copy(uuid = "22222222-2222-2222-2222-222222222222")).allowed)
        assertFalse("sni", CandidateMutationPolicy.check(parent, parent.copy(sni = "other.example")).allowed)
        assertFalse("insecure", CandidateMutationPolicy.check(parent, parent.copy(allowInsecure = true)).allowed)
        assertFalse("security mode", CandidateMutationPolicy.check(parent, parent.copy(security = "none")).allowed)
        assertFalse("pins", CandidateMutationPolicy.check(parent, parent.copy(pinnedPeerCertSha256 = "a".repeat(64))).allowed)
        assertFalse("raw", CandidateMutationPolicy.check(parent, parent.copy(rawConfig = "{}")).allowed)
        assertFalse("private endpoint", CandidateMutationPolicy.check(parent, parent.copy(address = "10.0.0.1")).allowed)
        assertTrue("public endpoint", CandidateMutationPolicy.check(parent, parent.copy(address = "104.16.1.1")).allowed)
        assertFalse("unchanged", CandidateMutationPolicy.check(parent, parent).allowed)
    }

    @Test fun unknownAndProtectedRequestedFieldsAreRefusedBeforeAnythingIsBuilt() {
        assertFalse(CandidateMutationPolicy.checkRequest(parent, mapOf("uuid" to "x")).first.allowed)
        assertFalse(CandidateMutationPolicy.checkRequest(parent, mapOf("killSwitch" to "off")).first.allowed)
        assertFalse(CandidateMutationPolicy.checkRequest(parent, mapOf("allowInsecure" to "true")).first.allowed)
        assertFalse(CandidateMutationPolicy.checkRequest(parent, mapOf("mtu" to "1200")).first.allowed)
        assertFalse(CandidateMutationPolicy.checkRequest(parent, mapOf("command" to "rm -rf /")).first.allowed)
        val (ok, built) = CandidateMutationPolicy.checkRequest(parent, mapOf("fingerprint" to "safari"))
        assertTrue(ok.allowed)
        assertEquals("safari", built!!.fingerprint)
        assertEquals("chrome", parent.fingerprint)
    }

    // ---- Candidates ----

    @Test fun candidatesAreBoundedSafeAndLeaveTheOriginalUntouched() {
        val before = parent.copy()
        val built = CandidateGenerator(clock = { now }).generate("EXP-001", parent, LabFailureCategory.TLS_HANDSHAKE_FAILED, net, max = 5)
        assertTrue(built.isNotEmpty())
        assertTrue(built.size <= 5)
        assertEquals(before, parent)
        built.forEach { b ->
            assertTrue(b.candidate.securityPassed)
            assertEquals("p1", b.candidate.parentProfileId)
            assertEquals(parent.uuid, b.profile!!.uuid)
            assertEquals(parent.sni, b.profile.sni)
            assertFalse(b.candidate.toJson().toString().contains(parent.uuid))
            assertFalse("no server name in stored changes", b.candidate.toJson().toString().contains("worker.example"))
        }
        // IPv6 endpoint profiles are not generated on a network without IPv6.
        val withEndpoints = CandidateGenerator(clock = { now }).generate("EXP-002", parent, LabFailureCategory.TCP_CONNECT_FAILED, net,
            endpoints = listOf("104.16.1.1", "2606:4700::1"))
        assertTrue(withEndpoints.none { it.candidate.endpoint?.contains(':') == true })
        assertTrue(withEndpoints.any { it.candidate.endpoint == "104.16.1.1" })
    }

    @Test fun unrecoverableFailuresGetNoCandidates() {
        val g = CandidateGenerator(clock = { now })
        for (c in listOf(LabFailureCategory.CERTIFICATE_VALIDATION_FAILED, LabFailureCategory.PROTOCOL_HANDSHAKE_FAILED, LabFailureCategory.SECURITY_REJECTED, LabFailureCategory.NETWORK_CHANGED)) {
            assertTrue(c.name, g.generate("E", parent, c, net).isEmpty())
        }
        assertTrue("REALITY is not a LAB target", g.generate("E", parent.copy(security = "reality", publicKey = "k"), LabFailureCategory.TLS_HANDSHAKE_FAILED, net).isEmpty())
    }

    @Test fun retiredMutationsAreNotTriedAgainAndStoredCandidatesRebuild() {
        val g = CandidateGenerator(clock = { now })
        val all = g.generate("E", parent, LabFailureCategory.TLS_HANDSHAKE_FAILED, net, max = 10).map { it.candidate.mutationProfileId }
        val first = all.first()
        assertFalse(g.generate("E", parent, LabFailureCategory.TLS_HANDSHAKE_FAILED, net, retired = setOf(first), max = 10).any { it.candidate.mutationProfileId == first })
        val rebuilt = g.rebuild(parent, first, null)
        assertNotNull(rebuilt)
        assertNull(g.rebuild(parent, "no-such-mutation@v1", null))
    }

    @Test fun unsupportedCapabilitiesAreNeverUsable() {
        assertFalse(CoreCapabilityRegistry.byId("masque")!!.usable)
        assertFalse(CoreCapabilityRegistry.byId("tuic")!!.usable)
        assertTrue(CoreCapabilityRegistry.byId("ech")!!.usable)
        assertTrue(CoreCapabilityRegistry.ALL.all { it.evidence.isNotBlank() })
    }

    // ---- Experiments ----

    private fun experiment(builds: List<CandidateGenerator.Built>) = LabExperiment(
        "EXP-001", context.sessionId, context.contextKey, context.label, parent.id, parent.effectiveFingerprint,
        LabFailureCategory.TLS_HANDSHAKE_FAILED, ExperimentState.CREATED, now, candidates = builds.map { it.candidate }
    )

    private fun builds() = CandidateGenerator(clock = { now }).generate("EXP-001", parent, LabFailureCategory.TLS_HANDSHAKE_FAILED, net, max = 3)

    @Test fun anExperimentWalksItsLifecycleAndVerifiesWhatWorkedEveryTime() = runBlocking {
        val b = builds()
        val winner = b.first().candidate.candidateId
        val states = mutableListOf<ExperimentState>()
        val engine = ExperimentEngine(LabBudget(rounds = 3, roundSpacingMs = 0), clock = { now }, sleep = { now += it })
        val result = engine.run(experiment(b), b.associate { it.candidate.candidateId to it.profile!! }, { ps ->
            now += 1_000
            ps.mapIndexed { i, _ -> if (i == 0) TestOutcome.passed(180) else TestOutcome.failed(LabFailureCategory.TLS_HANDSHAKE_FAILED) }
        }, { true }) { states += it.state }
        assertEquals(listOf(ExperimentState.QUEUED, ExperimentState.TESTING), states.distinct().take(2))
        assertEquals(ExperimentState.VERIFIED, result.state)
        assertEquals(PromotionState.VERIFIED, result.candidates.first { it.candidateId == winner }.state)
        assertEquals(winner, result.best?.candidateId)
        // Losers were dropped after two failures in a row, not tested every round.
        assertTrue(result.candidates.filter { it.candidateId != winner }.all { it.stats.attempts == 2 })
        assertNotNull(result.endTime)
    }

    @Test fun aNetworkChangeCancelsAndStaleResultsAreNotUsed() = runBlocking {
        val b = builds()
        var current = true
        val engine = ExperimentEngine(LabBudget(rounds = 3, roundSpacingMs = 0), clock = { now }, sleep = {})
        val result = engine.run(experiment(b), b.associate { it.candidate.candidateId to it.profile!! }, { ps ->
            current = false // the phone switched networks while this round ran
            ps.map { TestOutcome.passed(100) }
        }, { current })
        assertEquals(ExperimentState.CANCELLED, result.state)
        assertTrue(result.candidates.all { it.stats.attempts == 0 })
    }

    @Test fun anExperimentCanBeCancelled() = runBlocking {
        val b = builds()
        val gate = CompletableDeferred<Unit>()
        var last: LabExperiment? = null
        val engine = ExperimentEngine(LabBudget(rounds = 2, roundSpacingMs = 0), clock = { now }, sleep = {})
        val job = async {
            engine.run(experiment(b), b.associate { it.candidate.candidateId to it.profile!! }, { ps -> gate.await(); ps.map { TestOutcome.passed(1) } }, { true }) { last = it }
        }
        yield()
        job.cancel()
        runCatching { job.await() }
        assertEquals(ExperimentState.CANCELLED, last!!.state)
    }

    @Test fun nothingMeasuredIsNotAFailure() = runBlocking {
        val b = builds()
        val engine = ExperimentEngine(LabBudget(rounds = 2, roundSpacingMs = 0), clock = { now }, sleep = {})
        val result = engine.run(experiment(b), b.associate { it.candidate.candidateId to it.profile!! }, { ps -> ps.map { TestOutcome.notRun() } }, { true })
        assertEquals(ExperimentState.CANCELLED, result.state)
        assertTrue(result.candidates.all { it.stats.attempts == 0 && it.state == PromotionState.EXPERIMENTAL })
    }

    @Test fun refusedCandidatesMakeARejectedExperiment() = runBlocking {
        val refused = experiment(builds()).let { e -> e.copy(candidates = e.candidates.map { it.copy(securityPassed = false) }) }
        val result = ExperimentEngine(clock = { now }).run(refused, emptyMap(), { error("must not test") }, { true })
        assertEquals(ExperimentState.REJECTED, result.state)
    }

    @Test fun admissionRespectsBudgetBatteryMeteredAndVpn() {
        val budget = LabBudget(maxExperimentsPerHour = 2)
        val idle = LabBudget.DeviceState(metered = false, batteryPercent = 80, charging = false, vpnRunning = false, userStarted = false)
        assertNull(budget.refusal(idle, emptyList(), null, now))
        assertNotNull(budget.refusal(idle.copy(vpnRunning = true), emptyList(), null, now))
        assertNotNull(budget.refusal(idle, listOf(now - 1000, now - 2000), null, now))
        assertNotNull(budget.refusal(idle.copy(metered = true), emptyList(), null, now))
        assertNull("the user may start one on a metered network", budget.refusal(idle.copy(metered = true, userStarted = true), emptyList(), null, now))
        assertNotNull(budget.refusal(idle.copy(batteryPercent = 10), emptyList(), null, now))
        assertNotNull(budget.refusal(idle, emptyList(), now - 60_000, now))
    }

    // ---- Promotion ----

    @Test fun promotionIsDeterministicAndNeedsRepeatedFreshEvidence() {
        val p = CandidatePromotionPolicy()
        var s = CandidateStats()
        assertEquals(PromotionState.EXPERIMENTAL, p.evaluate(s, true, PromotionState.EXPERIMENTAL, now))
        s = s.record(true, 200, null, now)
        assertEquals("one success is not enough", PromotionState.EXPERIMENTAL, p.evaluate(s, true, PromotionState.EXPERIMENTAL, now))
        s = s.record(true, 210, null, now)
        assertEquals(PromotionState.CANDIDATE, p.evaluate(s, true, PromotionState.EXPERIMENTAL, now))
        s = s.record(true, 190, null, now)
        assertEquals(PromotionState.VERIFIED, p.evaluate(s, true, PromotionState.CANDIDATE, now))
        assertEquals("security overrides everything", PromotionState.REJECTED, p.evaluate(s, false, PromotionState.VERIFIED, now))
        assertEquals("old evidence degrades", PromotionState.DEGRADED, p.evaluate(s, true, PromotionState.VERIFIED, now + p.maxEvidenceAgeMs + 1))
        val failing = (1..3).fold(s) { acc, _ -> acc.record(false, null, LabFailureCategory.TIMEOUT, now) }
        assertEquals("retired after repeated failures", PromotionState.RETIRED, p.evaluate(failing, true, PromotionState.VERIFIED, now))
        val never = (1..3).fold(CandidateStats()) { acc, _ -> acc.record(false, null, LabFailureCategory.TIMEOUT, now) }
        assertEquals(PromotionState.RETIRED, p.evaluate(never, true, PromotionState.EXPERIMENTAL, now))
        assertTrue(p.confidence(s, now) > p.confidence(s, now + 48 * 3_600_000L))
        assertTrue(p.confidence(CandidateStats().record(true, 1, null, now), now) < p.confidence(s, now))
        assertTrue(p.score(s, true, now).eligible)
        assertFalse(p.score(s, false, now).eligible)
    }

    // ---- Memory, persistence, sessions ----

    @Test fun verifiedProfilesPersistAndNetworkMemoryReusesThem() = runBlocking {
        var saved: String? = null
        val store = LabStore({ saved }, { saved = it }, { now })
        val b = builds()
        val engine = ExperimentEngine(LabBudget(rounds = 3, roundSpacingMs = 0), clock = { now }, sleep = {})
        val result = engine.run(experiment(b), b.associate { it.candidate.candidateId to it.profile!! }, { ps -> now += 500; ps.mapIndexed { i, _ -> if (i == 0) TestOutcome.passed(150) else TestOutcome.failed(LabFailureCategory.TIMEOUT) } }, { true })
        store.saveExperiment(result)
        val changed = store.absorb(result)
        assertEquals(1, changed.size)
        assertEquals(PromotionState.VERIFIED, changed[0].state)
        assertEquals("LAB-NET-001", changed[0].profileId)

        val reloaded = LabStore({ saved }, { saved = it }, { now })
        assertEquals(changed, reloaded.verifiedProfiles())
        val plan = reloaded.planFor(context.contextKey)
        assertFalse(plan.explore)
        assertEquals("LAB-NET-001", plan.revalidate.single().profileId)
        assertTrue(reloaded.planFor("wifi|IPv4").explore)
        assertFalse(saved!!.contains(parent.uuid))
        assertFalse(saved!!.contains("worker.example"))

        reloaded.setDisabled("LAB-NET-001", true)
        assertTrue(reloaded.planFor(context.contextKey).explore)
        reloaded.retire("LAB-NET-001")
        assertTrue(reloaded.retiredMutations(context.contextKey, parent.effectiveFingerprint).isNotEmpty())
    }

    @Test fun anInterruptedExperimentIsClosedOnRestart() {
        var saved: String? = null
        val store = LabStore({ saved }, { saved = it }, { now })
        store.saveExperiment(experiment(builds()).copy(state = ExperimentState.TESTING))
        val reloaded = LabStore({ saved }, { saved = it }, { now })
        assertEquals(ExperimentState.CANCELLED, reloaded.experiments().single().state)
        assertEquals(AutomationLevel.RECOMMEND, reloaded.automation())
        assertEquals(0, LabStore({ "{garbage" }, {}).experiments().size)
    }

    @Test fun networkTransitionsStartNewSessions() {
        val t = NetworkSessionTracker { now }
        val (a, firstNew) = t.observe("cell:43235", "IPv4", "Irancell")
        assertTrue(firstNew)
        assertFalse(t.observe("cell:43235", "IPv4", "Irancell").second)
        now += 1
        val (b, changed) = t.observe("cell:43235", "IPv4+IPv6", "Irancell")
        assertTrue("dual stack appearing is a new session", changed)
        assertFalse(t.isCurrent(a.sessionId))
        assertTrue(t.isCurrent(b.sessionId))
        t.lost()
        assertFalse(t.isCurrent(b.sessionId))
        assertEquals("IPv4+IPv6", NetworkSessionTracker.families(true, true))
    }
}
