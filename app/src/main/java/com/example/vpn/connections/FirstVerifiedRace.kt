package com.example.vpn.connections

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Runs its entrants at the same time: the first result [run]'s `verified` accepts wins, every other entrant is
 * aborted, and the whole race has one deadline. A result that is not verified never wins; a result `decisive`
 * accepts ends the race without a winner (e.g. WireGuard handshook and carried no data on a carrier that drops
 * WARP data). Any result that arrives after the race closed is handed to [release] so nothing keeps running.
 *
 * Pattern from UAC SNI Spoofer's `EchKeyResolver.race` (first verified result wins, cancel the rest, one stage
 * deadline), rewritten with threads because the connection runner blocks on native engine readiness.
 */
class FirstVerifiedRace<T : Any>(private val release: (T) -> Unit) {
    /** One entrant's handle: an aborted lane stops what it registered with [onAbort] at once. */
    class Lane internal constructor(val label: String) {
        @Volatile var aborted = false
            private set
        private val hooks = CopyOnWriteArrayList<() -> Unit>()
        fun onAbort(hook: () -> Unit) {
            hooks += hook
            if (aborted) runCatching(hook)
        }
        internal fun abort() {
            if (aborted) return
            aborted = true
            hooks.forEach { runCatching(it) }
        }
    }

    data class Outcome<T>(
        /** Label of the entrant whose result was verified first, or null. */
        val winner: String?,
        val value: T?,
        /** Finished entrants that did not win, by label (their own results; nothing of theirs still runs). */
        val losers: Map<String, T>,
        /** Entrants that had not finished when the race ended (aborted). */
        val unfinished: List<String>,
        val timedOut: Boolean,
        /** Label of the result that ended the race without a winner, if any. */
        val decidedBy: String? = null
    )

    fun run(
        entrants: List<Pair<String, (Lane) -> T>>,
        deadlineMs: Long,
        verified: (T) -> Boolean,
        decisive: (T) -> Boolean = { false },
        cancelled: () -> Boolean = { false },
        now: () -> Long = System::currentTimeMillis
    ): Outcome<T> {
        require(entrants.isNotEmpty())
        val lanes = entrants.map { Lane(it.first) }
        val results = LinkedBlockingQueue<Pair<Int, T?>>()
        val lock = Any()
        var closed = false
        val pool = Executors.newFixedThreadPool(entrants.size) { r -> Thread(r, "first-verified-race").apply { isDaemon = true } }
        entrants.forEachIndexed { i, (_, body) ->
            pool.execute {
                val value = try { body(lanes[i]) } catch (t: Throwable) { null }
                val kept = synchronized(lock) { if (!closed) { results.add(i to value); true } else false }
                if (!kept && value != null) runCatching { release(value) }
            }
        }
        val end = now() + deadlineMs
        val finished = mutableSetOf<Int>()
        val losers = linkedMapOf<String, T>()
        var winner: Pair<Int, T>? = null
        var decidedBy: String? = null
        var timedOut = false
        try {
            while (finished.size < entrants.size) {
                if (cancelled()) throw CancellationException("Connection cancelled")
                val left = end - now()
                if (left <= 0) { timedOut = true; break }
                val next = results.poll(minOf(left, POLL_SLICE_MS), TimeUnit.MILLISECONDS) ?: continue
                val (i, value) = next
                finished += i
                if (value != null && verified(value)) { winner = i to value; break }
                if (value != null) losers[lanes[i].label] = value
                if (value != null && decisive(value)) { decidedBy = lanes[i].label; break }
            }
        } finally {
            val late = synchronized(lock) {
                closed = true
                mutableListOf<Pair<Int, T?>>().also { results.drainTo(it) }
            }
            late.forEach { (_, value) -> if (value != null) runCatching { release(value) } }
            lanes.forEachIndexed { i, lane -> if (winner?.first != i) lane.abort() }
            pool.shutdownNow()
        }
        val w = winner
        return Outcome(
            winner = w?.let { lanes[it.first].label }, value = w?.second, losers = losers,
            unfinished = lanes.indices.filter { it !in finished }.map { lanes[it].label },
            timedOut = timedOut, decidedBy = decidedBy
        )
    }

    companion object {
        const val POLL_SLICE_MS = 250L
    }
}
