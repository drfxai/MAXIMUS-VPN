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
 * report read [status] from it, so they cannot reach different conclusions.
 *
 * Only evidence from this process and within [FRESH_MS] decides a live state (TRAFFIC_VERIFIED, a retry skip):
 * history may order candidates elsewhere (NetworkMemory) but never proves current connectivity. Build 178 field report:
 * after many attempts on one network the report still said "nothing tested on this network yet" for every engine,
 * because the in-memory ledger lost everything when Android or the user ended the app and kept nothing older than
 * 10 minutes. The ledger now also keeps [HISTORY_MS] of outcomes per network in [store] (counts, times and short
 * reasons, never addresses), and [status] says what was tried on this network and how it ended.
 */
class EngineLedger(private val clock: () -> Long = System::currentTimeMillis) {
    /** [restored]: read back from [store] after a restart; history only, never live evidence. */
    private data class Event(val network: String, val kind: Kind, val at: Long, val reason: String?, val restored: Boolean = false)
    private enum class Kind { STARTED, VERIFIED, FAILED }

    /** Where the history survives a restart (SharedPreferences in the app); null keeps it in memory only. */
    class Store(val load: () -> String?, val save: (String) -> Unit)

    @Volatile var store: Store? = null
        set(value) {
            field = value
            synchronized(this) { loaded = false }
        }

    private val events = HashMap<String, MutableList<Event>>()
    private var loaded = false

    @Synchronized fun started(engineId: String, network: String) = add(engineId, Event(network, Kind.STARTED, clock(), null))
    @Synchronized fun verified(engineId: String, network: String) = add(engineId, Event(network, Kind.VERIFIED, clock(), null))
    @Synchronized fun failed(engineId: String, network: String, reason: String?) =
        add(engineId, Event(network, Kind.FAILED, clock(), reason?.take(160)))

    @Synchronized fun clear() {
        events.clear()
        persist()
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val raw = runCatching { store?.load?.invoke() }.getOrNull() ?: return
        val root = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: return
        if (root.optInt("schema") != SCHEMA) return
        val now = clock()
        val engines = root.optJSONObject("engines") ?: return
        engines.keys().forEach { id ->
            val list = engines.optJSONArray(id) ?: return@forEach
            val restored = (0 until list.length()).mapNotNull { i ->
                val o = list.optJSONObject(i) ?: return@mapNotNull null
                val kind = runCatching { Kind.valueOf(o.optString("k")) }.getOrNull() ?: return@mapNotNull null
                val at = o.optLong("t")
                if (now - at !in 0 until HISTORY_MS) return@mapNotNull null
                Event(o.optString("n"), kind, at, o.optString("r").takeIf { it.isNotEmpty() }, restored = true)
            }
            if (restored.isNotEmpty()) {
                val merged = (restored + events[id].orEmpty()).sortedBy { it.at }.takeLast(MAX_EVENTS)
                events[id] = merged.toMutableList()
            }
        }
    }

    private fun persist() {
        val s = store ?: return
        val now = clock()
        val engines = org.json.JSONObject()
        events.forEach { (id, list) ->
            val a = org.json.JSONArray()
            list.filter { now - it.at in 0 until HISTORY_MS }.forEach { e ->
                a.put(org.json.JSONObject().put("n", e.network).put("k", e.kind.name).put("t", e.at)
                    .apply { e.reason?.let { put("r", it) } })
            }
            if (a.length() > 0) engines.put(id, a)
        }
        runCatching { s.save(org.json.JSONObject().put("schema", SCHEMA).put("engines", engines).toString()) }
    }

    private fun add(engineId: String, e: Event) {
        ensureLoaded()
        val list = events.getOrPut(engineId) { mutableListOf() }
        // The same failure is often reported twice (the engine's start, then the connection's FAILED state).
        val last = list.lastOrNull()
        if (e.kind == Kind.FAILED && last != null && last.kind == Kind.FAILED && last.network == e.network &&
            !last.restored && e.at - last.at in 0..DUPLICATE_MS) {
            list[list.lastIndex] = last.copy(reason = last.reason ?: e.reason, at = e.at)
        } else list += e
        if (list.size > MAX_EVENTS) list.removeAt(0)
        persist()
    }

    /** Live evidence: this process, this network, within [FRESH_MS]. */
    private fun fresh(engineId: String, network: String, now: Long): List<Event> =
        events[engineId].orEmpty().filter { it.network == network && !it.restored && now - it.at in 0..FRESH_MS }

    private fun last(engineId: String, network: String): Event? = fresh(engineId, network, clock()).lastOrNull()

