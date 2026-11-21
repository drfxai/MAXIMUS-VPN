package com.example.vpn.verify

import java.io.IOException

/**
 * Can the current engine reach YouTube's front end and its video CDN? Runs once after a Psiphon session is
 * verified, through the engine's own local port (the engine resolves the names on its side), and records
 * what UDP can do on that path. A pass here with YouTube still failing in the app points at the phone side
 * (QUIC, DNS in the TUN); a failure points at the engine's exit.
 *
 * Targets: `www.youtube.com/generate_204` (HTTP 204) and `redirector.googlevideo.com/report_mapping`
 * (HTTP 200, the googlevideo.com front door that hands out the rrN---sn-xxx video hosts).
 */
class VideoPathProbe(
    private val dialer: Dialer,
    private val stepTimeoutMs: Int = TrafficCheck.STEP_TIMEOUT_MS,
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
        return try {
            val ms = TrafficCheck(dialer, tls = tls, small = target, smallFallbacks = emptyList(),
                stepTimeoutMs = stepTimeoutMs).smallRequest()
            Leg(name, target.host, ms, null)
        } catch (e: IOException) {
            val cause = generateSequence<Throwable>(e) { it.cause }.last()
            Leg(name, target.host, null, "${cause.javaClass.simpleName}: ${cause.message ?: e.message ?: ""}".trimEnd(' ', ':'))
        }
    }

    companion object {
        val YOUTUBE = TrafficCheck.Target("www.youtube.com", 443, "/generate_204")
        val GOOGLEVIDEO = TrafficCheck.Target("redirector.googlevideo.com", 443, "/report_mapping")
        val TARGETS = listOf("youtube" to YOUTUBE, "googlevideo" to GOOGLEVIDEO)
    }
}
