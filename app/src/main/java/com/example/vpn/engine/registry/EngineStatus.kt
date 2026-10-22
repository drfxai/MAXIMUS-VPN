package com.example.vpn.engine.registry

/**
 * Where one engine stands on this phone right now. [EngineRegistry] says what an engine is; this
 * says whether it can run and what it last did. Only [TRAFFIC_VERIFIED] means a real request went
 * through it, on the current network, recently; nothing older or from another network counts.
 */
enum class EngineState {
    /** The registry knows the engine but this build has no runtime for it. */
    UNSUPPORTED,
    /** Its program is not in this APK for this phone's CPU. */
    NOT_BUNDLED,
    /** Bundled, but it needs settings this install does not have (Psiphon without its config). */
    NOT_CONFIGURED,
    /** Ready to start; nothing recent is known about it on this network. */
    AVAILABLE,
    /** Started and reported ready; no request through it is known yet. */
    STARTED,
    /** A real request went through it on this network within [EngineLedger.FRESH_MS]. */
    TRAFFIC_VERIFIED,
    /** Its last start or request on this network failed, after an earlier success there. */
    DEGRADED,
    /** Its last start or request on this network failed. */
    FAILED,
    UNKNOWN
}

data class EngineStatus(
    val engineId: String,
    val name: String,
    val state: EngineState,
    /** Plain words for the state, for diagnostics and the LAB. */
    val detail: String,
    /** When the state's evidence was recorded, or null when the state comes from the build alone. */
    val at: Long? = null
)

/**
 * What engines did at run time, per network: started, carried a real request, or failed. The LAB,
 * Smart Connect, recovery and the connection all write here, and the LAB, diagnostics and the
 * report read [status] from it, so they cannot reach different conclusions. Kept in memory only:
 * history may order candidates elsewhere (NetworkMemory) but never proves current connectivity.
 */
class EngineLedger(private val clock: () -> Long = System::currentTimeMillis) {
    private data class Event(val network: String, val kind: Kind, val at: Long, val reason: String?)
    private enum class Kind { STARTED, VERIFIED, FAILED }

    private val events = HashMap<String, MutableList<Event>>()

    @Synchronized fun started(engineId: String, network: String) = add(engineId, Event(network, Kind.STARTED, clock(), null))
    @Synchronized fun verified(engineId: String, network: String) = add(engineId, Event(network, Kind.VERIFIED, clock(), null))
    @Synchronized fun failed(engineId: String, network: String, reason: String?) =
        add(engineId, Event(network, Kind.FAILED, clock(), reason?.take(160)))

    @Synchronized fun clear() = events.clear()

    private fun add(engineId: String, e: Event) {
        val list = events.getOrPut(engineId) { mutableListOf() }
        list += e
        if (list.size > MAX_EVENTS) list.removeAt(0)
    }

    /**
     * The state of [descriptor] on [network]. [bundled] says whether its program is in this APK
     * (null: the engine needs no program of its own); [problem] is why it cannot run without
     * settings, or null.
     */
    @Synchronized
    fun status(descriptor: EngineRegistry.EngineDescriptor, network: String, bundled: Boolean?, problem: String?): EngineStatus {
        fun s(state: EngineState, detail: String, at: Long? = null) = EngineStatus(descriptor.id, descriptor.name, state, detail, at)
        if (!descriptor.bundled) return s(EngineState.UNSUPPORTED, "not part of this build")
        if (bundled == false) return s(EngineState.NOT_BUNDLED, "its program is not in this APK for this phone")
        if (problem != null) return s(EngineState.NOT_CONFIGURED, problem)
        val now = clock()
        val here = events[descriptor.id].orEmpty().filter { it.network == network && now - it.at <= FRESH_MS }
        val last = here.lastOrNull() ?: return s(EngineState.AVAILABLE, "nothing tested on this network yet")
        return when (last.kind) {
            Kind.VERIFIED -> s(EngineState.TRAFFIC_VERIFIED, "a real request went through it", last.at)
            Kind.STARTED -> s(EngineState.STARTED, "running; no request through it yet", last.at)
            Kind.FAILED -> if (here.any { it.kind == Kind.VERIFIED })
                s(EngineState.DEGRADED, "worked here earlier, then failed: ${last.reason ?: "no reason given"}", last.at)
            else s(EngineState.FAILED, last.reason ?: "failed", last.at)
        }
    }

    companion object {
        /** Evidence older than this is not used for a state. */
        const val FRESH_MS = 10 * 60_000L
        private const val MAX_EVENTS = 40

        /** The one ledger of the app. */
        val shared = EngineLedger()
    }
}
