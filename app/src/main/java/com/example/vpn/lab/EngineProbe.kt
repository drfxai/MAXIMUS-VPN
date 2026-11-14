package com.example.vpn.lab

import android.content.Context
import com.example.data.model.AppSettings
import com.example.data.model.OperationalMode
import com.example.data.model.VlessProfile
import com.example.vpn.sidecar.EngineHandoff
import com.example.vpn.sidecar.EngineReaper
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
    fun test(
        context: Context, engine: SidecarEngine, profile: VlessProfile, timeoutSec: Int, requests: Int = 1,
        gapMs: Long = FullAnalysis.STABLE_WINDOW_MS,
        mode: OperationalMode = AppSettings().operationalMode,
        /** A passing engine is left running in [EngineHandoff] for the connect that follows. */
        keep: Boolean = false
    ): Result {
        val executable = Sidecars.executable(engine)
            ?: return Result(engine.id, ConnectionStage.NOT_TESTED, null, "the ${engine.id} engine is not in this build", notTested = true)
        engine.problem(profile)?.let { return Result(engine.id, ConnectionStage.NOT_TESTED, null, it, notTested = true) }
        val workDir = File(context.noBackupFilesDir, "lab-engines/${engine.id}-${Sidecars.randomToken(6)}").apply { mkdirs() }
        try {
            val hints = runCatching {
                com.example.vpn.connections.ConnectionEngineRunner.linkHints(com.example.vpn.smart.NetworkEnvironment.freshEvidence(
                    com.example.vpn.smart.NetworkCapabilityDetector.last,
                    com.example.vpn.smart.NetworkIdentity.current(context), System.currentTimeMillis()))
            }.getOrDefault(com.example.vpn.connections.ConnectionEngineRunner.LinkHints())
            return runIsolated(engine, profile, executable, workDir, timeoutSec, requests, gapMs, mode, keep, hints)
                .also { record(context, listOf(engine.id), it, failures = true) }
        } finally {
            if (!EngineHandoff.holds(workDir)) cleanup(workDir)
        }
    }

    /** Tests either a lone sidecar or every hop of a saved chain, with identical request criteria. */
    fun testProfile(
        context: Context, profile: VlessProfile, timeoutSec: Int, requests: Int = 1,
        mode: OperationalMode = AppSettings().operationalMode,
        keep: Boolean = false
    ): Result {
        val chain = com.example.vpn.sidecar.ChainRunner.readChain(profile)
        if (chain == null) {
            val engine = Sidecars.forProfile(profile)
                ?: return Result("unknown", ConnectionStage.NOT_TESTED, null, "no engine carries this profile", true)
            return test(context, engine, profile, timeoutSec, requests, mode = mode, keep = keep)
        }
        com.example.vpn.engine.RuntimeCapabilities.unsupportedReason(profile)?.let {
            return Result("chain", ConnectionStage.NOT_TESTED, null, it, true)
        }
        val workDir = File(context.noBackupFilesDir, "lab-engines/chain-${Sidecars.randomToken(6)}").apply { mkdirs() }
        var running: com.example.vpn.sidecar.ChainRunner.Running? = null
        try {
            val settings = AppSettings()
            running = com.example.vpn.sidecar.ChainRunner.start(chain,
                engineFor = { id -> Sidecars.ENGINES.firstOrNull { it.id == id } },
                freePort = { Sidecars.freeLoopbackPort() },
                context = { id, port, upstream ->
                    val engine = Sidecars.ENGINES.first { it.id == id }
                    SidecarContext(File(workDir, id).apply { mkdirs() }, Sidecars.executable(engine)!!,
                        port, Sidecars.randomToken(9), Sidecars.randomToken(), settings.operationalMode, upstream)
                },
                start = { engine, hop, ctx ->
                    val launch = engine.prepare(hop, settings, ctx)
                    val process = EngineReaper.track(engine.startPrepared(hop, settings, launch, ctx)
                        ?: SidecarProcess.start("lab-${engine.id}", launch, ctx.workDir, ctx.socksPort))
                    try {
                        if (!process.awaitReady(launch.readyTimeoutMs)) error("${engine.id} was not ready")
                        process to launch
                    } catch (t: Throwable) { process.stop(); throw t }
                })
            val chained = SidecarChain.probeProfile(profile, running.entryContext, running.entryLaunch)
            val started = System.currentTimeMillis()
            val first = judge("chain", true, RealDelayProbe.measure(chained, timeoutSec))
            val result = if (!first.stage.carriesTraffic || requests <= 1) first else combine(first, (2..requests).map {
                Thread.sleep(FullAnalysis.STABLE_WINDOW_MS)
                RealDelayProbe.measure(chained, timeoutSec) to (System.currentTimeMillis() - started)
            })
            // A passing chain proves every hop; a failing one does not say which hop failed.
            return result.also { record(context, chain.hops.map { it.engineId }, it, failures = false) }
        } catch (e: Exception) {
            if (e is InterruptedException || e is kotlinx.coroutines.CancellationException) throw e
            return Result("chain", ConnectionStage.NOT_TESTED, null, "chain start or request failed: ${e.javaClass.simpleName}", false)
        } finally {
            try { running?.stop() } finally { cleanup(workDir) }
        }
    }

    internal fun runIsolated(
        engine: SidecarEngine, profile: VlessProfile, executable: File, workDir: File, timeoutSec: Int, requests: Int, gapMs: Long,
        mode: OperationalMode, keep: Boolean,
        hints: com.example.vpn.connections.ConnectionEngineRunner.LinkHints = com.example.vpn.connections.ConnectionEngineRunner.LinkHints()
    ): Result {
        val ctx = SidecarContext(workDir, executable, Sidecars.freeLoopbackPort(), Sidecars.randomToken(9), Sidecars.randomToken(), mode)
        var running: com.example.vpn.sidecar.RunningEngine? = null
        var kept = false
        try {
            val settings = AppSettings().copy(operationalMode = mode)
            val managed = engine.id in setOf(com.example.vpn.sidecar.AetherSidecar.ID,
                com.example.vpn.sidecar.ServerlessSidecar.ID)
            // Smart Connect must test the same automatic carrier/variant sequence
            // as Connect and the Connections page, rather than only the first config.
            val started = if (managed) com.example.vpn.connections.ConnectionEngineRunner.start(engine, profile, settings, ctx,
                ipv4Only = hints.ipv4Only, preferWireGuard = hints.cloudflareFiltered) else null
            val launch = started?.launch ?: engine.prepare(profile, settings, ctx)
            val process = started?.running ?: EngineReaper.track(engine.startPrepared(profile, settings, launch, ctx)
                ?: SidecarProcess.start(engine.id, launch, workDir, ctx.socksPort))
            running = process
            val ready = started != null || process.awaitReady(launch.readyTimeoutMs)
            if (!ready) return judge(engine.id, false, null).copy(failure = process.startupFailure
                ?: "engine startup or traffic readiness failed")
            // A raced carrier may have won on its own loopback port.
            val engineCtx = started?.context ?: ctx
            val chained = SidecarChain.probeProfile(started?.profile ?: profile, engineCtx, launch)
            val began = System.currentTimeMillis()
            val first = if (started != null) judge(engine.id, true, RealDelayProbe.Outcome.Delay(checkNotNull(started.check.latencyMs)))
                else judge(engine.id, true, RealDelayProbe.measure(chained, timeoutSec))
            val result = if (!first.stage.carriesTraffic || requests <= 1) first else combine(first, (2..requests).map {
                Thread.sleep(gapMs)
                RealDelayProbe.measure(chained, timeoutSec) to (System.currentTimeMillis() - began)
            })
            if (keep && result.outcomes.lastOrNull() == true && process.isAlive) {
                EngineHandoff.park(EngineHandoff.Parked(profile.id, engine.id, mode, process, engineCtx, launch, System.currentTimeMillis()))
                kept = true
            }
            return result
        } catch (e: Exception) {
            // A recovery window timing out must terminate the worker and its engine;
            // it is never a measured failure of a server or a reason to try another variant.
            if (e is InterruptedException || e is kotlinx.coroutines.CancellationException) throw e
            val detail = (e as? com.example.vpn.connections.ConnectionEngineRunner.NoVerifiedPath)?.message
            return judge(engine.id, false, null).copy(failure = detail ?: "engine preparation or startup failed")
        } finally {
            if (!kept) running?.stop()
        }
    }

    /** Writes what a test found into the shared engine ledger, which the LAB and diagnostics read. */
    private fun record(context: Context, engineIds: List<String>, r: Result, failures: Boolean) {
        if (r.notTested) return
        val network = runCatching { com.example.vpn.smart.NetworkIdentity.current(context) }
            .getOrElse { com.example.vpn.smart.NetworkKey.current(context) }
        val ledger = com.example.vpn.engine.registry.EngineLedger.shared
        engineIds.forEach { id ->
            if (r.outcomes.lastOrNull() == true) ledger.verified(id, network)
            else if (failures) ledger.failed(id, network, r.failure)
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
