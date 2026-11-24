package com.example.vpn.sidecar

import java.util.concurrent.ConcurrentHashMap

/**
 * Every engine program the app starts (Tor, Psiphon, the DNS tunnel, ...), for a connection or for a
 * test, is tracked here until it stops. Stop and Disconnect end them all at once, so an engine that is
 * still starting (Tor over Snowflake can take a minute) does not hold the disconnect behind it.
 */
object EngineReaper {
    private val live: MutableSet<RunningEngine> = ConcurrentHashMap.newKeySet()

    /** Wraps [engine] so it is tracked until it is stopped. */
    fun track(engine: RunningEngine): RunningEngine = Tracked(engine).also { live.add(it) }

    /**
     * The engine [track] wrapped, so callers can still read what only that kind of engine reports (Psiphon's notices
     * and dials on [SidecarProcess]). Build 171: `tracked as? SidecarProcess` was always null, so Psiphon's startup
     * notices never reached the report and the TCP-retry skip (#272) could never apply.
     */
    fun underlying(engine: RunningEngine): RunningEngine = (engine as? Tracked)?.engine ?: engine

    private class Tracked(val engine: RunningEngine) : RunningEngine {
        override val isAlive: Boolean get() = engine.isAlive
        override val startupFailure: String? get() = engine.startupFailure
        override val transportGeneration get() = engine.transportGeneration
        override val startupStage: String? get() = engine.startupStage
        override val transportStageSinceMs: Long? get() = engine.transportStageSinceMs
        override fun awaitReady(timeoutMs: Long): Boolean = engine.awaitReady(timeoutMs)
        override fun awaitReadyUnless(timeoutMs: Long, giveUp: () -> Boolean): Boolean = engine.awaitReadyUnless(timeoutMs, giveUp)
        override fun stop() {
            live.remove(this)
            engine.stop()
        }
    }

    val count: Int get() = live.size

    /**
     * Stops every tracked engine, each on its own thread, and returns at once (safe on the main thread);
     * each engine's wait-for-ready then returns.
     */
    fun stopAll() {
        live.toList().forEach { e ->
            live.remove(e)
            Thread({ runCatching { e.stop() } }, "maximus-engine-stop").apply { isDaemon = true }.start()
        }
    }
}
