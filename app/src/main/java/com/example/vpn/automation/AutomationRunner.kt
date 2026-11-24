package com.example.vpn.automation

import kotlinx.coroutines.CancellationException

/** What one task body reports back. */
data class TaskResult(val found: Int, val detail: String = "", val ok: Boolean = true)

/** One upkeep task's work. */
fun interface TaskBody {
    suspend fun run(env: AutomationEnv): TaskResult
}

/**
 * One upkeep run: asks [AutomationPolicy] what to do, runs each chosen task body in order, and records every
 * outcome (run, failed or skipped with its reason) in the [AutomationStatusBook]. A task that throws is
 * recorded as failed and the run goes on; cancellation stops the run. Pure apart from the bodies.
 */
class AutomationRunner(
    private val book: AutomationStatusBook,
    private val bodies: Map<AutomationTask, TaskBody>,
    private val clock: () -> Long = System::currentTimeMillis
) {
    data class Summary(val ran: List<AutomationTask>, val skipped: Map<AutomationTask, SkipReason>, val failed: List<AutomationTask>) {
        /** Every task wanted to run but a VPN was up: worth trying again a little later. */
        val blockedByVpn: Boolean get() = ran.isEmpty() && skipped.values.any { it == SkipReason.VPN_ACTIVE } &&
            skipped.values.none { it == SkipReason.FRESH }
    }

    suspend fun run(trigger: AutomationTrigger, env: AutomationEnv, only: Set<AutomationTask>? = null): Summary {
        val ran = mutableListOf<AutomationTask>()
        val failed = mutableListOf<AutomationTask>()
        val skipped = linkedMapOf<AutomationTask, SkipReason>()
        for (d in AutomationPolicy.plan(trigger, env) { t, n -> book.lastOk(t, n) }) {
            if (only != null && d.task !in only) continue
            val network = AutomationPolicy.cacheKey(d.task, env)
            val skip = d.skip ?: if (bodies[d.task] == null) SkipReason.NOTHING_TO_DO else null
            if (skip != null) {
                if (skip != SkipReason.NOT_THIS_TRIGGER) {
                    skipped[d.task] = skip
                    book.record(TaskStatus(d.task, clock(), TaskOutcome.SKIPPED, network = network, trigger = trigger, skip = skip))
                }
                continue
            }
            val result = try {
                bodies.getValue(d.task).run(env)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TaskResult(0, e.message ?: e.javaClass.simpleName, ok = false)
            }
            ran += d.task
            if (!result.ok) failed += d.task
            book.record(TaskStatus(d.task, clock(), if (result.ok) TaskOutcome.OK else TaskOutcome.FAILED, result.found,
                network, trigger, null, result.detail))
        }
        return Summary(ran, skipped, failed)
    }
}

/**
 * Which saved configs keep failing revival. "Drop dead ones" only ever applies to configs that came from a
 * subscription (the subscription brings them back if they revive upstream), are not favourites, and were dead
 * in [STRIKES] runs in a row spread over at least [MIN_SPAN_MS]: one bad network or one bad hour never deletes
 * anything. A config that answers once is cleared. Pure; [load]/[save] hold JSON.
 */
class ConfigHealthBook(
    private val load: () -> String? = { null },
    private val save: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis
) {
    private class Strike(var count: Int, val firstAt: Long, val networks: MutableSet<String>)
    private val strikes: LinkedHashMap<String, Strike> by lazy { parse(load()) }

    data class Candidate(val id: String, val fromSubscription: Boolean, val favorite: Boolean)

    /** [results]: profile id → true (answered), false (dead after every recipe), null (not tested: no verdict). */
    @Synchronized
    fun record(network: String, results: Map<String, Boolean?>) {
        val now = clock()
        results.forEach { (id, alive) ->
            when (alive) {
                true -> strikes.remove(id)
                false -> strikes.getOrPut(id) { Strike(0, now, mutableSetOf()) }.apply { count++; networks += network }
                null -> Unit
            }
        }
        while (strikes.size > MAX) strikes.remove(strikes.keys.first())
        persist()
    }

    @Synchronized
    fun strikes(id: String): Int = strikes[id]?.count ?: 0

    @Synchronized
    fun droppable(candidates: List<Candidate>): List<String> {
        val now = clock()
        return candidates.filter { c ->
            val s = strikes[c.id] ?: return@filter false
            c.fromSubscription && !c.favorite && s.count >= STRIKES && now - s.firstAt >= MIN_SPAN_MS
        }.map { it.id }
    }

    @Synchronized
    fun forget(ids: Collection<String>) { ids.forEach { strikes.remove(it) }; persist() }

    private fun persist() {
        val o = org.json.JSONObject()
        strikes.forEach { (id, s) -> o.put(id, org.json.JSONObject().put("c", s.count).put("f", s.firstAt).put("n", org.json.JSONArray(s.networks.toList()))) }
        save(o.toString())
    }

    private fun parse(raw: String?): LinkedHashMap<String, Strike> {
        val out = linkedMapOf<String, Strike>()
        val o = runCatching { org.json.JSONObject(raw ?: return out) }.getOrNull() ?: return out
        o.keys().forEach { id ->
            val s = o.optJSONObject(id) ?: return@forEach
            val n = s.optJSONArray("n")
            out[id] = Strike(s.optInt("c"), s.optLong("f"), (0 until (n?.length() ?: 0)).map { n!!.optString(it) }.toMutableSet())
        }
        return out
    }

    companion object {
        const val STRIKES = 3
        const val MIN_SPAN_MS = 48 * 60 * 60 * 1000L
        private const val MAX = 512
    }
}
