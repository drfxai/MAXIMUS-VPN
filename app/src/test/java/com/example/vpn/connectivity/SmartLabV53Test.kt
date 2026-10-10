package com.example.vpn.connectivity

import com.example.data.model.VlessProfile
import com.example.vpn.ConnectionLifecycle
import com.example.vpn.lab.AdaptivePlanner
import com.example.vpn.lab.EchMeasurement
import com.example.vpn.lab.ExperimentPlanner
import com.example.vpn.lab.MtuIntelligence
import com.example.vpn.lab.NetworkState
import com.example.vpn.lab.PathFamily
import com.example.vpn.stealth.StealthVariants
import com.example.xray.ProbeTargets
import com.example.xray.RealDelayProbe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Smart LAB V5.3: shared evidence, fresh-evidence eligibility, recovery stages, single-flight rules, multi-target
 * verification, ECH and MTU semantics and the probe manifest. Pure unit tests; they say nothing about a phone.
 */
class SmartLabV53Test {
    private var now = 1_000_000L
    private val book = EvidenceBook { now }
    private val tls = VlessProfile(name = "tls", address = "a.example.com", port = 443, uuid = "8b0e2c4a-9f6d-4c1e-a7b3-2d5f8e1c0a9b",
        transport = "ws", security = "tls", sni = "a.example.com", host = "a.example.com", path = "/ws", fingerprint = "chrome")
    private val originalInvoker = RealDelayProbe.invoker

    @Before fun reset() { ProbeTargets.health.reset(); ProbeTargets.active = ProbeTargets.BUILT_IN }
    @After fun restore() { RealDelayProbe.invoker = originalInvoker; ProbeTargets.health.reset() }

    private fun pass(ref: String, type: MeasurementType = MeasurementType.REAL_REQUEST, family: String = "TLS") =
        book.recordPath(EvidenceSource.APP_TEST, type, ref, family, true, 300)
    private fun fail(ref: String, family: String = "TLS") = book.recordPath(EvidenceSource.APP_TEST, MeasurementType.REAL_REQUEST, ref, family, false)

    // ------------------------------------------------------------ eligibility from fresh evidence

    @Test fun eligibilityOrdersFreshVerifiedThenCandidateThenUntestedThenStaleAndDropsRecentFailures() {
        book.observeNetwork("net-a")
        pass("stale")
        now += 15 * 60_000L // older than fresh, still this session
        pass("verified"); now += 3_000; pass("verified")
        pass("candidate")
        fail("dead")
        assertEquals(PathEligibility.FRESH_VERIFIED, book.eligibility("verified"))
        assertEquals(PathEligibility.FRESH_CANDIDATE, book.eligibility("candidate"))
        assertEquals(PathEligibility.UNTESTED, book.eligibility("never"))
        assertEquals(PathEligibility.STALE_VERIFIED, book.eligibility("stale"))
        assertEquals(PathEligibility.RECENTLY_FAILED, book.eligibility("dead"))
        assertEquals(listOf("verified", "candidate", "never", "stale"),
            book.eligibleInOrder(listOf("dead", "stale", "never", "candidate", "verified")) { it })
    }

    @Test fun aRecentFailureAgesOutAndANetworkChangeStartsAFreshSession() {
        val first = book.observeNetwork("net-a")
        fail("x")
        assertEquals(PathEligibility.RECENTLY_FAILED, book.eligibility("x"))
        now += EvidenceBook.RECENT_FAIL_MS + 1
        assertEquals(PathEligibility.UNTESTED, book.eligibility("x"))
        fail("y")
        val second = book.observeNetwork("net-b")
        assertNotEquals(first.id, second.id)
        assertEquals("evidence of another network never blocks a path here", PathEligibility.UNTESTED, book.eligibility("y"))
        assertEquals(second.id, book.observeNetwork("net-b").id)
    }

    @Test fun oneTunnelOrMultiTargetPassIsEnoughToBeVerified() {
        book.observeNetwork("n")
        pass("t", MeasurementType.TUNNEL_TRAFFIC)
        book.recordPath(EvidenceSource.LAB, MeasurementType.MULTI_TARGET, "m", "TLS", true, 200, targetsPassed = 2, targetsTried = 3)
        assertEquals(PathEligibility.FRESH_VERIFIED, book.eligibility("t"))
        assertEquals(PathEligibility.FRESH_VERIFIED, book.eligibility("m"))
    }

    @Test fun nothingIsRecordedWithoutASession() {
        fail("x")
        book.recordNetwork(EvidenceSource.LAB, MeasurementType.UDP, "udp", false)
        assertTrue(book.all().isEmpty())
    }

