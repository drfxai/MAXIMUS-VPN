package com.example.vpn.connectivity

import com.example.data.model.VlessProfile
import com.example.vpn.diagnostics.FailureStage
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** One field a recovery candidate changed, with the exact old and new value (credentials never stored). */
data class FieldChange(val field: String, val from: String, val to: String)

/**
 * A copy of a saved config with one recovery profile applied. The original is never changed; this
 * candidate carries everything needed to audit it, test it and undo it.
 */
data class DerivedRecoveryCandidate(
    val candidateId: String,
    val parentFingerprint: String,
    val parentProfileId: String,
    val recoveryProfileKey: String,
    val createdAt: Long,
    val expiresAt: Long,
    val networkContext: String?,
    val transformation: List<FieldChange>,
    /** What the engine runs. Same id and fingerprint as the parent, so evidence stays with the saved config. */
    val profile: VlessProfile,
    val security: RecoverySecurityGate.Result,
    val endpoint: String? = null,
    val test: TestResult? = null,
    /** Consecutive failures after a success that withdraw this candidate and fall back to the parent. */
    val rollbackAfter: Int
) {
    sealed class TestResult {
        data class Passed(val latencyMs: Long) : TestResult()
        data class Failed(val reason: String) : TestResult()
        data class NotRun(val reason: String) : TestResult()
    }

    fun isExpired(now: Long) = now >= expiresAt
}

/**
 * Recovers degraded BPB / Cloudflare-fronted TLS configs where it is safe:
 *
 * original → failure classification → strategies → derived candidates (one recovery profile each) →
 * security gate → controlled real-request test → scored pool, recorded in [RecoveryLedger].
 *
 * Failures recovery cannot fix safely are not touched: a refused certificate, a failed login, a proxy
 * protocol error (wrong credential or dead backend), or a security refusal. Telegram reports and other
 * outside tips never become profiles here; only reviewed, built-in [RecoveryProfiles] do.
 */
