package com.example.vpn.smart

/**
 * What to do when the upload/download check after a connect finds a bulk download that stalled part-way
 * (outside the ~16 KB freeze window, which [FreezeFailover] handles) while small requests still pass: the
 * failover watchdog sees a healthy tunnel and never switches, so the user would sit on a connection that
 * cannot load anything large.
 *
 * 1. Move to the next path that also carried traffic in this Smart Connect (a runner-up).
 * 2. With none, a Smart Connect race over the saved servers that leaves out the stalled one and every server
 *    that froze or stalled on this network.
 * 3. When the switch cap is reached or no saved server is left, [Decision.Escalate]: the Smart Connect search
 *    goes on into its later stages (emergency transports, recovery engines, config revival), once per Connect.
 * 4. A pinned session (power button, Trade mode) is never moved ([Decision.Stay]).
 *
 * Pure: the service supplies the state and carries the decision out.
 */
object StallFailover {
    /** Moves after a stalled download, per user Connect. */
    const val MAX_SWITCHES = 2

    sealed class Decision {
        /** Switch to [toId], a server that also carried traffic in this Smart Connect. */
        data class RunnerUp(val toId: String) : Decision()
        /** No runner-up: race the saved servers, leaving [exclude] (stalled and frozen here) out. */
        data class Race(val exclude: Set<String>) : Decision()
        /** Every saved-server option of this session is used up: continue with the later stages. */
        data class Escalate(val reason: String) : Decision()
        /** Nothing more to do automatically; the session stays as it is. */
        data class Stay(val reason: String) : Decision()
    }

    data class Input(
        /** The saved server whose download stalled. */
        val stalledId: String,
        /** Power button or Trade mode: the config is pinned and never changed. */
        val pinned: Boolean,
        val switchesSoFar: Int,
        /** Servers that carried traffic in this Smart Connect, best first. */
        val runnersUp: List<String> = emptyList(),
        /** Servers that froze or stalled on this network and are still cooling down. */
        val frozen: Set<String> = emptySet(),
        /** Saved servers a Smart Connect race may use here (engine programs left out). */
        val candidates: List<String> = emptyList(),
        /** The later stages already ran after a stall or freeze in this session. */
        val escalated: Boolean = false,
        val maxSwitches: Int = MAX_SWITCHES
    )

    fun decide(i: Input): Decision {
        if (i.pinned) return Decision.Stay("the config is pinned (power button or Trade mode)")
        if (i.switchesSoFar >= i.maxSwitches) return exhausted(i, "${i.maxSwitches} stall switches already made in this session")
        val excluded = i.frozen + i.stalledId
        i.runnersUp.firstOrNull { it !in excluded }?.let { return Decision.RunnerUp(it) }
        if (i.candidates.any { it !in excluded }) return Decision.Race(excluded)
        return exhausted(i, "no saved server left that has not stalled or frozen on this network")
    }

    private fun exhausted(i: Input, reason: String): Decision =
        if (i.escalated) Decision.Stay("$reason; the later Smart Connect stages already ran in this session")
        else Decision.Escalate(reason)

    /**
     * Whether a freeze failover that gave up ([FreezeFailover.Decision.Fail], or a race with no server left)
     * continues with the later stages: never for a pinned session, and once per Connect.
     */
    fun escalatesAfterFreeze(pinned: Boolean, escalated: Boolean): Boolean = !pinned && !escalated

    /** The `action` attribute of the `failover.stall` event. */
    fun action(d: Decision): String = when (d) {
        is Decision.RunnerUp -> "runner-up"
        is Decision.Race -> "race"
        is Decision.Escalate -> "escalate"
        is Decision.Stay -> "stay"
    }

    fun reason(d: Decision): String = when (d) {
        is Decision.RunnerUp -> "a server that also carried traffic in this Smart Connect"
        is Decision.Race -> "no runner-up; ${d.exclude.size} stalled or frozen server(s) left out"
        is Decision.Escalate -> d.reason
        is Decision.Stay -> d.reason
    }

    /** One `failover · stall · …` log line. */
    fun describe(d: Decision, name: (String) -> String): String = when (d) {
        is Decision.RunnerUp -> "failover · stall · SWITCH -> ${name(d.toId)} (runner-up)"
        is Decision.Race -> "failover · stall · SWITCH -> Smart Connect race (stalled and frozen servers left out)"
        is Decision.Escalate -> "failover · stall · ESCALATE to emergency transports, recovery engines and revival (${d.reason})"
        is Decision.Stay -> "failover · stall · STAY (${d.reason})"
    }
}
