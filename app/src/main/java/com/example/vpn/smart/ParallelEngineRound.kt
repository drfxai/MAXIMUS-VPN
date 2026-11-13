package com.example.vpn.smart

import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * A short first round of the Connect recovery search: the first candidate of each engine type is checked
 * at the same time instead of one after another, so a working Psiphon is not kept waiting behind a MASQUE
 * scan that is going to fail. The first one that carries a real request wins and the others are stopped
 * at once (their threads are interrupted, which stops their engine processes).
 *
 * Bounded so battery and data stay sane:
 * - at most [MAX_PARALLEL] engines at a time, [METERED_PARALLEL] on a metered network, none on low battery;
 * - one candidate per engine type, never two configs of the same engine;
 * - Tor is left to the serial search, whose progress-aware window is the only one long enough for a Tor
 *   bootstrap on a filtered network (starting it here would pay that bootstrap twice);
 * - a hard [WINDOW_MS]; a check still running then is stopped and counts as not tested, so the serial
 *   search can still try it with its full window.
 */
object ParallelEngineRound {
    const val MAX_PARALLEL = 3
    const val METERED_PARALLEL = 2
    const val WINDOW_MS = 60_000L

    data class Entry(val id: String, val engineKey: String, val tor: Boolean)

    fun concurrency(metered: Boolean?, lowBattery: Boolean): Int = when {
        lowBattery -> 0
        metered == true -> METERED_PARALLEL
        else -> MAX_PARALLEL
    }

    /**
     * The candidates of the round, in the order given (best first): the first of each engine type, no Tor,
     * nothing in [exclude], at most [limit]. A round of fewer than two is not worth running in parallel.
     */
    fun pick(ordered: List<Entry>, exclude: Set<String>, limit: Int): List<Entry> {
        if (limit < 2) return emptyList()
        val chosen = ordered.asSequence().filter { !it.tor && it.id !in exclude }
            .distinctBy { it.engineKey }.take(limit).toList()
        return if (chosen.size >= 2) chosen else emptyList()
    }

    data class Outcome<T>(val winner: Pair<String, T>?, val finished: Map<String, T>)

    /**
     * Runs [tasks] at once on their own threads. Returns at the first result [passed] accepts (the rest are
     * interrupted), or when every task finished, or after [timeoutMs] (the rest are interrupted). [finished]
     * holds every task that completed with a result; a task that threw or was stopped is absent.
     * Blocking; an interrupt of the caller stops every task and is rethrown.
     */
    fun <T> race(tasks: List<Pair<String, () -> T>>, passed: (T) -> Boolean, timeoutMs: Long,
                 nanoTime: () -> Long = System::nanoTime): Outcome<T> {
        if (tasks.isEmpty()) return Outcome(null, emptyMap())
        val pool = Executors.newFixedThreadPool(tasks.size) { r -> Thread(r, "recovery-parallel").apply { isDaemon = true } }
        val service = ExecutorCompletionService<Pair<String, T>>(pool)
        val futures = mutableListOf<Future<Pair<String, T>>>()
        val finished = linkedMapOf<String, T>()
        try {
            tasks.forEach { (id, task) -> futures += service.submit { id to task() } }
            val deadline = nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            repeat(tasks.size) {
                val left = deadline - nanoTime()
                if (left <= 0) return Outcome(null, finished)
                val done = service.poll(left, TimeUnit.NANOSECONDS) ?: return Outcome(null, finished)
                val value = runCatching { done.get() }.getOrNull() ?: return@repeat
                finished[value.first] = value.second
                if (passed(value.second)) return Outcome(value, finished)
            }
            return Outcome(null, finished)
        } finally {
            futures.forEach { it.cancel(true) }
            pool.shutdownNow()
        }
    }
}
