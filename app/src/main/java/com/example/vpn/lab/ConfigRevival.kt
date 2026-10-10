package com.example.vpn.lab

import com.example.data.model.VlessProfile
import com.example.vpn.connectivity.BpbRecoveryEngine
import com.example.vpn.connectivity.DerivedRecoveryCandidate

/**
 * LAB "Revive configs": brings dead Cloudflare-fronted TLS configs (BPB and other Workers, Pages,
 * CDN-proxied servers, imported free configs) back with client-side settings, the way Proxy Builder
 * does by hand, but measured on this phone:
 *
 * 1. Every eligible config gets one real request as it is. Configs that answer are left alone.
 * 2. Each dead config gets derived copies, one recipe each: Cloudflare's shared ECH key with a Chrome
 *    fingerprint (over three DoH resolvers), the FIX BPB fragment recipes (v2, v1 and plain
 *    ClientHello splits, with the Go TLS stack and its cipher list), other fingerprints, HTTP/1.1, and
 *    clean Cloudflare addresses already validated on this network.
 * 3. Every copy passes [com.example.vpn.connectivity.RecoverySecurityGate] and gets a real request;
 *    the fastest that answers is recorded for this config on this network.
 *
 * Saved configs are never changed. The VPN tries the recorded copy first the next time that config
 * connects, and withdraws it (back to the saved config) after it stops working.
 */
class ConfigRevival(
    private val engine: BpbRecoveryEngine,
    /** Real requests through each profile, in order; at most [BATCH] per call. */
    private val probe: (List<VlessProfile>) -> List<DerivedRecoveryCandidate.TestResult>,
    /** Clean Cloudflare addresses validated on [network]; they become endpoint recipes. */
    private val endpoints: (network: String?) -> List<String> = { emptyList() }
) {
    enum class Outcome(val title: String) {
        REVIVED("Revived"),
        ALREADY_WORKING("Working"),
        NOT_REVIVED("Not revived"),
        NOT_TESTED("Not tested")
    }

    data class Result(
        val profileId: String,
        val name: String,
        val outcome: Outcome,
        /** The recipe that worked, in words, or why nothing did. */
        val detail: String,
        val latencyMs: Long? = null,
        /** Recipes tried on this config (0 when it already worked). */
        val tried: Int = 0,
        val recipe: String? = null
    )

    data class Progress(val done: Int, val total: Int, val current: String?)

    /** Cloudflare-fronted TLS configs revival can work on: TLS kept, certificate checked, a CDN transport. */
    fun eligible(profiles: List<VlessProfile>): List<VlessProfile> = all(profiles).take(MAX_CONFIGS)

    /** How many saved configs revival could work on, before the per-run cap. */
    fun eligibleCount(profiles: List<VlessProfile>): Int = all(profiles).size

    private fun all(profiles: List<VlessProfile>) = profiles.filter { engine.isRecoverable(it) }.distinctBy { it.effectiveFingerprint }

    /**
     * Revives the dead configs among [profiles] on [network]. Returns one result per eligible config, revived
     * ones first. [cancelled] is checked between configs; [onProgress] reports each step.
     */
    fun run(
        profiles: List<VlessProfile>,
        network: String?,
        cancelled: () -> Boolean = { false },
        onProgress: (Progress) -> Unit = {}
    ): List<Result> {
        val pool = eligible(profiles)
        if (pool.isEmpty()) return emptyList()
        onProgress(Progress(0, pool.size, "Testing ${pool.size} configs as they are"))
        val plain = pool.chunked(BATCH).flatMap { batch -> probe(batch).let { r -> batch.indices.map { r.getOrNull(it) ?: notRun } } }
        val results = mutableListOf<Result>()
        pool.forEachIndexed { i, p ->
            val name = p.name.ifBlank { "Config" }
            val before = plain[i]
            when {
                cancelled() -> results += Result(p.id, name, Outcome.NOT_TESTED, "stopped before this config")
                before is DerivedRecoveryCandidate.TestResult.Passed ->
                    results += Result(p.id, name, Outcome.ALREADY_WORKING, "answers as it is; nothing was changed", before.latencyMs)
                before is DerivedRecoveryCandidate.TestResult.NotRun ->
                    results += Result(p.id, name, Outcome.NOT_TESTED, before.reason)
                else -> {
                    onProgress(Progress(i, pool.size, name))
                    results += revive(p, name, network)
                }
            }
            onProgress(Progress(i + 1, pool.size, null))
        }
        return results.sortedBy { it.outcome.ordinal }
    }

    private fun revive(p: VlessProfile, name: String, network: String?): Result {
        // Failure stage unknown: every strategy is allowed; the gate and the real request decide.
        val candidates = engine.generate(p, null, network, endpoints(network))
        if (candidates.isEmpty()) return Result(p.id, name, Outcome.NOT_REVIVED, "no recipe applies to this config (or all were withdrawn on this network)")
        val tested = engine.test(candidates, probe)
        val ran = tested.count { it.test is DerivedRecoveryCandidate.TestResult.Passed || it.test is DerivedRecoveryCandidate.TestResult.Failed }
        val winner = tested.firstOrNull { it.test is DerivedRecoveryCandidate.TestResult.Passed }
            ?: return Result(p.id, name, if (ran == 0) Outcome.NOT_TESTED else Outcome.NOT_REVIVED,
                if (ran == 0) (tested.firstNotNullOfOrNull { (it.test as? DerivedRecoveryCandidate.TestResult.NotRun)?.reason } ?: "no recipe could be tested")
                else "no recipe got a request through ($ran tried); the server, its UUID or its Worker may be down", tried = ran)
        val ms = (winner.test as DerivedRecoveryCandidate.TestResult.Passed).latencyMs
        return Result(p.id, name, Outcome.REVIVED, "${describe(winner.recoveryProfileKey, winner.endpoint)} · used next time it connects",
            ms, ran, winner.recoveryProfileKey)
    }

    companion object {
        /** Real-request batch size ([com.example.xray.RealDelayProbe.MAX_BATCH]). */
        const val BATCH = 5
        /** Seconds each real request may take (FIX BPB uses the same). */
        const val TIMEOUT_SEC = 8
        /** Configs per run, so one run stays a few minutes at most. */
        const val MAX_CONFIGS = 12
        /** Recipes tried per dead config (two real-request batches): the engine's maxCandidates for revival. */
        const val RECIPES_PER_CONFIG = 10

        private val notRun = DerivedRecoveryCandidate.TestResult.NotRun("no result")

        /** A recipe in words, for the result row. */
        fun describe(profileKey: String, endpoint: String? = null): String {
            val id = profileKey.substringBefore('@')
            return when {
                id.startsWith("ech-cloudflare") -> "ECH via Cloudflare + Chrome fingerprint"
                id == "ech-doh" -> "ECH (the server's own key)"
                id == "bpb-fragment-original" -> "Fragment v2 + Go TLS"
                id == "bpb-fragment-v1" -> "Fragment v1 + Go TLS"
                id == "bpb-fragment" -> "Fragment (no empty record) + Go TLS"
                id.startsWith("fragment-tlshello") -> "ClientHello fragments"
                id.startsWith("fingerprint-") -> "${id.removePrefix("fingerprint-").replaceFirstChar { it.uppercase() }} fingerprint"
                id == "alpn-http11" -> "HTTP/1.1 only"
                id.startsWith("endpoint-") -> "Clean Cloudflare IP${endpoint?.let { " $it" }.orEmpty()}"
                else -> id
            }
        }
    }
}
