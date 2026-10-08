package com.example.vpn.connectivity

import com.example.vpn.hub.FreeConfigLifecycle
import com.example.vpn.hub.GlobalStatus
import kotlin.math.pow

/**
 * The phone's deterministic ranking score for a candidate, 0..100, with the reason for every part.
 *
 * - Security first: a candidate the security gate refused, a quarantined, dead or globally failed one
 *   is not eligible, whatever else it measured. Nothing can add points to it.
 * - Latency counts little (8 of 100 points, flat up to 300 ms): a stable 250 ms server outranks an
 *   unstable 70 ms one.
 * - Evidence ages: local results lose half their weight every [FRESH_HALF_LIFE_MS] and drift towards
 *   neutral, so an old success cannot keep a server on top.
 * - Diversity (address family, transport, failure domain) is applied by the selector over these scores
 *   ([DiversitySelector]); it is not a property of one server.
 * - [Inputs.boundedAdjustment] (Iran intelligence, Stage 11) is clamped to ±[MAX_ADJUSTMENT] and only
 *   applies to eligible candidates.
 */
object ConnectionScore {
    const val FRESH_HALF_LIFE_MS = 6L * 60 * 60 * 1000
    const val MAX_ADJUSTMENT = 5.0

    val WEIGHTS = linkedMapOf(
        "stages" to 20.0,        // protocol, tunnel, internet steps of the latest probe
        "recent" to 20.0,        // recent success rate on this phone
        "history" to 10.0,       // long-run reliability
        "stability" to 15.0,     // jitter, loss, reconnects, how long sessions lasted
        "latency" to 8.0,
        "dnsTunnel" to 5.0,
        "networkMatch" to 7.0,   // evidence comes from a network like the current one
        "local" to 10.0,         // verified sessions here
        "security" to 5.0        // strength of the security mode
    )

    data class Inputs(
        val securityProblem: String? = null,
        val lifecycle: FreeConfigLifecycle? = null,
        val globalStatus: GlobalStatus = GlobalStatus.UNKNOWN,
        val protocolOk: Boolean? = null,
        val tunnelOk: Boolean? = null,
        val internetOk: Boolean? = null,
        val dnsTunnelOk: Boolean? = null,
        val recentSuccesses: Int = 0,
        val recentAttempts: Int = 0,
        val historicalSuccesses: Int = 0,
        val historicalAttempts: Int = 0,
        val latencyMs: Long? = null,
        val jitterMs: Long? = null,
        val packetLossPercent: Double? = null,
        val reconnectsPerHour: Double? = null,
        val stableSessionMs: Long = 0,
        val networkMatch: Boolean? = null,
        val verifiedSessions: Int = 0,
        /** "reality", "tls", "quic", "wireguard", "aead" or "none". */
        val securityMode: String = "none",
        val lastEvidenceAt: Long? = null,
        val boundedAdjustment: Double = 0.0
    )

    data class Result(val score: Double, val eligible: Boolean, val reasons: Map<String, String>, val rejection: String? = null)

    fun eligibility(i: Inputs): String? = when {
        i.securityProblem != null -> "security: ${i.securityProblem}"
        i.lifecycle == FreeConfigLifecycle.SECURITY_REJECTED -> "security: rejected"
        i.lifecycle == FreeConfigLifecycle.QUARANTINED -> "quarantined"
        i.lifecycle == FreeConfigLifecycle.DEAD -> "dead on this network"
        i.globalStatus == GlobalStatus.GLOBAL_FAILED || i.lifecycle == FreeConfigLifecycle.GLOBAL_FAILED -> "failed global verification"
        else -> null
    }

