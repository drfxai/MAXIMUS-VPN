package com.example.xray

/**
 * The international endpoints a real request may use to prove that a candidate carries traffic, each from a
 * different provider (failure domain), so one provider's outage never reads as "this path is dead".
 *
 * What a pass means: libXray's pingBatch sends an HTTPS HEAD through the candidate's proxy outbound and Go's TLS
 * stack verifies the target's certificate for its name. Any response after that verified handshake came from
 * the real target over the candidate, so the response code is not judged (a middlebox cannot answer for an
 * authenticated HTTPS name). Only HTTPS targets are allowed for that reason.
 *
 * Semantics:
 *  - any one target passing proves the candidate has international egress;
 *  - in a full verification, passing fewer than all targets is EGRESS VERIFIED but DEGRADED;
 *  - every target failing is a statement about THIS CANDIDATE only, never about the international internet.
 */
object ProbeTargets {

    enum class AddressFamily { ANY, IPV4, IPV6 }

    data class Target(
        val id: String,
        val url: String,
        /** Who runs it; two targets of one provider fail together and never count as independent. */
        val failureDomain: String,
        /** What a pass looks like, for the report: any response after a verified TLS handshake. */
        val expected: String = "HTTPS response after a verified TLS handshake",
        val addressFamily: AddressFamily = AddressFamily.ANY,
        /** pingBatch sends HEAD, so the body is not read; kept for the manifest's contract. */
        val maxResponseBytes: Int = 0,
        val timeoutSec: Int? = null,
        /** How long a pass through this target counts as proof that the target itself is up. */
        val freshnessMs: Long = TARGET_HEALTH_MS
    ) {
        init {
            require(url.startsWith("https://")) { "probe targets must be HTTPS: $id" }
        }
    }

    /** How long one candidate's pass through a target proves that target is reachable from proxies. */
    const val TARGET_HEALTH_MS = 10 * 60_000L

    /** Built in, so a manifest that cannot be fetched never stops a verification. Independent providers. */
    val BUILT_IN: List<Target> = listOf(
        Target("google-204", RealDelayProbe.PROBE_URL, "google"),
        Target("cloudflare-204", "https://cp.cloudflare.com/generate_204", "cloudflare"),
        Target("apple-captive", "https://captive.apple.com/hotspot-detect.html", "apple"),
        Target("mozilla-portal", "https://detectportal.firefox.com/success.txt", "mozilla")
    )

    /** The targets in use: a valid signed manifest's list, or [BUILT_IN]. Replaced by ProbeManifest. */
    @Volatile
    var active: List<Target> = BUILT_IN

    /** The targets with distinct failure domains, in order, at most [n]. */
    fun independent(targets: List<Target> = active, n: Int = targets.size): List<Target> =
        targets.distinctBy { it.failureDomain }.take(n)

    /** Verdict of one candidate across several targets. */
    enum class Verdict(val title: String) {
        /** Every target tried answered. */
        EGRESS_VERIFIED("Egress verified"),
        /** Some targets answered: the candidate carries traffic, but not to every provider. */
        EGRESS_VERIFIED_DEGRADED("Egress verified, degraded"),
        /** No target answered through this candidate. Says nothing about the network as a whole. */
        CANDIDATE_FAILED("This candidate failed multi-target verification"),
        /** Nothing was measured (core busy, engine missing). */
        NOT_RUN("Not measured")
    }

    fun verdict(passed: Int, tried: Int): Verdict = when {
        tried == 0 -> Verdict.NOT_RUN
        passed == 0 -> Verdict.CANDIDATE_FAILED
        passed < tried -> Verdict.EGRESS_VERIFIED_DEGRADED
        else -> Verdict.EGRESS_VERIFIED
    }

    /**
     * Which targets recently carried a pass through any candidate. A failure through a target that is known to
     * be up is the candidate's failure; a failure through a target with no recent pass may be the target's own
     * outage, so the next target is tried before the candidate is called failed.
     */
    class Health(private val clock: () -> Long = System::currentTimeMillis) {
        private val lastPass = java.util.concurrent.ConcurrentHashMap<String, Long>()

        fun recordPass(target: Target) { lastPass[target.id] = clock() }

        fun knownUp(target: Target): Boolean = lastPass[target.id]?.let { clock() - it in 0..target.freshnessMs } == true

        fun reset() = lastPass.clear()
    }

    val health = Health()
}
