package com.example.vpn.connections

import com.example.data.model.VlessProfile
import com.example.vpn.engine.ProfileExtras
import com.example.vpn.sidecar.AetherApiBridge
import com.example.vpn.sidecar.AetherSidecar

/**
 * Automatic WARP (Aether) on Irancell: one short MASQUE-over-HTTP/2 attempt, then the next Smart Connect stage.
 *
 * Irancell diagnostics (2026-11-14): the WARP identity registration failed with a TLS handshake failure, so the
 * H3 + WireGuard race never ran (it needs both identities stored) and H3 then waited out its whole 75 s window.
 * WARP's WireGuard mode is blocked in Iran and MASQUE H3 is UDP; neither is started here any more. The bundled
 * Aether (2.4.0) registers through the API's second name and two Cloudflare edge addresses when the first name
 * fails, and `--fragment` (passed only to the TCP carriers) now also splits the account API ClientHello. So:
 *
 * - one attempt only, best first: a MASQUE HTTP/2 method verified on this network before, a remembered clean
 *   HTTP/2 gateway, plain HTTP/2 (TCP; Aether's gateway scan dials port 443 first), then split-hello HTTP/2;
 * - WireGuard, H3, WARP-in-WARP and every WireGuard follow-up are skipped, and so is the H3 + WireGuard race;
 * - the account API goes through Aether's own route (`apiRoute` native, unless the user chose a route), so its
 *   name/edge fallback and the API ClientHello fragment apply, and no Direct OOB helper start eats the budget;
 * - everything from preparing the engine to its readiness fits in [BUDGET_MS];
 * - a registration failure that is still reported after [REGISTRATION_GRACE_MS] ends the attempt at once.
 *
 * WARP through a saved server is left to the background relay ([AetherBackgroundRelay]) after a saved server is
 * verified, outside this budget.
 */
object IrancellWarp {
    /** Whole direct WARP attempt on Irancell, engine preparation to readiness. */
    const val BUDGET_MS = 15_000L

    /**
     * Aether retries the account call on its fallback routes by itself; a registration failure still reported this
     * long after it first appeared (with no later startup stage clearing it) is final for this attempt.
     */
    const val REGISTRATION_GRACE_MS = 2_000L

    /** Never less than this to wait for readiness, so a slow prepare still lets the engine report its failure. */
    const val MIN_WAIT_MS = 1_000L

    /** Methods the single attempt may use, in order of preference when none was verified here before. */
    val ALLOWED = listOf(ConnectionEngineRunner.CLEAN_H2, "h2", ConnectionEngineRunner.H2_SPLIT)

    /** Only automatic Aether in the foreground: never a relay-only background run or a reverse Tor/Psiphon relay. */
    fun applies(engineId: String, automatic: Boolean, irancell: Boolean, relayOnly: Boolean, reverseRelay: Boolean): Boolean =
        engineId == AetherSidecar.ID && automatic && irancell && !relayOnly && !reverseRelay

    /**
     * The one method tried: [preferred] when it is a MASQUE HTTP/2 method that is available, otherwise the first of
     * [ALLOWED] in [methods]. Null when no MASQUE HTTP/2 method is available at all.
     */
    fun pick(methods: List<String>, preferred: String?): String? =
        preferred?.takeIf { it in ALLOWED && it in methods } ?: ALLOWED.firstOrNull { it in methods }

    /** The methods left out of the plan, in their original order, for the log. */
    fun skipped(methods: List<String>, picked: String?): List<String> = methods.filter { it != picked }

    /**
     * Readiness wait for the attempt: the engine's own [windowMs], cut to what is left of [BUDGET_MS] since
     * [startedAt], never below [MIN_WAIT_MS].
     */
    fun waitMs(windowMs: Long, startedAt: Long, now: Long): Long =
        minOf(windowMs, maxOf(MIN_WAIT_MS, BUDGET_MS - (now - startedAt)))

    /** The attempt's account API route: Aether's own (native) unless the user picked a route. */
    fun withNativeApi(candidate: VlessProfile): VlessProfile {
        val e = ProfileExtras.read(candidate)
        if (e.optString("apiRoute", "auto") != "auto") return candidate
        return candidate.copy(extraSettings = org.json.JSONObject(e.toString()).put("apiRoute", "native").toString())
    }

    /**
     * Ends the readiness wait once a WARP registration failure has been reported for [graceMs] without a later
     * startup stage clearing it. Not thread-safe: one per wait.
     */
    class RegistrationWatch(private val graceMs: Long = REGISTRATION_GRACE_MS) {
        private var since: Long? = null

        fun shouldStop(startupFailure: String?, now: Long): Boolean {
            if (startupFailure == null || !AetherApiBridge.registrationFailed(startupFailure)) {
                since = null
                return false
            }
            val first = since ?: now.also { since = it }
            return now - first >= graceMs
        }
    }
}
