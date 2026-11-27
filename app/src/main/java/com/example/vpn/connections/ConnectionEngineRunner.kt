package com.example.vpn.connections

import com.example.data.model.AppSettings
import com.example.data.model.VlessProfile
import com.example.vpn.sidecar.*
import com.example.vpn.engine.ProfileExtras
import com.example.vpn.verify.Socks5
import kotlinx.coroutines.CancellationException

/** Serial alternatives, each proven by HTTPS data; never crosses direct/tunnel mode boundaries. */
object ConnectionEngineRunner {
    data class Started(val running: RunningEngine, val launch: SidecarLaunch, val profile: VlessProfile,
        val check: AccessProbe.Result,
        /** The context the winning engine runs in when it is not the caller's (a raced carrier on its own port). */
        val context: SidecarContext? = null)
    class NoVerifiedPath(attempts: List<String>, val lastCheck: AccessProbe.Result?,
        /**
         * WARP data is blocked here and no saved server was verified on this network yet to carry it: the caller
         * should fall back to saved servers and, once one is verified and connected, retry WARP through it in the
         * background ([AetherBackgroundRelay]).
         */
        val relayDeferred: Boolean = false) :
        IllegalStateException(attempts.joinToString(" · "))

    /**
     * A built-in transport can fail after the Smart Connect path search has finished:
     * unlike a saved proxy, its success is only measurable after process startup.
     * Escalate once to a saved server search, never across exact/Trade/forced boundaries.
     */
    fun mayRecoverAether(
        aether: Boolean, smart: Boolean, autoFailover: Boolean,
        exact: Boolean, exitLocked: Boolean, force: Boolean, alreadyEscalated: Boolean,
        /** "Power button fallback" (default on): a non-exact session may hand over even with failover off. */
        fallback: Boolean = false
    ): Boolean = aether && (smart || autoFailover || fallback) && !exact && !exitLocked &&
        !force && !alreadyEscalated

    /**
     * Every automatic method failed on this same network in the last half hour and none was ever verified
     * here: the Irancell pattern where WARP handshakes pass and the data is dropped for every carrier. Run one
     * quick recheck of the first method (has the block lifted?) without follow-ups, instead of repeating the
     * whole 5-6 minute chain, so Smart Connect / the power-button fallback reaches Psiphon, Tor or saved
     * servers in about a minute.
     */
    fun quickRecheckOnly(methods: List<String>, preferred: String?, recentlyFailed: Set<String>,
                         warpDataBlocked: Boolean = false): Boolean =
        preferred == null && methods.isNotEmpty() && (warpDataBlocked || methods.all { it in recentlyFailed })

    /**
     * Readiness windows for automatic Aether carriers. Every attempt on the same link gets the same window:
     * the windows used to shrink from retry to retry (180 s, then 65 s, then 40 s), so a carrier that needed
     * a full gateway scan could never finish on a second try, and the first try on a Cloudflare-filtered link
     * (measured by the LAB, cf=0 with UDP answering) still paid the full 180 s per MASQUE carrier.
     *
     * - Cloudflare TLS filtered with UDP answering: fixed per-carrier windows ([FILTERED_BUDGET_MS]).
     *   H2 MASQUE is TLS over TCP to Cloudflare, exactly what the probe saw fail, so it gets the shortest.
     * - A carrier that failed here in the last half hour on a link that is not (or no longer) measured as
     *   filtered: [RETRY_FLOOR_MS], never less, so a retry can still complete a scan.
     * - Manual/pinned carriers and other engines keep the engine's own window.
     * A ready SOCKS port alone still never counts as verified traffic.
     */
    fun readinessBudgetMs(
        engineId: String, automatic: Boolean, filteredTlsWithUdp: Boolean,
        carrier: String, defaultMs: Long, failedHere: Boolean = false
    ): Long = when {
        engineId != AetherSidecar.ID || !automatic -> defaultMs
        // WARP inside WARP is only ever an automatic follow-up to a WireGuard handshake whose data was blocked.
        carrier == "wiw" -> minOf(defaultMs, WIW_BUDGET_MS)
        filteredTlsWithUdp -> minOf(defaultMs, FILTERED_BUDGET_MS[carrier] ?: FILTERED_DEFAULT_BUDGET_MS)
        failedHere -> minOf(defaultMs, RETRY_FLOOR_MS)
        else -> defaultMs
    }

    /** Fixed windows on a measured Cloudflare-filtered link (same on the first try and on every retry). */
    val FILTERED_BUDGET_MS = mapOf("wireguard" to 75_000L, "h3" to 75_000L, "h2" to 60_000L)
    const val FILTERED_DEFAULT_BUDGET_MS = 75_000L
    const val RETRY_FLOOR_MS = 90_000L

    const val WIW_BUDGET_MS = 90_000L

    const val H2_BLOCKED_TLS_BUDGET_MS = 60_000L

    /**
     * What the LAB measured on the *current* physical link that matters to Aether, shared by Connect, the
     * Connections page test and the LAB engine probe (the latter two used to pass nothing, so a test on a
     * cf=0 link spent two full 180 s MASQUE scans before WireGuard was even tried).
     */
    data class LinkHints(val ipv4Only: Boolean = false, val cloudflareFiltered: Boolean = false, val metered: Boolean = false)

    fun linkHints(measured: com.example.vpn.smart.NetworkCapabilityProfile?): LinkHints {
        val m = measured ?: return LinkHints()
        return LinkHints(
            ipv4Only = m.ipv4Available == true && m.ipv6Available == false,
            // Cloudflare's TLS (or Cloudflare itself) failed while UDP abroad answered: MASQUE is the carrier
            // the probe saw fail, WireGuard is still worth a try. Only a measured failure counts, never a guess.
            cloudflareFiltered = m.udpAvailable == true &&
                (m.cloudflareIpv4Tls == false || m.cloudflareReachable == false),
            metered = m.meteredNetwork == true
        )
    }

    /** Method name of the obfuscated WireGuard retry, recorded so a verified result is reused next time. */
    const val WIREGUARD_NOIZE = "wireguard-noize"

    /** The obfuscation profile used for the retry: Aether's strongest pre-handshake junk-packet profile. */
    const val WIREGUARD_NOIZE_PROFILE = "gfw"

    /**
     * WireGuard's handshake passed but no HTTPS came back, with the default obfuscation: try WireGuard once
     * more with Aether's GFW junk-packet profile and a fresh endpoint/port scan (no quick reconnect to the
     * endpoint that just carried nothing) before WARP-in-WARP. Aether's own profile fallback only changes
     * obfuscation when the *handshake* fails, so without this it never tries a stronger profile on a link
     * that lets handshakes through and drops the data afterwards (the Irancell pattern). Never for a user's
     * own obfuscation choice, a reverse relay, or twice.
     */
    fun obfuscatedWireGuardFollowUp(engineId: String, automatic: Boolean, carrier: String, noize: String,
                                    alreadyQueued: Boolean, reverseRelay: Boolean): Boolean =
        engineId == AetherSidecar.ID && automatic && carrier == "wireguard" && noize == "auto" &&
            !alreadyQueued && !reverseRelay

    fun obfuscatedWireGuard(candidate: VlessProfile): VlessProfile = candidate.copy(extraSettings =
        org.json.JSONObject(candidate.extraSettings).put("carrier", "wireguard").put("noize", WIREGUARD_NOIZE_PROFILE)
            .put("rescan", true).put(NOIZE_FOLLOW_UP, true).toString())

    internal const val NOIZE_FOLLOW_UP = "noizeFollowUp"

    /** Method names of WARP carried through a saved server verified on this network (see [relayed]). */
    const val WIREGUARD_RELAY = "wireguard-relay"
    const val H2_RELAY = "h2-relay"
    internal const val RELAY_FOLLOW_UP = "relayFollowUp"

    /**
     * WARP through a saved server that carried verified traffic on this same network (Aether's exit-node
     * upstream: WireGuard's UDP and MASQUE's TLS leave the phone inside that server's own protocol, e.g. VLESS
     * REALITY). On a carrier that drops WARP data after the handshake (Irancell) the censor never sees WARP,
     * and the exit is still Cloudflare's. Only for automatic carriers, when the user picked no exit node of
     * their own and no Tor/Psiphon relay mode.
     */
    fun relayEligible(engineId: String, automatic: Boolean, options: AetherSettings, relayExitNode: String?): Boolean =
        engineId == AetherSidecar.ID && automatic && !relayExitNode.isNullOrBlank() && options.exitNode.isBlank() &&
            options.tor == "off" && options.psiphon == "off"

    fun relayed(candidate: VlessProfile, carrier: String, exitNode: String,
                route: BuiltinExitNode.Route = BuiltinExitNode.Route()): VlessProfile = candidate.copy(extraSettings =
        route.write(org.json.JSONObject(candidate.extraSettings).put("carrier", carrier).put("exitNode", exitNode)
            .put(RELAY_FOLLOW_UP, true).apply { remove(NOIZE_FOLLOW_UP); remove(MTU_FOLLOW_UP); remove("rescan") }).toString())

    /** Method name of WARP's MASQUE HTTP/2 tunnel dialled through Lantern (Radiance) as the relay hop. */
    const val H2_LANTERN = "h2-lantern"

    /**
     * Aether's readiness window through the Lantern hop, counted after Radiance itself is ready (its own start-up,
     * up to 60 s, is waited for before Aether starts): registration is already stored, the gateway scan and the
     * tunnel go through Lantern.
     */
    const val LANTERN_ATTEMPT_MS = 90_000L

    /**
     * WARP through Lantern (Radiance) instead of only scanning gateways directly: the user's own Aether run
     * ([manual]), Cloudflare filtered here ([cloudflareBlocked]: cf_quick = filtered or a fresh cf=0 reading), no saved
     * server verified on this network for the saved-server relay ([relayExitNode] absent), Radiance in this build,
     * and no exit node, Tor or Psiphon mode of the user's own. Never for the background relay run. Automatic runs
     * (Smart Connect) do not use it: they reach Lantern itself next anyway.
     */
    fun lanternRelayEligible(engineId: String, automatic: Boolean, manual: Boolean, cloudflareBlocked: Boolean,
                             lanternAvailable: Boolean, options: AetherSettings, relayExitNode: String?,
                             relayOnly: Boolean): Boolean =
        engineId == AetherSidecar.ID && automatic && manual && cloudflareBlocked && lanternAvailable &&
            relayExitNode.isNullOrBlank() && !relayOnly && options.exitNode.isBlank() &&
            options.tor == "off" && options.psiphon == "off"

    /**
     * Build 181 (3.2): run the MASQUE SNI pre-check for this start: Aether, Cloudflare measured as filtered here (or
     * Irancell), a carrier that can be MASQUE (auto, h2, h3), not the background relay retry. Pure.
     */
    fun masqueSniPrecheck(engineId: String, relayOnly: Boolean, cloudflareBlocked: Boolean, irancell: Boolean, carrier: String): Boolean =
        engineId == AetherSidecar.ID && !relayOnly && (cloudflareBlocked || irancell) && carrier in listOf("auto", "h2", "h3")

    /** [candidate] with the MASQUE TLS name [sni] when it is a MASQUE carrier (h2, h3); others unchanged. Pure. */
    fun withMasqueSni(candidate: VlessProfile, sni: String): VlessProfile {
        val e = org.json.JSONObject(candidate.extraSettings)
        if (e.optString("carrier") !in listOf("h2", "h3") || !AetherSniProbe.validName(sni)) return candidate
        return candidate.copy(extraSettings = e.put(AetherSidecar.MASQUE_SNI, sni).toString())
    }

    /** [candidate] (an HTTP/2 carrier) as MASQUE HTTP/2 through the Lantern hop: no split hello, no remembered gateway. */
    fun lanternRelayed(candidate: VlessProfile): VlessProfile = candidate.copy(extraSettings =
        org.json.JSONObject(candidate.extraSettings).put("carrier", "h2").put(LanternRelayHop.EXTRA, true)
            .put(RELAY_FOLLOW_UP, true).apply {
                remove(NOIZE_FOLLOW_UP); remove(MTU_FOLLOW_UP); remove("rescan")
                remove(AetherSidecar.SNI_SPLIT); remove(AetherSidecar.CLEAN_H2_PEER); remove(AetherSidecar.MASQUE_SNI)
                remove(BuiltinExitNode.EXIT_ENDPOINT); remove(BuiltinExitNode.EXIT_VARIANT)
            }.toString())

    /** A failed [H2_LANTERN] attempt on [network]: remembered ([LanternRelayMemory]) unless it is not about the network. */
    internal fun rememberLanternFailure(network: String?, reason: String?, record: (String, String, String, String) -> Unit) {
        if (reason == LanternRelayHop.CLOUDFLARE_UNREACHABLE) LanternRelayHop.lastPreflight?.let {
            record(H2_LANTERN, "lantern-hop", "NOTE", "Cloudflare preflight through Lantern: " +
                it.tries.joinToString(", ").ifEmpty { "no try fitted the budget" })
        }
        if (!LanternRelayMemory.counts(reason)) return
        LanternRelayMemory.record(network)
        record(H2_LANTERN, "lantern-hop", "REMEMBER", "WARP through Lantern failed here; not retried on this network " +
            "for ${LanternRelayMemory.HOLD_MS / 60_000} min")
    }

