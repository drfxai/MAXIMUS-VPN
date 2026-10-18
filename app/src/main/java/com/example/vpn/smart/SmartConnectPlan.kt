package com.example.vpn.smart

import com.example.vpn.lab.NetworkState
import com.example.vpn.lab.NetworkStateClassifier
import com.example.vpn.lab.NetworkStateReading

/**
 * The stages of one Smart Connect search, in the order they run. Each stage stops the search the moment
 * one of its paths carries a real request; a later stage only runs when every earlier one found nothing.
 */
enum class SearchStage(val letter: Char, val title: String) {
    REMEMBERED_ROUTE('A', "Route that worked here before"),
    SELECTED('B', "Selected server"),
    SAME_SERVER('C', "Recovered settings and disguises"),
    OTHER_SERVERS('D', "Other saved servers"),
    EMERGENCY('E', "Emergency transports"),
    RECOVERY('F', "Recovery methods"),
    REVIVAL('G', "Config revival")
}

/** Ways out that need no server of the user's, tried in stage E in this order. */
enum class EmergencyTransport(val title: String) {
    CLEAN_IP("Clean Cloudflare address"),
    WARP("Cloudflare WARP (WireGuard)"),
    MASQUE("WARP over MASQUE (HTTP/3)"),
    PSIPHON("Psiphon"),
    TOR("Tor")
}

/**
 * Which stages and emergency transports this connect may run, and why any are left out. Pure: the
 * service passes in what it knows, so every rule is tested without a phone. The plan never adds a stage
 * or changes their order; it only leaves out what settings forbid or the measured network rules out.
 */
data class SmartConnectPlan(
    val skipped: Map<SearchStage, String>,
    val transports: List<EmergencyTransport>,
    val transportsSkipped: Map<EmergencyTransport, String>,
    /** The measured network state that steered the plan, or null when nothing fresh was measured. */
    val reading: NetworkStateReading?
) {
    fun allows(stage: SearchStage): Boolean = stage !in skipped
    fun allows(transport: EmergencyTransport): Boolean = transport in transports

    data class Input(
        /** GOD MODE: the emergency engines may be started directly. */
        val godMode: Boolean,
        /** Trade mode or the power button: the exit is pinned to the selected server. */
        val lockExit: Boolean,
        /** The power button (not Trade mode): only the config selected in Nodes, as saved. */
        val exact: Boolean = false,
        /** "Connect anyway": the user's choice, started without a search. */
        val force: Boolean,
        /** Smart Connect, or automatic failover, may move the session to another server. */
        val mayMove: Boolean,
        /** The measured state of this network, only when measured on it in the last few minutes. */
        val reading: NetworkStateReading?
    )

    companion object {
        fun of(input: Input): SmartConnectPlan {
            val skipped = linkedMapOf<SearchStage, String>()
            val off = linkedMapOf<EmergencyTransport, String>()
            when {
                input.lockExit -> SearchStage.values().filter { it != SearchStage.SELECTED }
                    .forEach { skipped[it] = if (input.exact) "Power button: only the config selected in Nodes; Smart Connect searches"
                        else "Trade mode keeps the exit on the selected server" }
                !input.mayMove -> listOf(SearchStage.OTHER_SERVERS, SearchStage.RECOVERY, SearchStage.REVIVAL)
                    .forEach { skipped[it] = "Smart Connect and automatic failover are off" }
            }
            // "Connect anyway" starts the user's choice: no remembered route replaces it and no long recovery runs.
            if (input.force) listOf(SearchStage.REMEMBERED_ROUTE, SearchStage.RECOVERY, SearchStage.REVIVAL)
                .forEach { skipped.putIfAbsent(it, "Connect anyway: started as chosen") }
            if (SearchStage.EMERGENCY in skipped) {
                EmergencyTransport.values().forEach { off[it] = skipped.getValue(SearchStage.EMERGENCY) }
            } else {
                if (!input.godMode) {
                    listOf(EmergencyTransport.MASQUE, EmergencyTransport.PSIPHON, EmergencyTransport.TOR)
                        .forEach { off[it] = "Started directly in GOD MODE only; recovery methods may still reach it" }
                }
                val states = input.reading?.let { it.restrictions + it.primary }.orEmpty()
                // A measured block, never a guess: WARP's WireGuard and MASQUE's HTTP/3 both need UDP abroad.
                if (NetworkState.UDP_BLOCKED_SUSPECTED in states) {
                    off.putIfAbsent(EmergencyTransport.WARP, "UDP abroad got no answer on this network")
                    off.putIfAbsent(EmergencyTransport.MASQUE, "UDP abroad got no answer on this network")
                }
                if (NetworkState.QUIC_BLOCKED_SUSPECTED in states) {
                    off.putIfAbsent(EmergencyTransport.MASQUE, "No QUIC endpoint answered on this network")
                }
            }
            val transports = EmergencyTransport.values().filter { it !in off }
            return SmartConnectPlan(skipped, transports, off, input.reading)
        }

        /** Measurements older than this may describe another network and are not used. */
        const val READING_MAX_AGE_MS = 10 * 60_000L

        /**
         * The state of [measured], only when it was measured in the last [READING_MAX_AGE_MS] on the same kind
         * of network as [networkKey] (and, on mobile data, the same carrier when both are known).
         */
        fun freshReading(measured: NetworkCapabilityProfile?, networkKey: String, now: Long): NetworkStateReading? {
            val p = measured ?: return null
            if (now - p.measuredAt !in 0 until READING_MAX_AGE_MS) return null
            val kind = networkKey.substringBefore(':')
            val sameKind = when (kind) {
                "cell" -> p.transport == "cellular"
                "wifi", "ethernet" -> p.transport == kind
                else -> false
            }
            if (!sameKind) return null
            val operator = networkKey.substringAfter(':', "").takeIf { kind == "cell" && it.isNotEmpty() }
            if (operator != null && p.carrierCode != null && operator != p.carrierCode) return null
            return NetworkStateClassifier.classify(p, now)
        }
    }
}