    @Test fun aVariantFailureNeverMarksItsSavedConfig() {
        val ech = tls.copy(echConfigList = "a.example.com+${StealthVariants.ECH_DNS}")
        assertNotEquals(PathRef.of(tls), PathRef.of(ech))
        assertEquals("a resolved copy is the same path", PathRef.of(tls), PathRef.of(tls.copy(address = "203.0.113.7", canonicalFingerprint = tls.effectiveFingerprint)))
    }

    // ------------------------------------------------------------ assessment and decisions

    @Test fun tlsFailuresAreATlsPathFailureNotSni() {
        book.observeNetwork("n")
        fail("r1", "REALITY"); fail("t1", "TLS")
        val a = book.assess()
        assertTrue(NetworkState.TLS_PATH_FAILURE in a.restrictions)
        assertFalse(NetworkState.SNI_INTERFERENCE_SUSPECTED in a.restrictions)
        assertTrue(a.basis.any { it.contains("not proof of SNI") })
    }

    @Test fun onlyAControlledComparisonNamesSni() {
        book.observeNetwork("n")
        fail("t1", "TLS")
        book.recordNetwork(EvidenceSource.LAB, MeasurementType.SNI_COMPARISON, "sni", false, detail = "2/2 cut")
        val a = book.assess()
        assertTrue(NetworkState.SNI_INTERFERENCE_SUSPECTED in a.restrictions)
        assertFalse("the controlled comparison replaces the vaguer label", NetworkState.TLS_PATH_FAILURE in a.restrictions)
    }

    @Test fun noVerifiedEgressOnlyAfterRecoveryWasExhausted() {
        book.observeNetwork("n")
        fail("a"); fail("b", "QUIC"); fail("c", "WireGuard")
        assertNotEquals(NetworkState.NO_VERIFIED_EGRESS_AFTER_RECOVERY, book.assess().primary)
        assertTrue(NetworkState.UDP_DEGRADED in book.assess().restrictions)
        book.markRecoveryExhausted()
        assertEquals(NetworkState.NO_VERIFIED_EGRESS_AFTER_RECOVERY, book.assess().primary)
        book.observeNetwork("other")
        assertNotEquals("exhaustion belongs to one network session", NetworkState.NO_VERIFIED_EGRESS_AFTER_RECOVERY, book.assess().primary)
    }

    @Test fun decisionsNeverConnectAnyway() {
        assertEquals(RecoveryDecision.Action.CONNECT_VERIFIED, book.decide(RecoveryStage.SAVED_PROFILE_RACE, "ref", true).action)
        assertEquals(RecoveryDecision.Action.ESCALATE_TO_RECOVERY, book.decide(RecoveryStage.SAVED_PROFILE_RACE, null, true).action)
        assertEquals(RecoveryDecision.Action.STOP_NO_VERIFIED_PATH, book.decide(RecoveryStage.RECOVERY_ENGINES, null, true).action)
        RecoveryStage.values().forEach { from ->
            assertFalse("$from → connect anyway must not be automatic", RecoveryStage.allowed(from, RecoveryStage.USER_CONNECT_ANYWAY, byUser = false))
        }
        assertTrue(RecoveryStage.allowed(RecoveryStage.NO_VERIFIED_EGRESS_AFTER_RECOVERY, RecoveryStage.USER_CONNECT_ANYWAY, byUser = true))
    }

    @Test fun theRecoveryLadderOnlyMovesForward() {
        val seen = mutableListOf<RecoveryStage>()
        val m = RecoveryMachine { seen += it }
        assertTrue(m.advance(RecoveryStage.ORDINARY_PREFLIGHT))
        assertTrue(m.advance(RecoveryStage.RECOVERY_ENGINES))
        assertFalse(m.advance(RecoveryStage.SAFE_VARIANTS))
        assertTrue(m.advance(RecoveryStage.NO_VERIFIED_EGRESS_AFTER_RECOVERY))
        assertFalse(m.advance(RecoveryStage.CONNECTED_VERIFIED))
        assertEquals(listOf(RecoveryStage.ORDINARY_PREFLIGHT, RecoveryStage.RECOVERY_ENGINES, RecoveryStage.NO_VERIFIED_EGRESS_AFTER_RECOVERY), seen)
    }

