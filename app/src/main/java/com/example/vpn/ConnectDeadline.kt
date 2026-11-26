package com.example.vpn

import com.example.data.model.ConnectionStatus
import com.example.data.model.VlessProfile
import com.example.vpn.connections.BuiltinConnections

/**
 * How long one connect attempt may go without verified traffic, and what happens then.
 *
 * 2026-11-25 engine test (V1.0.4 build 175, Irancell LTE, manual picks from Advanced options): Aether / WARP held the
 * tunnel with zero internet for 7+ minutes, Psiphon, Shir o Khorshid, Lantern and MASQUE HTTP/2 for the whole 3-minute
 * window, with no timeout, no error and no fallback, while Psiphon over Lantern worked in 11 s and Tor in ~127 s.
 *
 *  - Each attempt (its own attemptId) gets [budgetMs] from the moment it is seen: 45 s for Serverless, 60 s for one
 *    engine or a saved config, 90 s for a chain of engines, 180 s for anything that runs Tor.
 *  - A saved config (no engine program) is only timed once its tunnel is up without traffic: its Smart Connect race
 *    and failover have their own limits.
 *  - When the budget runs out: the user's Stop always wins ([Decision.Stopped]); a locked exit (Trade mode) stops with
 *    a clear error; the user's own pick of one connection ([ManualEnginePick]) stops with a clear error and is never
 *    replaced by another engine or chain; otherwise (Smart Connect and other automatic sessions) the path verified on
 *    this network goes next, then the next built-in candidate that was not tried in this Connect, at most
 *    [MAX_FALLBACKS] times. Serverless Direct (the ISP's own exit) is never picked
 *    as a fallback: it does not hide the address, and the test showed it passing plain sites while filtered ones failed.
 *
 * Pure, so the rules are unit-tested.
 */
object ConnectDeadline {
    const val SERVERLESS_MS = 45_000L
    const val ENGINE_MS = 60_000L
    const val CHAIN_MS = 90_000L
    const val TOR_MS = 180_000L
    /**
     * The user's own pick of direct Psiphon: its own region rotation ([com.example.vpn.sidecar.PsiphonRegionPlan.TOTAL_AUTO_MS])
     * plus the traffic check, so the same engine can finish retrying itself before the pick counts as failed.
     */
    const val PSIPHON_MANUAL_MS = com.example.vpn.sidecar.PsiphonRegionPlan.TOTAL_AUTO_MS + 20_000L
    /** The user's own Aether pick: [com.example.vpn.connections.ConnectionEngineRunner.RACE_DEFAULT_DEADLINE_MS]. */
    const val AETHER_MANUAL_MS = 200_000L
    /** Shir o Khorshid as a chain's entry hop: its own 120 s scan window plus the traffic check. */
    const val SHIROKHORSHID_CHAIN_MS = 140_000L
    const val MAX_FALLBACKS = 3

    /** Statuses in which the attempt has not carried verified traffic yet. */
    val WAITING: Set<ConnectionStatus> = setOf(
        ConnectionStatus.PREPARING, ConnectionStatus.CONNECTING, ConnectionStatus.VPN_INTERFACE_ESTABLISHED,
        ConnectionStatus.ENGINE_STARTED, ConnectionStatus.PROXY_CONNECTING, ConnectionStatus.VERIFYING,
        ConnectionStatus.TUNNEL_STARTED_CONNECTIVITY_UNVERIFIED
    )

    /** Built-ins tried after a deadline, best first (from the engine test: the chain in 11 s, Tor in ~2 min). */
    val CANDIDATES: List<String> = listOf(
        BuiltinConnections.ConfigFreeMethod.PSIPHON_RADIANCE.id,
        BuiltinConnections.ConfigFreeMethod.TOR_SNOWFLAKE.id,
        BuiltinConnections.ConfigFreeMethod.PSIPHON.id,
        BuiltinConnections.ConfigFreeMethod.SHIROKHORSHID.id,
        BuiltinConnections.ConfigFreeMethod.RADIANCE.id,
        BuiltinConnections.ConfigFreeMethod.AETHER_WARP.id
    )

    /** Profile ids whose exit is the phone's own ISP address. */
    private val ISP_EXIT_IDS = setOf(BuiltinConnections.ConfigFreeMethod.SERVERLESS.id, BuiltinConnections.DIRECT)

    fun ispExit(profileId: String?): Boolean = profileId != null && profileId in ISP_EXIT_IDS