/** How one stage of the search went. [tested] counts paths that got a real request; [verified] those that carried it. */
data class StageReport(
    val stage: SearchStage,
    val outcome: Outcome = Outcome.PENDING,
    val tested: Int = 0,
    val verified: Int = 0,
    val note: String? = null
) {
    enum class Outcome {
        PENDING, RUNNING, VERIFIED, FAILED, SKIPPED, NOT_REACHED,
        /** An engine that can only be judged once it runs was chosen; the traffic check after start decides. */
        CHOSEN
    }

    val failed: Int get() = tested - verified
}

/** The search for one connect attempt, stage by stage, as the screen shows it. */
data class SearchProgress(val attemptId: String?, val stages: List<StageReport>) {
    val current: StageReport? get() = stages.firstOrNull { it.outcome == StageReport.Outcome.RUNNING }
    val tested: Int get() = stages.sumOf { it.tested }
    val verified: Int get() = stages.sumOf { it.verified }
    val failed: Int get() = stages.sumOf { it.failed }

    fun of(stage: SearchStage): StageReport = stages.first { it.stage == stage }

    /** "Stage D · Other saved servers · 6 tested, 0 verified". */
    fun describe(): String {
        val s = current ?: stages.lastOrNull { it.outcome == StageReport.Outcome.VERIFIED }
            ?: stages.lastOrNull { it.outcome == StageReport.Outcome.FAILED } ?: return "Not started"
        return "Stage ${s.stage.letter} · ${s.stage.title} · ${s.tested} tested, ${s.verified} verified"
    }
}

/**
 * Keeps [SearchProgress] for one attempt. Results are counted against the stage that is running, so a
 * probe made by a shared helper lands in the right stage. Thread-safe; [publish] gets every change.
 */
class SearchTracker(
    private val attemptId: String?,
    plan: SmartConnectPlan,
    private val publish: (SearchProgress) -> Unit = {}
) {
    private var reports: List<StageReport> = SearchStage.values().map { st ->
        plan.skipped[st]?.let { StageReport(st, StageReport.Outcome.SKIPPED, note = it) } ?: StageReport(st)
    }
    private var running: SearchStage? = null

    @Synchronized
    fun progress(): SearchProgress = SearchProgress(attemptId, reports)

    /** [stage] starts; an earlier stage still running ends as failed (it found nothing). */
    fun start(stage: SearchStage) = change {
        running?.takeIf { it != stage }?.let { closeLocked(it, verified = false) }
        // A stage the plan left out never runs; nothing is counted against it.
        if (reports.first { it.stage == stage }.outcome == StageReport.Outcome.SKIPPED) {
            running = null
        } else {
            running = stage
            update(stage) { it.copy(outcome = StageReport.Outcome.RUNNING) }
        }
    }

    /** One path of the running stage got a real request; [carried] says whether traffic went through. */
    fun count(carried: Boolean, paths: Int = 1) = change {
        val st = running ?: return@change
        update(st) { it.copy(tested = it.tested + paths, verified = it.verified + if (carried) 1 else 0) }
    }

    /** Adds results measured elsewhere (the LAB's recovery, revival) to [stage]. */
    fun add(stage: SearchStage, tested: Int, verified: Int) = change {
        update(stage) { it.copy(tested = it.tested + tested, verified = it.verified + verified) }
    }

    /** [stage] had nothing to try (no remembered route, nothing configured). */
    fun skip(stage: SearchStage, why: String) = change {
        if (running == stage) running = null
        update(stage) { it.copy(outcome = StageReport.Outcome.SKIPPED, note = why) }
    }

    /** [stage] ends: a verified path, or nothing. */
    fun finish(stage: SearchStage, verified: Boolean, note: String? = null) = change {
        closeLocked(stage, verified, note)
    }

    /** [stage] chose an engine that is checked only after it starts (Psiphon, Tor, MASQUE). */
    fun chose(stage: SearchStage, note: String) = change {
        if (running == stage) running = null
        update(stage) { it.copy(outcome = StageReport.Outcome.CHOSEN, note = note) }
    }

    /** The running stage, if any, ends: a verified path, or nothing. */
    fun finishRunning(verified: Boolean) = change {
        running?.let { closeLocked(it, verified) }
    }

    /** The stage results are being counted against now. */
    @get:Synchronized val runningStage: SearchStage? get() = running

    /** The search is over: stages never started are marked as not reached. */
    fun end() = change {
        running?.let { closeLocked(it, verified = false) }
        reports = reports.map { if (it.outcome == StageReport.Outcome.PENDING) it.copy(outcome = StageReport.Outcome.NOT_REACHED) else it }
    }

    private fun closeLocked(stage: SearchStage, verified: Boolean, note: String? = null) {
        if (running == stage) running = null
        update(stage) {
            when (it.outcome) {
                StageReport.Outcome.SKIPPED, StageReport.Outcome.VERIFIED -> it
                else -> it.copy(outcome = if (verified) StageReport.Outcome.VERIFIED else StageReport.Outcome.FAILED, note = note ?: it.note)
            }
        }
    }

    private fun update(stage: SearchStage, f: (StageReport) -> StageReport) {
        reports = reports.map { if (it.stage == stage) f(it) else it }
    }

    private fun change(block: () -> Unit) {
        val snapshot = synchronized(this) { block(); SearchProgress(attemptId, reports) }
        publish(snapshot)
    }
}