    /**
     * Whole-ladder budget for automatic Aether on a metered link that is Irancell or measured Cloudflare-filtered:
     * once it is spent no further direct carrier starts (WARP through a verified saved server still may, it is
     * one quick attempt) and the session falls back to saved servers / Psiphon / Tor instead of spending
     * minutes of mobile data on gateway scans that the carrier filters anyway.
     */
    const val HEAVY_LADDER_BUDGET_MS = 120_000L
    /** Below this much budget left a direct carrier is not started at all. */
    const val LADDER_MIN_ATTEMPT_MS = 20_000L

    fun ladderBudgetMs(engineId: String, automatic: Boolean, metered: Boolean, irancell: Boolean,
                       cloudflareFiltered: Boolean): Long? =
        if (engineId == AetherSidecar.ID && automatic && metered && (irancell || cloudflareFiltered)) HEAVY_LADDER_BUDGET_MS else null

    /**
     * The user's own Aether pick on a network where Cloudflare is known to be filtered (cf_quick = filtered or a fresh
     * cf=0 reading). Build 178 field report (Irancell): such picks ran 2-3 minute gateway scans (H2 220 s ladder, MIM,
     * WireGuard + MTU retry) that all ended "gateway scan completed without a verified endpoint" while the app already
     * knew cf=0. The pick still runs only Aether, never another engine; it gets one short, honest check.
     */
    const val CF_FILTERED_MANUAL_BUDGET_MS = 60_000L

    /** [CF_FILTERED_MANUAL_BUDGET_MS] for the user's own Aether run with Cloudflare filtered here; null otherwise. Pure. */
    fun cfFilteredManualBudgetMs(engineId: String, manual: Boolean, cloudflareBlocked: Boolean, relayOnly: Boolean): Long? =
        if (engineId == AetherSidecar.ID && manual && cloudflareBlocked && !relayOnly) CF_FILTERED_MANUAL_BUDGET_MS else null

    /** The whole-run budget: the tighter of the metered ladder budget and the cf=0 manual budget. Pure. */
    fun effectiveLadderBudgetMs(ladder: Long?, cfManual: Long?): Long? = when {
        ladder == null -> cfManual
        cfManual == null -> ladder
        else -> minOf(ladder, cfManual)
    }

    /**
     * Build 178: Aether with Cloudflare filtered (cf=0) here, a link not measured IPv4-only, and neither the Irancell nor
     * the Hamrah-e-Aval ladder: WARP over IPv6 (WireGuard, then HTTP/2) is tried first. Same engine, so a manual pick may.
     * Pure.
     */
    fun cfV6First(engineId: String, automatic: Boolean, cloudflareBlocked: Boolean, ipv4Only: Boolean,
                  irancell: Boolean, mci: Boolean, relayOnly: Boolean,
                  /**
                   * Build 179 device test: the IPv6-first attempt never ran on Irancell, the network it was added for: this
                   * gate excluded Irancell and the Irancell ladder pinned every carrier to IPv4. The user's own Aether pick
                   * on Irancell now tries MASQUE H2 over IPv6 first (capped at [IRANCELL_V6_MS]); Smart Connect keeps its
                   * single short IPv4 attempt there.
                   */
                  manual: Boolean = false): Boolean =
        engineId == AetherSidecar.ID && automatic && cloudflareBlocked && !ipv4Only && (!irancell || manual) && !mci && !relayOnly

    /** The IPv6-first MASQUE H2 attempt of Aether on Irancell (Smart Connect). */
    const val IRANCELL_V6_MS = 45_000L

    /**
     * Build 180 device test (A2): Methods → Aether / WARP ran 102.5 s ("105s readiness budget") while the picker promises
     * "up to 60 s": the IPv6-first lane added 45 s on top of the short check, on a phone with no IPv6 at all. The user's
     * own pick now keeps the 60 s promise: the IPv6 lane shares that window, capped at this, and is skipped outright when
     * the phone's link has no global IPv6 address ([noIpv6]).
     */
    const val MANUAL_V6_SHARE_MS = 20_000L

    /** The window of one IPv6-first attempt: a share of the 60 s for the user's own pick, else [IRANCELL_V6_MS]. Pure. */
    fun v6AttemptMs(manual: Boolean): Long = if (manual) MANUAL_V6_SHARE_MS else IRANCELL_V6_MS

    /**
     * IPv4-only for Aether's purposes: measured so, or the phone's own link has no global IPv6 address right now
     * ([linkGlobalIpv6] false). Unknown (null) keeps IPv6 possible. Pure.
     */
    fun noIpv6(measuredIpv4Only: Boolean, linkGlobalIpv6: Boolean?): Boolean = measuredIpv4Only || linkGlobalIpv6 == false

    /** The stage line shown before a cf=0 manual Aether run starts. Pure. */
    fun cfFilteredManualNotice(budgetMs: Long): String =
        "Cloudflare is filtered on this network (cf=0): WARP rarely connects here, so this pick gets one short check " +
            "(up to ${budgetMs / 1000}s) instead of minutes of gateway scans. Lantern or Serverless usually work on such networks."

    /**
     * WireGuard's handshake passed and no data came back on Irancell or a measured Cloudflare-filtered link: the
     * carrier is dropping WARP data (seen on every WARP carrier there). Record that at once and go straight to
     * WARP through a verified saved server, with at most one cheap follow-up (the MTU retry on the same endpoint),
     * instead of the obfuscated rescan, H2, H2-split and WARP-in-WARP full scans that all fail the same way.
     */
    fun warpDataBlockedFastPath(engineId: String, automatic: Boolean, carrier: String, irancell: Boolean,
                                cloudflareFiltered: Boolean, reverseRelay: Boolean): Boolean =
        engineId == AetherSidecar.ID && automatic && carrier == "wireguard" && (irancell || cloudflareFiltered) && !reverseRelay

    /**
     * Irancell: MASQUE H3 and WireGuard start at the same time instead of H3 spending its whole window first
     * ([FirstVerifiedRace]: only HTTPS-verified data wins, the loser is stopped, one deadline inside the ladder
     * budget). Only when both identities are already stored (two engines must never register at once), never for
     * a recheck, a relay-only run, a reverse relay, or a second time.
     */
    fun raceEligible(engineId: String, automatic: Boolean, irancell: Boolean, next: String, queued: Collection<String>,
                     recheckOnly: Boolean, relayOnly: Boolean, reverseRelay: Boolean, identitiesStored: Boolean,
                     alreadyRaced: Boolean): Boolean =
        engineId == AetherSidecar.ID && automatic && irancell && next == "h3" && "wireguard" in queued &&
            !recheckOnly && !relayOnly && !reverseRelay && identitiesStored && !alreadyRaced

    /** HTTPS check plus the 6 s recheck must still fit after a raced carrier becomes ready. */
    const val RACE_CHECK_RESERVE_MS = 20_000L
    /** Race deadline when no metered ladder budget applies (the engine's own readiness window plus the check). */
    const val RACE_DEFAULT_DEADLINE_MS = 180_000L + RACE_CHECK_RESERVE_MS

    /**
     * WireGuard handshook without data (WARP data blocked here) and there is no saved server verified on this
     * network to carry WARP: stop Aether now and let the session fall back to saved servers, with WARP through the
     * first one verified retried in the background afterwards. Only when a relay would otherwise be allowed (no
     * exit node, Tor or Psiphon mode of the user's own).
     */
    fun deferRelayToSavedServer(engineId: String, automatic: Boolean, options: AetherSettings, relayExitNode: String?,
                                warpDataBlocked: Boolean): Boolean =
        warpDataBlocked && relayExitNode.isNullOrBlank() && relayEligible(engineId, automatic, options, "saved-server")

    /**
     * Automatic Aether (Smart Connect, the config-free button, the power-button fallback) on a link where Cloudflare is
     * measured filtered, with a connection verified on this same network to hand over to: skip the gateway scans.
     * Never for the user's own run ([manual]) or the background relay. Pure.
     */
    fun skipScansForVerifiedFallback(engineId: String, automatic: Boolean, manual: Boolean, relayOnly: Boolean,
                                     cloudflareBlocked: Boolean, fallbackAvailable: Boolean): Boolean =
        engineId == AetherSidecar.ID && automatic && !manual && !relayOnly && cloudflareBlocked && fallbackAvailable

    /** The user's own MASQUE-over-HTTP/2 pick inside the hold with Cloudflare still filtered fails at once. Pure. */
    fun explicitH2FastFail(engineId: String, automatic: Boolean, relayOnly: Boolean, cloudflareBlocked: Boolean,
                           carrier: String, held: Boolean): Boolean =
        engineId == AetherSidecar.ID && !automatic && !relayOnly && cloudflareBlocked && held && carrier == "h2"

    /**
     * Build 179 device test (N3): a WARP failure anywhere (Smart Connect included) held every manual WARP pick for 30 min
     * with no way to try anyway. The hold no longer refuses the user's own pick; it only steers Smart Connect and the
     * background relays. Pure.
     */
    fun holdRefuses(manual: Boolean): Boolean = !manual

    fun manualPickDespiteHold(elapsedMs: Long): String =
        "WARP found no gateway on this network ${elapsedMs / 60_000} min ago; your own pick runs anyway " +
            "(the pause only applies to Smart Connect and background relays)"

    /** The least time the HTTPS check gets after a carrier started, even when the run's budget is nearly used. */
    const val VERIFY_MIN_MS = 8_000L

    /** How long the HTTPS check may take with [leftMs] of the run's budget left (null: no budget). Pure. */
    fun verifyWindowMs(leftMs: Long?): Long? = leftMs?.let { maxOf(VERIFY_MIN_MS, it) }

