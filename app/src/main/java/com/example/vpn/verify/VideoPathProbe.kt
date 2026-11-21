package com.example.vpn.verify

import java.io.IOException

/**
 * Can the current engine reach YouTube's front end and its video CDN? Runs once after a Psiphon or Aether session
 * is verified, through the engine's own local port (the engine resolves the names on its side), and records
 * what UDP can do on that path. A pass here with YouTube still failing in the app points at the phone side
 * (QUIC, DNS in the TUN); a failure points at the engine's exit.
 *
 * Targets: `www.youtube.com/generate_204` (HTTP 204) and `redirector.googlevideo.com/report_mapping`
 * (HTTP 200, the googlevideo.com front door that hands out the rrN---sn-xxx video hosts).
 *
 * Each leg is one capped GET ([TrafficCheck.cappedGet]): a `Range` request for the first [maxBytes] that stops
 * reading there. A 2xx answer passes when it is a 204 or carried at least one body byte. Build 167: reading the
 * whole googlevideo answer failed with "Response too long" (and once the 10 s deadline) while YouTube played.
 */
class VideoPathProbe(
    private val dialer: Dialer,
    private val stepTimeoutMs: Int = LEG_TIMEOUT_MS,
    private val maxBytes: Int = MAX_BYTES,
    /** Off only in tests, which run a plain HTTP server on the loopback interface. */
    private val tls: Boolean = true,
    private val targets: List<Pair<String, TrafficCheck.Target>> = TARGETS
) {
    data class Leg(val name: String, val host: String, val latencyMs: Long?, val error: String?) {
        val passed: Boolean get() = latencyMs != null
        fun describe(): String = if (latencyMs != null) "$name ok in $latencyMs ms" else "$name failed (${error ?: "unknown"})"
    }

    data class Result(val legs: List<Leg>, val udp: String, val dns: String) {
        val passed: Boolean get() = legs.isNotEmpty() && legs.all { it.passed }
        val verdict: String get() = when {
            passed -> "reachable"
            legs.any { it.passed } -> "partial"
            else -> "unreachable"
        }
        fun describe(): String = legs.joinToString(", ") { it.describe() } + "; udp: $udp; dns: $dns"

        /** Event attributes: host names are public references, never user data. */
        fun attributes(engine: String): Map<String, String> = buildMap {
            put("engine.name", engine)
            put("result", verdict)
            put("udp", udp)
            put("dns", dns)
            legs.forEach { leg ->
                put("${leg.name}.result", if (leg.passed) "PASS" else "FAIL")
                leg.latencyMs?.let { put("${leg.name}.ms", it.toString()) }
                leg.error?.let { put("${leg.name}.error", it.take(160)) }
            }
        }
    }

    fun run(udp: String, dns: String): Result = Result(targets.map { (name, target) -> leg(name, target) }, udp, dns)

    private fun leg(name: String, target: TrafficCheck.Target): Leg {
        if (Thread.currentThread().isInterrupted) return Leg(name, target.host, null, "interrupted")
        val started = System.currentTimeMillis()
        return try {
            val r = TrafficCheck(dialer, tls = tls, small = target, smallFallbacks = emptyList(),
                stepTimeoutMs = stepTimeoutMs).cappedGet(target, maxBytes)
            val error = verdict(r.status, r.bodyBytes)
            if (error != null) Leg(name, target.host, null, error)
            else Leg(name, target.host, (System.currentTimeMillis() - started).coerceAtLeast(1), null)
        } catch (e: IOException) {
            val cause = generateSequence<Throwable>(e) { it.cause }.last()
            Leg(name, target.host, null, "${cause.javaClass.simpleName}: ${cause.message ?: e.message ?: ""}".trimEnd(' ', ':'))
        }
    }

    companion object {
        /** Body bytes read per leg: enough to show data flows, small enough for a throttled link. */
        const val MAX_BYTES = 32 * 1024
        /** Per-leg deadline (connect, TLS, request and the capped read). */
        const val LEG_TIMEOUT_MS = 15_000

        /** Null when an answer with [status] and [bodyBytes] read counts as a pass, else why it does not. */
        fun verdict(status: Int, bodyBytes: Int): String? = when {
            status == 204 -> null
            status in 200..299 && bodyBytes > 0 -> null
            status in 200..299 -> "HTTP $status with no data"
            else -> "HTTP $status"
        }

        val YOUTUBE = TrafficCheck.Target("www.youtube.com", 443, "/generate_204")
        val GOOGLEVIDEO = TrafficCheck.Target("redirector.googlevideo.com", 443, "/report_mapping")
        val TARGETS = listOf("youtube" to YOUTUBE, "googlevideo" to GOOGLEVIDEO)
    }
}
