package com.example.vpn.lab

import com.example.data.model.VlessProfile
import com.example.vpn.connectivity.BpbRecoveryEngine
import com.example.vpn.connectivity.DerivedRecoveryCandidate
import com.example.vpn.connectivity.NetworkFirewalls
import com.example.vpn.connectivity.NetworkFirewalls.Firewall
import com.example.vpn.connectivity.RecoveryProfile
import com.example.vpn.connectivity.RecoveryProfiles

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
 *
 * Before reviving, a run looks at the network: recipes are ordered for its firewall
 * ([NetworkFirewalls]), Cloudflare ECH recipes whose DoH resolver does not return the ECH key here are
 * skipped ([com.example.vpn.connectivity.EchKeyCheck]), and when no clean Cloudflare address is
 * validated on this network yet, [findCleanIps] scans for some through the first dead config.
 */
class ConfigRevival(
    private val engine: BpbRecoveryEngine,
    /** Real requests through each profile, in order; at most [BATCH] per call. */
    private val probe: (List<VlessProfile>) -> List<DerivedRecoveryCandidate.TestResult>,
    /** Clean Cloudflare addresses validated on [network]; they become endpoint recipes. */
    private val endpoints: (network: String?) -> List<String> = { emptyList() },
    /** Each Cloudflare ECH DoH resolver → whether it returns the ECH key on this network (empty: not checked). */
    private val echCheck: () -> Map<String, Boolean> = { emptyMap() },
    /** Scans for clean Cloudflare addresses through a config on a network, records them, and returns them. */
    private val findCleanIps: (VlessProfile, String?) -> List<String> = { _, _ -> emptyList() }
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

    /** One run: a result per config, and what the run learned about the network. */
    data class Report(
        val results: List<Result>,
        val firewall: Firewall = Firewall.OTHER,
        /** Cloudflare ECH DoH resolver → returned the ECH key; empty when not checked. */
        val echResolvers: Map<String, Boolean> = emptyMap(),
        /** Clean Cloudflare addresses used as recipes on this network. */
        val cleanIps: List<String> = emptyList(),
        /** True when this run scanned for them. */
        val scannedForIps: Boolean = false
    )

    /** Cloudflare-fronted TLS configs revival can work on: TLS kept, certificate checked, a CDN transport. */
    fun eligible(profiles: List<VlessProfile>): List<VlessProfile> = all(profiles).take(MAX_CONFIGS)

    /** How many saved configs revival could work on, before the per-run cap. */
    fun eligibleCount(profiles: List<VlessProfile>): Int = all(profiles).size

    private fun all(profiles: List<VlessProfile>) = profiles.filter { engine.isRecoverable(it) }.distinctBy { it.effectiveFingerprint }

    /**
     * Revives the dead configs among [profiles] on [network]. Returns one result per eligible config, revived
     * ones first. [firewall] overrides the one detected from [network]. [cancelled] is checked between
     * configs; [onProgress] reports each step.
     */
    fun run(
        profiles: List<VlessProfile>,
        network: String?,
        cancelled: () -> Boolean = { false },
        firewall: Firewall? = null,
        onProgress: (Progress) -> Unit = {}
    ): Report {
        val wall = firewall ?: NetworkFirewalls.detect(network)
        val pool = eligible(profiles)
        if (pool.isEmpty()) return Report(emptyList(), wall)
        onProgress(Progress(0, pool.size, "Testing ${pool.size} configs as they are"))
        val plain = pool.chunked(BATCH).flatMap { batch -> probe(batch).let { r -> batch.indices.map { r.getOrNull(it) ?: notRun } } }
        val dead = pool.filterIndexed { i, _ -> plain[i] is DerivedRecoveryCandidate.TestResult.Failed }
        var ech = emptyMap<String, Boolean>()
        var ips = endpoints(network)
        var scanned = false
        if (dead.isNotEmpty() && !cancelled()) {
            onProgress(Progress(0, pool.size, "Checking which resolvers return the ECH key"))
            ech = runCatching { echCheck() }.getOrDefault(emptyMap())
            if (ips.isEmpty()) {
                onProgress(Progress(0, pool.size, "Looking for clean Cloudflare IPs"))
                runCatching { findCleanIps(dead.first(), network) }
                scanned = true
                ips = endpoints(network)
            }
        }
        val bias = biasFor(wall, ech)
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
                    results += revive(p, name, network, ips, bias)
                }
            }
            onProgress(Progress(i + 1, pool.size, null))
        }
        return Report(results.sortedBy { it.outcome.ordinal }, wall, ech, ips, scanned)
    }

    private fun revive(p: VlessProfile, name: String, network: String?, ips: List<String>, bias: (RecoveryProfile) -> Double?): Result {
        // Failure stage unknown: every strategy is allowed; the gate and the real request decide.
        val candidates = engine.generate(p, null, network, ips, bias)
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

        /**
         * The recipe order for [firewall], with the Cloudflare ECH recipes whose resolver did not return the
         * ECH key in [ech] skipped. When no resolver returned it, the ECH recipes are still tried (the
         * check itself may have been blocked while Xray's lookup is not).
         */
        fun biasFor(firewall: Firewall, ech: Map<String, Boolean>): (RecoveryProfile) -> Double? {
            val anyWorks = ech.values.any { it }
            return { rp ->
                val doh = rp.parameters["echConfigList"]?.takeIf { it in RecoveryProfiles.CLOUDFLARE_ECH_VALUES }?.substringAfter('+')
                if (doh != null && anyWorks && ech[doh] == false) null else NetworkFirewalls.bias(firewall, rp)
            }
        }

        /** A recipe in words, for the result row. */
        fun describe(profileKey: String, endpoint: String? = null): String {
            val id = profileKey.substringBefore('@')
            return when {
                id.startsWith("ech-cloudflare") -> "ECH via Cloudflare + Chrome fingerprint"
                id == "ech-doh" -> "ECH (the server's own key)"
                id == "bpb-fragment-original" -> "Fragment v2 + Go TLS"
                id == "bpb-fragment-v1" -> "Fragment v1 + Go TLS"
                id == NetworkFirewalls.IRANCELL_PROFILE -> "Irancell fragment + Firefox ciphers"
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