    /** The engine programs [profile] runs ("psiphon", "radiance", ...); empty for a saved config on Xray alone. */
    fun engines(profile: VlessProfile): List<String> {
        val chain = runCatching { com.example.vpn.sidecar.ChainRunner.readChain(profile) }.getOrNull()
        if (chain != null) return chain.hops.map { it.engineId }
        val id = com.example.vpn.sidecar.EngineUdp.engineId(profile)
        if (id.isBlank()) return emptyList()
        if (id == com.example.vpn.sidecar.AetherSidecar.ID) {
            val options = runCatching {
                com.example.vpn.sidecar.AetherSettings.read(com.example.vpn.engine.ProfileExtras.read(profile))
            }.getOrNull()
            return listOfNotNull(id,
                com.example.vpn.sidecar.PsiphonSidecar.ID.takeIf { options != null && options.psiphon != "off" },
                com.example.vpn.sidecar.TorSidecar.ID.takeIf { options != null && options.tor != "off" })
        }
        return listOf(id)
    }

    /** The time an attempt on these engines gets before it counts as failed. */
    fun budgetMs(engines: List<String>): Long = when {
        com.example.vpn.sidecar.TorSidecar.ID in engines -> TOR_MS
        engines.size > 1 -> CHAIN_MS
        engines == listOf(com.example.vpn.sidecar.ServerlessSidecar.ID) -> SERVERLESS_MS
        else -> ENGINE_MS
    }

    fun budgetMs(profile: VlessProfile): Long = budgetMs(engines(profile))

    /**
     * [budgetMs] for [profile]; a [manual] pick of direct Psiphon also gets the time its own region rotation needs
     * ([budgetFor]).
     */
    fun budgetMs(profile: VlessProfile, manual: Boolean): Long =
        budgetFor(engines(profile), manual,
            runCatching { com.example.vpn.connections.PsiphonChainMemory.isDirect(profile) }.getOrDefault(false))

    /** The budget of an attempt on [engines]; a [manual] pick of [directPsiphon] waits out its region rotation. Pure. */
    fun budgetFor(engines: List<String>, manual: Boolean, directPsiphon: Boolean): Long {
        val base = budgetMs(engines)
        return when {
            manual && directPsiphon -> maxOf(base, PSIPHON_MANUAL_MS)
            // Aether's own carrier ladder (scan, then the next carrier) before it reports anything: the engine's default
            // readiness window plus the check. An Irancell ladder announces its exact budget (EngineBudgetHint).
            manual && engines.firstOrNull() == com.example.vpn.sidecar.AetherSidecar.ID -> maxOf(base, AETHER_MANUAL_MS)
            // Shir o Khorshid over Lantern: its CDN scan needs up to 120 s before the chain carries anything (build 178).
            engines.size > 1 && engines.firstOrNull() == com.example.vpn.sidecar.ShirokhorshidSidecar.ID -> maxOf(base, SHIROKHORSHID_CHAIN_MS)
            else -> base
        }
    }

    /** The traffic check after an engine reports ready: what a deadline adds on top of the engine's own window. */
    const val VERIFY_MARGIN_MS = 20_000L

    /**
     * One source of truth for a manual pick's deadline (build 176 device test: Psiphon cut at 40 s, Shir o Khorshid at
     * 60 s while its stage said "ready within 120s", Aether at 60 s inside its own 220 s ladder). The attempt started
     * at [since] gets [baseMs]; when the engine announced its own readiness window ending at [engineReadyBy]
     * ([EngineBudgetHint]), a [manual] pick waits until then plus [VERIFY_MARGIN_MS]. Smart Connect keeps [baseMs]:
     * its search moves on instead. Pure.
     */
    fun effectiveBudget(baseMs: Long, since: Long, engineReadyBy: Long?, manual: Boolean): Long {
        if (!manual || engineReadyBy == null) return baseMs
        return maxOf(baseMs, engineReadyBy + VERIFY_MARGIN_MS - since)
    }

    /**
     * The readiness window shown in "engine starting · ready within Ns": the engine's own [readyMs] for a manual pick;
     * for Smart Connect never longer than its deadline for this engine ([smartBudgetMs]). Pure.
     */
    fun shownReadyMs(readyMs: Long, manual: Boolean, smartBudgetMs: Long): Long =
        if (manual) readyMs else minOf(readyMs, smartBudgetMs)

