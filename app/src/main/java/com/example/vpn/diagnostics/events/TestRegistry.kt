package com.example.vpn.diagnostics.events

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Every background test the app runs (pings, tunnel checks, diagnostics), so a user and a report can
 * see what is queued, running, done or cancelled, and why.
 *
 * A test belongs to a session (or to none, for tests of saved servers while disconnected). Ending a
 * session cancels its tests, and a result that arrives for a cancelled test, or for a session that
 * is no longer current, is refused: stale results never overwrite a new session.
 */
class TestRegistry(
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (DiagEvent) -> Unit = { EventLog.record(it) }
) {
    enum class State { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }

    data class Entry(
        val id: String,
        val type: String,
        val sessionId: String?,
        val profileRef: String?,
        val state: State,
        val queuedAt: Long,
        val startedAt: Long? = null,
        val endedAt: Long? = null,
        val result: String? = null,
        val cancelReason: String? = null,
        val failureStage: com.example.vpn.diagnostics.FailureStage? = null
    ) {
        val finished: Boolean get() = state == State.COMPLETED || state == State.FAILED || state == State.CANCELLED
        val durationMs: Long? get() = if (startedAt != null && endedAt != null) endedAt - startedAt else null
    }

    companion object {
        const val MAX_FINISHED = 100
    }

    private val jobs = HashMap<String, Job>()
    private val _tests = MutableStateFlow<List<Entry>>(emptyList())
    val tests: StateFlow<List<Entry>> = _tests.asStateFlow()

    @Volatile var currentSession: String? = null
        private set

    private fun emit(e: Entry, severity: Severity = Severity.INFO) {
        log(DiagEvent(
            timestamp = clock(), severity = severity, name = "test.${e.state.name.lowercase()}",
            sessionId = e.sessionId, testId = e.id, profileRef = e.profileRef,
            attributes = buildMap {
                put("test.type", e.type)
                e.cancelReason?.let { put("cancel.reason", it) }
            },
            durationMs = e.durationMs,
            failureStage = e.failureStage,
            result = when (e.state) { State.COMPLETED -> "PASS"; State.FAILED -> "FAIL"; State.CANCELLED -> "CANCELLED"; else -> null }
        ))
    }

    @Synchronized
    private fun update(id: String, change: (Entry) -> Entry?): Entry? {
        var updated: Entry? = null
        val list = _tests.value.map { if (it.id == id) (change(it) ?: it).also { n -> if (n !== it) updated = n } else it }
        if (updated == null) return null
        val finished = list.filter { it.finished }
        _tests.value = if (finished.size > MAX_FINISHED) {
            val drop = finished.sortedBy { it.endedAt ?: 0 }.take(finished.size - MAX_FINISHED).map { it.id }.toSet()
            list.filterNot { it.id in drop }
        } else list
        return updated
    }

    /** Registers a test; returns its id. */
    @Synchronized
    fun queue(type: String, sessionId: String?, profileRef: String? = null): String {
        val e = Entry("t-" + UUID.randomUUID().toString().take(8), type, sessionId, profileRef, State.QUEUED, clock())
        _tests.value = _tests.value + e
        emit(e, Severity.DEBUG)
        return e.id
    }

    /** Ties a coroutine to the test so cancelling the test cancels the work. */
    @Synchronized
    fun attach(id: String, job: Job) {
        jobs[id] = job
        job.invokeOnCompletion { synchronized(this) { jobs.remove(id) } }
    }

    fun start(id: String): Boolean =
        update(id) { if (it.state == State.QUEUED) it.copy(state = State.RUNNING, startedAt = clock()) else null }?.also { emit(it, Severity.DEBUG) } != null

    /**
     * Records the outcome. Returns false, and changes nothing, when the test was already cancelled or
     * finished, or belongs to a session that has ended: the caller must then drop its result.
     */
    fun finish(id: String, success: Boolean, result: String? = null, stage: com.example.vpn.diagnostics.FailureStage? = null): Boolean {
        val session = currentSession
        val e = update(id) {
            if (it.finished) null
            else if (it.sessionId != null && it.sessionId != session) null
            else it.copy(state = if (success) State.COMPLETED else State.FAILED, endedAt = clock(), result = result, startedAt = it.startedAt ?: clock(), failureStage = if (success) null else stage)
        } ?: return false
        emit(e, if (success) Severity.INFO else Severity.WARN)
        return true
    }

    fun cancel(id: String, reason: String): Boolean {
        val e = update(id) { if (it.finished) null else it.copy(state = State.CANCELLED, endedAt = clock(), cancelReason = reason) } ?: return false
        synchronized(this) { jobs.remove(id) }?.cancel()
        emit(e)
        return true
    }

    /** Is a result for [id] still wanted? */
    fun isLive(id: String): Boolean = _tests.value.firstOrNull { it.id == id }?.let { !it.finished && (it.sessionId == null || it.sessionId == currentSession) } ?: false

    /** A new session begins: tests of any other session are cancelled. */
    fun beginSession(sessionId: String?) {
        currentSession = sessionId
        _tests.value.filter { !it.finished && it.sessionId != null && it.sessionId != sessionId }.forEach { cancel(it.id, "session ended") }
    }

    /** The session ended (disconnect): its tests no longer mean anything and are cancelled. */
    fun endSession(reason: String = "disconnected") {
        val ended = currentSession
        currentSession = null
        _tests.value.filter { !it.finished && it.sessionId != null && it.sessionId == ended }.forEach { cancel(it.id, reason) }
    }

    fun counts(): Map<State, Int> = _tests.value.groupingBy { it.state }.eachCount()
}

/** The app-wide registry. */
object Tests {
    val registry = TestRegistry()
}