class BpbRecoveryEngine(
    private val profiles: List<RecoveryProfile> = RecoveryProfiles.BUILT_IN,
    private val ledger: RecoveryLedger,
    private val clock: () -> Long = System::currentTimeMillis,
    val maxCandidates: Int = 6,
    val candidateTtlMs: Long = 24L * 60 * 60 * 1000,
    /** A fragmentation profile measured worse than the plain config on a network (see [FragmentProfileEngine]) is skipped there. */
    private val fragmentReverted: (profileKey: String, network: String?) -> Boolean = { _, _ -> false }
) {
    /** TLS over a transport a CDN can carry: BPB workers and other Cloudflare-fronted configs. */
    fun isRecoverable(profile: VlessProfile): Boolean =
        profile.security.equals("tls", true) && !profile.allowInsecure &&
            profile.transport.lowercase().ifBlank { "tcp" } in setOf("ws", "httpupgrade", "xhttp", "splithttp", "grpc", "h2")

    /** What may help after a failure at [stage]; empty when recovery must not be attempted. */
    fun strategiesFor(stage: FailureStage?): Set<RecoveryProfile.Strategy> = when (stage) {
        FailureStage.CERTIFICATE_VALIDATION_FAILED, FailureStage.PROXY_AUTH_FAILED, FailureStage.PROXY_HANDSHAKE_FAILED,
        FailureStage.SECURITY_REJECTED, FailureStage.CANCELLED, FailureStage.NETWORK_CHANGED -> emptySet()
        FailureStage.DNS_RESOLUTION_FAILED, FailureStage.DNS_RESPONSE_INVALID, FailureStage.TCP_CONNECT_FAILED ->
            setOf(RecoveryProfile.Strategy.ALT_ENDPOINT_V4, RecoveryProfile.Strategy.ALT_ENDPOINT_V6)
        else -> RecoveryProfile.Strategy.entries.toSet()
    }

    /**
     * Candidates for [parent] after it failed at [failure] on [network]. [endpoints] are locally
     * validated alternative addresses (clean-IP hints are not enough). Withdrawn, expired and
     * locally worse profiles are skipped; candidates the gate refuses are returned with the refusal
     * and never tested. [bias] moves a profile up (positive) or down in the order, or skips it (null); see [NetworkFirewalls].
     */
    fun generate(
        parent: VlessProfile,
        failure: FailureStage?,
        network: String?,
        endpoints: List<String> = emptyList(),
        bias: (RecoveryProfile) -> Double? = { 0.0 }
    ): List<DerivedRecoveryCandidate> {
        if (!isRecoverable(parent)) return emptyList()
        val wanted = strategiesFor(failure)
        if (wanted.isEmpty()) return emptyList()
        val now = clock()
        val fp = parent.effectiveFingerprint
        val usable = profiles
            .filter { !it.isExpired(now) && it.networkConditions.any { s -> s in wanted } && it.appliesTo(parent) }
            .filter { !ledger.profileIsWorse(it.key, network) }
            .filter { RecoveryProfile.Strategy.FRAGMENT !in it.networkConditions || !fragmentReverted(it.key, network) }
            .mapNotNull { rp -> bias(rp)?.let { rp to it } }
            .sortedWith(compareBy({ (rp, b) -> -(ledger.profileSuccessRate(rp.key, network, rp.confidence) + b) }, { it.first.key }))
            .map { it.first }
        val out = mutableListOf<DerivedRecoveryCandidate>()
        for (rp in usable) {
            val targets: List<String?> = if (rp.needsEndpoint()) endpoints.filter { familyOf(it) == rp.addressFamily }.distinct().take(2) else listOf(null)
            for (endpoint in targets) {
                if (out.size >= maxCandidates) return out
                if (ledger.isWithdrawn(fp, rp.key, endpoint, network)) continue
                val derived = rp.derive(parent, endpoint) ?: continue
                if (derived == parent) continue
                out += DerivedRecoveryCandidate(
                    candidateId = idOf(fp, rp.key, endpoint),
                    parentFingerprint = fp, parentProfileId = parent.id, recoveryProfileKey = rp.key,
                    createdAt = now, expiresAt = minOf(now + candidateTtlMs, rp.expiresAt), networkContext = network,
                    transformation = diff(parent, derived), profile = derived,
                    security = RecoverySecurityGate.check(parent, derived), endpoint = endpoint, rollbackAfter = rp.rollbackPolicy
                )
            }
        }
        return out
    }

    /**
     * Tests the candidates that passed the gate with [probe] (real requests, at most 5 per call), records
     * every result, and returns them all with their results, passing ones first by latency.
     */
    fun test(
        candidates: List<DerivedRecoveryCandidate>,
        probe: (List<VlessProfile>) -> List<DerivedRecoveryCandidate.TestResult>
    ): List<DerivedRecoveryCandidate> {
        val safe = candidates.filter { it.security.passed && !it.isExpired(clock()) }
        val tested = safe.chunked(5).flatMap { batch ->
            val results = probe(batch.map { it.profile })
            batch.mapIndexed { i, c -> c.copy(test = results.getOrNull(i) ?: DerivedRecoveryCandidate.TestResult.NotRun("no result")) }
        }
        tested.forEach { c ->
            when (val t = c.test) {
                is DerivedRecoveryCandidate.TestResult.Passed -> ledger.record(c, success = true, now = clock())
                is DerivedRecoveryCandidate.TestResult.Failed -> ledger.record(c, success = false, now = clock())
                else -> Unit
            }
        }
        val refused = candidates.filter { !it.security.passed }
        return tested.sortedBy { (it.test as? DerivedRecoveryCandidate.TestResult.Passed)?.latencyMs ?: Long.MAX_VALUE } + refused
    }

    /**
     * The candidate that recovered [parent] on [network] and has not been withdrawn or expired, rebuilt
     * fresh on [base] (the parent with its host already resolved, when the caller has that form).
     */
    fun active(parent: VlessProfile, network: String?, base: VlessProfile = parent): DerivedRecoveryCandidate? {
        val now = clock()
        val entry = ledger.activeFor(parent.effectiveFingerprint, network, now) ?: return null
        val rp = profiles.firstOrNull { it.key == entry.profileKey } ?: return null
        if (rp.isExpired(now)) return null
        val derived = rp.derive(base, entry.endpoint) ?: return null
        val gate = RecoverySecurityGate.check(base, derived)
        if (!gate.passed) return null
        return DerivedRecoveryCandidate(
            idOf(parent.effectiveFingerprint, rp.key, entry.endpoint), parent.effectiveFingerprint, parent.id, rp.key,
            entry.createdAt, entry.expiresAt, network, diff(base, derived), derived, gate, entry.endpoint, rollbackAfter = rp.rollbackPolicy
        )
    }

    /** A session through [candidate] carried traffic, or stopped carrying it; rollback follows the profile's policy. */
    fun recordOutcome(candidate: DerivedRecoveryCandidate, success: Boolean) = ledger.record(candidate, success, clock())

    companion object {
        fun familyOf(ip: String): String = if (ip.contains(':')) "ipv6" else "ipv4"

        fun idOf(fp: String, key: String, endpoint: String?): String =
            MessageDigest.getInstance("SHA-256").digest("$fp|$key|${endpoint.orEmpty()}".toByteArray())
                .take(8).joinToString("") { "%02x".format(it) }

        private val AUDITED = listOf<Pair<String, (VlessProfile) -> String>>(
            "finalMask" to { it.finalMask }, "fingerprint" to { it.fingerprint }, "alpn" to { it.alpn },
            "cipherSuites" to { it.cipherSuites }, "echConfigList" to { it.echConfigList }, "address" to { it.address }
        )

        fun diff(parent: VlessProfile, derived: VlessProfile): List<FieldChange> =
            AUDITED.mapNotNull { (name, get) -> if (get(parent) != get(derived)) FieldChange(name, get(parent), get(derived)) else null }
    }
}

