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
    fun track(engine: RunningEngine): RunningEngine {
        val tracked = object : RunningEngine {
            override val isAlive: Boolean get() = engine.isAlive
            override fun awaitReady(timeoutMs: Long): Boolean = engine.awaitReady(timeoutMs)
            override fun stop() {
                live.remove(this)
                engine.stop()
            }
        }
        live.add(tracked)
        return tracked
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