    /** Tor's own start deadline at most ([com.example.vpn.sidecar.TorStartDeadline.CAP_MS]) plus the traffic check. */
    const val TOR_MANUAL_CAP_MS = com.example.vpn.sidecar.TorStartDeadline.CAP_MS + VERIFY_MARGIN_MS

    /**
     * The "engine starting" stage text, with the number the deadline really applies. A manual Tor pick is limited by
     * the connect deadline ([manualBudgetMs], 180 s), not by Tor's 120 s readiness window, and both move out while
     * Tor's bootstrap advances (build 177: "ready within 120s", cut at 180 s at 30 %). Pure.
     */
    fun startingText(engineId: String, readyMs: Long, manual: Boolean, smartBudgetMs: Long, manualBudgetMs: Long): String {
        if (manual && engineId == com.example.vpn.sidecar.TorSidecar.ID) {
            return "ready within ${maxOf(readyMs, manualBudgetMs) / 1000}s (longer while Tor's bootstrap keeps advancing, " +
                "at most ${TOR_MANUAL_CAP_MS / 1000}s)"
        }
        val shown = shownReadyMs(readyMs, manual, smartBudgetMs)
        return "ready within ${shown / 1000}s" + if (shown < readyMs) " (Smart Connect's limit; the engine allows ${readyMs / 1000}s)" else ""
    }

    /** Aether / Serverless: their carrier test runs before the engine is handed over. Pure. */
    fun carrierTestText(engineId: String, budgetMs: Long): String =
        "testing carriers" + (if (engineId == com.example.vpn.sidecar.AetherSidecar.ID) " (gateway scan)" else "") +
            " · ready within ${budgetMs / 1000}s"

    /** The engine came from its carrier test after [elapsedMs]. Pure. */
    fun carrierChosenText(elapsedMs: Long): String = "carrier chosen by its test after ${elapsedMs / 1000}s"

    /**
     * True when the attempt is still waiting for traffic at [now]. A saved config ([engineProfile] false) is timed only
     * while its tunnel is up without traffic.
     */
    fun expired(status: ConnectionStatus, engineProfile: Boolean, since: Long, now: Long, budgetMs: Long): Boolean {
        if (status !in WAITING) return false
        if (!engineProfile && status != ConnectionStatus.TUNNEL_STARTED_CONNECTIVITY_UNVERIFIED) return false
        return now - since >= budgetMs
    }

    sealed interface Decision {
        /** The user stopped (or a newer Connect came): nothing more is started. */
        data object Stopped : Decision
        data class Fallback(val profileId: String, val why: String) : Decision
        data class GiveUp(val reason: GiveUpReason) : Decision
    }

    enum class GiveUpReason { EXIT_LOCKED, MANUAL_PICK, FALLBACKS_USED, NONE_LEFT }

    /**
     * What follows an attempt that ran out of time. [tried]: profile ids already tried in this Connect (the user's own
     * pick included); [verifiedHere]: the path that carried verified traffic on this network, if one is remembered;
     * [runnable]: whether this build can run a candidate here; [manualPick]: the user picked this one connection
     * themselves (not Smart Connect), so no other engine or chain is ever started ([ManualEnginePick]).
     */
    fun decide(
        stopped: Boolean,
        exitLocked: Boolean,
        fallbacksSoFar: Int,
        tried: Set<String>,
        verifiedHere: String?,
        runnable: (String) -> Boolean,
        candidates: List<String> = CANDIDATES,
        manualPick: Boolean = false
    ): Decision {
        if (stopped) return Decision.Stopped
        if (exitLocked) return Decision.GiveUp(GiveUpReason.EXIT_LOCKED)
        if (!ManualEnginePick.mayLeaveEngine(manualPick)) return Decision.GiveUp(GiveUpReason.MANUAL_PICK)
        if (fallbacksSoFar >= MAX_FALLBACKS) return Decision.GiveUp(GiveUpReason.FALLBACKS_USED)
        if (verifiedHere != null && verifiedHere !in tried && !ispExit(verifiedHere) && runnable(verifiedHere)) {
            return Decision.Fallback(verifiedHere, "verified on this network before")
        }
        val next = candidates.firstOrNull { it !in tried && !ispExit(it) && runnable(it) }
            ?: return Decision.GiveUp(GiveUpReason.NONE_LEFT)
        return Decision.Fallback(next, "next built-in connection")
    }
}