    @Test fun dnsPropertiesStayApartAndRecursiveEgressNeedsInfrastructure() {
        book.observeNetwork("n")
        book.recordDns(DnsEvidenceKind.SYSTEM_DNS, DnsEvidenceStatus.POISONED)
        book.recordDns(DnsEvidenceKind.PRECONNECT_DOH_RESOLUTION, DnsEvidenceStatus.VERIFIED)
        val d = book.dnsEvidence()
        assertEquals(DnsEvidenceStatus.POISONED, d[DnsEvidenceKind.SYSTEM_DNS])
        assertEquals(DnsEvidenceStatus.VERIFIED, d[DnsEvidenceKind.PRECONNECT_DOH_RESOLUTION])
        assertEquals(DnsEvidenceStatus.NOT_TESTED, d[DnsEvidenceKind.DNS_THROUGH_TUNNEL])
        assertEquals(DnsEvidenceStatus.INFRASTRUCTURE_REQUIRED, d[DnsEvidenceKind.RECURSIVE_FOREIGN_DNS_EGRESS])
    }

    // ------------------------------------------------------------ single flight

    @Test fun staleAutomaticWorkIsDroppedAndOnlyOneSwitchRuns() {
        val l = ConnectionLifecycle()
        val ticket = l.newIntent()
        assertTrue(l.isCurrent(ticket))
        l.newIntent() // the user pressed Disconnect
        assertFalse(l.isCurrent(ticket))
        assertTrue(l.tryBeginSwitch())
        assertFalse(l.tryBeginSwitch())
        l.endSwitch()
        assertTrue(l.tryBeginSwitch())
    }

    @Test fun aSecondTeardownJoinsTheFirst() = runBlocking {
        val l = ConnectionLifecycle()
        val runs = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val first = async { l.singleTeardown { runs.incrementAndGet(); gate.await() } }
        while (runs.get() == 0) kotlinx.coroutines.yield()
        val second = async { l.singleTeardown { runs.incrementAndGet() } }
        gate.complete(Unit)
        assertTrue(first.await())
        assertFalse(second.await())
        assertEquals(1, runs.get())
    }

    // ------------------------------------------------------------ multi-target verification

    private fun answer(passByUrl: (String) -> Boolean): (String) -> String = { request ->
        val payload = JSONObject(request).getJSONObject("payload")
        val ok = passByUrl(payload.getString("url"))
        val n = payload.getJSONArray("configs").length()
        val items = JSONArray((0 until n).map { if (ok) JSONObject().put("success", true).put("delay", 250) else JSONObject().put("success", false).put("error", "EOF") })
        JSONObject().put("success", true).put("data", JSONObject().put("results", items)).toString()
    }

    @Test fun oneOfThreeTargetsIsVerifiedButDegradedAndNoneIsTheCandidatesFailureOnly() {
        assertEquals(ProbeTargets.Verdict.EGRESS_VERIFIED_DEGRADED, ProbeTargets.verdict(1, 3))
        assertEquals(ProbeTargets.Verdict.EGRESS_VERIFIED, ProbeTargets.verdict(3, 3))
        assertEquals(ProbeTargets.Verdict.CANDIDATE_FAILED, ProbeTargets.verdict(0, 3))
        RealDelayProbe.invoker = answer { it.contains("cloudflare") }
        val r = RealDelayProbe.measureTargets(tls, 5)
        assertEquals(3, r.tried)
        assertEquals(listOf("cloudflare-204"), r.passedTargets)
        assertEquals(ProbeTargets.Verdict.EGRESS_VERIFIED_DEGRADED, r.verdict)
    }

    @Test fun oneTargetsOutageNeverFailsAWorkingCandidate() {
        RealDelayProbe.invoker = answer { !it.contains("gstatic") }
        val o = RealDelayProbe.measure(tls, 5)
        assertTrue(o is RealDelayProbe.Outcome.Delay)
        assertEquals("cloudflare-204", (o as RealDelayProbe.Outcome.Delay).target)
        assertEquals(2, o.targetsTried)
    }

    @Test fun aKnownUpTargetCostsNoSecondRequest() {
        val calls = AtomicInteger()
        RealDelayProbe.invoker = answer { calls.incrementAndGet(); true }
        RealDelayProbe.measure(tls, 5)
        RealDelayProbe.invoker = answer { calls.incrementAndGet(); false }
        val o = RealDelayProbe.measure(tls.copy(name = "other"), 5)
        assertTrue(o is RealDelayProbe.Outcome.Failed)
        assertEquals(2, calls.get())
    }

