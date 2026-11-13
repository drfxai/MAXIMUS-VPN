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
        exact: Boolean, exitLocked: Boolean, force: Boolean, alreadyEscalated: Boolean
    ): Boolean = aether && (smart || autoFailover) && !exact && !exitLocked &&
        !force && !alreadyEscalated

    /**
     * Limit automatic Aether recovery when this network has already said no: freshly observed blocked
     * Cloudflare TLS with usable UDP, or this carrier failed here in the last half hour (the network
     * reading only lasts ten minutes, the failure memory longer, so a second tap or a test does not
     * pay three full 180-second gateway scans again). Manual/pinned modes keep their full window; a
     * ready SOCKS port alone still never counts as verified traffic.
     */
    fun readinessBudgetMs(
        engineId: String, automatic: Boolean, filteredTlsWithUdp: Boolean,
        carrier: String, defaultMs: Long, failedHere: Boolean = false
    ): Long = when {
        engineId != AetherSidecar.ID || !automatic -> defaultMs
        // WARP inside WARP is only ever an automatic follow-up to a WireGuard handshake whose data was blocked.
        carrier == "wiw" -> minOf(defaultMs, WIW_BUDGET_MS)
        // H2 MASQUE is TLS over TCP to Cloudflare: exactly what the probe saw fail. It still gets a
        // short try (fragmentation can pass), but not the full gateway scan the UDP carriers need.
        filteredTlsWithUdp || failedHere ->
            minOf(defaultMs, when (carrier) { "wireguard" -> 75_000L; "h2" -> H2_BLOCKED_TLS_BUDGET_MS; else -> 65_000L })
        else -> defaultMs
    }

    const val WIW_BUDGET_MS = 90_000L

    const val H2_BLOCKED_TLS_BUDGET_MS = 40_000L

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
            if (engine.id == AetherSidecar.ID) it.optString("carrier") else it.optString("variant")
        }
        val cacheScope = scope()
        val choices = candidates(profile, ipv4Only = ipv4Only)
        val preferred = BuiltinMethodMemory.preferred(cacheScope, choices.map(::method))
        // Keep a previously HTTPS-verified method first. Without such evidence, try WG
        // before long MASQUE gateway scans when fresh physical-link probes show that
        // ordinary Cloudflare TLS is unreachable but UDP is answering.
        val recentlyFailed = BuiltinMethodMemory.recentlyFailed(cacheScope)
        val rank = order(choices.map(::method), preferred, preferWireGuard, recentlyFailed)
        val ordered = choices.sortedBy { rank.indexOf(method(it)) }
        val failedRegistrationKinds = mutableSetOf<String>()
        val queue = ArrayDeque(ordered)
        val reverseRelay = engine.id == AetherSidecar.ID &&
            AetherSettings.read(ProfileExtras.read(profile)).let { it.tor == "reverse" || it.psiphon == "reverse" }
        var warpInWarpQueued = false
        while (queue.isNotEmpty()) {
            val candidate = queue.removeFirst()
            checkCancellation()
            val extras = ProfileExtras.read(candidate)
            val name = if (engine.id == AetherSidecar.ID) extras.optString("carrier") else extras.optString("variant")
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
                    engine.id, automatic, preferWireGuard, name, launch.readyTimeoutMs,
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
                if (warpInWarpFollowUp(engine.id, automatic, name, handshakePassed = true,
                        alreadyQueued = warpInWarpQueued || choices.any { method(it) == "wiw" }, reverseRelay = reverseRelay)) {
                    warpInWarpQueued = true
                    record("wiw", "queue", "START", "WireGuard handshake passed but no data came back; trying WARP inside WARP")
                    queue.addLast(candidate.copy(extraSettings =
                        org.json.JSONObject(candidate.extraSettings).put("carrier", "wiw").toString()))
                }
            } catch (e: Exception) {
                if (e is InterruptedException || e is CancellationException) throw e
                checkCancellation()
                // Never print arbitrary process exceptions: they may contain profile secrets.
                // This fixed, non-sensitive preflight error is safe and actionable.
                val reason = if (engine.id == AetherSidecar.ID &&
                    e.message in setOf("WARP API TLS unreachable via forced Direct OOB route", AetherApiBridge.INCOMPATIBLE_OOB))
                    e.message ?: stage else stage
                record(name, "startup", "FAIL", reason)
                attempts += "$name: $reason"
                BuiltinMethodMemory.recordFailure(cacheScope, name)
            } finally {
                running?.stop()
            }
        }
        checkCancellation()
        if (engine.id == AetherSidecar.ID && preferWireGuard) attempts += CLOUDFLARE_FILTERED_HINT
        throw NoVerifiedPath(attempts, lastCheck)
    }
}
