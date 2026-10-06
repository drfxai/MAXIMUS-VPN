package com.example.vpn.diagnostics.events

import com.example.vpn.diagnostics.FailureStage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The app's structured event history: a bounded in-memory ring for the screens, and a bounded file
 * (JSON lines, one OTel-style record each) so recent events survive a restart or a crash.
 *
 * Recording never blocks the caller: events are redacted, added to memory and handed to one
 * background thread that appends them to disk. When limits drop old events, [truncated] says so.
 */
object EventLog {
    const val MAX_MEMORY = 1000
    const val MAX_FILE_BYTES = 512 * 1024L
    /** Events read back from disk at startup. */
    const val RESTORE = 500

    /** What the current connection is, attached to every event that does not name its own. */
    data class Context(val sessionId: String? = null, val attemptId: String? = null, val engine: String? = null, val network: String? = null)

    @Volatile var context: () -> Context = { Context() }
    @Volatile var clock: () -> Long = System::currentTimeMillis

    private val ring = ArrayDeque<DiagEvent>(MAX_MEMORY + 1)
    private val _events = MutableStateFlow<List<DiagEvent>>(emptyList())
    val events: StateFlow<List<DiagEvent>> = _events.asStateFlow()

    @Volatile var truncated: Boolean = false
        private set

    private var dir: File? = null
    private var writer: ExecutorService? = null

    /** Starts persistence in [directory] and restores the most recent events from an earlier run. */
    @Synchronized
    fun init(directory: File) {
        if (dir != null) return
        dir = directory
        writer = Executors.newSingleThreadExecutor { r -> Thread(r, "maximus-events").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
        writer?.execute { directory.mkdirs(); restore() }
    }

    private fun current(): File? = dir?.let { File(it, "events.jsonl") }
    private fun previous(): File? = dir?.let { File(it, "events.1.jsonl") }

    private fun restore() {
        val lines = ArrayList<String>()
        listOfNotNull(previous(), current()).filter { it.exists() }.forEach { f -> runCatching { lines += f.readLines() } }
        val restored = lines.takeLast(RESTORE).mapNotNull { runCatching { DiagEvent.fromJson(JSONObject(it)) }.getOrNull() }
        if (lines.size > RESTORE) truncated = true
        synchronized(this) {
            val newer = ring.toList()
            ring.clear()
            (restored + newer).takeLast(MAX_MEMORY).forEach { ring.addLast(it) }
            _events.value = ring.toList()
        }
    }

    /** Records [event] (redacted, with the current context filled in where the event has none). */
    fun record(event: DiagEvent): DiagEvent {
        val ctx = runCatching { context() }.getOrDefault(Context())
        val e = event.copy(
            sessionId = event.sessionId ?: ctx.sessionId,
            attemptId = event.attemptId ?: ctx.attemptId,
            engine = event.engine ?: ctx.engine,
            network = event.network ?: ctx.network
        ).redacted()
        synchronized(this) {
            if (ring.size >= MAX_MEMORY) {
                ring.pollFirst()
                truncated = true
            }
            ring.addLast(e)
            _events.value = ring.toList()
        }
        val line = e.toJson().toString()
        writer?.let { w -> runCatching { w.execute { append(line) } } }
        return e
    }

    fun event(
        name: String,
        severity: Severity = Severity.INFO,
        attributes: Map<String, String> = emptyMap(),
        profileRef: String? = null,
        testId: String? = null,
        durationMs: Long? = null,
        result: String? = null,
        stage: FailureStage? = null,
        error: Throwable? = null,
        kind: DiagEvent.Kind = DiagEvent.Kind.OBSERVATION
    ): DiagEvent = record(DiagEvent(
        timestamp = clock(), severity = severity, name = name, testId = testId, profileRef = profileRef,
        attributes = attributes, durationMs = durationMs, result = result, failureStage = stage,
        exception = DiagEvent.describe(error), kind = kind
    ))

    /**
     * A PASS that can be checked later: when, for which profile and engine, on which network and
     * interface, against what destination, and how long it took. Never reused for another attempt.
     */
    fun pass(testType: String, destination: String, profileRef: String?, elapsedMs: Long, interfaceName: String, testId: String? = null) =
        event("test.pass", Severity.INFO, mapOf(
            "test.type" to testType, "destination" to destination, "interface" to interfaceName,
            "measured.at" to clock().toString()
        ), profileRef = profileRef, testId = testId, durationMs = elapsedMs, result = "PASS")

    private fun append(line: String) {
        val file = current() ?: return
        runCatching {
            if (file.length() > MAX_FILE_BYTES) {
                previous()?.let { old -> old.delete(); file.renameTo(old) }
                truncated = true
            }
            file.appendText(line + "\n")
        }
    }

    /** Writes everything queued so far; used before the process dies on a crash. */
    fun flush(timeoutMs: Long = 1500) {
        val w = writer ?: return
        runCatching { w.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    fun snapshot(): List<DiagEvent> = synchronized(this) { ring.toList() }

    /** Clears memory and disk; used by "Clear logs". */
    fun clear() {
        synchronized(this) {
            ring.clear()
            _events.value = emptyList()
            truncated = false
        }
        writer?.execute { current()?.delete(); previous()?.delete() }
    }

    /** For tests: back to an empty, unpersisted log. */
    @Synchronized
    internal fun resetForTest() {
        ring.clear()
        _events.value = emptyList()
        truncated = false
        writer?.shutdownNow()
        writer = null
        dir = null
    }
}
