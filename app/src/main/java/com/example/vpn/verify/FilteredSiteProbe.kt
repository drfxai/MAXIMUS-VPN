package com.example.vpn.verify

/**
 * A request to sites filtered in Iran, through the route under test, before Smart Connect calls a path working.
 *
 * 2026-11-25 (V1.0.4 build 175, Irancell LTE): Smart Connect picked Serverless Direct. Its small request
 * (www.gstatic.com/generate_204, not filtered) passed, so it counted as verified, yet YouTube and Instagram failed their
 * TLS handshakes and Telegram timed out for 8 minutes. A path is now also asked for YouTube's generate_204 and Telegram
 * Web; any HTTP answer after a verified TLS handshake (certificate and name checked by [TrafficCheck]) passes, since
 * the filter answers these names with a reset, a timeout or a certificate that does not match.
 *
 * Build 176 device test: Serverless Direct was reported "filtered sites reachable · www.youtube.com" while
 * web.telegram.org timed out, because the first answer ended the probe. Every target is now asked (at the same time,
 * so the probe takes no longer): all answering is [Verdict.PASSED], some is [Verdict.PARTIAL], none is
 * [Verdict.BLOCKED].
 *
 * Pure apart from the dialer, so it is unit-tested against a local server.
 */
object FilteredSiteProbe {
    val TARGETS: List<TrafficCheck.Target> = listOf(
        TrafficCheck.Target("www.youtube.com", 443, "/generate_204"),
        TrafficCheck.Target("web.telegram.org", 443, "/")
    )
    const val STEP_MS = 8_000

    enum class Verdict { PASSED, PARTIAL, BLOCKED }

    data class Result(val verdict: Verdict, val detail: String) {
        /** Every filtered site answered. */
        val passed: Boolean get() = verdict == Verdict.PASSED
        /** At least one filtered site answered. */
        val anyReachable: Boolean get() = verdict != Verdict.BLOCKED
    }

    /** [status]: one request to a target, its HTTP status or an exception. Every target is asked, in parallel. */
    fun probe(targets: List<TrafficCheck.Target> = TARGETS, status: (TrafficCheck.Target) -> Int): Result {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(targets.size.coerceAtLeast(1)) { r ->
            Thread(r, "filtered-site-probe").apply { isDaemon = true }
        }
        val outcomes = try {
            targets.map { t -> pool.submit(java.util.concurrent.Callable { runCatching { status(t) } }) }.map { it.get() }
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause as? Exception) ?: e
        } finally {
            pool.shutdownNow()
        }
        return verdict(targets.zip(outcomes))
    }

    /** The verdict from each target's outcome, in target order. Pure. */
    fun verdict(outcomes: List<Pair<TrafficCheck.Target, kotlin.Result<Int>>>): Result {
        outcomes.forEach { (_, r) -> (r.exceptionOrNull() as? InterruptedException)?.let { throw it } }
        val ok = outcomes.filter { it.second.isSuccess }.map { (t, r) -> "${t.host} answered HTTP ${r.getOrThrow()}" }
        val failed = outcomes.filter { it.second.isFailure }.map { (t, r) ->
            "${t.host}: ${describe(r.exceptionOrNull() as? Exception ?: RuntimeException(r.exceptionOrNull()))}"
        }
        val detail = (ok + failed).joinToString("; ")
        return when {
            failed.isEmpty() -> Result(Verdict.PASSED, detail)
            ok.isEmpty() -> Result(Verdict.BLOCKED, detail)
            else -> Result(Verdict.PARTIAL, detail)
        }
    }

    /** Through [dialer]: the route's engine port or the VPN network. */
    fun probe(dialer: Dialer, stepMs: Int = STEP_MS): Result {
        val check = TrafficCheck(dialer, stepTimeoutMs = stepMs)
        return probe(TARGETS) { check.status(it) }
    }

    /**
     * Whether a just-verified path must pass this before it counts: an automatic pick (Smart Connect, not a config
     * the user pinned), or a path whose exit is the phone's own ISP address (Serverless Direct), which the filter sees.
     */
    fun required(smart: Boolean, exact: Boolean, ispExit: Boolean): Boolean = (smart && !exact) || ispExit

    private fun describe(e: Exception): String {
        val cause = (e as? TrafficCheck.TransferException)?.cause ?: e
        val kind = when {
            (e as? TrafficCheck.TransferException)?.timedOut == true -> "timed out"
            cause is javax.net.ssl.SSLException -> "TLS failed"
            cause is java.net.ConnectException -> "refused"
            else -> cause.javaClass.simpleName
        }
        return kind
    }
}