/**
 * What recovery learned on this phone, by original config, recovery profile, endpoint and network.
 * Bounded, expiring, persisted as JSON with no address of the original and no credential.
 *
 * Rollback: a candidate that worked and then failed [DerivedRecoveryCandidate.rollbackAfter] times in a
 * row is withdrawn, and the original config is used again. A profile that failed [WORSE_AFTER] times on a
 * network without ever working there is not offered on that network again until its entries expire.
 */
class RecoveryLedger(private val load: () -> String?, private val save: (String) -> Unit) {
    data class Entry(
        val parentFingerprint: String,
        val profileKey: String,
        val endpoint: String?,
        val network: String?,
        val createdAt: Long,
        val expiresAt: Long,
        val successes: Int = 0,
        val failures: Int = 0,
        val consecutiveFailures: Int = 0,
        val lastSuccessAt: Long? = null,
        val withdrawn: Boolean = false,
        val withdrawnReason: String? = null,
        /** Copied from a sibling config of the same server that passed; untested for this one until used. */
        val inherited: Boolean = false
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("f", parentFingerprint); put("k", profileKey); endpoint?.let { put("e", it) }; network?.let { put("n", it) }
            put("c", createdAt); put("x", expiresAt); put("s", successes); put("fl", failures); put("cf", consecutiveFailures)
            lastSuccessAt?.let { put("ls", it) }; put("w", withdrawn); withdrawnReason?.let { put("wr", it) }
            if (inherited) put("i", true)
        }

