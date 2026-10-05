package com.example.vpn.subscription

import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Fetches a subscription from the first of its sources that answers with configurations.
 *
 * Sources start one after another, [staggerMs] apart, and a failed source starts the next one at once,
 * so a working address answers in one request while a blocked one costs at most [staggerMs]. A source
 * only counts when its payload holds at least one configuration: a filter's block page (HTML with
 * status 200) or an emptied file moves on to the next source instead of being taken as an empty list.
 */
class SubscriptionFetcher(
    /** Downloads one address and returns its body; throws on any network or HTTP error. */
    private val download: (String) -> String,
    /** Number of configurations in a payload. */
    private val count: (String) -> Int,
    private val staggerMs: Long = STAGGER_MS,
    private val deadlineMs: Long = DEADLINE_MS
) {
    sealed class Outcome {
        abstract val failures: List<Pair<String, String>>

        data class Fetched(
            val url: String,
            val payload: String,
            val configs: Int,
            override val failures: List<Pair<String, String>>
        ) : Outcome()

        data class AllFailed(override val failures: List<Pair<String, String>>) : Outcome()
    }

    fun fetch(candidates: List<String>): Outcome {
        val failures = mutableListOf<Pair<String, String>>()
        if (candidates.isEmpty()) return Outcome.AllFailed(failures)
        val pool = Executors.newCachedThreadPool { r -> Thread(r, "subscription-fetch").apply { isDaemon = true } }
        val done = ExecutorCompletionService<Pair<String, Result<Pair<String, Int>>>>(pool)
        val started = mutableListOf<Future<*>>()
        val queue = ArrayDeque(candidates)
        var running = 0
        fun startNext() {
            val url = queue.removeFirstOrNull() ?: return
            running++
            started += done.submit {
                url to runCatching {
                    val body = download(url)
                    val configs = count(body)
                    if (configs == 0) throw IllegalStateException(describeEmpty(body))
                    body to configs
                }
            }
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineMs)
            startNext()
            while (running > 0 || queue.isNotEmpty()) {
                val left = deadline - System.nanoTime()
                if (left <= 0) break
                val next = done.poll(minOf(TimeUnit.MILLISECONDS.toNanos(staggerMs), left), TimeUnit.NANOSECONDS)
                if (next == null) {
                    startNext()
                    continue
                }
                running--
                val (url, result) = next.get()
                result.onSuccess { (body, configs) -> return Outcome.Fetched(url, body, configs, failures.toList()) }
                result.onFailure { failures += url to (it.message ?: it.javaClass.simpleName) }
                startNext()
            }
            if (running > 0) failures += "(still waiting)" to "no answer within ${deadlineMs / 1000} s"
            return Outcome.AllFailed(failures)
        } finally {
            started.forEach { it.cancel(true) }
            pool.shutdownNow()
        }
    }

    companion object {
        const val STAGGER_MS = 2500L
        const val DEADLINE_MS = 30_000L

        internal fun describeEmpty(body: String): String {
            val head = body.trimStart().take(200).lowercase()
            return when {
                body.isBlank() -> "empty file"
                head.startsWith("<!doctype") || head.startsWith("<html") || "<body" in head || "<iframe" in head ->
                    "a web page instead of a subscription (blocked?)"
                else -> "no configurations in the file"
            }
        }
    }
}
