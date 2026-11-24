package com.example.vpn.dnsfirst

/**
 * The DNS tunnel ladder: for every saved DNS tunnel (dnstt, Slipstream, CottenDNS/MasterDNS) it plans resolver
 * variants from what [TunnelResolverProbe] measured on this network, puts what worked here before first, drops
 * what kept failing here, and interleaves the tunnels so one dead server never uses up the whole budget.
 *
 * Variants ("rungs"), in order, per tunnel:
 *  1. remembered: the variant that carried a real request on this network ([DnsLadderMemory]);
 *  2. stripe-udp: the best [STRIPE] resolvers that suit the engine, all at once (each engine spreads its queries
 *     over every resolver it is given), with dnstt's per-resolver rate set from the measured burst;
 *  3. single-udp: the single best resolver (some resolvers only cope with one client's whole rate);
 *  4. tcp: CottenDNS over TCP/53 through the resolvers that answered TCP;
 *  5. doh: dnstt through a DoH endpoint that recursed for the tunnel domain (domestic ones included);
 *  6. as-configured: the saved profile untouched.
 * Pure: the caller turns a rung into a profile ([DnsTunnelSpecs]) and tests it with a real request.
 */
object DnsTunnelLadder {
    const val STRIPE = 3
    const val MAX_RUNGS = 10
    /** A variant that failed this often on a network is skipped there until [DnsLadderMemory.FAIL_TTL_MS] passes. */
    const val SKIP_AFTER_FAILURES = 2

    enum class Via { UDP, TCP, DOH, AS_CONFIGURED }

    /** What the ladder needs to know about one saved tunnel. */
    data class Spec(
        val profileId: String,
        val kind: EngineKind,
        val domain: String,
        /** Resolver IPs (or DoH URLs) the profile carries now. */
        val configured: List<String>,
        /** Hash of the saved settings: a remembered winner is only reused while the profile is unchanged. */
        val settingsHash: String
    )

    data class Rung(
        val profileId: String,
        val kind: EngineKind,
        val variant: String,
        val via: Via,
        val resolvers: List<String>,
        /** dnstt's per-resolver queries a second; null leaves the profile's rate. */
        val qpsPerResolver: Int? = null
    ) {
        /** Identifies the variant in [DnsLadderMemory]; independent of the order of the resolvers. */
        val key: String get() = "${kind.name.lowercase()}:${via.name.lowercase()}:${resolvers.sorted().joinToString(",")}"
    }

    fun plan(
        specs: List<Spec>,
        /** Probe results per tunnel domain, best first (as [TunnelResolverProbe.scan] returns them). */
        caps: Map<String, List<TunnelCaps>>,
        memory: DnsLadderMemory,
        network: String,
        maxRungs: Int = MAX_RUNGS
    ): List<Rung> {
        val perSpec = specs.map { spec -> spec to rungsFor(spec, caps[spec.domain].orEmpty(), memory, network) }
            .filter { it.second.isNotEmpty() }
            // Tunnels with a remembered winner first, then by their best measured resolver.
            .sortedWith(compareByDescending<Pair<Spec, List<Rung>>> { memory.winner(network, it.first.profileId, it.first.settingsHash) != null }
                .thenByDescending { (s, _) -> caps[s.domain].orEmpty().filter { it.suits(s.kind) }.maxOfOrNull { it.score } ?: 0 })
        val out = mutableListOf<Rung>()
        var round = 0
        while (out.size < maxRungs && perSpec.any { round < it.second.size }) {
            perSpec.forEach { (_, rungs) -> rungs.getOrNull(round)?.let { if (out.size < maxRungs) out += it } }
            round++
        }
        return out
    }

    fun rungsFor(spec: Spec, caps: List<TunnelCaps>, memory: DnsLadderMemory, network: String): List<Rung> {
        val rungs = mutableListOf<Rung>()
        memory.winner(network, spec.profileId, spec.settingsHash)?.let { w ->
            rungs += Rung(spec.profileId, spec.kind, "remembered", w.via, w.resolvers, w.qpsPerResolver)
        }
        val udp = caps.filter { it.transport == "udp" && it.suits(spec.kind) }.sortedByDescending { it.score }
        if (udp.isNotEmpty()) {
            val stripe = udp.take(STRIPE)
            rungs += Rung(spec.profileId, spec.kind, "stripe-udp", Via.UDP, stripe.map { it.resolver },
                if (spec.kind == EngineKind.DNSTT) stripe.minOf { it.safeQps } else null)
            if (stripe.size > 1) rungs += Rung(spec.profileId, spec.kind, "single-udp", Via.UDP, listOf(udp.first().resolver),
                if (spec.kind == EngineKind.DNSTT) udp.first().safeQps else null)
        }
        if (spec.kind == EngineKind.COTTEN) {
            val tcp = caps.filter { it.transport == "udp" && it.suits(spec.kind, overTcp = true) }.sortedByDescending { it.score }.take(STRIPE)
            if (tcp.isNotEmpty()) rungs += Rung(spec.profileId, spec.kind, "tcp", Via.TCP, tcp.map { it.resolver })
        }
        if (spec.kind == EngineKind.DNSTT) {
            caps.filter { it.transport == "doh" && it.suits(spec.kind) }.maxByOrNull { it.score }?.let {
                rungs += Rung(spec.profileId, spec.kind, "doh", Via.DOH, listOf(it.resolver), 1)
            }
        }
        rungs += Rung(spec.profileId, spec.kind, "as-configured", Via.AS_CONFIGURED, spec.configured)
        return rungs.distinctBy { it.key }.filter { it.variant == "remembered" || memory.failures(network, spec.profileId, it.key) < SKIP_AFTER_FAILURES }
    }
}

/**
 * Executes a ladder plan: each rung is tested with a real request ([test]); the first rung that passes for a
 * tunnel is remembered for the network and that tunnel's other rungs are skipped. Failures are remembered too,
 * so the next run on this network starts past them. Stops when [cancelled] says so or [budgetMs] runs out.
 */
object DnsLadderRunner {
    data class Outcome(val ok: Boolean, val latencyMs: Long? = null, val failure: String? = null, val notTested: Boolean = false)
    data class Step(val rung: DnsTunnelLadder.Rung, val outcome: Outcome)
    data class Report(val steps: List<Step>) {
        val wins: List<Step> get() = steps.filter { it.outcome.ok }
        val tested: Int get() = steps.count { !it.outcome.notTested }
    }

    fun run(
        plan: List<DnsTunnelLadder.Rung>,
        specs: List<DnsTunnelLadder.Spec>,
        memory: DnsLadderMemory,
        network: String,
        budgetMs: Long,
        clock: () -> Long = System::currentTimeMillis,
        cancelled: () -> Boolean = { false },
        test: (DnsTunnelLadder.Rung) -> Outcome
    ): Report {
        val started = clock()
        val done = mutableSetOf<String>()
        val steps = mutableListOf<Step>()
        val hashes = specs.associate { it.profileId to it.settingsHash }
        for (rung in plan) {
            if (cancelled() || clock() - started > budgetMs) break
            if (rung.profileId in done) continue
            val outcome = runCatching { test(rung) }.getOrElse { Outcome(false, failure = it.message ?: it.javaClass.simpleName) }
            steps += Step(rung, outcome)
            when {
                outcome.ok -> {
                    done += rung.profileId
                    memory.recordWin(network, rung.profileId, hashes[rung.profileId].orEmpty(), rung, outcome.latencyMs)
                }
                !outcome.notTested -> memory.recordFailure(network, rung.profileId, rung.key, forgetWinner = rung.variant == "remembered")
            }
        }
        return Report(steps)
    }
}