        companion object {
            fun fromJson(o: JSONObject) = Entry(
                o.getString("f"), o.getString("k"), o.optString("e").ifBlank { null }, o.optString("n").ifBlank { null },
                o.optLong("c"), o.optLong("x"), o.optInt("s"), o.optInt("fl"), o.optInt("cf"),
                if (o.has("ls")) o.optLong("ls") else null, o.optBoolean("w"), o.optString("wr").ifBlank { null },
                o.optBoolean("i")
            )
        }
    }

    private var entries: MutableList<Entry>? = null

    @Synchronized
    private fun list(): MutableList<Entry> = entries ?: runCatching {
        val a = JSONArray(load() ?: "[]")
        (0 until a.length()).map { Entry.fromJson(a.getJSONObject(it)) }.toMutableList()
    }.getOrDefault(mutableListOf()).also { entries = it }

    @Synchronized
    fun entries(): List<Entry> = list().toList()

    private fun same(e: Entry, fp: String, key: String, endpoint: String?, network: String?) =
        e.parentFingerprint == fp && e.profileKey == key && e.endpoint == endpoint && e.network == network

    @Synchronized
    fun record(c: DerivedRecoveryCandidate, success: Boolean, now: Long): Entry {
        val all = list()
        all.removeAll { it.expiresAt <= now }
        val old = all.firstOrNull { same(it, c.parentFingerprint, c.recoveryProfileKey, c.endpoint, c.networkContext) }
            ?: Entry(c.parentFingerprint, c.recoveryProfileKey, c.endpoint, c.networkContext, now, c.expiresAt)
        all.remove(old)
        var next = if (success) old.copy(successes = old.successes + 1, consecutiveFailures = 0, lastSuccessAt = now)
        else old.copy(failures = old.failures + 1, consecutiveFailures = old.consecutiveFailures + 1)
        if (!success && next.successes > 0 && next.consecutiveFailures >= c.rollbackAfter) {
            next = next.copy(withdrawn = true, withdrawnReason = "failed ${next.consecutiveFailures} times after working; back to the original")
        }
        if (!success && next.successes == 0 && next.failures >= WORSE_AFTER) {
            next = next.copy(withdrawn = true, withdrawnReason = "never worked on this network")
        }
        all.add(next)
        while (all.size > MAX_ENTRIES) all.remove(all.minByOrNull { it.lastSuccessAt ?: it.createdAt }!!)
        persist()
        return next
    }

    /**
     * Offers [key], which passed for a sibling config of the same server on [network], to config [fp] as
     * its first path. It is tried with one real request before use and withdrawn after it fails
     * [WORSE_AFTER] times without working.
     */
    @Synchronized
    fun adopt(fp: String, key: String, network: String?, now: Long, expiresAt: Long) {
        val all = list()
        if (all.any { same(it, fp, key, null, network) }) return
        all.add(Entry(fp, key, null, network, now, expiresAt, inherited = true))
        persist()
    }

    @Synchronized
    fun isWithdrawn(fp: String, key: String, endpoint: String?, network: String?): Boolean =
        list().any { same(it, fp, key, endpoint, network) && it.withdrawn }

    /** The best working, not withdrawn, unexpired entry for [fp] on [network]. */
    @Synchronized
    fun activeFor(fp: String, network: String?, now: Long): Entry? =
        list().filter { it.parentFingerprint == fp && it.network == network && !it.withdrawn && it.expiresAt > now && (it.successes > 0 || it.inherited) }
            .maxWithOrNull(compareBy({ it.lastSuccessAt ?: 0 }, { it.successes }))

    /** Success rate of a profile on a network across configs, or [prior] when it has no record there. */
    @Synchronized
    fun profileSuccessRate(key: String, network: String?, prior: Double): Double {
        val es = list().filter { it.profileKey == key && it.network == network }
        val tries = es.sumOf { it.successes + it.failures }
        return if (tries == 0) prior else es.sumOf { it.successes }.toDouble() / tries
    }

    /** True when the profile failed [WORSE_AFTER] times on [network] and never worked there. */
    @Synchronized
    fun profileIsWorse(key: String, network: String?): Boolean {
        val es = list().filter { it.profileKey == key && it.network == network }
        return es.sumOf { it.successes } == 0 && es.sumOf { it.failures } >= WORSE_AFTER
    }

    /** Forgets everything recorded for [fp] (the user deleted the config, or asked to undo recovery). */
    @Synchronized
    fun forget(fp: String) {
        if (list().removeAll { it.parentFingerprint == fp }) persist()
    }

    private fun persist() {
        val a = JSONArray()
        list().forEach { a.put(it.toJson()) }
        runCatching { save(a.toString()) }
    }

    companion object {
        const val MAX_ENTRIES = 200
        const val WORSE_AFTER = 3
    }
}
