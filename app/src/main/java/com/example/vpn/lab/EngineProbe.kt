package com.example.vpn.lab

import android.content.Context
import com.example.data.model.AppSettings
import com.example.data.model.VlessProfile
import com.example.vpn.sidecar.SidecarChain
import com.example.vpn.sidecar.SidecarContext
import com.example.vpn.sidecar.SidecarEngine
import com.example.vpn.sidecar.SidecarProcess
import com.example.vpn.sidecar.Sidecars
import com.example.xray.RealDelayProbe
import java.io.File

/**
 * Tests a config carried by its own engine program (DNS tunnel, Psiphon, Tor, Mihomo) in isolation from
 * the VPN: the engine starts in a separate LAB work folder on its own loopback port, a real HTTPS request
 * goes through it via a throwaway Xray core, and the engine is stopped. The VPN's tunnel, interface and
 * engines are never touched; while the VPN runs the isolated core is refused and the result is NOT_TESTED.
 *
 * DNS tunnel lifecycle as reported here: ENGINE_AVAILABLE → ENGINE_STARTING → LOCAL_PROXY_READY →
 * APPLICATION_TRAFFIC_VERIFIED. dnstt opens its port before its Noise handshake, so a ready port proves
 * nothing; only the real request does. Only APPLICATION_TRAFFIC_VERIFIED counts as connected.
 */
object EngineProbe {

    enum class DnsTunnelStage {
        ENGINE_UNAVAILABLE, ENGINE_AVAILABLE, ENGINE_STARTING, LOCAL_PROXY_READY, RESOLVER_SELECTED, DNS_TRANSPORT_NEGOTIATING,
        AUTHENTICATED_TUNNEL_ESTABLISHED, REMOTE_EGRESS_VERIFIED, APPLICATION_TRAFFIC_VERIFIED
    }

    data class Result(
        val engineId: String,
        val stage: ConnectionStage,
        val latencyMs: Long?,
        val failure: String?,
        /** True when nothing was measured (engine missing, VPN running, no Android context). */
        val notTested: Boolean
    )

    /** A ready engine plus the outcome of a real request through it; pure, so the semantics are tested. */
    fun judge(engineId: String, ready: Boolean, outcome: RealDelayProbe.Outcome?): Result = when {
        !ready -> Result(engineId, ConnectionStage.NOT_TESTED, null, "the engine did not open its local port", notTested = false)
        outcome is RealDelayProbe.Outcome.Delay -> Result(engineId, ConnectionStage.APPLICATION_REQUEST_PASSED, outcome.latencyMs, null, false)
        outcome is RealDelayProbe.Outcome.Failed -> Result(engineId, ConnectionStage.LOCAL_PROXY_READY, null, outcome.reason, false)
        outcome is RealDelayProbe.Outcome.NotRun -> Result(engineId, ConnectionStage.LOCAL_PROXY_READY, null, outcome.reason, true)
        else -> Result(engineId, ConnectionStage.LOCAL_PROXY_READY, null, "not measured", true)
    }

    fun dnsTunnelStage(r: Result?): DnsTunnelStage = when {
        r == null -> DnsTunnelStage.ENGINE_UNAVAILABLE
        r.stage.carriesTraffic -> DnsTunnelStage.APPLICATION_TRAFFIC_VERIFIED
        r.stage == ConnectionStage.LOCAL_PROXY_READY -> DnsTunnelStage.LOCAL_PROXY_READY
        else -> DnsTunnelStage.ENGINE_AVAILABLE
    }

    /** Runs one isolated test. Blocking; call from an IO thread. [timeoutSec] bounds the real request. */
    fun test(context: Context, engine: SidecarEngine, profile: VlessProfile, timeoutSec: Int): Result {
        val executable = Sidecars.executable(engine)
            ?: return Result(engine.id, ConnectionStage.NOT_TESTED, null, "the ${engine.id} engine is not in this build", notTested = true)
        engine.problem(profile)?.let { return Result(engine.id, ConnectionStage.NOT_TESTED, null, it, notTested = true) }
        val workDir = File(context.noBackupFilesDir, "lab-engines/${engine.id}").apply { mkdirs() }
        val ctx = SidecarContext(workDir, executable, Sidecars.freeLoopbackPort(), Sidecars.randomToken(9), Sidecars.randomToken(), AppSettings().operationalMode)
        val launch = runCatching { engine.prepare(profile, AppSettings(), ctx) }.getOrElse {
            return Result(engine.id, ConnectionStage.NOT_TESTED, null, it.message ?: "could not prepare the engine", notTested = true)
        }
        val running = runCatching { engine.startInApp(launch, ctx) ?: SidecarProcess.start("lab-${engine.id}", launch, workDir, ctx.socksPort) }.getOrElse {
            return Result(engine.id, ConnectionStage.NOT_TESTED, null, it.message ?: "the engine did not start", notTested = false)
        }
        try {
            val ready = running.awaitReady(minOf(launch.readyTimeoutMs, 45_000L))
            if (!ready) return judge(engine.id, false, null)
            val outcome = RealDelayProbe.measure(SidecarChain.xrayProfile(profile, ctx, launch), timeoutSec)
            return judge(engine.id, true, outcome)
        } finally {
            running.stop()
        }
    }
}