    @Test fun everyRealTestReachesTheObserver() {
        val seen = mutableListOf<RealDelayProbe.Outcome>()
        val before = RealDelayProbe.observer
        RealDelayProbe.observer = { _, o -> seen += o }
        try {
            RealDelayProbe.invoker = answer { false }
            RealDelayProbe.measure(tls, 5)
        } finally { RealDelayProbe.observer = before }
        assertEquals(1, seen.size)
        assertEquals(2, (seen.single() as RealDelayProbe.Outcome.Failed).targetsTried)
    }

    // ------------------------------------------------------------ ECH

    @Test fun echIsProvenOnlyByRealTrafficAndAFallbackIsNotEch() {
        val fallback = EchMeasurement.judge(RealDelayProbe.Outcome.Delay(200), RealDelayProbe.Outcome.Failed("ech config: no ech dns record"), null)
        assertEquals(EchMeasurement.State.ECH_FALLBACK_USED, fallback.state)
        assertEquals(EchMeasurement.State.ECH_CONFIG_UNAVAILABLE, fallback.echAttempt)
        assertFalse(EchMeasurement.proven(fallback.state))
        assertEquals(EchMeasurement.State.ECH_VERIFIED,
            EchMeasurement.judge(null, RealDelayProbe.Outcome.Delay(300), RealDelayProbe.Outcome.Delay(280)).state)
        assertEquals(EchMeasurement.State.ECH_DEGRADED,
            EchMeasurement.judge(null, RealDelayProbe.Outcome.Delay(300), RealDelayProbe.Outcome.Failed("EOF")).state)
        assertEquals(EchMeasurement.State.ECH_NOT_TESTED, EchMeasurement.judge(null, RealDelayProbe.Outcome.NotRun("busy"), null).state)
    }

    @Test fun echCopiesKeepSecurityAndUseOnlyEncryptedDns() {
        val v = EchMeasurement.variant(tls)!!
        assertEquals(tls.allowInsecure, v.allowInsecure)
        assertEquals(tls.uuid, v.uuid)
        assertEquals(tls.sni, v.sni)
        assertTrue(v.echConfigList.endsWith(StealthVariants.ECH_DNS))
        assertTrue(EchMeasurement.safeSource(v.echConfigList))
        assertFalse(EchMeasurement.safeSource("a.example.com+udp://8.8.8.8:53"))
        assertNotNull(EchMeasurement.unsupportedReason(tls.copy(security = "reality")))
        assertNotNull(EchMeasurement.unsupportedReason(tls.copy(allowInsecure = true)))
        assertNull(EchMeasurement.variant(tls.copy(echConfigList = "a.example.com+udp://1.1.1.1:53")))
    }

    @Test fun echPriorityFollowsTheEvidence() {
        assertEquals(1.0, EchMeasurement.priority(setOf(NetworkState.SNI_INTERFERENCE_SUSPECTED), NetworkState.FILTERED, false, false), 0.0)
        assertEquals(0.0, EchMeasurement.priority(emptySet(), NetworkState.DOMESTIC_ONLY, false, false), 0.0)
        assertTrue(EchMeasurement.priority(emptySet(), NetworkState.NORMAL, true, false) < 0.5)
        assertEquals(0.0, EchMeasurement.priority(setOf(NetworkState.SNI_INTERFERENCE_SUSPECTED), NetworkState.FILTERED, false, true), 0.0)
    }

    // ------------------------------------------------------------ MTU

    @Test fun mtuSearchTriesTheFloorFirstAndRulesMtuOutWhenItFails() {
        val s = MtuIntelligence.Search(1420, ipv6 = false)
        assertEquals(1280, s.next())
        s.record(1280, false)
        assertNull(s.next())
        assertTrue(s.ruledOut)
        assertEquals(MtuIntelligence.Suspicion.NOT_MTU, MtuIntelligence.suspicion(MtuIntelligence.Signals(lowerMtuNoImprovement = 1)))
        assertEquals(MtuIntelligence.Suspicion.NONE, MtuIntelligence.suspicion(MtuIntelligence.Signals()))
    }

    @Test fun mtuSearchFindsTheHighestWorkingValueWithinItsBudget() {
        val blackhole = 1380 // the path drops anything larger
        val s = MtuIntelligence.Search(1500, ipv6 = false)
        var tests = 0
        while (true) { val m = s.next() ?: break; tests++; s.record(m, m <= blackhole) }
        assertTrue(tests <= MtuIntelligence.MAX_TESTS)
        assertEquals(1360, s.best())
        assertTrue(MtuIntelligence.Search(1500, ipv6 = true).next()!! >= MtuIntelligence.IPV6_MINIMUM)
    }

