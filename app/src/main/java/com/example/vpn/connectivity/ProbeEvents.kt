package com.example.vpn.connectivity

import com.example.vpn.diagnostics.events.DiagEvent
import com.example.vpn.diagnostics.events.EventLog
import com.example.vpn.diagnostics.events.Severity

/** Writes a [HealthReport] to the event log: one OBSERVATION per step, then one ASSESSMENT. */
object ProbeEvents {
    fun record(report: HealthReport, profileId: String?, testId: String? = null) {
        val ref = DiagEvent.profileRef(profileId)
        report.results.forEach { r ->
            EventLog.event(
                name = "probe.${r.step.name.lowercase()}",
                severity = if (r.success) Severity.INFO else Severity.WARN,
                attributes = buildMap {
                    put("probe.session", report.sessionId)
                    put("probe.retries", r.retryCount.toString())
                    r.engine?.let { put("probe.engine", it) }
                    r.networkProfileId?.let { put("probe.network", it) }
                    r.valueMs?.let { put("probe.value.ms", it.toString()) }
                },
                profileRef = ref, testId = testId, durationMs = r.duration,
                result = if (r.success) "PASS" else "FAIL", stage = r.failureReason
            )
        }
        EventLog.event(
            name = "probe.assessment",
            severity = if (report.passed) Severity.INFO else Severity.WARN,
            attributes = mapOf("probe.verdict" to report.verdict.name, "probe.observation" to report.observation(),
                "probe.assessment" to report.assessment(), "probe.stale" to report.stale.toString()),
            profileRef = ref, testId = testId,
            result = if (report.stale) "CANCELLED" else if (report.passed) "PASS" else "FAIL",
            stage = report.failureStage, kind = DiagEvent.Kind.ASSESSMENT
        )
    }
}
