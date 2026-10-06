package com.example.vpn.diagnostics.report

import com.example.core.SecretRedactor
import com.example.data.model.ConnectionState
import com.example.vpn.diagnostics.events.DiagEvent
import com.example.vpn.diagnostics.events.ErrorAggregator
import com.example.vpn.diagnostics.events.TestRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Who and what produced a report. Only build and device model facts; nothing that identifies a person. */
data class ReportEnvironment(
    val appVersion: String,
    val versionCode: Int,
    val buildCommit: String,
    val buildTime: String,
    val androidVersion: String,
    val sdkInt: Int,
    val device: String,
    val engineVersion: String
)

/**
 * The two diagnostic reports: a short one a person can read in a minute, and a detailed JSON one
 * for tools. Both are built from the same inputs, so they never disagree. Pure, so they are tested
 * without a phone. Everything passes [ReportRedaction.secondPass] before it leaves the builder.
 */
object DiagnosticReportBuilder {
    const val SCHEMA_VERSION = 1
    /** Most events and tests a report carries; older ones are left out and [truncated] says so. */
    const val MAX_EVENTS = 400
    const val MAX_TESTS = 100

    data class Inputs(
        val generatedAt: Long,
        val env: ReportEnvironment,
        val connection: ConnectionState,
        /** NetworkCapabilityProfile.key() of the last measurement, or null. */
        val networkProfile: String?,
        val networkObservations: List<String> = emptyList(),
        val events: List<DiagEvent>,
        val eventLogTruncated: Boolean,
        val tests: List<TestRegistry.Entry>,
        /** RuntimeHealth.summary: earlier exits (crash, ANR, low memory) with redacted traces. */
        val runtime: JSONObject,
        val profileRef: String?
    )

