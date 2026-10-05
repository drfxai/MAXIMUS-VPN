package com.example.vpn.engine.registry

/**
 * Stops starting an engine that keeps failing to start. After [threshold] failures in a row it is
 * open (skipped) for [cooldownMs]; then one try is let through, and a success closes it again.
 * NetworkMemory already does the same per kind of connection on each network; this is per engine.
 */
class EngineCircuitBreaker(
    private val threshold: Int = 3,
    private val cooldownMs: Long = 5 * 60 * 1000L
) {
    private class State(var failures: Int = 0, var openedAt: Long? = null)

    private val states = HashMap<String, State>()

    @Synchronized
    fun allows(engineId: String, now: Long = System.currentTimeMillis()): Boolean {
        val s = states[engineId] ?: return true
        val opened = s.openedAt ?: return true
        return now - opened >= cooldownMs
    }

    @Synchronized
    fun recordFailure(engineId: String, now: Long = System.currentTimeMillis()) {
        val s = states.getOrPut(engineId) { State() }
        s.failures++
        if (s.failures >= threshold) s.openedAt = now
    }

    @Synchronized
    fun recordSuccess(engineId: String) {
        states.remove(engineId)
    }
}