    fun of(i: Inputs, now: Long): Result {
        eligibility(i)?.let { return Result(0.0, false, mapOf("eligible" to "no: $it"), it) }
        val fresh = i.lastEvidenceAt?.let { freshness(now - it) } ?: 0.0
        fun aged(v: Double) = 0.5 + (v - 0.5) * fresh

        val stageChecks = listOfNotNull(i.protocolOk, i.tunnelOk, i.internetOk)
        val stages = if (stageChecks.isEmpty()) 0.5 else aged(stageChecks.count { it }.toDouble() / stageChecks.size)
        val recent = if (i.recentAttempts == 0) 0.5 else aged(i.recentSuccesses.toDouble() / i.recentAttempts)
        val history = if (i.historicalAttempts == 0) 0.5 else i.historicalSuccesses.toDouble() / i.historicalAttempts
        val stability = stability(i)
        val latency = when (val ms = i.latencyMs) {
            null -> 0.0
            in 0..300 -> 1.0
            else -> (1.0 - (ms - 300) / 1700.0).coerceIn(0.0, 1.0)
        }
        val dns = when (i.dnsTunnelOk) { true -> aged(1.0); false -> aged(0.0); null -> 0.5 }
        val match = when (i.networkMatch) { true -> 1.0; false -> 0.3; null -> 0.5 }
        val local = (i.verifiedSessions / 3.0).coerceAtMost(1.0).let { if (i.verifiedSessions == 0) 0.0 else aged(0.5 + it / 2) }
        val security = when (i.securityMode.lowercase()) {
            "reality" -> 1.0; "tls", "quic", "wireguard" -> 0.85; "aead" -> 0.6; else -> 0.0
        }
        val parts = linkedMapOf(
            "stages" to (stages to "${stageChecks.count { it }}/${stageChecks.size} steps passed"),
            "recent" to (recent to if (i.recentAttempts == 0) "no recent tests" else "${i.recentSuccesses}/${i.recentAttempts} recent"),
            "history" to (history to if (i.historicalAttempts == 0) "no history" else "${i.historicalSuccesses}/${i.historicalAttempts} overall"),
            "stability" to (stability to "jitter ${i.jitterMs ?: "?"} ms, loss ${i.packetLossPercent ?: "?"} %, reconnects/h ${i.reconnectsPerHour ?: "?"}"),
            "latency" to (latency to (i.latencyMs?.let { "$it ms" } ?: "not measured")),
            "dnsTunnel" to (dns to (i.dnsTunnelOk?.let { if (it) "names resolve in the tunnel" else "names failed in the tunnel" } ?: "not measured")),
            "networkMatch" to (match to when (i.networkMatch) { true -> "measured on a network like this one"; false -> "measured on another kind of network"; null -> "unknown" }),
            "local" to (local to "${i.verifiedSessions} verified sessions here"),
            "security" to (security to i.securityMode)
        )
        val base = parts.entries.sumOf { (k, v) -> WEIGHTS.getValue(k) * v.first }
        val adjustment = i.boundedAdjustment.coerceIn(-MAX_ADJUSTMENT, MAX_ADJUSTMENT)
        val score = (base + adjustment).coerceIn(0.0, 100.0)
        val reasons = parts.mapValues { (k, v) -> "%.1f/%.0f: %s".format(java.util.Locale.US, WEIGHTS.getValue(k) * v.first, WEIGHTS.getValue(k), v.second) } +
            mapOf("freshness" to "%.2f".format(java.util.Locale.US, fresh), "adjustment" to "%.1f".format(java.util.Locale.US, adjustment))
        return Result(Math.round(score * 100) / 100.0, true, reasons)
    }

    /** 1.0 for evidence from now, 0.5 after one half-life, towards 0 after that. */
    fun freshness(ageMs: Long): Double = if (ageMs <= 0) 1.0 else 0.5.pow(ageMs.toDouble() / FRESH_HALF_LIFE_MS)

    private fun stability(i: Inputs): Double {
        val parts = mutableListOf<Double>()
        i.jitterMs?.let { j -> parts += (1.0 - j / maxOf(i.latencyMs?.toDouble() ?: 100.0, 50.0)).coerceIn(0.0, 1.0) }
        i.packetLossPercent?.let { parts += (1.0 - it / 20.0).coerceIn(0.0, 1.0) }
        i.reconnectsPerHour?.let { parts += (1.0 - it / 6.0).coerceIn(0.0, 1.0) }
        if (i.stableSessionMs > 0) parts += (i.stableSessionMs / (30.0 * 60_000)).coerceAtMost(1.0)
        return if (parts.isEmpty()) 0.5 else parts.average()
    }

    /** Eligible first, then score, then fingerprint: the same inputs always give the same order. */
    fun <T> rank(items: List<T>, result: (T) -> Result, tieBreak: (T) -> String): List<T> =
        items.map { it to result(it) }
            .sortedWith(compareBy<Pair<T, Result>>({ !it.second.eligible }, { -it.second.score }, { tieBreak(it.first) }))
            .map { it.first }
}
