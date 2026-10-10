package com.example.vpn.sidecar

import com.example.data.model.AppSettings
import com.example.data.model.OperationalMode
import com.example.data.model.VlessProfile
import java.io.File

/**
 * Starts the hops of an [EngineChain] and wires each one to dial through the next, so the result is a
 * single SOCKS5 port (the entry hop's) that Xray proxies to exactly as it would a lone engine.
 *
 * The exit hop is started first, so its port is already listening when the hop before it is told to
 * dial through it (`upstreamSocks`); the entry hop is started last and is what [Running.entry] names.
 * If any hop fails to start, every hop already started is stopped and the failure is thrown, so a
 * half-built chain never carries traffic.
 */
object ChainRunner {
    /** A started chain: the entry hop Xray talks to, and a stop that tears the whole chain down. */
    class Running(
        val entryContext: SidecarContext,
        val entryLaunch: SidecarLaunch,
        val engines: List<RunningEngine>,
        private val onStop: () -> Unit
    ) : RunningEngine {
        override val isAlive: Boolean get() = engines.all { it.isAlive }
        override fun awaitReady(timeoutMs: Long): Boolean = engines.all { it.awaitReady(timeoutMs) }
        override fun stop() = onStop()
    }

    /** Builds one hop's work directory, SOCKS port and login; [upstreamSocks] is the next hop's port. */
    fun interface ContextFactory {
        fun create(engineId: String, socksPort: Int, upstreamSocks: Int?): SidecarContext
    }

    /** Prepares and starts one hop, returning the running engine and the launch that configured it. */
    fun interface HopStarter {
        fun start(engine: SidecarEngine, profile: VlessProfile, context: SidecarContext): Pair<RunningEngine, SidecarLaunch>
    }

    /**
     * Starts [chain]. [engineFor] resolves a hop's engine id (null when this build does not carry it),
     * [freePort] hands out loopback ports, and [start] prepares and launches one hop.
     *
     * @throws IllegalStateException when a hop's engine is missing or a hop fails to start.
     */
    fun start(
        chain: EngineChain.Chain,
        engineFor: (String) -> SidecarEngine?,
        freePort: () -> Int,
        context: ContextFactory,
        start: HopStarter
    ): Running {
        EngineChain.problem(chain)?.let { throw IllegalStateException(it) }
        val started = ArrayList<RunningEngine>()
        fun stopAll() = started.asReversed().forEach { runCatching { it.stop() } }
        try {
            // Port per hop index, filled as each hop is started (exit first).
            val ports = IntArray(chain.hops.size)
            var entryContext: SidecarContext? = null
            var entryLaunch: SidecarLaunch? = null
            for (step in EngineChain.plan(chain)) {
                val engine = engineFor(step.hop.engineId)
                    ?: throw IllegalStateException("The ${step.hop.engineId} engine is not included in this build for this phone")
                val port = freePort()
                ports[step.index] = port
                val upstream = step.upstreamHop?.let { ports[it] }
                val ctx = context.create(step.hop.engineId, port, upstream)
                val (running, launch) = start.start(engine, EngineChain.hopProfile(step.hop), ctx)
                started += running
                if (step.index == 0) {
                    entryContext = ctx
                    entryLaunch = launch
                }
            }
            return Running(entryContext!!, entryLaunch!!, started.toList(), ::stopAll)
        } catch (t: Throwable) {
            stopAll()
            throw t
        }
    }

    // -- Chain profiles -------------------------------------------------------

    private const val KEY_CHAIN = "maximusChain"

    /** True when [profile] is a saved chain (as [profile] below wrote it). */
    fun isChain(profile: VlessProfile): Boolean =
        runCatching { org.json.JSONObject(profile.extraSettings).has(KEY_CHAIN) }.getOrDefault(false)

    /**
     * A saved profile standing for a chain. Each hop keeps only its own knobs (Psiphon's region, Tor's
     * bridges), so the hop engines are rebuilt from them at run time.
     */
    fun profile(chain: EngineChain.Chain, titleOf: (String) -> String): VlessProfile {
        val hops = org.json.JSONArray()
        chain.hops.forEach { hop ->
            val o = org.json.JSONObject().put("engine", hop.engineId)
            if (hop.region.isNotBlank()) o.put("region", hop.region)
            if (hop.bridges.isNotBlank()) o.put("bridges", hop.bridges)
            hops.put(o)
        }
        val extras = org.json.JSONObject().put(KEY_CHAIN, hops)
        return VlessProfile(
            name = EngineChain.describe(chain, titleOf),
            address = "chain",
            port = 0,
            uuid = "",
            extraSettings = extras.toString()
        )
    }

    /** Reads the chain a [profile] stands for, or null when it is not a chain or is invalid. */
    fun readChain(profile: VlessProfile): EngineChain.Chain? {
        val array = runCatching { org.json.JSONObject(profile.extraSettings).optJSONArray(KEY_CHAIN) }.getOrNull() ?: return null
        val hops = (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val engine = o.optString("engine").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            EngineChain.Hop(engine, o.optString("region").trim(), o.optString("bridges").trim())
        }
        return EngineChain.Chain(hops).takeIf { it.hops.size == array.length() && EngineChain.problem(it) == null }
    }
}
