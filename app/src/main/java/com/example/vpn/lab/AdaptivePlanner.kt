package com.example.vpn.lab

import com.example.vpn.smart.NetworkCapabilityProfile

/**
 * The decision core of the adaptive Full Analysis: OBSERVE → CLASSIFY → DIAGNOSE → HYPOTHESES → choose the
 * experiment with the highest expected value → EXECUTE → RECORD → REPLAN, until a stop rule fires. Pure and
 * clock-injected, so every replanning rule is unit-tested without a phone.
 *
 * Each candidate is scored as probability × information gain ÷ cost:
 *  - probability starts from the plan (preferred families high, low-prior families low) and moves with what
 *    this run has seen: a family or failure domain that failed twice drops, a UDP pass raises UDP families,
 *    IPv6 endpoints rise when IPv4 fails abroad and IPv6 works, and fresh network memory and an optional AI
 *    hint add a small nudge. History never makes a path count as working; only this run's requests do.
 *  - gain is highest before any path works (Fast Recovery), then favours families that have no working path
 *    yet (diversity) and rechecks of winners (Deep Optimization). A saved config that already works does not
 *    end the run.
 *  - cost is higher for engine programs (start-up, battery), and higher again on metered data or low battery.
 *
 * Nothing here can add a method: candidates are saved configs that already passed the security gate.
 */
