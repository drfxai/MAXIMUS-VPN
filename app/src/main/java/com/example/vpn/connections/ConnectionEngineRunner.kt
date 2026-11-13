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
        val check: AccessProbe.Result)
    class NoVerifiedPath(attempts: List<String>, val lastCheck: AccessProbe.Result?) :
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
    fun quickRecheckOnly(methods: List<String>, preferred: String?, recentlyFailed: Set<String>): Boolean =
        preferred == null && methods.isNotEmpty() && methods.all { it in recentlyFailed }

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
    data class LinkHints(val ipv4Only: Boolean = false, val cloudflareFiltered: Boolean = false)

    fun linkHints(measured: com.example.vpn.smart.NetworkCapabilityProfile?): LinkHints {
        val m = measured ?: return LinkHints()
        return LinkHints(
            ipv4Only = m.ipv4Available == true && m.ipv6Available == false,
            // Cloudflare's TLS (or Cloudflare itself) failed while UDP abroad answered: MASQUE is the carrier
            // the probe saw fail, WireGuard is still worth a try. Only a measured failure counts, never a guess.
            cloudflareFiltered = m.udpAvailable == true &&
                (m.cloudflareIpv4Tls == false || m.cloudflareReachable == false)
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

    /** One line telling the user what to do when Aether found nothing on a link whose Cloudflare TLS is filtered. */
    const val CLOUDFLARE_FILTERED_HINT = "Cloudflare TLS is filtered on this network: Smart Connect can try saved servers, Psiphon or Tor"

    /**
     * The order the methods are tried in: a method verified here first, then WireGuard when Cloudflare TLS
     * is filtered but UDP answers, then the rest, with methods that failed here in the last half hour last.
     */
    fun order(methods: List<String>, preferred: String?, preferWireGuard: Boolean, recentlyFailed: Set<String>): List<String> =
        methods.withIndex().sortedWith(compareBy<IndexedValue<String>>({
            when {
                it.value == preferred -> 0
                it.value in recentlyFailed -> 3
                preferWireGuard && it.value == "wireguard" -> 1
                else -> 2
            }
        }, {
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

    fun candidates(profile: VlessProfile, ipv4Only: Boolean = false): List<VlessProfile> {
        val e = ProfileExtras.read(profile)
        val variants = when {
            AetherSidecar.handles(profile) && e.optString("carrier", "auto") == "auto" -> (if (AetherSettings.read(e).let { it.tor in listOf("reverse", "only") || it.psiphon in listOf("reverse", "only") }) listOf("h2") else listOf("h2", "h3", "wireguard")).map {
                profile.copy(extraSettings = org.json.JSONObject(e.toString()).put("carrier", it).toString())
            }
            ServerlessSidecar.handles(profile) && e.optString("variant", "auto") == "auto" -> listOf("fragA", "fragB", "oob").map {
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
    fun start(
        engine: SidecarEngine,
        profile: VlessProfile,
        settings: AppSettings,
        context: SidecarContext,
        checkDns: Boolean = false,
        ipv4Only: Boolean = false,
        preferWireGuard: Boolean = false,
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
        fun scope(): String? {
            if (!automatic) return null
            val network = BuiltinMethodMemory.network() ?: return null
            val options = org.json.JSONObject(profile.extraSettings).apply { remove("rescan") }.toString()
            val input = network + "|" + engine.id + "|" + options + "|" + settings.dnsServer
            return java.security.MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
        fun method(candidate: VlessProfile) = ProfileExtras.read(candidate).let {
            if (engine.id == AetherSidecar.ID) {
                if (it.optBoolean(MTU_FOLLOW_UP)) WIREGUARD_MTU
                else if (it.optBoolean(NOIZE_FOLLOW_UP)) WIREGUARD_NOIZE else it.optString("carrier")
            } else it.optString("variant")
        }
        val cacheScope = scope()
        val baseChoices = candidates(profile, ipv4Only = ipv4Only)
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
        val followUps = listOfNotNull(noizeChoice, mtuChoice)
        val preferred = BuiltinMethodMemory.preferred(cacheScope, baseChoices.map(::method) + followUps.map(::method))
        val choices = followUps.firstOrNull { method(it) == preferred }?.let { listOf(it) + baseChoices } ?: baseChoices
        // Keep a previously HTTPS-verified method first. Without such evidence, try WG
        // before long MASQUE gateway scans when fresh physical-link probes show that
        // ordinary Cloudflare TLS is unreachable but UDP is answering.
        val recentlyFailed = BuiltinMethodMemory.recentlyFailed(cacheScope)
        val rank = order(choices.map(::method), preferred, preferWireGuard, recentlyFailed)
        val ordered = choices.sortedBy { rank.indexOf(method(it)) }
        val failedRegistrationKinds = mutableSetOf<String>()
        val recheckOnly = engine.id == AetherSidecar.ID && automatic &&
            quickRecheckOnly(ordered.map(::method), preferred, recentlyFailed)
        val queue = ArrayDeque(if (recheckOnly) ordered.take(1) else ordered)
        if (recheckOnly) ordered.drop(1).forEach {
            record(method(it), "queue", "SKIP", "failed on this network in the last 30 min; one quick recheck of ${method(ordered.first())} only")
        }
        val reverseRelay = engine.id == AetherSidecar.ID &&
            AetherSettings.read(ProfileExtras.read(profile)).let { it.tor == "reverse" || it.psiphon == "reverse" }
        var warpInWarpQueued = recheckOnly
        var noizeQueued = recheckOnly || preferred == WIREGUARD_NOIZE
        var mtuQueued = recheckOnly || preferred == WIREGUARD_MTU
        while (queue.isNotEmpty()) {
            val candidate = queue.removeFirst()
            checkCancellation()
            val extras = ProfileExtras.read(candidate)
            val name = method(candidate)
            // Native carrier name: budgets and follow-ups are about the transport, not the label.
            val carrier = if (engine.id == AetherSidecar.ID) extras.optString("carrier") else name
            if (engine.id == AetherSidecar.ID && AetherApiBridge.skipAfterBootstrapFailure(candidate, failedRegistrationKinds)) {
                record(name, "registration", "SKIP", "account API unavailable; no independent stored identity")
                continue
            }
            var running: RunningEngine? = null
            var stage = "configuration preparation failed"
            try {
                record(name, "prepare", "START")
                val launch = engine.prepare(candidate, settings, context)
                checkCancellation()
                stage = "engine process could not start"
                val process = EngineReaper.track(engine.startPrepared(candidate, settings, launch, context) ?: SidecarProcess.start(engine.id, launch, context.workDir, context.socksPort))
                running = process
                checkCancellation()
                stage = "local proxy did not become ready"
                val ready = process.awaitReady(readinessBudgetMs(
                    engine.id, automatic, preferWireGuard, carrier, launch.readyTimeoutMs,
                    failedHere = name in recentlyFailed
                ))
                checkCancellation()
                if (!ready) {
                    val reason = process.startupFailure ?: stage
                    record(name, "startup", "FAIL", reason)
                    attempts += "$name: $reason"
                    BuiltinMethodMemory.recordFailure(cacheScope, name)
                    if (name == preferred) BuiltinMethodMemory.forget(cacheScope)
                    // Registration is shared by H2/H3 MASQUE. Do not repeat a failed signup
                    // for the same identity, but allow an independent stored or new WG identity.
                    // A proven API transport failure/rate limit blocks all *new* registrations.
                    if (engine.id == AetherSidecar.ID && AetherApiBridge.registrationFailed(reason)) {
                        failedRegistrationKinds += if (AetherApiBridge.bootstrapBlocked(reason))
                            AetherApiBridge.API_UNAVAILABLE else AetherApiBridge.keyKind(candidate)
                    }
                    continue
                }
                record(name, "startup", "PASS")
                stage = "access check failed"
                val endpoint = Socks5.Endpoint("127.0.0.1", context.socksPort,
                    context.socksUser.takeIf { launch.socksAuth }, context.socksPass.takeIf { launch.socksAuth })
                val generation = process.transportGeneration
                val first = AccessProbe.check(endpoint, checkDns, diagnoseAddress = engine.id == AetherSidecar.ID)
                checkCancellation()
                val result = if (engine.id == AetherSidecar.ID && first.traffic) {
                    record(name, "https", "RECHECK", "checking that the same transport keeps carrying traffic")
                    Thread.sleep(6_000)
                    checkCancellation()
                    if (process.isAlive && process.transportGeneration == generation) {
                        val second = AccessProbe.check(endpoint, checkDns = false)
                        if (process.transportGeneration == generation) second.copy(dns = first.dns)
                        else first.copy(traffic = false, latencyMs = null, failure = "Aether transport restarted during traffic verification")
                    } else first.copy(traffic = false, latencyMs = null, failure = "Aether transport stopped or restarted before traffic recheck")
                } else first
                checkCancellation()
                if (result.traffic && process.isAlive) {
                    record(name, "https", "PASS")
                    if (scope() == cacheScope) {
                        BuiltinMethodMemory.record(cacheScope, name)
                        BuiltinMethodMemory.clearFailure(cacheScope, name)
                    }
                    running = null
                    return Started(process, launch, candidate, result)
                }
                lastCheck = result.takeUnless { it.traffic }
                val reason = result.failure ?: "engine stopped during check"
                record(name, "https", "FAIL", reason)
                attempts += "$name: $reason"
                BuiltinMethodMemory.recordFailure(cacheScope, name)
                if (name == preferred) BuiltinMethodMemory.forget(cacheScope)
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
            } catch (e: Exception) {
                if (e is InterruptedException || e is CancellationException) throw e
                checkCancellation()
                // Never print arbitrary process exceptions: they may contain profile secrets.
                // This fixed, non-sensitive preflight error is safe and actionable.
                val reason = if (engine.id == AetherSidecar.ID &&
                    e.message in setOf("WARP API TLS unreachable via forced Direct OOB route", AetherApiBridge.INCOMPATIBLE_OOB,
                        AetherApiBridge.UNREACHABLE_WITHOUT_IDENTITY))
                    e.message ?: stage else stage
                record(name, "startup", "FAIL", reason)
                attempts += "$name: $reason"
                BuiltinMethodMemory.recordFailure(cacheScope, name)
                // No saved identity and the account API cannot be reached: every other carrier that also
                // lacks an identity would wait out its whole window for the same registration.
                if (engine.id == AetherSidecar.ID && reason == AetherApiBridge.UNREACHABLE_WITHOUT_IDENTITY)
                    failedRegistrationKinds += AetherApiBridge.API_UNAVAILABLE
            } finally {
                running?.stop()
            }
        }
        checkCancellation()
        if (engine.id == AetherSidecar.ID && preferWireGuard) attempts += CLOUDFLARE_FILTERED_HINT
        throw NoVerifiedPath(attempts, lastCheck)
    }
}