    private fun time(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    fun truncated(i: Inputs) = i.eventLogTruncated || i.events.size > MAX_EVENTS || i.tests.size > MAX_TESTS

    fun json(i: Inputs): JSONObject {
        val c = i.connection
        val events = i.events.takeLast(MAX_EVENTS)
        val root = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("generatedAt", time(i.generatedAt))
            .put("truncated", truncated(i))
            .put("app", JSONObject()
                .put("version", i.env.appVersion)
                .put("versionCode", i.env.versionCode)
                .put("buildCommit", i.env.buildCommit)
                .put("buildTime", i.env.buildTime))
            .put("device", JSONObject()
                .put("android", i.env.androidVersion)
                .put("sdk", i.env.sdkInt)
                .put("model", i.env.device))
            .put("engine", JSONObject()
                .put("name", c.activeEngineName ?: JSONObject.NULL)
                .put("version", i.env.engineVersion))
            .put("session", JSONObject()
                .put("sessionId", c.sessionId ?: JSONObject.NULL)
                .put("attemptId", c.attemptId ?: JSONObject.NULL)
                .put("status", c.status.name)
                .put("profile", i.profileRef ?: JSONObject.NULL)
                .put("verifiedAt", c.verifiedAt?.let(::time) ?: JSONObject.NULL)
                .put("failureStage", c.failureStage?.name ?: JSONObject.NULL)
                .put("probeFailures", c.probeFailures)
                .put("networkGeneration", c.networkGeneration))
            .put("network", JSONObject()
                .put("profile", i.networkProfile ?: JSONObject.NULL)
                .put("observations", JSONArray(i.networkObservations)))
        root.put("errors", JSONArray().apply {
            ErrorAggregator.aggregate(i.events).take(30).forEach { g ->
                put(JSONObject()
                    .put("summary", g.summary)
                    .put("count", g.count)
                    .put("firstAt", time(g.firstAt))
                    .put("lastAt", time(g.lastAt))
                    .put("failureStage", g.stage?.name ?: JSONObject.NULL)
                    .put("profiles", JSONArray(g.profiles.toList()))
                    .put("networks", JSONArray(g.networks.toList()))
                    .put("sessions", JSONArray(g.sessions.toList()))
                    .put("sample", g.sample ?: JSONObject.NULL))
            }
        })
        root.put("tests", JSONArray().apply {
            i.tests.takeLast(MAX_TESTS).forEach { t ->
                put(JSONObject()
                    .put("testId", t.id).put("type", t.type).put("state", t.state.name)
                    .put("sessionId", t.sessionId ?: JSONObject.NULL)
                    .put("profile", t.profileRef ?: JSONObject.NULL)
                    .put("queuedAt", time(t.queuedAt))
                    .put("durationMs", t.durationMs ?: JSONObject.NULL)
                    .put("result", t.result ?: JSONObject.NULL)
                    .put("failureStage", t.failureStage?.name ?: JSONObject.NULL)
                    .put("cancelReason", t.cancelReason ?: JSONObject.NULL))
            }
        })
        root.put("attempts", JSONArray().apply {
            events.filter { it.name == "attempt.start" }.forEach { e ->
                put(JSONObject().put("attemptId", e.attemptId).put("sessionId", e.sessionId).put("at", time(e.timestamp))
                    .put("selected", e.attributes["profile.selected"] ?: JSONObject.NULL)
                    .put("attempted", e.attributes["profile.attempted"] ?: JSONObject.NULL))
            }
        })
        root.put("runtime", i.runtime)
        root.put("events", JSONArray().apply { events.forEach { put(it.toJson()) } })
        // The second redaction pass runs over every string in the report, so nothing added above escapes it.
        return ReportRedaction.secondPass(root)
    }

    /** The short report: what is happening, what failed most, and what to look at. */
    fun summary(i: Inputs): String {
        val c = i.connection
        val sb = StringBuilder()
        sb.appendLine("MAXIMUS VPN · diagnostic summary")
        sb.appendLine("Generated ${time(i.generatedAt)} · report schema $SCHEMA_VERSION")
        sb.appendLine("App ${i.env.appVersion} (${i.env.versionCode}) · build ${i.env.buildCommit} · ${i.env.buildTime}")
        sb.appendLine("Android ${i.env.androidVersion} (API ${i.env.sdkInt}) · ${i.env.device}")
        sb.appendLine("Engine ${c.activeEngineName ?: "none"} · ${i.env.engineVersion}")
        sb.appendLine()
        sb.appendLine("Connection: ${c.statusLabel}" + (c.failureStage?.let { " · stage $it" } ?: ""))
        c.verifiedAt?.let { sb.appendLine("Traffic last verified: ${time(it)}") }
        sb.appendLine("Session ${c.sessionId ?: "-"} · attempt ${c.attemptId ?: "-"} · profile ${i.profileRef ?: "-"}")
        sb.appendLine("Network: ${i.networkProfile ?: "not measured"}")
        val exits = i.runtime.optJSONArray("exits")
        val problems = (0 until (exits?.length() ?: 0)).map { exits!!.getJSONObject(it) }
            .filter { it.optString("reason") in setOf("CRASH", "CRASH_NATIVE", "ANR", "LOW_MEMORY") }
        if (problems.isNotEmpty()) {
            sb.appendLine("Earlier exits: " + problems.groupingBy { it.optString("reason") }.eachCount().entries.joinToString { "${it.value} × ${it.key}" })
        }
        val groups = ErrorAggregator.aggregate(i.events)
        sb.appendLine()
        if (groups.isEmpty()) sb.appendLine("No failures recorded.")
        else {
            sb.appendLine("Most frequent failures:")
            groups.take(5).forEach { g -> sb.appendLine("· ${g.summary}, last ${time(g.lastAt)}, ${g.profiles.size} profile(s)") }
        }
        val counts = i.tests.groupingBy { it.state }.eachCount()
        if (counts.isNotEmpty()) sb.appendLine("Background tests: " + counts.entries.joinToString { "${it.value} ${it.key.name.lowercase()}" })
        val lastPass = i.events.lastOrNull { it.result == "PASS" && it.name == "test.pass" }
        sb.appendLine("Last verified PASS: " + (lastPass?.let {
            "${time(it.timestamp)} · ${it.attributes["test.type"]} to ${it.attributes["destination"]} in ${it.durationMs} ms (attempt ${it.attemptId ?: "-"})"
        } ?: "none"))
        if (truncated(i)) sb.appendLine("Note: older events were dropped by the size limits (truncated).")
        return ReportRedaction.secondPass(sb.toString())
    }
}

/**
 * The export-time redaction pass. Events were redacted when recorded; this catches anything that
 * slipped through or came from older code: proxy credentials, API keys, bot tokens, long tokens,
 * addresses, e-mail addresses and phone numbers.
 */
object ReportRedaction {
    private val TELEGRAM_TOKEN = Regex("\\b\\d{6,12}:[A-Za-z0-9_-]{30,}")
    private val GOOGLE_KEY = Regex("AIza[0-9A-Za-z_-]{30,}")
    private val LONG_TOKEN = Regex("(?<![A-Za-z0-9])[A-Za-z0-9+/_-]{40,}={0,2}")
    private val IPV4 = Regex("\\b(\\d{1,3})\\.(\\d{1,3})\\.\\d{1,3}\\.\\d{1,3}\\b")
    /** Full IPv6 (at least four groups, so a "12:30:45" time is left alone) or a compressed one with "::". */
    private val IPV6 = Regex("(?<![0-9A-Fa-f:])(?:(?:[0-9A-Fa-f]{1,4}:){3,7}[0-9A-Fa-f]{1,4}|(?:[0-9A-Fa-f]{1,4}:)+:(?:[0-9A-Fa-f]{1,4}:?)*[0-9A-Fa-f]{0,4})(?![0-9A-Fa-f:])")
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val PHONE = Regex("(?<![\\w.])\\+\\d{8,15}\\b")
    /** Public resolvers and the probe targets: knowing them helps and reveals nothing. */
    private val KEEP_IPS = setOf("1.1.1.1", "1.0.0.1", "8.8.8.8", "8.8.4.4", "9.9.9.9", "127.0.0.1", "0.0.0.0")

    /** Redacts every string value (and key) of [json] in place, recursively, and returns it. */
    fun secondPass(json: JSONObject): JSONObject {
        val keys = json.keys().asSequence().toList()
        for (k in keys) {
            val v = json.get(k)
            val safeKey = secondPass(k)
            if (safeKey != k) json.remove(k)
            json.put(safeKey, clean(v))
        }
        return json
    }

    private fun clean(v: Any?): Any? = when (v) {
        is String -> secondPass(v)
        is JSONObject -> secondPass(v)
        is JSONArray -> { for (i in 0 until v.length()) v.put(i, clean(v.get(i))); v }
        else -> v
    }

    fun secondPass(text: String): String {
        var t = SecretRedactor.redact(text)
        t = TELEGRAM_TOKEN.replace(t, "[REDACTED_BOT_TOKEN]")
        t = GOOGLE_KEY.replace(t, "[REDACTED_API_KEY]")
        t = EMAIL.replace(t, "[REDACTED_EMAIL]")
        t = PHONE.replace(t, "[REDACTED_PHONE]")
        t = LONG_TOKEN.replace(t, "[REDACTED_TOKEN]")
        t = IPV4.replace(t) { m -> if (m.value in KEEP_IPS) m.value else "${m.groupValues[1]}.${m.groupValues[2]}.x.x" }
        t = IPV6.replace(t) { m -> m.value.split(':').take(2).joinToString(":") + ":x:x" }
        return t
    }
}
