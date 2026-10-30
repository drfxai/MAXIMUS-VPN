package com.example.xray

import com.example.core.SecretRedactor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

enum class LogLevel(val label: String) {
    DEBUG("DEBUG"),
    INFO("INFO"),
    WARN("WARN"),
    ERROR("ERROR"),
    FATAL("FATAL")
}

object XrayLogManager {

    private const val MAX_LOGS = 500

    /**
     * Log tags whose errors are never a connection failure. A subscription refresh answered "HTTP 503 ...
     * this does not establish a tunnel failure" was read as TUN_ESTABLISH_FAILED ("establish" + "tun").
     */
    private val NOT_CONNECTION_TAGS = setOf("SUBSCRIPTION")
    private val logQueue = ArrayDeque<String>(MAX_LOGS + 20)
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _logsFlow = MutableStateFlow<List<String>>(emptyList())
    val logsFlow: StateFlow<List<String>> = _logsFlow.asStateFlow()

    @Synchronized
    fun appendLog(message: String, tag: String = "XRAY") {
        log(tag = tag, message = message, level = LogLevel.INFO)
    }

    @Synchronized
    fun d(tag: String, message: String) {
        log(tag = tag, message = message, level = LogLevel.DEBUG)
    }

    @Synchronized
    fun i(tag: String, message: String) {
        log(tag = tag, message = message, level = LogLevel.INFO)
    }

    @Synchronized
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        log(tag = tag, message = message, level = LogLevel.WARN, throwable = throwable)
    }

    @Synchronized
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        log(tag = tag, message = message, level = LogLevel.ERROR, throwable = throwable)
    }

    @Synchronized
    fun fatal(tag: String, message: String, throwable: Throwable? = null) {
        log(tag = tag, message = message, level = LogLevel.FATAL, throwable = throwable)
    }

    @Synchronized
    fun log(
        tag: String,
        message: String,
        level: LogLevel = LogLevel.INFO,
        throwable: Throwable? = null
    ) {
        val timestamp = dateFormat.format(Date())
        val sanitizedMsg = SecretRedactor.redact(message)
        val levelTag = if (level != LogLevel.INFO) " [${level.label}]" else ""
        val mainLine = "[$timestamp]$levelTag [$tag] $sanitizedMsg"

        addEntry(mainLine)

        if (throwable != null) {
            val trace = SecretRedactor.formatThrowable(throwable, maxFrames = 8)
            for (line in trace.lines()) {
                if (line.isNotBlank()) {
                    addEntry("[$timestamp] [${level.label}] [$tag]   $line")
                }
            }
        }

        _logsFlow.value = ArrayList(logQueue)

        // Errors also become structured events, so reports can group and count them.
        if (level == LogLevel.ERROR || level == LogLevel.FATAL) {
            runCatching {
                com.example.vpn.diagnostics.events.EventLog.event(
                    name = "log.error.${tag.lowercase()}",
                    severity = if (level == LogLevel.FATAL) com.example.vpn.diagnostics.events.Severity.FATAL else com.example.vpn.diagnostics.events.Severity.ERROR,
                    attributes = mapOf("message" to sanitizedMsg.take(300)),
                    // A subscription refresh is not a connection: its errors carry no connection stage.
                    stage = if (tag.uppercase() in NOT_CONNECTION_TAGS) null
                        else com.example.vpn.diagnostics.FailureStage.fromText(message).takeIf { it != com.example.vpn.diagnostics.FailureStage.UNKNOWN },
                    error = throwable
                )
            }
        }
    }

    private fun addEntry(entry: String) {
        if (logQueue.size >= MAX_LOGS) {
            logQueue.pollFirst()
        }
        logQueue.addLast(entry)
        writeToDisk(entry)
    }

    // The log is also written through to a small file in the app's private storage, so a crash, a
    // forced stop or Android ending the app does not wipe it: the next run shows the previous run's
    // tail first. Lines are already redacted. Panic lock deletes the files (see wipeDisk).
    private const val PREVIOUS_TAIL = 200
    private const val REWRITE_AFTER = 3_000
    private var diskDir: java.io.File? = null
    private var writer: java.io.Writer? = null
    private var writtenSinceRewrite = 0

    /** Starts writing through to [dir], first moving the last run's file aside and showing its tail. */
    @Synchronized
    fun attachDisk(dir: java.io.File) {
        if (diskDir != null) return
        dir.mkdirs()
        val current = java.io.File(dir, SESSION_FILE)
        val previous = java.io.File(dir, PREVIOUS_FILE)
        if (current.length() > 0) {
            previous.delete()
            if (!current.renameTo(previous)) current.delete()
        }
        val tail = runCatching { previous.readLines().takeLast(PREVIOUS_TAIL) }.getOrDefault(emptyList())
        diskDir = dir
        openWriter(append = false)
        if (tail.isNotEmpty()) {
            val earlier = ArrayList(logQueue)
            logQueue.clear()
            logQueue.addLast("──── Previous run (kept across the restart) ────")
            tail.forEach { if (logQueue.size >= MAX_LOGS) logQueue.pollFirst(); logQueue.addLast(it) }
            logQueue.addLast("──── This run ────")
            earlier.forEach { addEntry(it) }
            _logsFlow.value = ArrayList(logQueue)
        }
    }

    /** Deletes the log files and clears the log on screen (panic lock). */
    @Synchronized
    fun wipeDisk() {
        logQueue.clear()
        _logsFlow.value = emptyList()
        val dir = diskDir ?: return
        runCatching { writer?.close() }
        writer = null
        java.io.File(dir, SESSION_FILE).delete()
        java.io.File(dir, PREVIOUS_FILE).delete()
        openWriter(append = false)
    }

    private fun openWriter(append: Boolean) {
        val dir = diskDir ?: return
        writer = runCatching {
            java.io.OutputStreamWriter(java.io.FileOutputStream(java.io.File(dir, SESSION_FILE), append), Charsets.UTF_8)
        }.getOrNull()
        writtenSinceRewrite = 0
    }

    private fun writeToDisk(entry: String) {
        val w = writer ?: return
        val ok = runCatching { w.write(entry); w.write("\n"); w.flush() }.isSuccess
        if (!ok) { runCatching { w.close() }; writer = null; return }
        // Keep the file about as long as the on-screen log.
        if (++writtenSinceRewrite >= REWRITE_AFTER) {
            runCatching { w.close() }
            openWriter(append = false)
            val lines = ArrayList(logQueue)
            writer?.let { nw -> runCatching { lines.forEach { nw.write(it); nw.write("\n") }; nw.flush() } }
        }
    }

    private const val SESSION_FILE = "session.log"
    private const val PREVIOUS_FILE = "previous-session.log"

    @Synchronized
    fun getLogs(): List<String> = ArrayList(logQueue)

    @Synchronized
    fun clear() {
        logQueue.clear()
        _logsFlow.value = emptyList()
        // Clearing the log on screen also clears this run's file.
        if (diskDir != null) { runCatching { writer?.close() }; openWriter(append = false) }
    }
}