    @Test fun onlyAVerifiedMtuIsCommittedAndOnlyForItsOwnKey() {
        val cache = MtuIntelligence.Cache(ttlMs = 1_000)
        val key = MtuIntelligence.Key("net-a", "xray", "wireguard", "ipv4")
        val staged = cache.propose(key, 1360, 0).stage()
        assertEquals(MtuIntelligence.TxState.STAGED, staged.commit().state)
        cache.store(staged.commit())
        assertNull(cache.working(key, 10))
        cache.store(staged.verify().commit())
        assertEquals(1360, cache.working(key, 10))
        assertNull(cache.working(key.copy(network = "net-b"), 10))
        assertNull(cache.working(key.copy(addressFamily = "ipv6"), 10))
        assertNull("expired", cache.working(key, 2_000))
        val p = VlessProfile(name = "wg", address = "198.51.100.2", port = 51820, uuid = "")
        val copy = MtuIntelligence.withWireGuardMtu(p, 1360)
        assertEquals(1360, MtuIntelligence.wireGuardMtu(copy))
        assertNotEquals(p.id, copy.id)
    }

    // ------------------------------------------------------------ probe manifest

    private fun manifest(targets: String, ttl: Long = 86_400_000L, version: Int = 2) =
        """{"schema":1,"version":$version,"ttlMs":$ttl,"targets":$targets}"""
    private val twoDomains = """[{"id":"a","url":"https://a.example/204","failureDomain":"A"},{"id":"b","url":"https://b.example/204","failureDomain":"B"}]"""

    @Test fun theProbeManifestIsStrict() {
        val ok = ProbeManifest.parse(manifest(twoDomains), now)
        assertEquals(2, ok.targets.size)
        fun refused(json: String) = runCatching { ProbeManifest.parse(json, now) }.isFailure
        assertTrue(refused(manifest("""[{"id":"a","url":"http://a.example/204","failureDomain":"A"},{"id":"b","url":"https://b.example/204","failureDomain":"B"}]""")))
        assertTrue(refused(manifest("""[{"id":"a","url":"https://a.example/204","failureDomain":"A"},{"id":"b","url":"https://b.example/204","failureDomain":"A"}]""")))
        assertTrue(refused(manifest(twoDomains, ttl = ProbeManifest.MAX_TTL_MS + 1)))
        assertTrue(refused("""{"schema":2,"version":1,"ttlMs":1000,"targets":$twoDomains}"""))
        assertEquals(ok.targets, ProbeManifest.choose(ok, now))
        assertEquals("an expired manifest falls back to the built-in targets", ProbeTargets.BUILT_IN, ProbeManifest.choose(ok, ok.expiresAt))
        assertFalse(ProbeManifest.newer(ok, ProbeManifest.parse(manifest(twoDomains, version = 1), now), now))
        assertTrue("an expired manifest is replaced", ProbeManifest.newer(ok, ProbeManifest.parse(manifest(twoDomains, version = 1), now), ok.expiresAt))
    }

    // ------------------------------------------------------------ planner: connect goal and recent failures

    private val ordinary = ExperimentPlanner.Plan(ExperimentPlanner.Mode.ORDINARY, emptyList(), emptyMap(), 20, emptyList())

    @Test fun recentlyFailedCandidatesAreNotRetested() {
        val p = AdaptivePlanner(ordinary, null, AdaptivePlanner.Priors(recentlyFailed = mapOf("dead" to "failed moments ago")))
        p.add(listOf(AdaptivePlanner.Candidate("dead", "dead", PathFamily.VLESS_TLS), AdaptivePlanner.Candidate("ok", "ok", PathFamily.VLESS_REALITY)))
        assertNotNull(p.track("dead")?.notTested)
        val d = p.decide(now) as AdaptivePlanner.Decision.Test
        assertEquals("ok", d.candidate.id)
    }

    @Test fun theConnectGoalStopsAtTheFirstVerifiedPath() {
        val p = AdaptivePlanner(ordinary, null, goal = AdaptivePlanner.Goal.CONNECT)
        p.add(listOf(AdaptivePlanner.Candidate("a", "a", PathFamily.VLESS_TLS), AdaptivePlanner.Candidate("b", "b", PathFamily.VLESS_REALITY)))
        val first = (p.decide(now) as AdaptivePlanner.Decision.Test).candidate.id
        p.record(first, listOf(true, true), 200, now, spanMs = 3_000)
        assertTrue(p.decide(now + 3_000) is AdaptivePlanner.Decision.Stop)
    }
}