class AdaptivePlanner(
    private val plan: ExperimentPlanner.Plan,
    private val net: NetworkCapabilityProfile?,
    private var priors: Priors = Priors(),
    private val device: Device = Device(),
    /** CONNECT stops at the first verified path; FULL_ANALYSIS goes on to bounded optimization. */
    private val goal: Goal = Goal.FULL_ANALYSIS
) {
    enum class Goal { CONNECT, FULL_ANALYSIS }

    enum class Phase(val title: String) {
        FAST_RECOVERY("Fast recovery: finding a first working path"),
        DEEP_OPTIMIZATION("Deep optimization: verifying and comparing paths"),
        DONE("Done")
    }

    /** What a failure says about the network: methods in one domain tend to fail together. */
    enum class FailureDomain(val title: String) {
        TCP_TLS("TCP/TLS abroad"), UDP_QUIC("UDP/QUIC"), UDP_WIREGUARD("WireGuard-style UDP"), DNS("DNS tunnel"),
        PSIPHON("Psiphon"), TOR("Tor"), MIHOMO("Mihomo"), OTHER("Other");

        companion object {
            fun of(f: PathFamily): FailureDomain = when (f) {
                PathFamily.HYSTERIA2, PathFamily.TUIC -> UDP_QUIC
                PathFamily.WIREGUARD, PathFamily.AMNEZIAWG -> UDP_WIREGUARD
                PathFamily.DNS_TUNNEL -> DNS
                PathFamily.PSIPHON -> PSIPHON
                PathFamily.TOR, PathFamily.TOR_WEBTUNNEL, PathFamily.TOR_OBFS4, PathFamily.TOR_SNOWFLAKE -> TOR
                PathFamily.MIHOMO -> MIHOMO
                PathFamily.OTHER -> OTHER
                else -> TCP_TLS
            }
        }
    }

    data class Candidate(
        val id: String,
        val name: String,
        val family: PathFamily,
        /** The server address is an IPv6 literal. */
        val ipv6: Boolean = false,
        /** Worked on this network recently (fresh memory); only a nudge. */
        val provenHere: Boolean = false
    ) {
        val domain: FailureDomain get() = FailureDomain.of(family)
    }

    /**
     * Soft priors: [memory] per family in -1..1 from fresh, unexpired evidence on this network (never stale
     * history), and [ai], families an optional AI checkpoint ranked first. Both only reorder.
     */
    data class Priors(
        val memory: Map<PathFamily, Double> = emptyMap(),
        val ai: List<PathFamily> = emptyList(),
        /**
         * Candidates that failed a real request on this network session moments ago (ConnectivityBrain): not
         * retested now, so the budget goes to other paths. A network change, an aged-out failure or a newer pass
         * makes them eligible again.
         */
        val recentlyFailed: Map<String, String> = emptyMap()
    )

    data class Device(val metered: Boolean? = null, val lowBattery: Boolean = false)

    sealed class Decision {
        data class Test(val candidate: Candidate, val recheck: Boolean, val score: Double, val why: String) : Decision()
        /** Only rechecks remain and none is due yet. */
        data class Wait(val untilMs: Long, val why: String) : Decision()
        data class Stop(val why: String) : Decision()
    }

    /** Everything one candidate has shown in this run. */
    data class Track(
        val candidate: Candidate,
        val outcomes: List<Boolean> = emptyList(),
        val latencies: List<Long> = emptyList(),
        val firstPassAt: Long? = null,
        val lastPassAt: Long? = null,
        val notTested: String? = null,
        val failure: String? = null,
        /** When a recheck may run (a pass needs a second one at least the stable window later). */
        val recheckDueAt: Long? = null
    ) {
        val passes: Int get() = outcomes.count { it }
        val status: PathStatus get() = if (notTested != null) PathStatus.NOT_TESTED
            else FullAnalysis.verdict(outcomes, (lastPassAt ?: 0L) - (firstPassAt ?: 0L))
        val bestLatency: Long? get() = latencies.minOrNull()
        val working: Boolean get() = status == PathStatus.VERIFIED || status == PathStatus.CANDIDATE
    }

    private val tracks = linkedMapOf<String, Track>()
    private val events = mutableListOf<String>()
    private val raised = mutableSetOf<String>()
    var spent = 0
        private set
    var phase = Phase.FAST_RECOVERY
        private set
    /** The first candidate that passed a real request in this run. */
    var firstWorking: String? = null
        private set
    var stopReason: String? = null
        private set

    /** An AI checkpoint's validated family order: a small nudge only, never a verdict. */
    fun hint(ai: List<PathFamily>) {
        priors = priors.copy(ai = ai)
        if (ai.isNotEmpty()) event("ai-${ai.joinToString { it.name }}", "AI checkpoint suggested ${ai.joinToString { it.title }} first (order only).")
    }

    fun add(candidates: List<Candidate>) = candidates.forEach { c ->
        val why = priors.recentlyFailed[c.id]
        tracks.putIfAbsent(c.id, if (why != null) Track(c, notTested = "recently failed: $why") else Track(c))
        if (why != null) event("recent-${c.family.name}", "${c.name} failed a real request on this network moments ago: not retested now.")
    }

    fun tracks(): List<Track> = tracks.values.toList()
    fun track(id: String): Track? = tracks[id]

    /** Replanning steps in the order they happened, for the report ("UDP passed: UDP families raised"). */
    fun events(): List<String> = events.toList()

    /** True after a DNS tunnel passed in emergency mode: ordinary config copies stop. */
    val ordinaryMutationsStopped: Boolean get() = "dns-pass" in raised

    /** The best working path: verified first, then by latency. */
    fun best(): Track? = tracks.values.filter { it.working }
        .sortedWith(compareByDescending<Track> { it.status == PathStatus.VERIFIED }.thenBy { it.bestLatency ?: Long.MAX_VALUE })
        .firstOrNull()

    /** Best verified path per family, best first: the independent secure paths found so far. */
    fun verifiedFamilies(): List<Track> = tracks.values.filter { it.status == PathStatus.VERIFIED }
        .groupBy { it.candidate.family }.values.mapNotNull { list -> list.minByOrNull { it.bestLatency ?: Long.MAX_VALUE } }
        .sortedBy { it.bestLatency ?: Long.MAX_VALUE }

    fun record(id: String, outcomes: List<Boolean>, latencyMs: Long?, now: Long, failure: String? = null, spanMs: Long = 0) {
        val t = tracks[id] ?: return
        spent++
        val passed = outcomes.any { it }
        val firstPass = t.firstPassAt ?: if (passed) now - spanMs else null
        val lastPass = if (passed) now else t.lastPassAt
        val next = t.copy(
            outcomes = t.outcomes + outcomes,
            latencies = t.latencies + listOfNotNull(latencyMs),
            firstPassAt = firstPass,
            lastPassAt = lastPass,
            failure = if (passed) t.failure else failure ?: t.failure,
            notTested = null,
            recheckDueAt = null
        )
        // A first pass from a single request needs a recheck after the stable window.
        val needsRecheck = next.status == PathStatus.CANDIDATE && next.outcomes.size < 3
        tracks[id] = if (needsRecheck) next.copy(recheckDueAt = (lastPass ?: now) + FullAnalysis.STABLE_WINDOW_MS) else next
        if (passed && firstWorking == null) {
            firstWorking = id
            phase = Phase.DEEP_OPTIMIZATION
            event("first-pass", "First working path: ${t.candidate.name} (${t.candidate.family.title}). Moving on to verification and comparison.")
        }
        replan(t.candidate, passed)
    }

    /** The test could not run (engine missing, VPN on); it costs nothing and is not retried. */
    fun notTested(id: String, why: String) {
        val t = tracks[id] ?: return
        tracks[id] = t.copy(notTested = why, recheckDueAt = null)
    }

    private fun replan(c: Candidate, passed: Boolean) {
        if (passed && c.family.udp) event("udp-pass", "A UDP method passed: UDP families raised.")
        if (passed && c.family == PathFamily.DNS_TUNNEL && plan.mode == ExperimentPlanner.Mode.EMERGENCY_RECOVERY)
            event("dns-pass", "DNS tunnel passed: ordinary config copies stop; other recovery families are still compared.")
        if (!passed) {
            val d = c.domain
            val domainFails = tracks.values.filter { it.candidate.domain == d && it.outcomes.isNotEmpty() && it.passes == 0 }.size
            val domainPasses = tracks.values.count { it.candidate.domain == d && it.passes > 0 }
            if (domainFails >= 2 && domainPasses == 0) event("domain-${d.name}", "${d.title} failed $domainFails times: that failure domain is deprioritized.")
        }
    }

    private fun event(key: String, text: String) { if (raised.add(key)) events += text }

    private fun probability(c: Candidate): Pair<Double, List<String>> {
        val why = mutableListOf<String>()
        val i = plan.prefer.indexOf(c.family)
        var p = when {
            c.family in plan.lowPrior -> 0.15.also { why += "low prior on this network" }
            i >= 0 -> (0.8 - 0.03 * i).coerceAtLeast(0.5).also { why += "preferred by the plan" }
            else -> 0.5
        }
        if (c.provenHere) { p += 0.15; why += "worked here recently" }
        priors.memory[c.family]?.let { m -> p += 0.15 * m.coerceIn(-1.0, 1.0); if (m > 0) why += "fresh memory favours it" }
        if (c.family in priors.ai) { p += 0.1; why += "AI checkpoint ranked it" }
        if (c.family.udp) {
            if ("udp-pass" in raised || net?.quicStatus == "QUIC_AVAILABLE") { p += 0.1; why += "UDP answered" }
            if (ExperimentPlanner.udpDead(net)) p *= 0.2
        }
        if (c.ipv6) when {
            net?.internationalReachable == false && net?.ipv6TlsOk == true -> { p += 0.2; why += "IPv6 works while IPv4 fails" }
            net?.ipv6TlsOk == false || net?.ipv6Available == false -> p *= 0.3
        }
        val sameDomain = tracks.values.filter { it.candidate.domain == c.domain && it.candidate.id != c.id && it.outcomes.isNotEmpty() }
        val domainFails = sameDomain.count { it.passes == 0 }
        if (sameDomain.any { it.passes > 0 }) { p += 0.1; why += "its failure domain works" }
        else if (domainFails > 0) p *= Math.pow(0.6, domainFails.toDouble())
        val familyFails = tracks.values.count { it.candidate.family == c.family && it.candidate.id != c.id && it.outcomes.isNotEmpty() && it.passes == 0 }
        p *= Math.pow(0.7, familyFails.toDouble())
        return p.coerceIn(0.01, 0.95) to why
    }

    private fun gain(c: Candidate): Double {
        if (phase == Phase.FAST_RECOVERY) return 1.0
        val familyWorks = tracks.values.any { it.candidate.family == c.family && it.working }
        var g = if (familyWorks) 0.3 else 0.8
        if (ordinaryMutationsStopped && c.family in ExperimentPlanner.ORDINARY_FAMILIES) g *= 0.4
        return g
    }

    private fun cost(c: Candidate): Double {
        var k = when {
            c.family in PathFamily.TOR_FAMILIES -> 3.5
            c.family.engine -> 2.5
            else -> 1.0
        }
        if (c.family.engine && (device.metered == true || device.lowBattery)) k *= 1.5
        return k
    }

    fun score(c: Candidate): Double = probability(c).first * gain(c) / cost(c)

    fun decide(now: Long): Decision {
        if (phase == Phase.DONE) return Decision.Stop(stopReason ?: "done")
        stop(now)?.let { phase = Phase.DONE; stopReason = it; return Decision.Stop(it) }
        val due = tracks.values.filter { it.recheckDueAt != null && it.recheckDueAt <= now }
        // A due recheck of a working path comes first: verification is what ranks it.
        due.minByOrNull { it.recheckDueAt!! }?.let { t ->
            return Decision.Test(t.candidate, recheck = true, score = 1.0, why = "recheck after ${FullAnalysis.STABLE_WINDOW_MS / 1000}s to verify stability")
        }
        val fresh = tracks.values.filter { it.outcomes.isEmpty() && it.notTested == null }
        val bestFresh = fresh.maxByOrNull { score(it.candidate) }
        val pending = tracks.values.mapNotNull { it.recheckDueAt }.minOrNull()
        if (bestFresh != null && score(bestFresh.candidate) >= MIN_SCORE) {
            val (_, why) = probability(bestFresh.candidate)
            return Decision.Test(bestFresh.candidate, recheck = false, score = score(bestFresh.candidate),
                why = (if (phase == Phase.FAST_RECOVERY) "fast recovery" else if (gain(bestFresh.candidate) >= 0.8) "diversity: no ${bestFresh.candidate.family.title} path works yet" else "comparison") +
                    (if (why.isEmpty()) "" else "; " + why.joinToString(", ")))
        }
        if (pending != null) return Decision.Wait(pending, "waiting for the stability window before a recheck")
        val reason = if (fresh.isEmpty()) "every candidate was tested" else "nothing left to test could change the result"
        phase = Phase.DONE
        stopReason = reason
        return Decision.Stop(reason)
    }

    /** The stop rules; null to continue. */
    private fun stop(now: Long): String? {
        if (spent >= plan.budget) return "the test budget of ${plan.budget} is spent"
        if (goal == Goal.CONNECT && verifiedFamilies().isNotEmpty()) return "a verified path was found for the connection"
        val verified = verifiedFamilies()
        if (verified.size >= 3) {
            val a = verified[0].bestLatency
            val b = verified[1].bestLatency
            if (a != null && b != null && a <= b * CLEAR_LEAD) return "three independent paths are verified and the best is clearly ahead"
        }
        return null
    }

    /** One line for the screen: what the LAB believes now and what it is doing about it. */
    fun hypothesis(): String {
        val best = best()
        return when {
            phase == Phase.DONE -> stopReason?.let { "Stopped: $it." } ?: "Done."
            best == null && spent == 0 -> "No path tested yet. " + (plan.reasons.firstOrNull() ?: "")
            best == null -> "No path works yet after $spent test(s). " + (events.lastOrNull() ?: "Trying the next most promising family.")
            best.status == PathStatus.VERIFIED -> "${best.candidate.family.title} is verified. Looking for independent alternatives."
            else -> "${best.candidate.family.title} passed once. Rechecking it and trying other families."
        }
    }

    companion object {
        /** Below this expected value a test is not worth its cost. */
        const val MIN_SCORE = 0.03
        /** The best path must be at most this share of the second's latency to count as clearly superior. */
        const val CLEAR_LEAD = 0.7
    }
}
