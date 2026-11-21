package com.example.vpn.diagnostics.report

import com.example.data.model.ConnectionState
import com.example.vpn.diagnostics.events.TestRegistry
import com.example.vpn.verify.ProbePath
import com.example.vpn.verify.TunCheckPlan
import com.example.vpn.verify.TunForwarded
import com.example.vpn.verify.TunPathStatus

/**
 * The two hops a report shows separately: the engine path (the engine carries traffic, checked through its local
 * port or, for an in-process engine, through the VPN network) and the device path (the Android TUN that other apps
 * use). A session whose app is excluded from its own VPN cannot dial the TUN; its device path is judged by what the
 * TUN forwarded for other apps, and is "not testable" (never "failed") until that shows something. Pure.
 */
object RoutePaths {
    enum class State(val word: String) { VERIFIED("verified"), NOT_TESTABLE("not testable"), FAILED("failed"), NOT_TESTED("not tested") }

    data class Path(val state: State, val detail: String)

    data class Paths(val engine: Path, val device: Path, val selfExcluded: Boolean)

    fun of(c: ConnectionState, tests: List<TestRegistry.Entry>, tun: TunPathStatus.View?): Paths {
        val evidence = c.trafficEvidence?.takeIf { it.appliesTo(c) }
        val session = c.sessionId
        val ofSession = tests.filter { session != null && it.sessionId == session }
        val bulkTun = ofSession.lastOrNull { it.type == "bulk.tun" && it.finished }
        val forwarded = ofSession.lastOrNull { it.type == TunForwarded.EVENT && it.finished }
        val selfExcluded = tun?.selfExcluded == true ||
            bulkTun?.state == TestRegistry.State.NOT_TESTABLE && bulkTun?.result.orEmpty().startsWith(TunCheckPlan.NOT_TESTABLE_SELF_EXCLUDED)

        val engine = when {
            evidence == null -> Path(State.NOT_TESTED, "no bulk check yet")
            evidence.bidirectional -> Path(State.VERIFIED, "bulk upload/download ${evidence.path.title}")
            evidence.bindRefused -> Path(State.NOT_TESTABLE, "the app may not use its own VPN network")
            else -> Path(State.FAILED, (if (evidence.bulkStalled) "stalled" else "partial") + " ${evidence.path.title}")
        }

        val device = if (selfExcluded) {
            val live = tun?.cumulative
            val liveState = if (live != null) tun?.state else null
            val counted = TunForwarded.describe(live)
            when {
                liveState == TunForwarded.State.VERIFIED ->
                    Path(State.VERIFIED, "other apps' traffic forwarded by the TUN: $counted")
                forwarded != null && forwarded.state == TestRegistry.State.COMPLETED ->
                    Path(State.VERIFIED, forwarded.result ?: "other apps' traffic forwarded by the TUN")
                liveState == TunForwarded.State.FAILED ->
                    Path(State.FAILED, "the engine refused flows from the TUN: $counted")
                live != null -> Path(State.NOT_TESTABLE, "${TunCheckPlan.SELF_EXCLUDED_LINE}; " +
                    "${TunForwarded.State.WAITING.word} ($counted)")
                else -> Path(State.NOT_TESTABLE, TunCheckPlan.SELF_EXCLUDED_LINE)
            }
        } else {
            val tunEvidence = evidence?.takeIf { it.path == ProbePath.TUN }
            when {
                tunEvidence != null && tunEvidence.bidirectional -> Path(State.VERIFIED, "bulk upload/download through the VPN interface")
                tunEvidence != null && tunEvidence.bindRefused || bulkTun?.state == TestRegistry.State.NOT_TESTABLE ->
                    Path(State.NOT_TESTABLE, bulkTun?.result ?: "the app may not use its own VPN network")
                tunEvidence != null -> Path(State.FAILED, tunEvidence.describe())
                bulkTun?.state == TestRegistry.State.FAILED -> Path(State.FAILED, bulkTun?.result ?: "bulk.tun failed")
                else -> Path(State.NOT_TESTED, "no device-path check yet")
            }
        }
        return Paths(engine, device, selfExcluded)
    }
}
