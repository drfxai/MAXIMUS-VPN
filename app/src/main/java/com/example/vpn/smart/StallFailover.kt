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
 * How many bytes came before the stall decides its class ([classify]): at most [MTU_STALL_MAX_BYTES] is a path
 * MTU black hole (the first full-size segment never arrives; the MTU 1280 trial is for that), more is a
 * per-connection cap on the server's address range (a censor that freezes a flow after N packets). For a cap
 * the MTU trial is skipped, and every server on the stalled server's network ([HostPrefix]) is left out of the
 * runner-up and the race as well: the cap follows the address range, not the config.
 *
 * Pure: the service supplies the state and carries the decision out.
 */
object StallFailover {
    /** Moves after a stalled download, per user Connect. */
    const val MAX_SWITCHES = 2

    /** A stall at or below this many bytes is an MTU black hole; above it, a per-connection cap. */
    const val MTU_STALL_MAX_BYTES = 1500

    enum class StallClass(val key: String) { MTU("mtu"), CAP("cap") }

    /** [bytes] that came before the stall; null when unknown (the user's "Try another server"). */
    fun classify(bytes: Int?): StallClass? = when {
        bytes == null || bytes < 0 -> null
        bytes <= MTU_STALL_MAX_BYTES -> StallClass.MTU
        else -> StallClass.CAP
    }

    /** Whether a stall of [bytes] may start the MTU 1280 trial: only an MTU-class stall does. */
    fun mtuTrialFor(bytes: Int?): Boolean = classify(bytes) == StallClass.MTU

    /**
     * Ids among [networks] on the same network as the stalled server ([stalledKeys]), [stalledId] excluded:
     * the servers a per-connection cap is likely to hit too.
     */
    fun sameNetwork(stalledId: String, stalledKeys: Set<String>, networks: Map<String, Set<String>>): Set<String> =
        if (stalledKeys.isEmpty()) emptySet()
        else networks.filter { (id, keys) -> id != stalledId && keys.any { it in stalledKeys } }.keys

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
        val maxSwitches: Int = MAX_SWITCHES,
        /** Bytes that came before the stall; null when unknown. */
        val stallBytes: Int? = null,
        /** [HostPrefix.keys] of the stalled server. */
        val stalledKeys: Set<String> = emptySet(),
        /** [HostPrefix.keys] of the runners-up and candidates, by id. */
        val networks: Map<String, Set<String>> = emptyMap()
    )

    fun decide(i: Input): Decision {
        if (i.pinned) return Decision.Stay("the config is pinned (power button or Trade mode)")
        if (i.switchesSoFar >= i.maxSwitches) return exhausted(i, "${i.maxSwitches} stall switches already made in this session")
        val sameNet = if (classify(i.stallBytes) == StallClass.CAP) sameNetwork(i.stalledId, i.stalledKeys, i.networks) else emptySet()
        val excluded = i.frozen + i.stalledId + sameNet
        i.runnersUp.firstOrNull { it !in excluded }?.let { return Decision.RunnerUp(it) }
        if (i.candidates.any { it !in excluded }) return Decision.Race(excluded)
        return exhausted(i, if (sameNet.isEmpty()) "no saved server left that has not stalled or frozen on this network"
            else "no saved server left off the stalled server's network that has not stalled or frozen here")
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

    /** The `failover · stall · bytes=<n> class=<mtu|cap> prefix=<p>` line logged before the decision. */
    fun stallLine(bytes: Int?, stalledKeys: Set<String>, sameNetworkLeftOut: Int = 0): String {
        val cls = classify(bytes)
        return "failover · stall · bytes=${bytes ?: "?"} class=${cls?.key ?: "unknown"} prefix=${HostPrefix.label(stalledKeys)}" +
            (if (cls == StallClass.CAP && sameNetworkLeftOut > 0) " (same-network servers left out: $sameNetworkLeftOut)" else "") +
            (if (cls == StallClass.CAP) " · MTU trial skipped" else "")
    }

    /** One `failover · stall · …` log line. */
    fun describe(d: Decision, name: (String) -> String): String = when (d) {
        is Decision.RunnerUp -> "failover · stall · SWITCH -> ${name(d.toId)} (runner-up)"
        is Decision.Race -> "failover · stall · SWITCH -> Smart Connect race (stalled and frozen servers left out)"
        is Decision.Escalate -> "failover · stall · ESCALATE to emergency transports, recovery engines and revival (${d.reason})"
        is Decision.Stay -> "failover · stall · STAY (${d.reason})"
    }
}
