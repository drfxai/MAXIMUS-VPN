package com.example.vpn.automation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** The background upkeep jobs, in the order a run does them (the ladder uses what the resolver scan found). */
enum class AutomationTask { RESOLVER_SCAN, DNS_LADDER, CLEAN_IP_SCAN, CONFIG_REVIVAL, GEO_UPDATE }

/** Why a run started. */
enum class AutomationTrigger { NETWORK_CHANGED, VPN_STOPPED, DAILY, CONNECT_FAILED, CONNECTED, MANUAL }

enum class TaskOutcome { OK, FAILED, SKIPPED }

/** Why a task did not run. */
enum class SkipReason {
    /** A VPN (this app's or another) is up: measurements would go through it or be refused. */
    VPN_ACTIVE,
    /** Done on this network recently enough. */
    FRESH,
    /** No saved config this task works on. */
    NOTHING_TO_DO,
    /** Large download on mobile data without a tunnel. */
    METERED,
    /** The user wants GeoFiles only through the tunnel ("GeoFiles via proxy") and it is not up. */
    NEEDS_TUNNEL,
    /** Not one of this task's triggers. */
    NOT_THIS_TRIGGER
}

/** What the last run of one task did. [found]: resolvers kept, tunnels verified, edges found, configs alive, files updated. */
data class TaskStatus(
    val task: AutomationTask,
    val lastRunAt: Long = 0,
    val outcome: TaskOutcome? = null,
    val found: Int = 0,
    val network: String? = null,
    val trigger: AutomationTrigger? = null,
    val skip: SkipReason? = null,
    val detail: String = "",
    /** When it last completed (OK) on [network]; drives the per-network cache freshness. */
    val lastOkAt: Long = 0
)

/**
 * The status every upkeep task leaves behind, per task, plus when each task last succeeded on each network
 * (the per-network-fingerprint cache). Exposed as a [StateFlow] for the status card; [load]/[save] hold JSON.
 */
class AutomationStatusBook(
    private val load: () -> String? = { null },
    private val save: (String) -> Unit = {}
) {
    private val okAt = linkedMapOf<String, Long>()
    private val state = MutableStateFlow<Map<AutomationTask, TaskStatus>>(emptyMap())
    val flow: StateFlow<Map<AutomationTask, TaskStatus>> = state.asStateFlow()
    private var loaded = false

    @Synchronized
    private fun ensure() {
        if (loaded) return
        loaded = true
        val root = runCatching { JSONObject(load() ?: return) }.getOrNull() ?: return
        if (root.optInt("schema") != SCHEMA) return
        root.optJSONObject("ok")?.let { o -> o.keys().forEach { k -> okAt[k] = o.optLong(k) } }
        val tasks = root.optJSONObject("tasks") ?: JSONObject()
        state.value = AutomationTask.entries.mapNotNull { t ->
            val o = tasks.optJSONObject(t.name) ?: return@mapNotNull null
            t to TaskStatus(
                task = t, lastRunAt = o.optLong("at"),
                outcome = runCatching { TaskOutcome.valueOf(o.optString("o")) }.getOrNull(),
                found = o.optInt("n"), network = o.optString("net").ifBlank { null },
                trigger = runCatching { AutomationTrigger.valueOf(o.optString("tr")) }.getOrNull(),
                skip = runCatching { SkipReason.valueOf(o.optString("s")) }.getOrNull(),
                detail = o.optString("d"), lastOkAt = o.optLong("ok")
            )
        }.toMap()
    }

    fun current(): Map<AutomationTask, TaskStatus> { ensure(); return state.value }

    /** When [task] last completed on [network] (0: never). */
    @Synchronized
    fun lastOk(task: AutomationTask, network: String): Long { ensure(); return okAt[key(task, network)] ?: 0L }

    @Synchronized
    fun record(status: TaskStatus) {
        ensure()
        val network = status.network ?: "*"
        val okTime = if (status.outcome == TaskOutcome.OK) status.lastRunAt.also { okAt[key(status.task, network)] = it } else 0L
        while (okAt.size > MAX_KEYS) okAt.remove(okAt.keys.first())
        val previous = state.value[status.task]
        // A skip keeps the last real result visible; only its reason and time change.
        val merged = if (status.outcome == TaskOutcome.SKIPPED && previous != null && previous.outcome != TaskOutcome.SKIPPED)
            previous.copy(skip = status.skip, trigger = status.trigger)
        else status.copy(lastOkAt = if (okTime > 0) okTime else previous?.lastOkAt ?: 0L)
        state.value = state.value + (status.task to merged)
        persist()
    }

    @Synchronized
    fun clear() { ensure(); okAt.clear(); state.value = emptyMap(); persist() }

    private fun persist() {
        val tasks = JSONObject()
        state.value.values.forEach { s ->
            tasks.put(s.task.name, JSONObject().put("at", s.lastRunAt).put("o", s.outcome?.name ?: "").put("n", s.found)
                .put("net", s.network ?: "").put("tr", s.trigger?.name ?: "").put("s", s.skip?.name ?: "")
                .put("d", s.detail.take(240)).put("ok", s.lastOkAt))
        }
        val ok = JSONObject()
        okAt.forEach { (k, v) -> ok.put(k, v) }
        save(JSONObject().put("schema", SCHEMA).put("tasks", tasks).put("ok", ok).toString())
    }

    private fun key(task: AutomationTask, network: String) = "${task.name}|$network"

    companion object {
        private const val SCHEMA = 1
        private const val MAX_KEYS = 64
    }
}