    /** [block] within [timeoutMs] (null: no limit); a check still running then counts as a timed-out failure. */
    fun boundedCheck(timeoutMs: Long?, block: () -> AccessProbe.Result): AccessProbe.Result {
        if (timeoutMs == null) return block()
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        return try {
            val future = pool.submit(java.util.concurrent.Callable { block() })
            try {
                future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: java.util.concurrent.TimeoutException) {
                future.cancel(true)
                AccessProbe.Result(failure = "HTTPS check did not finish within the ${timeoutMs / 1000}s left of this pick's budget")
            } catch (e: java.util.concurrent.ExecutionException) {
                throw e.cause ?: e
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * The line when the run's budget is used. Build 179 device test (N13): a manual WireGuard pick logged "falling back to
     * saved servers" although nothing else ran (a manual pick never starts another connection). Pure.
     */
    fun budgetStopText(budgetMs: Long, dropped: List<String>, manual: Boolean): String =
        "${budgetMs / 1000}s Aether budget on this filtered link used; skipping " + dropped.joinToString(", ") +
            if (manual) "; your pick ends here (nothing else is started)" else "; falling back to saved servers"

    fun verifiedFallbackMessage(verified: String): String =
        "Cloudflare is filtered on this network and $verified carried verified traffic here recently: " +
            "skipping Aether's gateway scans and connecting with it"

    /** One raced carrier's result; [process] is set (still running) only when its HTTPS was verified. */
    private class RaceLeg(val name: String, val candidate: VlessProfile, val context: SidecarContext,
                          val process: RunningEngine?, val launch: SidecarLaunch?, val check: AccessProbe.Result?,
                          val ready: Boolean, val reason: String?)

    /** The reason recorded for an exception while starting a carrier: fixed, non-secret strings only. */
    internal fun startFailureReason(engineId: String, e: Throwable, stage: String): String {
        val message = e.message
        return when {
            e is BuiltinExitNode.ExitNodeFailure && (message in BuiltinExitNode.REASONS ||
                message in LanternRelayHop.REASONS) -> message!!
            engineId == AetherSidecar.ID && message in setOf("WARP API TLS unreachable via forced Direct OOB route",
                AetherApiBridge.INCOMPATIBLE_OOB, AetherApiBridge.UNREACHABLE_WITHOUT_IDENTITY) -> message!!
            else -> stage
        }
    }

    /** Method name of the reduced-MTU WireGuard retry, recorded so a verified result is reused next time. */
    const val WIREGUARD_MTU = "wireguard-mtu"
    internal const val MTU_FOLLOW_UP = "mtuFollowUp"

    /**
     * WireGuard's handshake passed (small packets) but no HTTPS came back: the signature of a path that drops
     * full-size packets, common on mobile carriers that add their own encapsulation. Retry WireGuard once with
     * a smaller data-plane MTU ([AetherSettings.MTU_RETRY], inside the 1180–1220 band) on the endpoint that just
     * handshook (quick reconnect, no rescan: the endpoint was fine, the packet size was not). Only when the
     * user left the MTU on automatic; never for a reverse relay, a manual carrier, or twice.
     */
    fun reducedMtuWireGuardFollowUp(engineId: String, automatic: Boolean, carrier: String, mtu: String,
                                    alreadyQueued: Boolean, reverseRelay: Boolean): Boolean =
        engineId == AetherSidecar.ID && automatic && carrier == "wireguard" && mtu.isBlank() &&
            !alreadyQueued && !reverseRelay

    fun reducedMtuWireGuard(candidate: VlessProfile): VlessProfile = candidate.copy(extraSettings =
        org.json.JSONObject(candidate.extraSettings).put("carrier", "wireguard")
            .put("mtu", AetherSettings.MTU_RETRY.toString()).put(MTU_FOLLOW_UP, true)
            .apply { remove(NOIZE_FOLLOW_UP); remove("rescan") }.toString())

    /**
     * Aether's carrier order on Irancell (MCC-MNC 43235): MASQUE H3, then WireGuard, then MASQUE H2, then H2 with
     * the ClientHello split at the server name (UAC SNI Spoofer tries the split hello on Irancell last).
     */
    val IRANCELL_LADDER = listOf("h3", "wireguard", "h2", "h2-split")

    /**
     * Aether's carrier order on Hamrah-e-Aval (MCC-MNC 43211). patterniha (2026-11): MCI's IPv4 is a whitelist and its
     * IPv6 a blacklist, so WARP over IPv6 goes first: WireGuard over IPv6 ([WIREGUARD_V6]), then MASQUE HTTP/2 over
     * IPv6 with the full ClientHello ([H2_V6]); both only when the user left the family on Auto and the link was not
     * measured IPv4-only. Then UAC SNI Spoofer's MCI outer plan: H2 with the full, unfragmented ClientHello on both
     * address families ("the same dial that connected on an MCI modem"), H2 with the ClientHello split at the server
     * name (both families; IPv4 only on a measured IPv4-only link), H3, then WireGuard.
     */
    val MCI_LADDER = listOf(WIREGUARD_V6, H2_V6, "h2", "h2-split", "h3", "wireguard")

    /** Method name of WireGuard over IPv6, the first step of the Hamrah-e-Aval ladder. */
    const val WIREGUARD_V6 = "wireguard-v6"
    /** Method name of MASQUE HTTP/2 over IPv6 with the full ClientHello, the second step of the Hamrah-e-Aval ladder. */
    const val H2_V6 = "h2-v6"
    /** Extras key marking the Hamrah-e-Aval IPv6-first variants ([WIREGUARD_V6], [H2_V6]). */
    const val V6_FIRST = "mciV6First"
    /**
     * The IPv6-first steps are probes ahead of the known MCI path: each gets a short window so a dead IPv6 route
     * costs little ("wireguard" answers or fails fast; H2 gets the filtered-link H2 window).
     */
    val V6_FIRST_BUDGET_MS = mapOf("wireguard" to 45_000L, "h2" to 60_000L)

    /** Method name of MASQUE over HTTP/2 to a gateway remembered as clean on this network ([CleanH2PeerMemory]). */
    const val CLEAN_H2 = "h2-clean"
    /** Method name of MASQUE over HTTP/3 to a gateway remembered as clean on this network. */
    const val CLEAN_H3 = "h3-clean"
    /** Method name of WireGuard to an endpoint remembered as clean on this network. */
    const val CLEAN_WG = "wireguard-clean"
    /** Every remembered-endpoint method: short window, no scan, forgotten (not marked failed) when it fails. */
    val CLEAN_METHODS = setOf(CLEAN_H2, CLEAN_H3, CLEAN_WG)
    /** Remembered H3 gateways / WireGuard endpoints tried per connect (one each: a UDP dial answers at once or not). */
    const val CLEAN_UDP_TRIES = 1
    /** Method name of MASQUE over HTTP/2 with the first TLS write split inside the server name. */
    const val H2_SPLIT = "h2-split"
    /** A remembered gateway either answers quickly or not at all: no scan runs behind it. */
    const val CLEAN_H2_BUDGET_MS = 15_000L
    /** Remembered gateways tried per connect before the normal carriers (UAC keeps its speed pick small too). */
    const val CLEAN_H2_TRIES = 2

    /** Whether [networkKey] is Hamrah-e-Aval (MCI). */
    fun mci(networkKey: String?): Boolean =
        com.example.vpn.connectivity.NetworkFirewalls.detect(networkKey) == com.example.vpn.connectivity.NetworkFirewalls.Firewall.MCI

    /**
     * Operator variants of the automatic Aether carriers. Hamrah-e-Aval: H2 sends the full ClientHello (no
     * fragment) and an [H2_SPLIT] variant follows it; with the family on Auto, [WIREGUARD_V6] and [H2_V6] (the same
     * carriers pinned to IPv6) are added for the IPv6-first steps of [MCI_LADDER]. Irancell: an [H2_SPLIT] variant
     * follows H2. The split variant fragments the first TLS write inside the server name (Aether's SNI-targeted
     * split, the HTTP/2 form of UAC's Iran ClientHello split). On Irancell it dials IPv4 unless the user chose a
     * family; on Hamrah-e-Aval it keeps the family (both, unless [candidates] already pinned IPv4 on a measured
     * IPv4-only link), since IPv6 is the open side there. Never for reverse relays.
     */
    fun operatorVariants(choices: List<VlessProfile>, irancell: Boolean, mci: Boolean,
                         /**
                          * Build 178: Cloudflare filtered (cf=0) on a link not measured IPv4-only, outside the Irancell and
                          * Hamrah-e-Aval ladders: WARP over IPv6 (WireGuard, then H2) is tried first, as on Hamrah-e-Aval;
                          * the filtering seen so far was on Cloudflare's IPv4 addresses. Same engine, so a manual pick may.
                          */
                         cfV6: Boolean = false): List<VlessProfile> {
        if (!irancell && !mci && !cfV6) return choices
        return choices.flatMap { candidate ->
            val e = ProfileExtras.read(candidate)
            val options = AetherSettings.read(e)
            val carrier = e.optString("carrier")
            val reverse = options.tor == "reverse" || options.psiphon == "reverse"
            // Family Auto on a link not measured IPv4-only ([candidates] pins IPv4 there): IPv6 can be tried first.
            val v6 = (mci || cfV6) && !reverse && e.optString("family", "auto") == "auto" && options.endpoint.isBlank()
            if (carrier == "wireguard" && v6) return@flatMap listOf(ipv6First(candidate, fragment = null), candidate)
            if (carrier != "h2" || reverse) return@flatMap listOf(candidate)
            val split = candidate.copy(extraSettings = org.json.JSONObject(e.toString()).put("fragment", true)
                .put(AetherSidecar.SNI_SPLIT, true).apply { if (irancell && optString("family", "auto") == "auto") put("family", "ipv4") }.toString())
            val plain = if (mci) candidate.copy(extraSettings = org.json.JSONObject(e.toString()).put("fragment", false).toString()) else candidate
            if (v6) listOf(plain, ipv6First(candidate, fragment = false), split) else listOf(plain, split)
        }
    }

    /** [candidate] pinned to IPv6 and marked [V6_FIRST]; [fragment] set when given (H2 sends the full hello). */
    private fun ipv6First(candidate: VlessProfile, fragment: Boolean?): VlessProfile = candidate.copy(extraSettings =
        org.json.JSONObject(candidate.extraSettings).put("family", "ipv6").put(V6_FIRST, true)
            .apply { if (fragment != null) put("fragment", fragment) }.toString())

    /**
     * MASQUE over HTTP/2 to the gateways remembered as clean on this network (most recent / fastest first), at
     * most [CLEAN_H2_TRIES]. Each uses the hello it worked with (split or not). Only for automatic H2 without a
     * user endpoint, exit node or reverse/standalone relay; IPv6 gateways are left out on an IPv4-only link.
     */
    fun cleanH2Candidates(choices: List<VlessProfile>, peers: List<CleanH2PeerMemory.Peer>, ipv4Only: Boolean): List<VlessProfile> {
        val plain = choices.firstOrNull { ProfileExtras.read(it).let { e -> e.optString("carrier") == "h2" && !e.optBoolean(AetherSidecar.SNI_SPLIT) && !e.optBoolean(V6_FIRST) } }
            ?: return emptyList()
        val split = choices.firstOrNull { ProfileExtras.read(it).let { e -> e.optString("carrier") == "h2" && e.optBoolean(AetherSidecar.SNI_SPLIT) } }
        val options = AetherSettings.read(ProfileExtras.read(plain))
        if (options.endpoint.isNotBlank() || options.exitNode.isNotBlank() ||
            options.tor in listOf("reverse", "only") || options.psiphon in listOf("reverse", "only")) return emptyList()
        return preferFingerprint(peers.filter { it.carrier == "h2" && !(ipv4Only && it.address.startsWith("[")) }, options.fingerprint)
            .take(CLEAN_H2_TRIES).map { peer ->
            val base = if (peer.split && split != null) split else plain
            base.copy(extraSettings = org.json.JSONObject(base.extraSettings).put(AetherSidecar.CLEAN_H2_PEER, peer.address)
                .put("family", if (peer.address.startsWith("[")) "ipv6" else "ipv4").toString())
        }
    }

    /**
     * MASQUE HTTP/3 to the gateway and WireGuard to the endpoint remembered as clean on this network, at most
     * [CLEAN_UDP_TRIES] each, with the family they worked on. Same conditions as [cleanH2Candidates]: automatic, no
     * user endpoint, exit node or reverse/standalone relay; IPv6 endpoints are left out on an IPv4-only link.
     */
    fun cleanUdpCandidates(choices: List<VlessProfile>, peers: List<CleanH2PeerMemory.Peer>, ipv4Only: Boolean): List<VlessProfile> =
        listOf("h3", "wireguard").flatMap { carrier ->
            val base = choices.firstOrNull { ProfileExtras.read(it).let { e -> e.optString("carrier") == carrier && !e.optBoolean(V6_FIRST) } }
                ?: return@flatMap emptyList<VlessProfile>()
            val options = AetherSettings.read(ProfileExtras.read(base))
            if (options.endpoint.isNotBlank() || options.exitNode.isNotBlank() ||
                options.tor in listOf("reverse", "only") || options.psiphon in listOf("reverse", "only")) return@flatMap emptyList<VlessProfile>()
            preferFingerprint(peers.filter { it.carrier == carrier && !(ipv4Only && it.address.startsWith("[")) },
                if (carrier == "wireguard") "" else options.fingerprint).take(CLEAN_UDP_TRIES).map { peer ->
                base.copy(extraSettings = org.json.JSONObject(base.extraSettings).put(AetherSidecar.CLEAN_PEER, peer.address)
                    .put("family", peer.family).toString())
            }
        }

    /**
     * Peers remembered with the fingerprint this attempt will send come first (a gateway that took one ClientHello may
     * not take another); the rest keep their order. The fingerprint itself always stays the user's setting.
     */
    private fun preferFingerprint(peers: List<CleanH2PeerMemory.Peer>, fingerprint: String): List<CleanH2PeerMemory.Peer> =
        peers.sortedBy { if (it.fingerprint.isBlank() || it.fingerprint == fingerprint) 0 else 1 }

    /** Whether [networkKey] (a [com.example.vpn.smart.NetworkIdentity] key such as "cell:43235") is Irancell. */
    fun irancell(networkKey: String?): Boolean =
        com.example.vpn.connectivity.NetworkFirewalls.detect(networkKey) == com.example.vpn.connectivity.NetworkFirewalls.Firewall.IRANCELL

    /**
     * On Irancell MASQUE dials IPv4 only: H3 (UAC's Irancell plan) and H2 (PattNG's default family; `--dual` spent the
     * short Irancell budget on IPv6 gateways). Automatic carriers only; a family the user chose stays theirs.
     */
    fun irancellIpv4(candidate: VlessProfile): VlessProfile {
        val e = ProfileExtras.read(candidate)
        if (e.optString("carrier") !in setOf("h2", "h3") || e.optString("family", "auto") != "auto") return candidate
        return candidate.copy(extraSettings = org.json.JSONObject(e.toString()).put("family", "ipv4").toString())
    }

    internal const val LANTERN_QUEUE_REASON = "Cloudflare is filtered here and no saved server is verified on this network: " +
        "trying WARP through Lantern (Radiance) as the relay hop"

    /** One line telling the user what to do when Aether found nothing on a link whose Cloudflare TLS is filtered. */
    const val CLOUDFLARE_FILTERED_HINT = "Cloudflare TLS is filtered on this network: connect with Lantern or Serverless; " +
        "Smart Connect can also try saved servers, Psiphon or Tor"

    /** The hint when the Lantern relay hop itself failed: "connect with Lantern" would point back at the pick. */
    const val LANTERN_HOP_HINT = "Cloudflare TLS is filtered on this network and Lantern's exit did not reach Cloudflare " +
        "this time: connect with Lantern alone, or Psiphon / Shir o Khorshid over Lantern; your next Aether over Lantern pick tries again"

    /** [LANTERN_HOP_HINT] when an attempt failed in the Lantern hop, else [CLOUDFLARE_FILTERED_HINT]. Pure. */
    fun filteredHint(attempts: List<String>): String =
        if (attempts.any { a -> LanternRelayHop.REASONS.any { a.endsWith(it) } }) LANTERN_HOP_HINT else CLOUDFLARE_FILTERED_HINT

    /**
     * The order the methods are tried in: a method verified here first, then WireGuard when Cloudflare TLS
     * is filtered but UDP answers, then the rest, with methods that failed here in the last half hour last.
     */
    fun order(methods: List<String>, preferred: String?, preferWireGuard: Boolean, recentlyFailed: Set<String>,
              irancell: Boolean = false, mci: Boolean = false, cfV6: Boolean = false): List<String> =
        methods.withIndex().sortedWith(compareBy<IndexedValue<String>>({
            when {
                it.value == preferred -> 0
                it.value in recentlyFailed -> 4
                // A gateway that carried HTTP/2 here recently, before any scan (UAC's saved clean HTTP/2 list).
                it.value in CLEAN_METHODS -> 1
                // Irancell ladder: MASQUE over HTTP/3 (QUIC, IPv4) first; Irancell lets WireGuard handshakes
                // through and then drops the data, so WireGuard goes after it (pattern from UAC SNI Spoofer).
                irancell && it.value == "h3" -> 2
                // Hamrah-e-Aval ladder: WARP over IPv6 first (WireGuard, then H2), then MASQUE over HTTP/2 with the
                // full ClientHello (UAC's MCI plan); MCI_LADDER orders them.
                mci && it.value in setOf(WIREGUARD_V6, H2_V6, "h2") -> 2
                // Build 178: cf=0 outside the operator ladders: WARP over IPv6 before the filtered IPv4 carriers.
                cfV6 && !irancell && !mci && it.value in setOf(WIREGUARD_V6, H2_V6) -> 1
                !irancell && !mci && preferWireGuard && it.value == "wireguard" -> 2
                else -> 3
            }
        }, {
            if (irancell) return@compareBy IRANCELL_LADDER.indexOf(it.value).let { i -> if (i < 0) methods.size + it.index else i }
            if (mci) return@compareBy MCI_LADDER.indexOf(it.value).let { i -> if (i < 0) methods.size + it.index else i }
            // Among the rest keep the declared order, but on a filtered link (measured, or H2 just failed
            // here) put WireGuard first and H2 (TCP TLS) after H3: WireGuard answers or fails fastest.
            val filtered = preferWireGuard || "h2" in recentlyFailed
            when {
                filtered && it.value == "wireguard" -> -1
                filtered && it.value == "h2" -> methods.size
                else -> it.index
            }
        })).map { it.value }

    /**
     * WARP inside WARP (gool-classic), tried once, automatically, when the WireGuard handshake passed but
     * no HTTPS came back through it: the pattern of a censor that lets WireGuard handshakes through and
     * then blocks the data (the case Oblivion's and PattNG's "gool" exist for). Never for a reverse relay,
     * which needs MASQUE over HTTP/2, and never twice.
     */
    fun warpInWarpFollowUp(engineId: String, automatic: Boolean, carrier: String, handshakePassed: Boolean,
                           alreadyQueued: Boolean, reverseRelay: Boolean): Boolean =
        engineId == AetherSidecar.ID && automatic && carrier == "wireguard" && handshakePassed &&
            !alreadyQueued && !reverseRelay

    /**
     * Serverless's automatic variant order by firewall (patterniha's v65 subscription): oob is the Hamrah-e-Aval
     * config, fragA/fragB the Irancell ones. Hamrah-e-Aval: oob, fragA, fragB. Irancell and everywhere else: fragA,
     * fragB, oob (unchanged).
     */
    fun serverlessOrder(firewall: com.example.vpn.connectivity.NetworkFirewalls.Firewall): List<String> =
        if (firewall == com.example.vpn.connectivity.NetworkFirewalls.Firewall.MCI) listOf("oob", "fragA", "fragB")
        else listOf("fragA", "fragB", "oob")

    fun candidates(profile: VlessProfile, ipv4Only: Boolean = false,
                   firewall: com.example.vpn.connectivity.NetworkFirewalls.Firewall = com.example.vpn.connectivity.NetworkFirewalls.Firewall.OTHER): List<VlessProfile> {
        val e = ProfileExtras.read(profile)
        val variants = when {
            AetherSidecar.handles(profile) && e.optString("carrier", "auto") == "auto" -> (if (AetherSettings.read(e).let { it.tor in listOf("reverse", "only") || it.psiphon in listOf("reverse", "only") }) listOf("h2") else listOf("h2", "h3", "wireguard")).map {
                profile.copy(extraSettings = org.json.JSONObject(e.toString()).put("carrier", it).toString())
            }
            ServerlessSidecar.handles(profile) && e.optString("variant", "auto") == "auto" -> serverlessOrder(firewall).map {
                profile.copy(extraSettings = org.json.JSONObject(e.toString()).put("variant", it).toString())
            }
            else -> listOf(profile)
        }
        // "auto" may probe IPv6 indefinitely on an IPv4-only mobile network. Restrict only
        // when this session measured the *same physical link* with IPv4 and no IPv6.
        // User-selected ipv6/ipv4 settings remain authoritative.
        return if (ipv4Only && AetherSidecar.handles(profile) && e.optString("family", "auto") == "auto") {
            variants.map { candidate ->
                val options = org.json.JSONObject(candidate.extraSettings).put("family", "ipv4")
                candidate.copy(extraSettings = options.toString())
            }
        } else variants
    }
    /** Methods whose verified endpoint is remembered, by the carrier it is remembered under. */
    private val REMEMBERED = mapOf("h2" to "h2", H2_SPLIT to "h2", CLEAN_H2 to "h2", H2_V6 to "h2",
        "h3" to "h3", CLEAN_H3 to "h3", "wireguard" to "wireguard", WIREGUARD_V6 to "wireguard", CLEAN_WG to "wireguard")

    /**
     * After verified HTTPS over MASQUE HTTP/2 (plain, split, IPv6-first or a remembered gateway), MASQUE HTTP/3 or
     * WireGuard, keep the gateway/endpoint in this network's clean list with its carrier, IP family and the MASQUE
     * fingerprint it used. A scanned one is read from Aether's own last-connection file; relays and follow-up retries
     * (MTU, obfuscation) are skipped (a relay's gateway was reached through a saved server, not over this network).
     */
    internal fun rememberCleanH2(name: String, candidate: VlessProfile, networkKey: String?, since: Long,
                                 record: (String, String, String, String) -> Unit) {
        val carrier = REMEMBERED[name] ?: return
        if (networkKey.isNullOrBlank()) return
        val e = ProfileExtras.read(candidate)
        val split = carrier == "h2" && e.optBoolean(AetherSidecar.SNI_SPLIT)
        val given = if (carrier == "h2") e.optString(AetherSidecar.CLEAN_H2_PEER) else e.optString(AetherSidecar.CLEAN_PEER)
        val peer = given.ifBlank { null } ?: AetherSidecar.lastPeer(carrier, since) ?: return
        val fingerprint = if (carrier == "wireguard") "" else AetherSettings.read(e).fingerprint
        CleanH2PeerMemory.remember(networkKey, peer, split = split, carrier = carrier, fingerprint = fingerprint)
        record(name, "clean-$carrier", "SAVED", "${CleanH2PeerMemory.masked(peer)} remembered for this network " +
            "(${CleanH2PeerMemory.familyOf(peer)}" + (if (fingerprint.isNotBlank()) ", $fingerprint" else "") +
            (if (split) ", split ClientHello" else "") + ")")
    }

    /**
     * The key [BuiltinMethodMemory] keeps an automatic engine's verified method under: the network, the engine,
     * its options (without the one-off rescan flag) and the DNS setting. Null without a network.
     */
    fun methodScope(network: String?, engineId: String, profile: VlessProfile, dnsServer: String): String? {
        if (network == null) return null
        val options = org.json.JSONObject(profile.extraSettings).apply { remove("rescan") }.toString()
        val input = network + "|" + engineId + "|" + options + "|" + dnsServer
        return java.security.MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * An automatic Aether profile carried HTTPS on [network] with these options within [BuiltinMethodMemory.TTL_MS]
     * (any method). A user's own carrier choice is not remembered there, so it never counts.
     */
    fun aetherVerifiedHere(network: String?, profile: VlessProfile, dnsServer: String, now: Long = System.currentTimeMillis()): Boolean {
        if (!AetherSidecar.handles(profile) || ProfileExtras.read(profile).optString("carrier", "auto") != "auto") return false
        return BuiltinMethodMemory.verifiedRecently(methodScope(network, AetherSidecar.ID, profile, dnsServer), now)
    }

    fun start(
        engine: SidecarEngine,
        profile: VlessProfile,
        settings: AppSettings,
        context: SidecarContext,
        checkDns: Boolean = false,
        ipv4Only: Boolean = false,
        preferWireGuard: Boolean = false,
        /** Profile id of a saved server verified on this network, for WARP-through-relay (see [relayed]). */
        relayExitNode: String? = null,
        /** How that saved server carried the verified traffic here (pinned address, stealth alternate). */
        relayExitRoute: BuiltinExitNode.Route = BuiltinExitNode.Route(),
        /** The physical link is metered (mobile data): the whole automatic ladder gets [HEAVY_LADDER_BUDGET_MS]. */
        metered: Boolean = false,
        /** Only WARP through [relayExitNode] (wireguard-relay, then h2-relay): the background retry. */
        relayOnly: Boolean = false,
        /**
         * The user's own run (the Connections page test, the power button on Aether): never refused by
         * [WarpDataPlaneHold] or [AetherLadderGuard], but still recorded by them.
         */
        manual: Boolean = false,
        /** Cloudflare is filtered here (cf=0 / CLOUDFLARE_TLS_PATH_FAILURE): a gateway-scan failure starts [WarpDataPlaneHold]. */
        cloudflareBlocked: Boolean = preferWireGuard,
        /** Radiance (Lantern) can be started as a relay hop for WARP ([lanternRelayEligible]). */
        lanternAvailable: Boolean = false,
        /**
         * A connection that carried verified traffic on this network recently ("Serverless (41 KB/s)"), from
         * [com.example.vpn.smart.VerifiedWinnerBook]. Named in the fast-fail messages.
         */
        verifiedAlternative: String? = null,
        /** The caller falls back to [verifiedAlternative] when this run fails ([skipScansForVerifiedFallback]). */
        handOver: Boolean = false,
        onProgress: (String) -> Unit = {},
        cancelled: () -> Boolean = { false }
    ): Started {
        val attempts = mutableListOf<String>()
        var lastCheck: AccessProbe.Result? = null
        fun checkCancellation() {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            if (cancelled()) throw CancellationException("Connection cancelled")
        }
        fun record(method: String, stage: String, outcome: String, reason: String = "") {
            onProgress("${engine.id}/$method · $stage · $outcome" +
                reason.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty())
            com.example.vpn.diagnostics.events.EventLog.event("builtin.connection",
                attributes = mapOf("engine.name" to engine.id, "method" to method, "stage" to stage,
                    "reason" to reason), result = outcome)
            com.example.xray.XrayLogManager.i("BUILTIN", "${engine.id}/$method · $stage · $outcome" +
                reason.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty())
        }
        val automatic = ProfileExtras.read(profile).let {
            if (engine.id == AetherSidecar.ID) it.optString("carrier", "auto") == "auto"
            else it.optString("variant", "auto") == "auto"
        }
        fun scope(): String? = if (!automatic) null else methodScope(BuiltinMethodMemory.network(), engine.id, profile, settings.dnsServer)
        fun method(candidate: VlessProfile) = ProfileExtras.read(candidate).let {
            if (engine.id == AetherSidecar.ID) {
                if (LanternRelayHop.requested(it)) H2_LANTERN
                else if (it.optBoolean(RELAY_FOLLOW_UP)) it.optString("carrier") + "-relay"
                else if (it.optString(AetherSidecar.CLEAN_H2_PEER).isNotBlank()) CLEAN_H2
                else if (it.optString(AetherSidecar.CLEAN_PEER).isNotBlank() && it.optString("carrier") == "h3") CLEAN_H3
                else if (it.optString(AetherSidecar.CLEAN_PEER).isNotBlank() && it.optString("carrier") == "wireguard") CLEAN_WG
                else if (it.optBoolean(AetherSidecar.SNI_SPLIT) && it.optString("carrier") == "h2") H2_SPLIT
                else if (it.optBoolean(MTU_FOLLOW_UP)) WIREGUARD_MTU
                else if (it.optBoolean(NOIZE_FOLLOW_UP)) WIREGUARD_NOIZE
                else if (it.optBoolean(V6_FIRST) && it.optString("carrier") == "wireguard") WIREGUARD_V6
                else if (it.optBoolean(V6_FIRST) && it.optString("carrier") == "h2") H2_V6 else it.optString("carrier")
            } else it.optString("variant")
        }
        val cacheScope = scope()
        val networkKey = BuiltinMethodMemory.network()
        val onIrancell = engine.id == AetherSidecar.ID && automatic && irancell(networkKey)
        val onMci = engine.id == AetherSidecar.ID && automatic && mci(networkKey)
        // Build 178: Cloudflare filtered here, IPv6 not ruled out, not an operator ladder: WARP over IPv6 goes first.
        val cfV6 = cfV6First(engine.id, automatic, cloudflareBlocked, ipv4Only, irancell = onIrancell, mci = onMci, relayOnly = relayOnly,
            manual = manual)
        if (engine.id == AetherSidecar.ID && automatic && manual && onIrancell && cloudflareBlocked && !relayOnly)
            record("ladder", "ipv6-first", if (cfV6) "START" else "SKIP",
                if (cfV6) "MASQUE H2 over IPv6 first (up to ${v6AttemptMs(manual) / 1000}s), then the IPv4 carriers"
                else "this link has no IPv6 (measured, or no global IPv6 address on the phone's network)")
        // Build 181 (3.2): Cloudflare filtered here: a ≤10 s pre-check picks the TLS name the MASQUE handshakes send
        // (a name the filter lets through); Aether still enforces its MASQUE certificate pins on its own handshake.
        // Only while the VPN service runs (it installs the protector): never from unit tests or without a phone network.
        // Build 181 Phase 5 (C): a Smart (automatic, not the user's own run) start with no fresh clean WARP gateways here
        // scans them (≤8 s) beside the SNI pre-check; the best join the remembered clean peers below.
        val scanLines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val warpScan = if (engine.id == AetherSidecar.ID && automatic && !manual && !relayOnly && AetherSniProbe.socketProtector != null &&
                (cloudflareBlocked || onIrancell) && com.example.vpn.cleanip.SmartIpScan.needsScan(networkKey, com.example.vpn.cleanip.SmartIpScan.Kind.WARP))
            java.util.concurrent.FutureTask(java.util.concurrent.Callable {
                com.example.vpn.cleanip.SmartIpScan.scan(networkKey, com.example.vpn.smartsni.SmartSni.pick(networkKey),
                    setOf(com.example.vpn.cleanip.SmartIpScan.Kind.WARP)) { scanLines += it }
            }).also { Thread(it, "smart-ip-scan").apply { isDaemon = true }.start() } else null
        val masqueSni = if (AetherSniProbe.socketProtector != null && masqueSniPrecheck(engine.id, relayOnly, cloudflareBlocked, onIrancell,
                ProfileExtras.read(profile).optString("carrier", "auto")))
            AetherSniProbe.precheck(networkKey) { line -> record("ladder", "sni-precheck", "INFO", line) } else null
        if (warpScan != null) {
            runCatching { warpScan.get(com.example.vpn.cleanip.SmartIpScan.BUDGET_MS + 1_000L, java.util.concurrent.TimeUnit.MILLISECONDS) }
                .onFailure { warpScan.cancel(true); record("ladder", "clean-ip-scan", "SKIP", "no result within the scan budget") }
            synchronized(scanLines) { scanLines.toList() }.forEach { record("ladder", "clean-ip-scan", "INFO", it) }
        }
        // The IPv6 variants are made before the Irancell IPv4 pin (which only touches a family left on Auto).
        val carrierChoices = candidates(profile, ipv4Only = ipv4Only,
            firewall = com.example.vpn.connectivity.NetworkFirewalls.detect(networkKey).takeIf { automatic && ServerlessSidecar.handles(profile) }
                ?: com.example.vpn.connectivity.NetworkFirewalls.Firewall.OTHER)
            .let { operatorVariants(it, irancell = onIrancell, mci = onMci, cfV6 = cfV6) }
            .let { list -> if (onIrancell) list.map(::irancellIpv4) else list }
            .let { list -> if (masqueSni != null) list.map { withMasqueSni(it, masqueSni) } else list }
        val cleanPeers = if (engine.id == AetherSidecar.ID && automatic) CleanH2PeerMemory.peers(networkKey).let { saved ->
            // Smart clean-IP scan results (answered a ClientHello, not yet verified) come after the verified ones.
            if (!com.example.vpn.smartsni.SmartSniSettings.cleanIpScan) saved
            else saved + com.example.vpn.cleanip.SmartIpScan.warpPeers(networkKey).filter { a -> saved.none { it.address == a } }
                .take(2).map { CleanH2PeerMemory.Peer(it, at = System.currentTimeMillis()) }
        } else emptyList()
        val cleanChoices = if (engine.id == AetherSidecar.ID && automatic)
            cleanH2Candidates(carrierChoices, cleanPeers, ipv4Only) + cleanUdpCandidates(carrierChoices, cleanPeers, ipv4Only) else emptyList()
        val baseChoices = cleanChoices + carrierChoices
        // The obfuscated WireGuard retry is not one of the declared carriers, but once it carried HTTPS on
        // this network it is remembered and tried first next time instead of the plain carrier.
        val noizeChoice = if (engine.id == AetherSidecar.ID && automatic &&
            baseChoices.any { method(it) == "wireguard" } &&
            AetherSettings.read(ProfileExtras.read(profile)).noize == "auto")
            baseChoices.first { method(it) == "wireguard" }.let(::obfuscatedWireGuard) else null
        val mtuChoice = if (engine.id == AetherSidecar.ID && automatic &&
            baseChoices.any { method(it) == "wireguard" } &&
            AetherSettings.read(ProfileExtras.read(profile)).mtu.isBlank())
            baseChoices.first { method(it) == "wireguard" }.let(::reducedMtuWireGuard) else null
        val relays = if (relayEligible(engine.id, automatic, AetherSettings.read(ProfileExtras.read(profile)), relayExitNode))
            listOf("wireguard", "h2").filter { c -> baseChoices.any { method(it) == c } }
                .map { c -> relayed(baseChoices.first { method(it) == c }, c, relayExitNode!!, relayExitRoute) } else emptyList()
        val followUps = listOfNotNull(noizeChoice, mtuChoice) + relays
        val preferred = BuiltinMethodMemory.preferred(cacheScope, baseChoices.map(::method) + followUps.map(::method))
        val choices = followUps.firstOrNull { method(it) == preferred }?.let { listOf(it) + baseChoices } ?: baseChoices
        // Keep a previously HTTPS-verified method first. Without such evidence, try WG
        // before long MASQUE gateway scans when fresh physical-link probes show that
        // ordinary Cloudflare TLS is unreachable but UDP is answering.
        val recentlyFailed = BuiltinMethodMemory.recentlyFailed(cacheScope)
        val rank = order(choices.map(::method), preferred, preferWireGuard, recentlyFailed, irancell = onIrancell, mci = onMci, cfV6 = cfV6)
        val reverseRelay = engine.id == AetherSidecar.ID &&
            AetherSettings.read(ProfileExtras.read(profile)).let { it.tor == "reverse" || it.psiphon == "reverse" }
        // Irancell: restrict this ladder to HTTP/2 with a bounded extra variant for manual runs.
        val irancellHttp2 = IrancellWarp.applies(engine.id, automatic, onIrancell, relayOnly, reverseRelay)
        // Build 151: WARP's data plane is held on this network (gateway scan failed with Cloudflare filtered), or the same
        // ladder ran here under a minute ago: an automatic run stops at once so the search goes on.
        val ladderKind = if (relayOnly) "relay" else "ladder"
        val guarded = engine.id == AetherSidecar.ID && automatic
        // WARP through Lantern failed on this network recently (LanternRelayMemory): not queued again until it expires.
        val lanternEligible = lanternRelayEligible(engine.id, automatic, manual, cloudflareBlocked, lanternAvailable,
            AetherSettings.read(ProfileExtras.read(profile)), relayExitNode, relayOnly)
        val failedHop = if (lanternEligible) LanternRelayMemory.failedAt(networkKey) else null
        // Build 174: the user's own pick (lanternEligible implies it) tries the hop again a few minutes after a failure.
        val lanternRetry = failedHop != null && LanternRelayMemory.manualRetryDue(networkKey)
        val lanternFailedAt = failedHop.takeUnless { lanternRetry }
        if (lanternFailedAt != null) record(H2_LANTERN, "queue", "SKIP", LanternRelayMemory.describe(networkKey))
        if (lanternRetry) record(H2_LANTERN, "queue", "START", "your own Aether pick: WARP through Lantern failed here " +
            "${(System.currentTimeMillis() - failedHop!!) / 60_000} min ago and is tried once more")
        val lanternChoice = if (lanternEligible && lanternFailedAt == null)
            carrierChoices.firstOrNull { ProfileExtras.read(it).let { e -> e.optString("carrier") == "h2" && !e.optBoolean(AetherSidecar.SNI_SPLIT) } }
                ?.let(::lanternRelayed) else null
        val held = guarded && WarpDataPlaneHold.blocked(networkKey)
        if (held && !manual) {
            val why = WarpDataPlaneHold.describe(networkKey)
            record(ladderKind, "warp-data", "SKIP", why)
            throw NoVerifiedPath(listOf("$ladderKind: $why"), null)
        }
        // Build 171: Cloudflare filtered here (cf_quick) and another connection verified on this same network recently:
        // an automatic run hands over at once instead of up to three gateway scans (≈190 s) that fail the same way.
        if (skipScansForVerifiedFallback(engine.id, automatic, manual, relayOnly, cloudflareBlocked, handOver && verifiedAlternative != null)) {
            val why = verifiedFallbackMessage(verifiedAlternative!!)
            record(ladderKind, "warp-data", "SKIP", why)
            throw NoVerifiedPath(listOf("$ladderKind: $why"), null)
        }
        // The user's own pick of MASQUE over HTTP/2 (TLS to Cloudflare, what cf_quick saw filtered) while WARP's data plane
        // is held here: it used to run its full ~180 s scan (19:01-19:04Z in the build 171 report). It stops at once and
        // names what works here; it never switches by itself. UDP carriers (H3, WireGuard) still run.
        // The explicit "Aether over Lantern" pick dials WARP through Lantern: the filtered direct path is not its path.
        val lanternPick = engine.id == AetherSidecar.ID && LanternRelayHop.requested(ProfileExtras.read(profile))
        // Build 179 device test (N3): the user's own pick always runs; the hold only steers Smart Connect and the relays.
        val heldAtPick = if (manual) WarpDataPlaneHold.heldAt(networkKey) else null
        if (heldAtPick != null) record(ladderKind, "warp-data", "NOTE", manualPickDespiteHold(System.currentTimeMillis() - heldAtPick))
        if (!manual && !lanternPick && explicitH2FastFail(engine.id, automatic, relayOnly, cloudflareBlocked,
                carrier = ProfileExtras.read(profile).optString("carrier"), held = WarpDataPlaneHold.blocked(networkKey))) {
            val why = WarpDataPlaneHold.fastFailMessage(networkKey, verified = verifiedAlternative)
            record(method(profile), "warp-data", "SKIP", why)
            throw NoVerifiedPath(listOf(why), null)
        }
        // Build 170 (Irancell, cf_quick = filtered): the user's own Aether run repeated both 45 s gateway scans three
        // times inside the hold, each ending TLS_HANDSHAKE_FAILED. With Cloudflare still filtered the direct scans are
        // skipped: WARP goes through Lantern when it can, otherwise the run fails at once and names what works here.
        val lanternOnly = holdRefuses(manual) && held && manual && cloudflareBlocked && !relayOnly && lanternChoice != null
        if (holdRefuses(manual) && held && manual && cloudflareBlocked && !relayOnly && lanternChoice == null) {
            val why = WarpDataPlaneHold.fastFailMessage(networkKey, lanternFailedAt = lanternFailedAt, verified = verifiedAlternative)
            record(ladderKind, "warp-data", "SKIP", why)
            throw NoVerifiedPath(listOf(why), null)
        }
        if (lanternOnly) record(ladderKind, "warp-data", "SKIP", WarpDataPlaneHold.describe(networkKey) +
            "; direct gateway scans skipped, trying WARP through Lantern (Radiance) as the relay hop")
        if (guarded) AetherLadderGuard.begin(networkKey, ladderKind, enforce = !manual)?.let { since ->
            val why = "the same Aether $ladderKind ran on this network ${since / 1000}s ago; not repeated within " +
                "${AetherLadderGuard.MIN_GAP_MS / 1000}s"
            record(ladderKind, "queue", "SKIP", why)
            throw NoVerifiedPath(listOf("$ladderKind: $why"), null)
        }
        if (onMci && !relayOnly) record("ladder", "queue", "START", "Hamrah-e-Aval: " +
            (if (choices.any { method(it) == WIREGUARD_V6 }) "WireGuard over IPv6, then MASQUE H2 over IPv6, then " else "") +
            "MASQUE H2 with the full ClientHello, then H2 with the ClientHello split at the SNI, then H3, then WireGuard")
        if (cleanChoices.isNotEmpty() && !relayOnly) record(CLEAN_H2, "queue", "START", "${cleanPeers.size} clean gateway(s)/endpoint(s) remembered on this network; trying " +
            cleanChoices.joinToString(", ") { c -> ProfileExtras.read(c).let { e -> method(c) + " " + CleanH2PeerMemory.masked(
                e.optString(AetherSidecar.CLEAN_H2_PEER).ifBlank { e.optString(AetherSidecar.CLEAN_PEER) }) } } +
            " before any scan" + (cleanPeers.firstOrNull { it.carrier == "h2" }?.rttMs?.let { " (prewarm: $it ms)" }.orEmpty()))
        val ordered = choices.sortedBy { rank.indexOf(method(it)) }
        val irancellPlan = if (irancellHttp2) IrancellWarp.plan(ordered.map(::method), preferred, manual, v6First = cfV6)
            .map { name -> IrancellWarp.forAttempt(ordered.first { method(it) == name }, manual) } else emptyList()
        val cfManualBudget = cfFilteredManualBudgetMs(engine.id, manual, cloudflareBlocked, relayOnly).takeIf { !lanternOnly && !lanternPick }
            // Build 181 (A2): the IPv6 attempt shares the short check's window, so the pick keeps its 60 s promise.
        if (cfManualBudget != null && !lanternOnly) {
            record(ladderKind, "cf-filtered", "WARN", cfFilteredManualNotice(cfManualBudget))
            com.example.vpn.diagnostics.EngineStages.record(engine.id, "cloudflare filtered", cfFilteredManualNotice(cfManualBudget))
        }
        val irancellBudgetMs = (if (manual) irancellPlan.sumOf(IrancellWarp::manualAttemptMs) else IrancellWarp.BUDGET_MS)
            .let { b -> cfManualBudget?.let { minOf(b, it) } ?: b }
        if (irancellHttp2 && irancellPlan.isEmpty()) attempts += "irancell: no MASQUE HTTP/2 carrier available"
        if (irancellHttp2 && !lanternOnly) record("ladder", "queue", "START",
            "Irancell MASQUE HTTP/2: ${irancellPlan.joinToString(", ") { method(it) }.ifEmpty { "none available" }}; " +
                "${irancellBudgetMs / 1000}s readiness budget; account API racing enabled")
        // The user's own Aether pick: the connect deadline waits out the budget this line promises (build 176: cut at 60 s
        // inside a 220 s ladder). Smart Connect keeps its own limit.
        if (irancellHttp2 && !lanternOnly && manual && irancellPlan.isNotEmpty()) {
            com.example.vpn.EngineBudgetHint.announce(irancellBudgetMs)
            // The stage says the same number the deadline now waits for (build 177: stuck on "tunnel created" for 93 s).
            com.example.vpn.diagnostics.EngineStages.record(engine.id, "engine starting",
                "Irancell carrier ladder (${irancellPlan.joinToString(", ") { method(it) }}) · ready within ${irancellBudgetMs / 1000}s")
        }
        val failedRegistrationKinds = mutableSetOf<String>()
        // WARP registration failed on this network within WarpRegistrationMemory.HOLD_MS: a carrier that would need a
        // new registration is skipped at once (one with its own saved identity still runs).
        val registrationHeld = engine.id == AetherSidecar.ID && WarpRegistrationMemory.blocked(networkKey)
        if (registrationHeld) {
            failedRegistrationKinds += AetherApiBridge.API_UNAVAILABLE
            record("ladder", "registration", "SKIP", WarpRegistrationMemory.describe(networkKey) +
                "; carriers without a saved identity are not started")
        }
        val recheckOnly = !irancellHttp2 && engine.id == AetherSidecar.ID && automatic &&
            quickRecheckOnly(ordered.map(::method).filter { it !in CLEAN_METHODS }, preferred, recentlyFailed,
                warpDataBlocked = BuiltinMethodMemory.warpDataBlocked(cacheScope))
        var handshakeWithoutData = false
        // A remembered gateway that fails is dropped from this network's clean list instead of marking the method
        // failed, so the next remembered gateway is not pushed to the back.
        fun failed(name: String, candidate: VlessProfile) {
            // A fingerprint picked for Auto failed here: the next attempt on this network moves on to the next one.
            AetherFingerprintBook.record(networkKey, candidate, verified = false)
            // Build 181: a picked MASQUE name that failed (another site's certificate breaks the pin) is skipped next time.
            ProfileExtras.read(candidate).optString(AetherSidecar.MASQUE_SNI).takeIf { it.isNotBlank() }?.let {
                AetherSniProbe.recordFailure(networkKey, it)
                record(name, "sni", "FORGET", "MASQUE name $it failed here; the next pre-check skips it")
            }
            if (name in CLEAN_METHODS) {
                val e = ProfileExtras.read(candidate)
                val peer = e.optString(AetherSidecar.CLEAN_H2_PEER).ifBlank { e.optString(AetherSidecar.CLEAN_PEER) }
                val carrier = e.optString("carrier")
                CleanH2PeerMemory.forget(networkKey, peer, carrier)
                com.example.vpn.cleanip.SmartIpScan.drop(networkKey, peer)
                record(name, "clean-$carrier", "FORGET", "${CleanH2PeerMemory.masked(peer)} no longer carries $carrier here")
            } else BuiltinMethodMemory.recordFailure(cacheScope, name)
        }
        // WARP data blocked here recently: go straight through the verified saved server when there is one,
        // otherwise one quick recheck of the first method.
        if (relayOnly && relays.isEmpty()) throw NoVerifiedPath(listOf("relay: no saved server to carry WARP through"), null)
        val queue = ArrayDeque(when {
            lanternOnly -> listOf(lanternChoice!!)
            irancellHttp2 -> irancellPlan
            relayOnly -> relays
            recheckOnly && relays.isNotEmpty() -> relays
            recheckOnly -> ordered.take(1)
            else -> ordered
        })
        // Irancell: WARP through a saved server is the background relay's job, after a saved server is verified.
        var relayQueued = irancellHttp2 || relayOnly || recheckOnly && relays.isNotEmpty() || preferred in setOf(WIREGUARD_RELAY, H2_RELAY)
        if (recheckOnly && !relayOnly) ordered.filter { it !in queue }.forEach {
            record(method(it), "queue", "SKIP", if (relayQueued) "WARP data was blocked on this network recently; trying WARP through a verified saved server"
                else "WARP data was blocked on this network recently; one quick recheck of ${method(ordered.first())} only")
        }
        var warpInWarpQueued = recheckOnly || irancellHttp2
        var lanternQueued = lanternOnly
        var noizeQueued = recheckOnly || irancellHttp2 || preferred == WIREGUARD_NOIZE
        var mtuQueued = recheckOnly || irancellHttp2 || preferred == WIREGUARD_MTU
        val ladderBudget = effectiveLadderBudgetMs(ladderBudgetMs(engine.id, automatic, metered, onIrancell, preferWireGuard),
            cfManualBudget)
        val ladderStartedAt = System.currentTimeMillis()
        var ladderStopped = false
        var warpBlockFastPath = false
        fun queueRelays(reason: String) {
            if (relayQueued || relays.isEmpty()) return
            relayQueued = true
            relays.forEach { record(method(it), "queue", "START", reason) }
            queue.addAll(relays)
        }
        val options = AetherSettings.read(ProfileExtras.read(profile))
        // Build 167: nothing remembered for Aether here and Cloudflare not measured as filtered: a MASQUE carrier that
        // stops making startup progress is given up after AetherStartupStall's short window, not the full one.
        fun stallGuarded(name: String, carrier: String, extras: org.json.JSONObject) = AetherStartupStall.applies(
            engine.id, automatic, carrier, name, irancellLadder = irancellHttp2, cloudflareFiltered = preferWireGuard,
            rememberedGateway = cleanPeers.isNotEmpty(), verifiedHere = preferred != null,
            relay = relayOnly || extras.optBoolean(RELAY_FOLLOW_UP))
        if (!relayOnly && queue.any { c -> ProfileExtras.read(c).let { stallGuarded(method(c), it.optString("carrier"), it) } })
            record("ladder", "queue", "START", AetherStartupStall.SUMMARY)
        var deferRelay = false
        var raced = false
        /**
         * The direct carriers are done (or out of budget): WARP through Lantern once, when eligible. Not after the
         * WireGuard defer, which hands the session to saved servers at once. False when nothing was queued.
         */
        fun queueLantern(reason: String): Boolean {
            if (lanternQueued || lanternChoice == null || deferRelay) return false
            lanternQueued = true
            record(H2_LANTERN, "queue", "START", reason)
            queue.addLast(lanternChoice)
            return true
        }
        /** HTTPS through the engine's SOCKS port, rechecked after 6 s for Aether on the same transport generation. */
        fun verify(name: String, process: RunningEngine, launch: SidecarLaunch, ctx: SidecarContext,
                   reserveForNextRelay: Boolean = false, check: () -> Unit): AccessProbe.Result {
            val endpoint = Socks5.Endpoint("127.0.0.1", ctx.socksPort,
                ctx.socksUser.takeIf { launch.socksAuth }, ctx.socksPass.takeIf { launch.socksAuth })
            val generation = process.transportGeneration
            // Build 179 device test (N12): Gool Classic passed startup at 47 s and its HTTPS check ran to 74.6 s against a
            // 60 s limit. The check gets what is left of the run's budget (at least VERIFY_MIN_MS), never more.
            val left = ladderBudget?.let { it - (System.currentTimeMillis() - ladderStartedAt) }
            val first = boundedCheck(verifyWindowMs(left)) {
                AccessProbe.check(endpoint, checkDns, diagnoseAddress = engine.id == AetherSidecar.ID && !reserveForNextRelay)
            }
            check()
            return if (engine.id == AetherSidecar.ID && first.traffic) {
                record(name, "https", "RECHECK", "checking that the same transport keeps carrying traffic")
                Thread.sleep(6_000)
                check()
                if (process.isAlive && process.transportGeneration == generation) {
                    val second = AccessProbe.check(endpoint, checkDns = false)
                    if (process.transportGeneration == generation) second.copy(dns = first.dns)
                    else first.copy(traffic = false, latencyMs = null, failure = "Aether transport restarted during traffic verification")
                } else first.copy(traffic = false, latencyMs = null, failure = "Aether transport stopped or restarted before traffic recheck")
            } else first
        }
        /** A direct carrier ran and carried no HTTPS: queue its follow-ups (or the WARP-data-blocked fast path). */
        fun onNoData(name: String, candidate: VlessProfile, extras: org.json.JSONObject, carrier: String) {
            // Hamrah-e-Aval's IPv6-first WireGuard is a probe ahead of the known MCI path: a handshake without data on
            // IPv6 says nothing about H2 over IPv6 or the IPv4 ladder, so it neither ends the ladder nor queues retries.
            if (name == WIREGUARD_V6) {
                record(name, "queue", "NEXT", "WireGuard over IPv6 carried no data here; continuing with MASQUE H2 over IPv6")
                return
            }
            // Irancell / Cloudflare-filtered: handshake without data is the WARP data block. Remember it now (the
            // next connect goes straight to the relay) and skip the full scans that fail the same way.
            if (!warpBlockFastPath && warpDataBlockedFastPath(engine.id, automatic, carrier, onIrancell, preferWireGuard, reverseRelay)) {
                warpBlockFastPath = true
                BuiltinMethodMemory.recordWarpDataBlocked(cacheScope)
                // Not for the user's own pick: nothing follows it, so Aether keeps trying its own carriers (ManualEnginePick).
                if (relays.isEmpty() && com.example.vpn.ManualEnginePick.mayLeaveEngine(manual) &&
                    deferRelayToSavedServer(engine.id, automatic, options, relayExitNode, warpDataBlocked = true)) {
                    // No saved server verified here yet: stop Aether now (no MTU retry, no other carrier) so the session
                    // reaches saved servers at once; WARP through the first one verified is retried in the background.
                    deferRelay = true
                    val skipped = queue.map { method(it) }
                    queue.clear()
                    noizeQueued = true; warpInWarpQueued = true; mtuQueued = true
                    record("ladder", "relay", "DEFER", "WireGuard handshake passed but no data came back on this " +
                        (if (onIrancell) "Irancell" else "Cloudflare-filtered") + " link and no saved server is verified on this network yet: " +
                        "stopping Aether now" + (if (skipped.isNotEmpty()) " (skipping " + skipped.joinToString(", ") + ")" else "") +
                        "; saved servers next, then WARP through the first verified one in the background")
                    return
                }
                val dropped = queue.filterNot { ProfileExtras.read(it).optBoolean(RELAY_FOLLOW_UP) }
                queue.removeAll(dropped)
                val mtu = if (reducedMtuWireGuardFollowUp(engine.id, automatic, carrier, AetherSettings.read(extras).mtu,
                        alreadyQueued = mtuQueued, reverseRelay = reverseRelay)) reducedMtuWireGuard(candidate) else null
                noizeQueued = true; warpInWarpQueued = true; mtuQueued = true
                record(name, "queue", "FAST", "WireGuard handshake passed but no data came back on this " +
                    (if (onIrancell) "Irancell" else "Cloudflare-filtered") + " link: WARP data marked blocked here; " +
                    (if (relays.isNotEmpty()) "trying WARP through a verified saved server" else "no verified saved server for a relay") +
                    (if (mtu != null) ", then one MTU ${AetherSettings.MTU_RETRY} retry" else "") +
                    (if (dropped.isNotEmpty()) "; skipping " + dropped.joinToString(", ") { method(it) } else ""))
                queueRelays("direct WARP carried no data here; trying WARP through a verified saved server")
                mtu?.let { queue.addLast(it) }
                return
            }
            // Before WARP-in-WARP: the same WireGuard identity with the strongest junk-packet profile and a
            // fresh endpoint/port scan. It costs one more WireGuard window and is the cheaper fix when the
            // censor classifies the flow from its first packets.
            // Queue order after a plain WireGuard failure: reduced MTU first (same endpoint, quick), then
            // junk-packet obfuscation with a fresh scan, then the other carriers, WARP-in-WARP last.
            val obfuscate = obfuscatedWireGuardFollowUp(engine.id, automatic, carrier,
                    AetherSettings.read(extras).noize, alreadyQueued = noizeQueued, reverseRelay = reverseRelay)
            if (obfuscate) {
                noizeQueued = true
                record(WIREGUARD_NOIZE, "queue", "START",
                    "WireGuard handshake passed but no data came back; retrying with junk-packet obfuscation and a fresh endpoint scan")
                queue.addFirst(obfuscatedWireGuard(candidate))
            }
            if (reducedMtuWireGuardFollowUp(engine.id, automatic, carrier, AetherSettings.read(extras).mtu,
                    alreadyQueued = mtuQueued, reverseRelay = reverseRelay)) {
                mtuQueued = true
                record(WIREGUARD_MTU, "queue", "START",
                    "WireGuard handshake passed but no data came back; retrying with MTU ${AetherSettings.MTU_RETRY}")
                queue.addFirst(reducedMtuWireGuard(candidate))
            }
            if (warpInWarpFollowUp(engine.id, automatic, carrier, handshakePassed = true,
                    alreadyQueued = warpInWarpQueued || choices.any { method(it) == "wiw" }, reverseRelay = reverseRelay)) {
                warpInWarpQueued = true
                record("wiw", "queue", "START", "WireGuard handshake passed but no data came back; trying WARP inside WARP")
                queue.addLast(candidate.copy(extraSettings =
                    org.json.JSONObject(candidate.extraSettings).put("carrier", "wiw").apply { remove(NOIZE_FOLLOW_UP); remove(MTU_FOLLOW_UP) }.toString()))
            }
        }
        /**
         * Irancell: H3 and WireGuard at once ([raceEligible]). WireGuard runs on its own loopback port and work folder;
         * the winner is returned with its context. A WireGuard handshake without data does not end the race: H3 keeps
         * its chance until it finishes or the deadline passes. Only without a winner are both recorded as failed and
         * the WireGuard handshake without data takes the WARP-data-blocked path ([onNoData]).
         */
        fun race(h3: VlessProfile, wg: VlessProfile): Started? {
            val deadline = ladderBudget?.let { maxOf(LADDER_MIN_ATTEMPT_MS, it - (System.currentTimeMillis() - ladderStartedAt)) }
                ?: RACE_DEFAULT_DEADLINE_MS
            val deadlineAt = System.currentTimeMillis() + deadline
            val wgContext = context.copy(workDir = java.io.File(context.workDir, "race-wireguard").apply { mkdirs() },
                socksPort = Sidecars.freeLoopbackPort())
            val raceStartedAt = System.currentTimeMillis()
            record("ladder", "race", "START h3+wireguard", "first HTTPS-verified carrier wins, the other is stopped; ${deadline / 1000}s deadline")
            fun leg(lane: FirstVerifiedRace.Lane, candidate: VlessProfile, ctx: SidecarContext): RaceLeg {
                val name = method(candidate)
                val carrier = ProfileExtras.read(candidate).optString("carrier")
                var running: RunningEngine? = null
                var stage = "configuration preparation failed"
                fun check() {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    if (lane.aborted || cancelled()) throw CancellationException("race ended")
                }
                try {
                    record(name, "prepare", "START")
                    val launch = engine.prepare(candidate, settings, ctx)
                    check()
                    stage = "engine process could not start"
                    val process = EngineReaper.track(engine.startPrepared(candidate, settings, launch, ctx)
                        ?: SidecarProcess.start(engine.id, launch, ctx.workDir, ctx.socksPort))
                    running = process
                    lane.onAbort { process.stop() }
                    check()
                    stage = "local proxy did not become ready"
                    val window = readinessBudgetMs(engine.id, automatic, preferWireGuard, carrier, launch.readyTimeoutMs,
                        failedHere = name in recentlyFailed)
                    val wait = minOf(window, maxOf(1_000L, deadlineAt - System.currentTimeMillis() - RACE_CHECK_RESERVE_MS))
                    if (!process.awaitReady(wait)) {
                        check()
                        val reason = process.startupFailure ?: stage
                        record(name, "startup", "FAIL", reason)
                        return RaceLeg(name, candidate, ctx, null, launch, null, ready = false, reason = reason)
                    }
                    check()
                    record(name, "startup", "PASS")
                    stage = "access check failed"
                    val result = verify(name, process, launch, ctx, check = ::check)
                    check()
                    if (result.traffic && process.isAlive) {
                        running = null
                        return RaceLeg(name, candidate, ctx, process, launch, result, ready = true, reason = null)
                    }
                    val reason = result.failure ?: "engine stopped during check"
                    record(name, "https", "FAIL", reason)
                    return RaceLeg(name, candidate, ctx, null, launch, result, ready = true, reason = reason)
                } catch (e: Exception) {
                    if (e is InterruptedException || e is CancellationException)
                        return RaceLeg(name, candidate, ctx, null, null, null, ready = false, reason = "stopped by the race")
                    val reason = startFailureReason(engine.id, e, stage)
                    record(name, "startup", "FAIL", reason)
                    return RaceLeg(name, candidate, ctx, null, null, null, ready = false, reason = reason)
                } finally {
                    running?.stop()
                }
            }
            val h3Name = method(h3)
            val wgName = method(wg)
            val race = FirstVerifiedRace<RaceLeg> { it.process?.stop() }
            val outcome = race.claim(race.run(
                listOf(h3Name to { lane: FirstVerifiedRace.Lane -> leg(lane, h3, context) },
                    wgName to { lane: FirstVerifiedRace.Lane -> leg(lane, wg, wgContext) }),
                deadlineMs = deadline,
                verified = { it.process != null },
                // WireGuard handshook and carried nothing on this carrier: WARP data is blocked. Recorded as the
                // loser; H3 keeps running until it finishes or the deadline (onNoData runs only after the race).
                decisive = { it.name == wgName && it.ready && it.check != null &&
                    warpDataBlockedFastPath(engine.id, automatic, "wireguard", onIrancell, preferWireGuard, reverseRelay) },
                cancelled = { Thread.currentThread().isInterrupted || cancelled() })) {
                // Disconnect or a newer attempt after a winner came back: stop the winner's engine, then rethrow.
                checkCancellation()
            }
            outcome.value?.let { w ->
                record("ladder", "race", "WIN ${w.name}", outcome.unfinished.takeIf { it.isNotEmpty() }
                    ?.let { "stopped " + it.joinToString(", ") }.orEmpty())
                record(w.name, "https", "PASS")
                AetherFingerprintBook.record(networkKey, w.candidate, verified = true)
                ProfileExtras.read(w.candidate).optString(AetherSidecar.MASQUE_SNI).takeIf { it.isNotBlank() }
                    ?.let { AetherSniProbe.recordSuccess(networkKey, it) }
                if (engine.id == AetherSidecar.ID && automatic) rememberCleanH2(w.name, w.candidate, networkKey, raceStartedAt, ::record)
                if (engine.id == AetherSidecar.ID) WarpRegistrationMemory.clear(networkKey)
                if (scope() == cacheScope) {
                    BuiltinMethodMemory.record(cacheScope, w.name)
                    BuiltinMethodMemory.clearFailure(cacheScope, w.name)
                    BuiltinMethodMemory.clearWarpDataBlocked(cacheScope)
                }
                return Started(w.process!!, w.launch!!, w.candidate, w.check!!, context = w.context.takeIf { it !== context })
            }
            record("ladder", "race", "NONE", when {
                outcome.decidedBy != null -> "WireGuard handshake passed but no data came back; " +
                    (if (outcome.timedOut) "${deadline / 1000}s deadline reached" else "H3 carried no HTTPS either") +
                    outcome.unfinished.takeIf { it.isNotEmpty() }?.let { "; stopped " + it.joinToString(", ") }.orEmpty()
                outcome.timedOut -> "${deadline / 1000}s deadline reached" +
                    outcome.unfinished.takeIf { it.isNotEmpty() }?.let { "; stopped " + it.joinToString(", ") }.orEmpty()
                else -> "neither carried HTTPS data"
            })
            for ((m, racedCandidate) in listOf(h3Name to h3, wgName to wg)) {
                val r = outcome.losers[m]
                val reason = r?.reason ?: "race deadline reached"
                attempts += "$m: $reason"
                if (r != null || outcome.timedOut) failed(m, racedCandidate)
                if (m == preferred) BuiltinMethodMemory.forget(cacheScope)
                r?.check?.takeUnless { it.traffic }?.let { lastCheck = it }
                if (r?.reason != null && AetherApiBridge.registrationFailed(r.reason)) {
                    failedRegistrationKinds += if (AetherApiBridge.bootstrapBlocked(r.reason))
                        AetherApiBridge.API_UNAVAILABLE else AetherApiBridge.keyKind(racedCandidate)
                }
            }
            val wgLeg = outcome.losers[wgName]
            if (wgLeg != null && wgLeg.ready && wgLeg.check != null) {
                handshakeWithoutData = true
                onNoData(wgName, wg, ProfileExtras.read(wg), "wireguard")
            }
            return null
        }
        while (true) {
            if (!irancellHttp2 && ladderBudget != null && !ladderStopped && queue.isNotEmpty() &&
                !ProfileExtras.read(queue.first()).optBoolean(RELAY_FOLLOW_UP) &&
                ladderBudget - (System.currentTimeMillis() - ladderStartedAt) < LADDER_MIN_ATTEMPT_MS) {
                ladderStopped = true
                val dropped = queue.filterNot { ProfileExtras.read(it).optBoolean(RELAY_FOLLOW_UP) }
                queue.removeAll(dropped)
                record("ladder", "budget", "STOP", budgetStopText(ladderBudget, dropped.map { method(it) }, manual))
                queueRelays("Aether budget used; trying WARP through a verified saved server once")
                warpInWarpQueued = true; noizeQueued = true; mtuQueued = true
            }
            if (queue.isEmpty()) {
                // Every direct carrier failed: WARP through the saved server that works on this network.
                if (!relayQueued && relays.isNotEmpty()) {
                    relayQueued = true
                    relays.forEach { record(method(it), "queue", "START", "direct WARP carried no data here; trying WARP through a verified saved server") }
                    queue.addAll(relays)
                } else if (!queueLantern(LANTERN_QUEUE_REASON)) break
            }
            // The Irancell readiness budget covers the direct carriers; the Lantern attempt has its own window.
            if (irancellHttp2 && System.currentTimeMillis() - ladderStartedAt >= irancellBudgetMs &&
                !LanternRelayHop.requested(ProfileExtras.read(queue.first()))) {
                queue.clear()
                if (!queueLantern(LANTERN_QUEUE_REASON)) break
            }
            // A MASQUE fingerprint left at Auto gets this network's pick (AetherFingerprintBook); a user's own is kept.
            val candidate = queue.removeFirst().let { if (engine.id == AetherSidecar.ID) AetherFingerprintBook.resolve(it, networkKey) else it }
            checkCancellation()
            val extras = ProfileExtras.read(candidate)
            val name = method(candidate)
            AetherFingerprintBook.picked(candidate)?.let { record(name, "tls", "START", AetherFingerprintBook.describe(networkKey, it)) }
            // Native carrier name: budgets and follow-ups are about the transport, not the label.
            val carrier = if (engine.id == AetherSidecar.ID) extras.optString("carrier") else name
            val raceWith = if (name == "h3") queue.firstOrNull { method(it) == "wireguard" } else null
            if (raceWith != null && raceEligible(engine.id, automatic, onIrancell, name, queue.map { method(it) }, recheckOnly,
                    relayOnly, reverseRelay, identitiesStored = AetherApiBridge.hasStoredKeys(candidate) &&
                        AetherApiBridge.hasStoredKeys(raceWith), alreadyRaced = raced)) {
                raced = true
                queue.remove(raceWith)
                race(candidate, raceWith)?.let { return it }
                continue
            }
            if (engine.id == AetherSidecar.ID && AetherApiBridge.skipAfterBootstrapFailure(candidate, failedRegistrationKinds)) {
                record(name, "registration", "SKIP", "account API unavailable; no independent stored identity")
                attempts += "$name: skipped (WARP API registration unavailable on this network, no saved identity)"
                continue
            }
            var running: RunningEngine? = null
            var stage = "configuration preparation failed"
            var stallCut = false
            val attemptStartedAt = System.currentTimeMillis()
            val attemptStartedNanos = System.nanoTime()
            val reserveForNextRelay = relayOnly && metered && queue.any {
                !AetherApiBridge.skipAfterBootstrapFailure(it, failedRegistrationKinds)
            }
            try {
                val scan = if (irancellHttp2 && name !in CLEAN_METHODS) AetherSettings.read(extras).scan else null
                record(name, "prepare", "START", scan?.let { "gateway scan: $it" }.orEmpty())
                // Iranian mobile networks: Aether races its account calls through the OOB ClientHello lane (WarpApiLane).
                val launched = if (engine.id == AetherSidecar.ID && automatic &&
                    WarpApiLane.applies(onIrancell, onMci, candidate)) WarpApiLane.mark(candidate).also {
                        record(name, "api-lane", "START", WarpApiLane.summary(AetherSettings.read(ProfileExtras.read(it))))
                    } else candidate
                val launch = engine.prepare(launched, settings, context)
                checkCancellation()
                stage = "engine process could not start"
                val process = EngineReaper.track(engine.startPrepared(launched, settings, launch, context) ?: SidecarProcess.start(engine.id, launch, context.workDir, context.socksPort))
                running = process
                checkCancellation()
                stage = "local proxy did not become ready"
                val lanternHop = LanternRelayHop.requested(extras)
                val window = if (lanternHop) minOf(launch.readyTimeoutMs, LANTERN_ATTEMPT_MS)
                    else if (name in CLEAN_METHODS) minOf(launch.readyTimeoutMs, CLEAN_H2_BUDGET_MS)
                    else if (automatic && extras.optBoolean(V6_FIRST) && onIrancell) minOf(launch.readyTimeoutMs, v6AttemptMs(manual))
                    else if (automatic && manual && extras.optBoolean(V6_FIRST)) minOf(launch.readyTimeoutMs, MANUAL_V6_SHARE_MS)
                    else if (automatic && extras.optBoolean(V6_FIRST) && carrier in V6_FIRST_BUDGET_MS)
                        minOf(launch.readyTimeoutMs, V6_FIRST_BUDGET_MS.getValue(carrier))
                    else if (irancellHttp2 && manual) IrancellWarp.manualAttemptMs(candidate)
                    else if (engine.id == AetherSidecar.ID && manual && !automatic && carrier in setOf("h2", "h3")) {
                        val options = AetherSettings.read(extras)
                        if (options.tor == "off" && options.psiphon == "off")
                            AetherSidecar.manualMasqueReadinessMs(options.scan, pinned = options.endpoint.isNotBlank())
                        else launch.readyTimeoutMs
                    }
                    else if (!automatic && name == WIREGUARD_MTU) minOf(launch.readyTimeoutMs, 45_000L)
                    else readinessBudgetMs(
                    engine.id, automatic, preferWireGuard, carrier, launch.readyTimeoutMs,
                    failedHere = name in recentlyFailed
                )
                // On a metered filtered link a direct carrier never runs past what is left of the ladder budget.
                val left = ladderBudget?.takeIf { !(irancellHttp2 && manual) && !extras.optBoolean(RELAY_FOLLOW_UP) }
                    ?.let { maxOf(LADDER_MIN_ATTEMPT_MS, it - (System.currentTimeMillis() - ladderStartedAt)) }
                val capped = if (left != null) minOf(window, left) else window
                val ready = if (lanternHop) process.awaitReady(capped)
                else if (irancellHttp2) {
                    val wait = IrancellWarp.waitMs(capped, ladderStartedAt, System.currentTimeMillis(), irancellBudgetMs)
                    val watch = IrancellWarp.RegistrationWatch()
                    process.awaitReadyUnless(wait) {
                        val failure = process.startupFailure
                        failure == SidecarProcess.AETHER_SCAN_EXHAUSTED ||
                            watch.shouldStop(failure, System.currentTimeMillis())
                    }
                } else if (reserveForNextRelay) {
                    val elapsedMs = (System.nanoTime() - attemptStartedNanos) / 1_000_000
                    val wait = AetherBackgroundRelay.startupWindowMs(capped, elapsedMs, metered, hasNext = true)
                    record(name, "budget", "START", "up to ${wait} ms to reach readiness before the next relay")
                    wait > 0 && process.awaitReady(wait)
                } else if (stallGuarded(name, carrier, extras)) {
                    process.awaitReadyUnless(capped) {
                        AetherStartupStall.shouldGiveUp(System.currentTimeMillis(), attemptStartedAt,
                            process.startupStage, process.transportStageSinceMs).also { if (it) stallCut = true }
                    }
                } else process.awaitReady(capped)
                checkCancellation()
                if (!ready) {
                    val reason = (if (stallCut) AetherStartupStall.reason(process.startupStage) else null)
                        ?: process.startupFailure ?: if (reserveForNextRelay)
                        "Relay did not reach readiness within its startup budget; traffic is unverified." else stage
                    record(name, "startup", "FAIL", reason)
                    attempts += "$name: $reason"
                    failed(name, candidate)
                    if (lanternHop) rememberLanternFailure(networkKey, reason, ::record)
                    if (name == preferred) BuiltinMethodMemory.forget(cacheScope)
                    // Registration worked (the scan comes after it) but no gateway served a tunnel with Cloudflare
                    // filtered: WARP's data plane is blocked here. Hold automatic Aether; keep the saved identity.
                    if (guarded && WarpDataPlaneHold.startsHold(reason, cloudflareBlocked, relayed = extras.optBoolean(RELAY_FOLLOW_UP))) {
                        WarpDataPlaneHold.record(networkKey)
                        WarpRegistrationMemory.clear(networkKey)
                        record("ladder", "warp-data", "REMEMBER", "gateway scan found no serving tunnel with Cloudflare filtered; " +
                            "automatic Aether, the background relay and your own Aether runs' direct scans (while Cloudflare stays filtered) " +
                            "held for ${WarpDataPlaneHold.HOLD_MS / 60_000} min on this network " +
                            "(registration works: the saved identity is kept)")
                    }
                    // Registration is shared by H2/H3 MASQUE. Do not repeat a failed signup
                    // for the same identity, but allow an independent stored or new WG identity.
                    // A proven API transport failure/rate limit blocks all *new* registrations.
                    if (engine.id == AetherSidecar.ID && AetherApiBridge.registrationFailed(reason)) {
                        failedRegistrationKinds += if (AetherApiBridge.bootstrapBlocked(reason))
                            AetherApiBridge.API_UNAVAILABLE else AetherApiBridge.keyKind(candidate)
                        // At once, not only at the end: a cancelled or capped run (the background relay) still remembers it.
                        if (AetherApiBridge.bootstrapBlocked(reason)) WarpRegistrationMemory.record(networkKey)
                    }
                    continue
                }
                record(name, "startup", "PASS")
                stage = "access check failed"
                val result = verify(name, process, launch, context, reserveForNextRelay, ::checkCancellation)
                checkCancellation()
                if (result.traffic && process.isAlive) {
                    record(name, "https", "PASS")
                    AetherFingerprintBook.record(networkKey, candidate, verified = true)
                    extras.optString(AetherSidecar.MASQUE_SNI).takeIf { it.isNotBlank() }?.let { AetherSniProbe.recordSuccess(networkKey, it) }
                    if (engine.id == AetherSidecar.ID && automatic) rememberCleanH2(name, candidate, networkKey, attemptStartedAt, ::record)
                    if (engine.id == AetherSidecar.ID) WarpRegistrationMemory.clear(networkKey)
                    if (engine.id == AetherSidecar.ID && !extras.optBoolean(RELAY_FOLLOW_UP)) WarpDataPlaneHold.clear(networkKey)
                    if (LanternRelayHop.requested(extras)) LanternRelayMemory.clear(networkKey)
                    if (guarded) AetherLadderGuard.finish(networkKey, ladderKind)
                    if (scope() == cacheScope) {
                        BuiltinMethodMemory.record(cacheScope, name)
                        BuiltinMethodMemory.clearFailure(cacheScope, name)
                        BuiltinMethodMemory.clearWarpDataBlocked(cacheScope)
                    }
                    running = null
                    return Started(process, launch, candidate, result)
                }
                lastCheck = result.takeUnless { it.traffic }
                if ((carrier == "wireguard" || carrier == "wiw") && name != WIREGUARD_V6) handshakeWithoutData = true
                val reason = result.failure ?: "engine stopped during check"
                record(name, "https", "FAIL", reason)
                attempts += "$name: $reason"
                failed(name, candidate)
                if (LanternRelayHop.requested(extras)) rememberLanternFailure(networkKey, reason, ::record)
                if (name == preferred) BuiltinMethodMemory.forget(cacheScope)
                // A relayed carrier has no direct follow-ups of its own.
                if (extras.optBoolean(RELAY_FOLLOW_UP)) continue
                if (!automatic && reducedMtuWireGuardFollowUp(engine.id, true, carrier,
                        AetherSettings.read(extras).mtu, alreadyQueued = mtuQueued, reverseRelay = reverseRelay)) {
                    mtuQueued = true
                    record(WIREGUARD_MTU, "queue", "START",
                        "WireGuard started but HTTPS failed; retrying with MTU ${AetherSettings.MTU_RETRY}")
                    queue.addFirst(reducedMtuWireGuard(candidate))
                } else {
                    onNoData(name, candidate, extras, carrier)
                }
            } catch (e: Exception) {
                if (e is InterruptedException || e is CancellationException) throw e
                checkCancellation()
                // Never print arbitrary process exceptions: they may contain profile secrets.
                // Only fixed, non-sensitive reasons (preflight and exit-node failures) are shown as they are.
                val reason = startFailureReason(engine.id, e, stage)
                record(name, "startup", "FAIL", reason)
                attempts += "$name: $reason"
                failed(name, candidate)
                if (LanternRelayHop.requested(extras)) rememberLanternFailure(networkKey, reason, ::record)
                // No saved identity and the account API cannot be reached: every other carrier that also
                // lacks an identity would wait out its whole window for the same registration.
                if (engine.id == AetherSidecar.ID && reason == AetherApiBridge.UNREACHABLE_WITHOUT_IDENTITY)
                    failedRegistrationKinds += AetherApiBridge.API_UNAVAILABLE
            } finally {
                running?.stop()
            }
        }
        checkCancellation()
        // The account API failed here in this run: remembered for this network, so neither the next ladder nor the
        // background relay repeats the same registration for WarpRegistrationMemory.HOLD_MS.
        if (engine.id == AetherSidecar.ID && !registrationHeld && AetherApiBridge.API_UNAVAILABLE in failedRegistrationKinds) {
            WarpRegistrationMemory.record(networkKey)
            record("ladder", "registration", "REMEMBER", "WARP API registration failed on this network; " +
                "not retried for ${WarpRegistrationMemory.HOLD_MS / 60_000} min")
        }
        if (engine.id == AetherSidecar.ID && automatic && handshakeWithoutData)
            BuiltinMethodMemory.recordWarpDataBlocked(cacheScope)
        if (engine.id == AetherSidecar.ID && preferWireGuard) attempts += filteredHint(attempts)
        // Irancell's single attempt never ran WARP through a saved server: the background relay may, once one is verified.
        val relayDeferred = irancellHttp2 && relayEligible(engine.id, automatic, options, "saved-server") ||
            !relayOnly && relays.isEmpty() && (deferRelay || deferRelayToSavedServer(engine.id, automatic, options,
            relayExitNode, warpDataBlocked = BuiltinMethodMemory.warpDataBlocked(cacheScope)))
        if (guarded) AetherLadderGuard.finish(networkKey, ladderKind)
        throw NoVerifiedPath(attempts, lastCheck, relayDeferred = relayDeferred)
    }
}
