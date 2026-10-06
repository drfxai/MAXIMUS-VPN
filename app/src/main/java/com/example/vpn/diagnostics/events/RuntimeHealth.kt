package com.example.vpn.diagnostics.events

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.example.core.SecretRedactor
import org.json.JSONArray
import org.json.JSONObject

/**
 * Evidence about how the app itself is doing: why earlier runs ended (crash, ANR, low memory, killed
 * by the system), main-thread stalls, memory pressure, and native engines that stopped. All of it goes
 * into the [EventLog] as events; reports read the latest snapshot from [summary].
 */
object RuntimeHealth {
    /** A main thread blocked this long is recorded as a stall (Android shows an ANR at 5 s). */
    const val STALL_MS = 2000L
    private const val PREFS = "runtime_health"
    private const val MAX_TRACE = 4000

    @Volatile var summary: JSONObject = JSONObject()
        private set

    /** Pure: which exit reasons are worth reporting, and how they are named. */
    fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo_REASON_CRASH -> "CRASH"
        ApplicationExitInfo_REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo_REASON_ANR -> "ANR"
        ApplicationExitInfo_REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo_REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo_REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo_REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo_REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo_REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo_REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo_REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo_REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo_REASON_OTHER -> "OTHER"
        else -> "UNKNOWN"
    }

    fun isProblem(reason: String) = reason in setOf("CRASH", "CRASH_NATIVE", "ANR", "LOW_MEMORY", "EXCESSIVE_RESOURCE_USAGE", "SIGNALED", "INITIALIZATION_FAILURE")

    // ApplicationExitInfo's constants, copied so this file can be read (and the pure parts tested) without API 30.
    private const val ApplicationExitInfo_REASON_EXIT_SELF = 1
    private const val ApplicationExitInfo_REASON_SIGNALED = 2
    private const val ApplicationExitInfo_REASON_LOW_MEMORY = 3
    private const val ApplicationExitInfo_REASON_CRASH = 4
    private const val ApplicationExitInfo_REASON_CRASH_NATIVE = 5
    private const val ApplicationExitInfo_REASON_ANR = 6
    private const val ApplicationExitInfo_REASON_INITIALIZATION_FAILURE = 7
    private const val ApplicationExitInfo_REASON_PERMISSION_CHANGE = 8
    private const val ApplicationExitInfo_REASON_EXCESSIVE_RESOURCE_USAGE = 9
    private const val ApplicationExitInfo_REASON_USER_REQUESTED = 10
    private const val ApplicationExitInfo_REASON_USER_STOPPED = 11
    private const val ApplicationExitInfo_REASON_DEPENDENCY_DIED = 12
    private const val ApplicationExitInfo_REASON_OTHER = 13

    /** Starts every collector. Call once from Application.onCreate; the slow parts run on [background]. */
    fun install(context: Context, background: (() -> Unit) -> Unit) {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) background { runCatching { collectExits(app) } }
        // The watchdog runs only while a screen is visible: no wake-ups while the VPN runs in the background.
        (app as? android.app.Application)?.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: android.app.Activity) = visible(+1)
            override fun onActivityStopped(a: android.app.Activity) = visible(-1)
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) = Unit
            override fun onActivityResumed(a: android.app.Activity) = Unit
            override fun onActivityPaused(a: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) = Unit
            override fun onActivityDestroyed(a: android.app.Activity) = Unit
        })
        startWatchdog()
        app.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) memoryEvent("memory.pressure", level)
            }
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = memoryEvent("memory.low", -1)
        })
    }

    private fun memoryEvent(name: String, level: Int) {
        val rt = Runtime.getRuntime()
        EventLog.event(name, Severity.WARN, mapOf(
            "trim.level" to level.toString(),
            "heap.used.mb" to ((rt.totalMemory() - rt.freeMemory()) / 1_048_576).toString(),
            "heap.max.mb" to (rt.maxMemory() / 1_048_576).toString()
        ))
    }

    /** Reads why earlier runs of the app ended, once per exit (remembered by timestamp). */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun collectExits(context: Context) {
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getLong("last_exit", 0L)
        val exits: List<ApplicationExitInfo> = am.getHistoricalProcessExitReasons(context.packageName, 0, 10)
        val recent = JSONArray()
        var newest = seen
        for (info in exits) {
            val reason = reasonName(info.reason)
            val entry = JSONObject()
                .put("timestamp", info.timestamp)
                .put("reason", reason)
                .put("process", info.processName.substringAfter(':', "main"))
                .put("importance", info.importance)
                .put("pss.kb", info.pss)
                .put("rss.kb", info.rss)
                .put("description", SecretRedactor.redact(info.description.orEmpty()).take(300))
            if (info.reason == ApplicationExitInfo_REASON_ANR || info.reason == ApplicationExitInfo_REASON_CRASH_NATIVE) {
                runCatching { info.traceInputStream?.use { it.readBytes().decodeToString() } }.getOrNull()?.let {
                    entry.put("trace", SecretRedactor.redact(mainThreadPart(it)).take(MAX_TRACE))
                }
            }
            recent.put(entry)
            if (info.timestamp > seen && isProblem(reason)) {
                EventLog.record(DiagEvent(
                    timestamp = info.timestamp, severity = if (reason == "LOW_MEMORY") Severity.WARN else Severity.ERROR,
                    name = "app.exit", attributes = mapOf("exit.reason" to reason, "process" to entry.getString("process"),
                        "description" to entry.getString("description")),
                    result = "FAIL"
                ))
            }
            if (info.timestamp > newest) newest = info.timestamp
        }
        if (newest > seen) prefs.edit().putLong("last_exit", newest).apply()
        summary = JSONObject().put("exits", recent)
    }

    /** Only the main thread's stack from an ANR dump: the rest is long and rarely tells why. */
    fun mainThreadPart(trace: String): String {
        val start = trace.indexOf("\"main\"")
        if (start < 0) return trace.take(MAX_TRACE)
        val end = trace.indexOf("\n\n", start).let { if (it < 0) trace.length else it }
        return trace.substring(start, end)
    }

    @Volatile private var lastTick = 0L
    private var watchdog: Thread? = null
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val visibility = java.lang.Object()
    private var visibleScreens = 0

    private fun visible(delta: Int) = synchronized(visibility) {
        visibleScreens = (visibleScreens + delta).coerceAtLeast(0)
        visibility.notifyAll()
    }

    /**
     * Posts a tick to the main thread every second; when the main thread has not run it for
     * [STALL_MS], the stall and the main thread's stack are recorded once per stall.
     */
    @Synchronized
    private fun startWatchdog() {
        if (watchdog != null) return
        val main = Handler(Looper.getMainLooper())
        lastTick = System.nanoTime()
        watchdog = Thread({
            var reported = false
            while (!Thread.currentThread().isInterrupted) {
                try {
                    synchronized(visibility) {
                        if (visibleScreens == 0) {
                            while (visibleScreens == 0) visibility.wait()
                            lastTick = System.nanoTime() // time spent in the background is not a stall
                        }
                    }
                } catch (_: InterruptedException) { return@Thread }
                main.post { lastTick = System.nanoTime() }
                try { Thread.sleep(1000) } catch (_: InterruptedException) { return@Thread }
                val stalledMs = (System.nanoTime() - lastTick) / 1_000_000
                if (stalledMs >= STALL_MS && !reported) {
                    reported = true
                    val stack = Looper.getMainLooper().thread.stackTrace.take(12).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                    EventLog.event("main.stall", Severity.WARN, mapOf("stalled.ms" to stalledMs.toString(), "stack" to stack), durationMs = stalledMs)
                } else if (stalledMs < STALL_MS) reported = false
            }
        }, "maximus-watchdog").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    /** A native engine (Xray, a sidecar) stopped without being asked to. */
    fun engineTerminated(engine: String, exitCode: Int?, expected: Boolean) {
        EventLog.event("engine.terminated", if (expected) Severity.INFO else Severity.ERROR, buildMap {
            put("engine.name", engine)
            exitCode?.let { put("exit.code", it.toString()) }
            put("expected", expected.toString())
        }, result = if (expected) null else "FAIL", stage = if (expected) null else com.example.vpn.diagnostics.FailureStage.ENGINE_START_FAILED)
    }
}
