package com.example.vpn.diagnostics

import com.example.core.SecretRedactor
import com.example.data.model.ConnectionState
import com.example.data.model.ConnectionStatus
import com.example.data.model.VlessProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The stages a connect went through, engine by engine, with the errors on the way: shown on the Connection analytics
 * screen while connecting, and written to the app log (which also goes to logcat).
 *
 * 2026-11-25 engine test (V1.0.4 build 175, Irancell LTE): Aether, Psiphon, Shir o Khorshid, Lantern and MASQUE HTTP/2
 * each held the tunnel for minutes with no internet, the analytics screen said only "CONNECTING", and logcat had no line
 * from the app. Every published state change now leaves an entry here ([onStateChange]); the service adds the steps
 * a state does not show (engine start, deadline, fallback, filtered-site check) with [record].
 *
 * Entries carry the engine id ("psiphon", "aether/h2", "psiphon→radiance", "xray" for a saved config) and a redacted
 * detail: never a server address, a key or a profile link. Pure apart from the [sink], so it is unit-tested.
 */
object EngineStages {
    data class Entry(
        val at: Long,
        val engine: String,
        val stage: String,
        val detail: String?,
        val error: Boolean
    ) {
        /** One line for the log and logcat. */
        fun line(): String = "$engine · $stage" + (detail?.let { " · $it" } ?: "")
    }

    const val MAX_ENTRIES = 40
    const val MAX_DETAIL = 200

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /** Where each new entry is also written (the app log, which mirrors to logcat). Set by the VPN service. */
    @Volatile var sink: ((Entry) -> Unit)? = null

    /** A new Connect from the user: the timeline starts again. */
    fun begin(engine: String, now: Long = System.currentTimeMillis()) {
        _entries.value = emptyList()
        record(engine, "connect requested", now = now)
    }

    fun record(engine: String, stage: String, detail: String? = null, error: Boolean = false,
               now: Long = System.currentTimeMillis()) {
        val safe = detail?.takeIf { it.isNotBlank() }?.let { SecretRedactor.redact(it).replace('\n', ' ').take(MAX_DETAIL) }
        val entry = Entry(now, engine, stage, safe, error)
        while (true) {
            val current = _entries.value
            val last = current.lastOrNull()
            // The same stage twice in a row with the same detail says nothing new.
            if (last != null && last.engine == entry.engine && last.stage == entry.stage && last.detail == entry.detail) return
            if (_entries.compareAndSet(current, (current + entry).takeLast(MAX_ENTRIES))) break
        }
        runCatching { sink?.invoke(entry) }
    }

    /** Statuses that are an error or a connection without traffic. */
    private val ERROR_STATUSES = setOf(ConnectionStatus.FAILED, ConnectionStatus.DEGRADED,
        ConnectionStatus.TUNNEL_STARTED_CONNECTIVITY_UNVERIFIED)

    /**
     * Called with every published state: a new status (or a new error, or a new recovery step) becomes an entry.
     * Speed and counter updates change nothing here.
     */
    fun onStateChange(previous: ConnectionState, next: ConnectionState, now: Long = System.currentTimeMillis()) {
        val engine = engineOf(next.activeProfile ?: previous.activeProfile)
        if (next.status != previous.status) {
            val detail = when (next.status) {
                in ERROR_STATUSES -> next.errorMessage ?: next.failureStage?.name
                ConnectionStatus.CONNECTED -> next.pingMs?.let { "verified, $it ms" } ?: "verified"
                else -> null
            }
            record(engine, stageName(next.status), detail, error = next.status in ERROR_STATUSES, now = now)
        } else if (next.status in ERROR_STATUSES && next.errorMessage != null && next.errorMessage != previous.errorMessage) {
            record(engine, stageName(next.status), next.errorMessage, error = true, now = now)
        }
        if (next.recoveryStage != null && next.recoveryStage != previous.recoveryStage) {
            record(engine, "recovery", next.recoveryStage, now = now)
        }
    }

    /** Words for a status, as the analytics screen lists it. */
    fun stageName(status: ConnectionStatus): String = when (status) {
        ConnectionStatus.PREPARING -> "preparing"
        ConnectionStatus.CONNECTING -> "connecting"
        ConnectionStatus.VPN_INTERFACE_ESTABLISHED -> "tunnel created"
        ConnectionStatus.ENGINE_STARTED -> "engine started"
        ConnectionStatus.PROXY_CONNECTING -> "starting proxy"
        ConnectionStatus.VERIFYING -> "verifying traffic"
        ConnectionStatus.TUNNEL_STARTED_CONNECTIVITY_UNVERIFIED -> "tunnel up, no traffic"
        ConnectionStatus.CONNECTED -> "connected"
        ConnectionStatus.DEGRADED -> "degraded"
        ConnectionStatus.RECONNECTING -> "reconnecting"
        ConnectionStatus.DISCONNECTING -> "disconnecting"
        ConnectionStatus.DISCONNECTED -> "disconnected"
        ConnectionStatus.FAILED -> "failed"
        ConnectionStatus.SWITCHING -> "switching"
        ConnectionStatus.NETWORK_CHANGED -> "network changed"
    }

    /** The engine a profile runs on, without anything that identifies a server. */
    fun engineOf(profile: VlessProfile?): String {
        if (profile == null) return "none"
        val chain = runCatching { com.example.vpn.sidecar.ChainRunner.readChain(profile) }.getOrNull()
        if (chain != null) return chain.hops.joinToString("→") { it.engineId }
        val id = com.example.vpn.sidecar.EngineUdp.engineId(profile)
        if (id.isBlank()) return "xray"
        if (id == com.example.vpn.sidecar.AetherSidecar.ID) return "$id/${com.example.vpn.sidecar.EngineUdp.aetherCarrier(profile)}"
        return id
    }

    internal fun resetForTest() {
        _entries.value = emptyList()
        sink = null
    }
}
