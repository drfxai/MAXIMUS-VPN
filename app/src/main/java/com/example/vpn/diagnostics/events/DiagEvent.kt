package com.example.vpn.diagnostics.events

import com.example.core.SecretRedactor
import com.example.vpn.diagnostics.FailureStage
import org.json.JSONObject

/** OpenTelemetry severity levels (the numbers are OTel's SeverityNumber ranges). */
enum class Severity(val otelNumber: Int) { DEBUG(5), INFO(9), WARN(13), ERROR(17), FATAL(21) }

/**
 * One structured diagnostic event, shaped like an OpenTelemetry log record: a timestamp, a severity,
 * an event name and attributes. Correlation ids tie it to a session, an attempt and a test. Values are
 * redacted when the event is made and again when a report is exported.
 *
 * [kind] keeps what was measured (OBSERVATION) apart from what is concluded from it (ASSESSMENT).
 */
data class DiagEvent(
    val timestamp: Long,
    val severity: Severity,
    val name: String,
    val sessionId: String? = null,
    val attemptId: String? = null,
    val testId: String? = null,
    /** A hash of the profile's id ("p:3fa2…"), never its address or credentials. */
    val profileRef: String? = null,
    val engine: String? = null,
    /** The anonymous network bucket (NetworkCapabilityProfile.key) or the network kind. */
    val network: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val durationMs: Long? = null,
    /** PASS, FAIL, CANCELLED, or null for plain events. */
    val result: String? = null,
    val failureStage: FailureStage? = null,
    /** Exception class and redacted message, when there was one. */
    val exception: String? = null,
    val kind: Kind = Kind.OBSERVATION
) {
    enum class Kind { OBSERVATION, ASSESSMENT }

    /** The event as an OpenTelemetry-style log record. */
    fun toJson(): JSONObject = JSONObject().apply {
        put("timeUnixNano", timestamp * 1_000_000)
        put("severityText", severity.name)
        put("severityNumber", severity.otelNumber)
        put("body", name)
        val attrs = JSONObject()
        attrs.put("event.name", name)
        attrs.put("event.kind", kind.name)
        sessionId?.let { attrs.put("session.id", it) }
        attemptId?.let { attrs.put("attempt.id", it) }
        testId?.let { attrs.put("test.id", it) }
        profileRef?.let { attrs.put("profile.ref", it) }
        engine?.let { attrs.put("engine", it) }
        network?.let { attrs.put("network", it) }
        durationMs?.let { attrs.put("duration.ms", it) }
        result?.let { attrs.put("result", it) }
        failureStage?.let { attrs.put("failure.stage", it.name) }
        exception?.let { attrs.put("exception", it) }
        attributes.forEach { (k, v) -> attrs.put(k, v) }
        put("attributes", attrs)
    }

    companion object {
        fun fromJson(o: JSONObject): DiagEvent {
            val a = o.optJSONObject("attributes") ?: JSONObject()
            val known = setOf("event.name", "event.kind", "session.id", "attempt.id", "test.id", "profile.ref", "engine",
                "network", "duration.ms", "result", "failure.stage", "exception")
            val extra = a.keys().asSequence().filter { it !in known }.associateWith { a.optString(it) }
            fun s(k: String) = if (a.has(k) && !a.isNull(k)) a.optString(k) else null
            return DiagEvent(
                timestamp = o.optLong("timeUnixNano") / 1_000_000,
                severity = runCatching { Severity.valueOf(o.optString("severityText")) }.getOrDefault(Severity.INFO),
                name = o.optString("body"),
                sessionId = s("session.id"), attemptId = s("attempt.id"), testId = s("test.id"),
                profileRef = s("profile.ref"), engine = s("engine"), network = s("network"),
                attributes = extra,
                durationMs = if (a.has("duration.ms")) a.optLong("duration.ms") else null,
                result = s("result"),
                failureStage = s("failure.stage")?.let { runCatching { FailureStage.valueOf(it) }.getOrNull() },
                exception = s("exception"),
                kind = runCatching { Kind.valueOf(a.optString("event.kind")) }.getOrDefault(Kind.OBSERVATION)
            )
        }

        /** Exception class plus its redacted message, short enough for a report. */
        fun describe(e: Throwable?): String? = e?.let {
            val message = it.message?.let(SecretRedactor::redact)?.take(300)
            if (message.isNullOrBlank()) it.javaClass.name else "${it.javaClass.name}: $message"
        }

        /** A stable, non-reversible reference to a profile: "p:" and 10 hex digits of its id's hash. */
        fun profileRef(profileId: String?): String? = profileId?.takeIf { it.isNotBlank() }?.let {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(("profile|$it").toByteArray())
            "p:" + digest.take(5).joinToString("") { b -> "%02x".format(b) }
        }
    }

    /** The same event with every free-text value redacted. */
    fun redacted(): DiagEvent = copy(
        name = SecretRedactor.redact(name),
        attributes = attributes.mapValues { (_, v) -> SecretRedactor.redact(v) },
        exception = exception?.let(SecretRedactor::redact)
    )
}
