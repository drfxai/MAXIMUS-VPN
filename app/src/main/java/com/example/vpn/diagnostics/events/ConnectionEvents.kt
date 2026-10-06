package com.example.vpn.diagnostics.events

import com.example.data.model.ConnectionState
import com.example.data.model.ConnectionStatus

/**
 * Turns connection state changes into events. Pure, so the rules are tested without a phone.
 *
 * The selected, attempted and active profiles are kept apart: the user may pick one server while
 * failover tries another and a third ends up carrying the traffic.
 */
object ConnectionEvents {
    /** The events a change from [old] to [new] produces (empty when nothing worth recording changed). */
    fun between(old: ConnectionState, new: ConnectionState, now: Long, refOf: (String?) -> String?): List<DiagEvent> {
        val events = ArrayList<DiagEvent>(3)
        fun ev(name: String, severity: Severity, attrs: Map<String, String> = emptyMap(), result: String? = null, kind: DiagEvent.Kind = DiagEvent.Kind.OBSERVATION) =
            DiagEvent(
                timestamp = now, severity = severity, name = name, sessionId = new.sessionId ?: old.sessionId,
                attemptId = new.attemptId ?: old.attemptId,
                profileRef = refOf(new.activeProfile?.id ?: new.attemptedProfileId),
                engine = new.activeEngineName ?: old.activeEngineName,
                attributes = attrs, result = result,
                failureStage = if (result == "FAIL" || new.status == ConnectionStatus.DEGRADED ||
                    new.status == ConnectionStatus.TUNNEL_STARTED_CONNECTIVITY_UNVERIFIED) new.failureStage else null,
                kind = kind
            )

        if (new.sessionId != null && new.sessionId != old.sessionId) {
            events += ev("session.start", Severity.INFO, profiles(new, refOf))
        }
        if (new.attemptId != null && new.attemptId != old.attemptId) {
            events += ev("attempt.start", Severity.INFO, profiles(new, refOf))
        }
        if (new.status != old.status) {
            val severity = when (new.status) {
                ConnectionStatus.FAILED -> Severity.ERROR
                ConnectionStatus.DEGRADED, ConnectionStatus.TUNNEL_STARTED_CONNECTIVITY_UNVERIFIED -> Severity.WARN
                else -> Severity.INFO
            }
            val attrs = buildMap {
                put("from", old.status.name)
                put("to", new.status.name)
                if (new.status == ConnectionStatus.CONNECTED) new.verifiedAt?.let { put("verified.at", it.toString()) }
                if (new.status == ConnectionStatus.FAILED || new.status == ConnectionStatus.DEGRADED) new.errorMessage?.let { put("message", it.take(300)) }
            }
            // A status is what the app concluded from measurements: an assessment.
            events += ev("connection.state", severity, attrs,
                result = when (new.status) { ConnectionStatus.FAILED -> "FAIL"; ConnectionStatus.CONNECTED -> "PASS"; else -> null },
                kind = DiagEvent.Kind.ASSESSMENT)
            if (new.status == ConnectionStatus.DISCONNECTED && old.sessionId != null) {
                events += DiagEvent(timestamp = now, severity = Severity.INFO, name = "session.end", sessionId = old.sessionId,
                    attemptId = old.attemptId, profileRef = refOf(old.activeProfile?.id), engine = old.activeEngineName,
                    durationMs = old.connectedDurationSeconds * 1000)
            }
        }
        if (new.networkGeneration != old.networkGeneration && new.status != ConnectionStatus.DISCONNECTED) {
            events += ev("network.changed", Severity.INFO, mapOf("generation" to new.networkGeneration.toString()))
        }
        return events
    }

    private fun profiles(s: ConnectionState, refOf: (String?) -> String?) = buildMap {
        refOf(s.selectedProfileId)?.let { put("profile.selected", it) }
        refOf(s.attemptedProfileId)?.let { put("profile.attempted", it) }
        refOf(s.activeProfile?.id)?.let { put("profile.active", it) }
    }
}
