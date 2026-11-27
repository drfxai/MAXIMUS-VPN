package com.example.vpn.connections

import com.example.data.model.VlessProfile
import com.example.vpn.engine.ProfileExtras
import com.example.vpn.sidecar.AetherApiBridge
import com.example.vpn.sidecar.AetherSettings
import com.example.vpn.sidecar.AetherSidecar

/**
 * Automatic WARP (Aether) on Irancell: one short MASQUE-over-HTTP/2 attempt, then the next Smart Connect stage. A manual run can try a second H2 variant.
 *
 * Irancell diagnostics (2026-11-14): the WARP identity registration failed with a TLS handshake failure, so the
 * H3 + WireGuard race never ran (it needs both identities stored) and H3 then waited out its whole 75 s window.
 * WARP's WireGuard mode is blocked in Iran and MASQUE H3 is UDP; neither is started here any more. The bundled
 * Aether (2.4.0) registers through the API's second name and two Cloudflare edge addresses when the first name
 * fails, and `--fragment` (passed only to the TCP carriers) now also splits the account API ClientHello. So:
 *
 * - one attempt only, best first: a MASQUE HTTP/2 method verified on this network before, a remembered clean
 *   HTTP/2 gateway, then split-hello HTTP/2, then plain HTTP/2. Field reports from build 164 verified only the split path;
 * - WireGuard, H3, WARP-in-WARP and every WireGuard follow-up are skipped, and so is the H3 + WireGuard race;
 * - the account API goes through Aether's own route (`apiRoute` native, unless the user chose a route), so its
 *   name/edge fallback and the API ClientHello fragment apply, and no Direct OOB helper start eats the budget;
 * - everything from preparing the engine to its readiness fits in [BUDGET_MS];
 * - a registration failure that is still reported after [REGISTRATION_GRACE_MS] ends the attempt at once.
 *
 * WARP through a saved server is left to the background relay ([AetherBackgroundRelay]) after a saved server is
 * verified, outside this budget.
 *
 * 2026-11-15: even with the fragment and the compatible TLS retry every start still failed with "account bootstrap or
 * refresh timed out after 14s". The attempt now also switches on Aether's account API lanes ([WarpApiLane]): each
 * account call races Aether's own TLS against the OOB ClientHello lane (and the user's Worker relay when set), and
 * only the first finished handshake sends it. The budget grew from 15 s to 20 s so one lane race (≤ about 9 s) plus
 * MASQUE enrollment still fits.
 */
object IrancellWarp {
    /** Whole direct WARP attempt on Irancell, engine preparation to readiness. */
    const val BUDGET_MS = 20_000L

    /**
     * Aether retries the account call on its fallback routes by itself; a registration failure still reported this
     * long after it first appeared (with no later startup stage clearing it) is final for this attempt.
     */
    const val REGISTRATION_GRACE_MS = 2_000L

    /** Never less than this to wait for readiness, so a slow prepare still lets the engine report its failure. */
    const val MIN_WAIT_MS = 1_000L

    /** Methods the single attempt may use, in order of preference when none was verified here before. */
    val ALLOWED = listOf(ConnectionEngineRunner.CLEAN_H2, ConnectionEngineRunner.H2_SPLIT, "h2")

    /** Only automatic Aether in the foreground: never a relay-only background run or a reverse Tor/Psiphon relay. */
    fun applies(engineId: String, automatic: Boolean, irancell: Boolean, relayOnly: Boolean, reverseRelay: Boolean): Boolean =
        engineId == AetherSidecar.ID && automatic && irancell && !relayOnly && !reverseRelay

    /**
     * Try a remembered gateway first. Otherwise use the previously verified HTTP/2 method if available,
     * then the default order. Null means no MASQUE HTTP/2 method is available.
     */
    fun pick(methods: List<String>, preferred: String?): String? =
        ConnectionEngineRunner.CLEAN_H2.takeIf { it in methods }
            ?: preferred?.takeIf { it in ALLOWED && it in methods }
            ?: ALLOWED.firstOrNull { it in methods }

    /**
     * An explicit connect can use a second H2 variant within the same overall budget. [v6First] (a manual pick with
     * cf=0 on a link not measured IPv4-only, build 179): MASQUE H2 over IPv6 goes before them.
     */
    fun plan(methods: List<String>, preferred: String?, manual: Boolean, v6First: Boolean = false): List<String> {
        val first = pick(methods, preferred) ?: return emptyList()
        if (!manual) return listOf(first)
        val alternate = listOf(ConnectionEngineRunner.H2_SPLIT, "h2", ConnectionEngineRunner.CLEAN_H2)
            .firstOrNull { it != first && it in methods }
        val v6 = ConnectionEngineRunner.H2_V6.takeIf { v6First && it in methods && first != ConnectionEngineRunner.CLEAN_H2 }
        return listOfNotNull(v6, first, alternate)
    }

    /** The methods left out of the plan, in their original order, for the log. */
    fun skipped(methods: List<String>, picked: String?): List<String> = methods.filter { it != picked }

    /**
     * Readiness wait for the attempt: the engine's own [windowMs], cut to what is left of [BUDGET_MS] since
     * [startedAt], never below [MIN_WAIT_MS].
     */
    fun waitMs(windowMs: Long, startedAt: Long, now: Long, budgetMs: Long = BUDGET_MS): Long =
        minOf(windowMs, maxOf(MIN_WAIT_MS, budgetMs - (now - startedAt)))

    /** Use upstream Aether's first-verified-gateway scan within the short readiness window. */
    internal fun forAttempt(candidate: VlessProfile, manual: Boolean): VlessProfile {
        // The MASQUE fingerprint is not set here: Auto is resolved per attempt by AetherFingerprintBook (semi-python,
        // firefox, go, then chrome on Irancell), and a fingerprint the user chose is sent as it is.
        val routed = if (manual) candidate else withNativeApi(candidate)
        val extras = ProfileExtras.read(routed)
        val options = AetherSettings.read(extras)
        if (extras.optString("carrier") != "h2" ||
            options.endpoint.isNotBlank() || options.tor != "off" || options.psiphon != "off" ||
            AetherSidecar.cleanH2Peer(extras, "h2", options) != null) return routed

        // Balanced may keep searching for a better gateway after already proving one.
        // Turbo returns the first gateway that passes the same transport validation.
        if (options.scan == "balanced") extras.put("scan", "turbo")
        // PattNG disables quick reconnect when requesting a fresh scan. Apply
        // that policy to the split retry so the previous gateway ring cannot consume its window.
        if (extras.optBoolean(AetherSidecar.SNI_SPLIT)) extras.put("rescan", true)
        return routed.copy(extraSettings = extras.toString())
    }

    internal fun manualAttemptMs(candidate: VlessProfile): Long {
        val extras = ProfileExtras.read(candidate)
        if (extras.optBoolean(ConnectionEngineRunner.V6_FIRST)) return ConnectionEngineRunner.MANUAL_V6_SHARE_MS
        val options = AetherSettings.read(extras)
        if (AetherSidecar.cleanH2Peer(extras, "h2", options) != null)
            return ConnectionEngineRunner.CLEAN_H2_BUDGET_MS
        return AetherSidecar.manualMasqueReadinessMs(options.scan, pinned = options.endpoint.isNotBlank())
    }

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
