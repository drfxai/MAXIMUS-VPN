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
            AetherSidecar.handles(profile) && e.optString("carrier", "auto") == "auto" -> listOf("h2", "h3", "wireguard").map {
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
        cancelled: () -> Boolean = { false }
    ): Started {
        val attempts = mutableListOf<String>()
        var lastCheck: AccessProbe.Result? = null
        fun checkCancellation() {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            if (cancelled()) throw CancellationException("Connection cancelled")
        }
        for (candidate in candidates(profile)) {
            checkCancellation()
            val extras = ProfileExtras.read(candidate)
            val name = if (engine.id == AetherSidecar.ID) extras.optString("carrier") else extras.optString("variant")
            var running: RunningEngine? = null
            var stage = "configuration preparation failed"
            try {
                val launch = engine.prepare(candidate, settings, context)
                checkCancellation()
                stage = "engine process could not start"
                val process = EngineReaper.track(SidecarProcess.start(engine.id, launch, context.workDir, context.socksPort))
                running = process
                checkCancellation()
                stage = "local proxy did not become ready"
                val ready = process.awaitReady(launch.readyTimeoutMs)
                checkCancellation()
                if (!ready) {
                    attempts += "$name: ${process.startupFailure ?: stage}"
                    continue
                }
                stage = "access check failed"
                val result = AccessProbe.check(
                    Socks5.Endpoint("127.0.0.1", context.socksPort, context.socksUser, context.socksPass), checkDns
                )
                checkCancellation()
                if (result.traffic && process.isAlive) {
                    running = null
                    return Started(process, launch, candidate, result)
                }
                lastCheck = result.takeUnless { it.traffic }
                attempts += "$name: ${result.failure ?: "engine stopped during check"}"
            } catch (e: Exception) {
                if (e is InterruptedException || e is CancellationException) throw e
                checkCancellation()
                attempts += "$name: $stage"
            } finally {
                running?.stop()
            }
        }
        checkCancellation()
        throw NoVerifiedPath(attempts, lastCheck)
    }
}
