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
        val notTested: Boolean,
        /** Each real request through the running engine, in order (two when re-verified). */
        val outcomes: List<Boolean> = if (stage.carriesTraffic) listOf(true) else if (notTested) emptyList() else listOf(false),
        /** Time between the first and the last passing request, for the stability window. */
        val spanMs: Long = 0
    )

    /**
     * Folds the repeated requests of one engine run. Pass then pass: the stage moves to STABILITY_VERIFIED
     * when they were at least the stable window apart. Pass then fail: the result keeps the pass but the
     * outcomes show the failure, so the verdict is DEGRADED. Pure.
     */
    fun combine(first: Result, more: List<Pair<RealDelayProbe.Outcome, Long>>): Result {
        if (!first.stage.carriesTraffic || more.isEmpty()) return first
        val outcomes = listOf(true) + more.map { it.first is RealDelayProbe.Outcome.Delay }
        val passTimes = more.filter { it.first is RealDelayProbe.Outcome.Delay }.map { it.second }
        val span = passTimes.maxOrNull() ?: 0L
        val latencies = listOfNotNull(first.latencyMs) + more.mapNotNull { (it.first as? RealDelayProbe.Outcome.Delay)?.latencyMs }
        val stable = outcomes.all { it } && span >= FullAnalysis.STABLE_WINDOW_MS
        return first.copy(
            stage = if (stable) ConnectionStage.STABILITY_VERIFIED else first.stage,
            latencyMs = latencies.minOrNull(),
            failure = more.firstNotNullOfOrNull { (it.first as? RealDelayProbe.Outcome.Failed)?.reason },
            outcomes = outcomes,
            spanMs = span
        )
    }

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

    /**
     * Runs one isolated test. Blocking; call from an IO thread. [timeoutSec] bounds each real request. With
     * [requests] above one, a passing engine is asked again after [gapMs] while it keeps running, so a
     * winner is re-verified without a second start-up. The work folder (generated configs, bridge lines,
     * credentials) is deleted after the engine stops; only the sanitized result is kept.
     */
    fun test(context: Context, engine: SidecarEngine, profile: VlessProfile, timeoutSec: Int, requests: Int = 1, gapMs: Long = FullAnalysis.STABLE_WINDOW_MS): Result {
        val executable = Sidecars.executable(engine)
            ?: return Result(engine.id, ConnectionStage.NOT_TESTED, null, "the ${engine.id} engine is not in this build", notTested = true)
        engine.problem(profile)?.let { return Result(engine.id, ConnectionStage.NOT_TESTED, null, it, notTested = true) }
        val workDir = File(context.noBackupFilesDir, "lab-engines/${engine.id}-${Sidecars.randomToken(6)}").apply { mkdirs() }
        try {
            return runIsolated(engine, profile, executable, workDir, timeoutSec, requests, gapMs)
        } finally {
            cleanup(workDir)
        }
    }

    private fun runIsolated(engine: SidecarEngine, profile: VlessProfile, executable: File, workDir: File, timeoutSec: Int, requests: Int, gapMs: Long): Result {
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
            val chained = SidecarChain.xrayProfile(profile, ctx, launch)
            val started = System.currentTimeMillis()
            val first = judge(engine.id, true, RealDelayProbe.measure(chained, timeoutSec))
            if (!first.stage.carriesTraffic || requests <= 1) return first
            val more = (2..requests).map {
                Thread.sleep(gapMs)
                RealDelayProbe.measure(chained, timeoutSec) to (System.currentTimeMillis() - started)
            }
            return combine(first, more)
        } finally {
            running.stop()
        }
    }

    /** Deletes one LAB engine work folder: generated configs, bridge lines and credentials. */
    fun cleanup(workDir: File) {
        runCatching { workDir.deleteRecursively() }
    }

    /** Removes every LAB engine work folder; called when the LAB starts so a crash leaves nothing behind. */
    fun cleanupAll(context: Context) {
        runCatching { File(context.noBackupFilesDir, "lab-engines").deleteRecursively() }
    }
}