    /**
     * Why [engineId] should not be retried on [network] right now: its last start or request here
     * failed within [FRESH_MS] and it never carried a request here in that time. Null otherwise
     * (a DEGRADED engine worked here, so it gets its retry).
     */
    @Synchronized
    fun recentFailure(engineId: String, network: String): String? {
        ensureLoaded()
        val now = clock()
        val here = fresh(engineId, network, now)
        val l = here.lastOrNull() ?: return null
        if (l.kind != Kind.FAILED || here.any { it.kind == Kind.VERIFIED }) return null
        return "the engine failed here ${(now - l.at) / 60_000} min ago" + (l.reason?.let { ": $it" } ?: "")
    }

    /** True when [engineId] carried a real request on [network] within [FRESH_MS] and has not failed since. */
    @Synchronized
    fun verifiedHere(engineId: String, network: String): Boolean {
        ensureLoaded()
        return last(engineId, network)?.kind == Kind.VERIFIED
    }

    /** What [engineId] did on [network] within [HISTORY_MS]: "3 attempts here in 24 h: 2 failed, 1 verified". */
    @Synchronized
    fun historyLine(engineId: String, network: String): String? {
        ensureLoaded()
        val now = clock()
        val here = events[engineId].orEmpty().filter { it.network == network && now - it.at in 0 until HISTORY_MS }
        val outcomes = here.filter { it.kind != Kind.STARTED }
        if (outcomes.isEmpty()) return null
        val failed = outcomes.count { it.kind == Kind.FAILED }
        val verified = outcomes.count { it.kind == Kind.VERIFIED }
        return "${outcomes.size} attempt(s) on this network in ${HISTORY_MS / 3_600_000} h: " +
            listOfNotNull(failed.takeIf { it > 0 }?.let { "$it failed" }, verified.takeIf { it > 0 }?.let { "$it verified" })
                .joinToString(", ")
    }

    /**
     * The state of [descriptor] on [network]. [bundled] says whether its program is in this APK
     * (null: the engine needs no program of its own); [problem] is why it cannot run without
     * settings, or null.
     */
    @Synchronized
    fun status(descriptor: EngineRegistry.EngineDescriptor, network: String, bundled: Boolean?, problem: String?): EngineStatus {
        ensureLoaded()
        fun s(state: EngineState, detail: String, at: Long? = null) = EngineStatus(descriptor.id, descriptor.name, state, detail, at)
        if (!descriptor.bundled) return s(EngineState.UNSUPPORTED, "not part of this build")
        if (bundled == false) return s(EngineState.NOT_BUNDLED, "its program is not in this APK for this phone")
        if (problem != null) return s(EngineState.NOT_CONFIGURED, problem)
        val now = clock()
        val history = historyLine(descriptor.id, network)
        fun withHistory(detail: String) = if (history == null) detail else "$detail · $history"
        val here = fresh(descriptor.id, network, now)
        val last = here.lastOrNull()
        if (last == null) {
            // Nothing live: say what the history on this network shows instead of "nothing tested".
            val past = events[descriptor.id].orEmpty()
                .lastOrNull { it.network == network && it.kind != Kind.STARTED && now - it.at in 0 until HISTORY_MS }
                ?: return s(EngineState.AVAILABLE, "nothing tested on this network yet")
            val ago = "${(now - past.at) / 60_000} min ago"
            return when (past.kind) {
                Kind.FAILED -> s(EngineState.FAILED, withHistory("last attempt failed $ago: ${past.reason ?: "no reason given"}"), past.at)
                else -> s(EngineState.AVAILABLE, withHistory("carried traffic here $ago; not re-checked since"), past.at)
            }
        }
        return when (last.kind) {
            Kind.VERIFIED -> s(EngineState.TRAFFIC_VERIFIED, withHistory("a real request went through it"), last.at)
            Kind.STARTED -> s(EngineState.STARTED, withHistory("running; no request through it yet"), last.at)
            Kind.FAILED -> if (here.any { it.kind == Kind.VERIFIED })
                s(EngineState.DEGRADED, withHistory("worked here earlier, then failed: ${last.reason ?: "no reason given"}"), last.at)
            else s(EngineState.FAILED, withHistory(last.reason ?: "failed"), last.at)
        }
    }

    companion object {
        /** Evidence older than this is not used for a live state. */
        const val FRESH_MS = 10 * 60_000L
        /** Outcomes kept per network (and across restarts) for the report's history line. */
        const val HISTORY_MS = 24 * 60 * 60_000L
        /** A second FAILED for the same engine and network within this window is the same failure. */
        const val DUPLICATE_MS = 5_000L
        private const val MAX_EVENTS = 40
        private const val SCHEMA = 1

        /** The one ledger of the app. */
        val shared = EngineLedger()
    }
}
