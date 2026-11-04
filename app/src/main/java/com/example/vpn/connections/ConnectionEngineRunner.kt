package com.example.vpn.connections

import com.example.data.model.AppSettings
import com.example.data.model.VlessProfile
import com.example.vpn.sidecar.*
import com.example.vpn.engine.ProfileExtras
import com.example.vpn.verify.Socks5

/** Serial alternatives, each proven by HTTPS data; never crosses direct/tunnel mode boundaries. */
object ConnectionEngineRunner {
    data class Started(val running: RunningEngine, val launch: SidecarLaunch, val profile: VlessProfile,
        val check: AccessProbe.Result)
    fun candidates(profile: VlessProfile): List<VlessProfile> {
        val e = ProfileExtras.read(profile)
        return when {
            AetherSidecar.handles(profile) && e.optString("carrier", "auto") == "auto" -> listOf("h2", "h3").map {
                profile.copy(extraSettings = org.json.JSONObject(e.toString()).put("carrier", it).toString())
            }
            ServerlessSidecar.handles(profile) && e.optString("variant", "auto") == "auto" -> listOf("fragA", "fragB", "oob").map {
                profile.copy(extraSettings = org.json.JSONObject(e.toString()).put("variant", it).toString())
            }
            else -> listOf(profile)
        }
    }
    fun start(engine: SidecarEngine, profile: VlessProfile, settings: AppSettings, context: SidecarContext, cancelled: () -> Boolean = { false }): Started {
        for (candidate in candidates(profile)) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            if (cancelled()) throw kotlinx.coroutines.CancellationException("Connection cancelled")
            val launch = engine.prepare(candidate, settings, context)
            if (cancelled()) throw kotlinx.coroutines.CancellationException("Connection cancelled")
            val running = EngineReaper.track(SidecarProcess.start(engine.id, launch, context.workDir, context.socksPort))
            var success = false
            try {
                if (!running.awaitReady(launch.readyTimeoutMs)) continue
                val result = AccessProbe.check(Socks5.Endpoint("127.0.0.1", context.socksPort, context.socksUser, context.socksPass))
                if (cancelled()) throw kotlinx.coroutines.CancellationException("Connection cancelled")
                if (!result.traffic) continue
                success = true
                return Started(running, launch, candidate, result)
            } finally { if (!success) running.stop() }
        }
        error("No verified path for ${if (engine.id == AetherSidecar.ID) "Aether / WARP" else "Direct Serverless"}; no other mode was enabled")
    }
}
