package com.example.vpn.sidecar

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Raw engine output lines for in-process watchers (the recovery search's caps): ByeDPI's receive timeouts, Tor's
 * bootstrap progress and the bridge program's dial failures. Lines are never stored or logged here; with no
 * watcher subscribed, publishing costs one empty-list check.
 */
object EngineSignals {
    /** [source]: "byedpi" (and other sidecar names), "tor" (Tor notice summaries), "lyrebird", "tor-bridge". */
    fun interface Listener {
        fun line(source: String, line: String)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    fun subscribe(listener: Listener): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    fun publish(source: String, line: String) {
        if (listeners.isEmpty()) return
        listeners.forEach { runCatching { it.line(source, line) } }
    }
}
