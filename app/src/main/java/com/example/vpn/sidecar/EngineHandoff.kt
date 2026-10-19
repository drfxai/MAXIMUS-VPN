package com.example.vpn.sidecar

import com.example.data.model.OperationalMode

/**
 * An engine that just carried real requests in a recovery test, kept running for the connect that
 * follows. Starting it again costs another bootstrap (Tor over a filtered network can take minutes and
 * fail where the test passed), so the connect takes this one over instead.
 *
 * One engine at most, for [MAX_AGE_MS]; anything not taken by then is stopped and its work folder
 * deleted. A take only matches the same saved config, engine and mode the test ran with.
 */
object EngineHandoff {
    data class Parked(
        val profileId: String,
        val engineId: String,
        val mode: OperationalMode,
        val engine: RunningEngine,
        val context: SidecarContext,
        val launch: SidecarLaunch,
        val parkedAt: Long
    )

    private var parked: Parked? = null

    /** Keeps [engine] for the next connect; a previously parked one is stopped. */
    @Synchronized
    fun park(p: Parked) {
        parked?.let { discard(it) }
        parked = p
    }

    /**
     * The parked engine when it matches and is still alive; the caller then owns it, and stopping it
     * also deletes its work folder. Anything else parked is stopped.
     */
    @Synchronized
    fun take(profileId: String, engineId: String, mode: OperationalMode, now: Long = System.currentTimeMillis()): Parked? {
        val p = parked ?: return null
        parked = null
        val fits = p.profileId == profileId && p.engineId == engineId && p.mode == mode &&
            now - p.parkedAt in 0..MAX_AGE_MS && p.engine.isAlive
        if (!fits) {
            discard(p)
            return null
        }
        val inner = p.engine
        return p.copy(engine = object : RunningEngine {
            override val isAlive: Boolean get() = inner.isAlive
            override fun awaitReady(timeoutMs: Long): Boolean = inner.awaitReady(timeoutMs)
            override fun stop() {
                try { inner.stop() } finally { runCatching { p.context.workDir.deleteRecursively() } }
            }
        })
    }

    /** Whether [workDir] belongs to the parked engine (its folder must not be deleted yet). */
    @Synchronized
    fun holds(workDir: java.io.File): Boolean = parked?.context?.workDir == workDir

    /** Stops whatever is parked. */
    @Synchronized
    fun clear() {
        parked?.let { discard(it) }
        parked = null
    }

    private fun discard(p: Parked) {
        try { runCatching { p.engine.stop() } } finally { runCatching { p.context.workDir.deleteRecursively() } }
    }

    const val MAX_AGE_MS = 60_000L
}
