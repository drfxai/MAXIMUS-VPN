package com.example.vpn.verify

/**
 * A request to sites filtered in Iran, through the route under test, before Smart Connect calls a path working.
 *
 * 2026-11-25 (V1.0.4 build 175, Irancell LTE): Smart Connect picked Serverless Direct. Its small request
 * (www.gstatic.com/generate_204, not filtered) passed, so it counted as verified, yet YouTube and Instagram failed their
 * TLS handshakes and Telegram timed out for 8 minutes. A path is now also asked for YouTube's generate_204 and Telegram
 * Web; any HTTP answer after a verified TLS handshake (certificate and name checked by [TrafficCheck]) passes, since
 * the filter answers these names with a reset, a timeout or a certificate that does not match. One answer is enough.
 *
 * Pure apart from the dialer, so it is unit-tested against a local server.
 */
object FilteredSiteProbe {
    val TARGETS: List<TrafficCheck.Target> = listOf(
        TrafficCheck.Target("www.youtube.com", 443, "/generate_204"),
        TrafficCheck.Target("web.telegram.org", 443, "/")
    )
    const val STEP_MS = 8_000

    enum class Verdict { PASSED, BLOCKED }

    data class Result(val verdict: Verdict, val detail: String) {
        val passed: Boolean get() = verdict == Verdict.PASSED
    }

    /** [status]: one request to a target, its HTTP status or an exception. */
    fun probe(targets: List<TrafficCheck.Target> = TARGETS, status: (TrafficCheck.Target) -> Int): Result {
        val failures = mutableListOf<String>()
        for (t in targets) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            try {
                val code = status(t)
                return Result(Verdict.PASSED, "${t.host} answered HTTP $code")
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                failures += "${t.host}: ${describe(e)}"
            }
        }
        return Result(Verdict.BLOCKED, failures.joinToString("; "))
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
