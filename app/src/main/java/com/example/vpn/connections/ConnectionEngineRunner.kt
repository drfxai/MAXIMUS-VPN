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

    fun candidates(profile: VlessProfile): List<VlessProfile> {
        val e = ProfileExtras.read(profile)
        return when {
            AetherSidecar.handles(profile) && e.optString("carrier", "auto") == "auto" -> (if (AetherSettings.read(e).let { it.tor in listOf("reverse", "only") || it.psiphon in listOf("reverse", "only") }) listOf("h2") else listOf("h2", "h3", "wireguard")).map {
                profile.copy(extraSettings = org.json.JSONObject(e.toString()).put("carrier", it).toString())
            }
            ServerlessSidecar.handles(profile) && e.optString("variant", "auto") == "auto" -> listOf("fragA", "fragB", "oob").map {
                profile.copy(extraSettings = org.json.JSONObject(e.toString()).put("variant", it).toString())
            }
            else -> listOf(profile)
        }
    }
    fun start(
        engine: SidecarEngine,
        profile: VlessProfile,
        settings: AppSettings,
        context: SidecarContext,
        checkDns: Boolean = false,
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
        val choices = candidates(profile)
        val preferred = BuiltinMethodMemory.preferred(cacheScope, choices.map(::method))
        val ordered = choices.sortedBy { if (method(it) == preferred) 0 else 1 }
        val failedRegistrationKinds = mutableSetOf<String>()
        for (candidate in ordered) {
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
                val ready = process.awaitReady(launch.readyTimeoutMs)
                checkCancellation()
                if (!ready) {
                    val reason = process.startupFailure ?: stage
                    record(name, "startup", "FAIL", reason)
                    attempts += "$name: $reason"
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
                val result = AccessProbe.check(
                    Socks5.Endpoint("127.0.0.1", context.socksPort,
                        context.socksUser.takeIf { launch.socksAuth }, context.socksPass.takeIf { launch.socksAuth }), checkDns
                )
                checkCancellation()
                if (result.traffic && process.isAlive) {
                    record(name, "https", "PASS")
                    if (scope() == cacheScope) BuiltinMethodMemory.record(cacheScope, name)
                    running = null
                    return Started(process, launch, candidate, result)
                }
                lastCheck = result.takeUnless { it.traffic }
                val reason = result.failure ?: "engine stopped during check"
                record(name, "https", "FAIL", reason)
                attempts += "$name: $reason"
                if (name == preferred) BuiltinMethodMemory.forget(cacheScope)
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
            } finally {
                running?.stop()
            }
        }
        checkCancellation()
        throw NoVerifiedPath(attempts, lastCheck)
    }
}
