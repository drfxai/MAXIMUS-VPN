package com.example.vpn.unlock

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * What Unlock mode knows and what the user chose: the server list (official + custom), the last health
 * check of each server, auto-pick or the manual choice, the server the running session uses, and whether
 * Android's Private DNS keeps the override from applying. The Home card, the Unlock DNS sheet and
 * RayVpnService all read [state]; choices are kept in SharedPreferences.
 */
object UnlockDns {
    data class Row(val ip: String, val custom: Boolean, val check: ServerCheck?)

    enum class ListResult { UPDATED, NOT_PUBLISHED, FAILED }

    data class State(
        val rows: List<Row> = emptyList(),
        val autoPick: Boolean = true,
        val manual: String? = null,
        /** The server the running Unlock session answers from; null while Unlock mode is not connected. */
        val active: String? = null,
        val activeMs: Long? = null,
        val checking: Boolean = false,
        /** The official list came from a verified resolver pack (else the copy bundled in the app). */
        val listFromPack: Boolean = false,
        val listUpdatedAt: Long? = null,
        val listBusy: Boolean = false,
        val listResult: ListResult? = null,
        val privateDnsStrict: Boolean = false,
        /** Home badges: null while not yet verified for the active server. */
        val services: Map<UnlockDnsHealth.Service, Boolean?> = emptyMap()
    ) {
        val servers: List<String> get() = rows.map { it.ip }
        val custom: List<String> get() = rows.filter { it.custom }.map { it.ip }
        val checks: List<ServerCheck> get() = rows.mapNotNull { it.check }
    }

    private const val PREFS = "maximus_unlock_dns"
    private const val KEY_AUTO = "auto_pick"
    private const val KEY_MANUAL = "manual"
    private const val KEY_CUSTOM = "custom"

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Told when the user changes auto-pick, the manual server or the list (set by RayVpnService). */
    @Volatile var onChoiceChanged: (() -> Unit)? = null
    /** Sockets of checks run while a VPN of this app is up must leave beside it (set by RayVpnService). */
    @Volatile var protector: UnlockDnsWire.Protector = UnlockDnsWire.Protector()
    /** The probe used by [recheck] and [verifyServices]; tests replace it. */
    @Volatile var probe: () -> UnlockProbe = { UnlockDnsWire.Probe(protector) }

    /** Tests only: forget every check and choice held in memory. */
    internal fun resetForTest() {
        _state.value = State()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    internal fun customOf(prefs: SharedPreferences): List<String> =
        prefs.getString(KEY_CUSTOM, null).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .filter(com.example.vpn.BundledResolvers::publicIpv4).distinct().take(UnlockDnsList.MAX_CUSTOM)

    /** (Re)reads the official list and the user's choices, keeping known check results. */
    fun load(context: Context) {
        val p = prefs(context)
        val loaded = UnlockDnsList.load()
        val custom = customOf(p).filter { it !in loaded.servers }
        val updatedAt = if (loaded.fromPack) com.example.update.ResolverPackUpdater.unlockUpdatedAt(context) else null
        val strict = PrivateDnsCheck.strict(context)
        _state.update { s ->
            val known = s.checks.associateBy { it.ip }
            s.copy(
                rows = order(loaded.servers.map { Row(it, false, known[it]) } + custom.map { Row(it, true, known[it]) }),
                autoPick = p.getBoolean(KEY_AUTO, true),
                manual = p.getString(KEY_MANUAL, null),
                listFromPack = loaded.fromPack,
                listUpdatedAt = updatedAt,
                privateDnsStrict = strict
            )
        }
    }

    /** Checked rows by rank (healthy and fastest first, blocked last); unchecked rows keep list order. */
    internal fun order(rows: List<Row>): List<Row> {
        if (rows.none { it.check != null }) return rows
        val ranked = UnlockDnsHealth.rank(rows.map { it.check ?: ServerCheck(it.ip, ServerHealth.UNKNOWN) })
        val byIp = rows.associateBy { it.ip }
        return ranked.mapNotNull { byIp[it.ip] }
    }

    fun refreshPrivateDns(context: Context) {
        val strict = PrivateDnsCheck.strict(context)
        _state.update { it.copy(privateDnsStrict = strict) }
    }

    /** Checks every listed server now and publishes the results. */
    suspend fun recheck(context: Context): List<ServerCheck> = withContext(Dispatchers.IO) {
        load(context)
        _state.update { it.copy(checking = true) }
        try {
            val checks = UnlockDnsHealth.checkAll(_state.value.servers, probe())
            publishChecks(checks)
            onChoiceChanged?.invoke()
            checks
        } finally {
            _state.update { it.copy(checking = false) }
        }
    }

    fun publishChecks(checks: List<ServerCheck>) {
        val byIp = checks.associateBy { it.ip }
        _state.update { s -> s.copy(rows = order(s.rows.map { r -> byIp[r.ip]?.let { r.copy(check = it) } ?: r })) }
    }

    fun setAutoPick(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO, on).apply()
        _state.update { s ->
            // Turning auto-pick off starts from the server in use, so nothing changes until the user picks.
            val manual = if (!on) s.manual ?: s.active ?: UnlockDnsHealth.pick(s.checks, true, null) else s.manual
            manual?.let { prefs(context).edit().putString(KEY_MANUAL, it).apply() }
            s.copy(autoPick = on, manual = manual)
        }
        onChoiceChanged?.invoke()
    }

    /** The user's own pick; only offered while auto-pick is off. */
    fun select(context: Context, ip: String) {
        if (_state.value.autoPick || ip !in _state.value.servers) return
        prefs(context).edit().putString(KEY_MANUAL, ip).apply()
        _state.update { it.copy(manual = ip) }
        onChoiceChanged?.invoke()
    }

    fun addCustom(context: Context, input: String): UnlockDnsList.CustomResult {
        val s = _state.value
        val result = UnlockDnsList.validateCustom(input, s.rows.filter { !it.custom }.map { it.ip }, s.custom)
        if (result is UnlockDnsList.CustomResult.Added) {
            val custom = s.custom + result.ip
            prefs(context).edit().putString(KEY_CUSTOM, custom.joinToString(",")).apply()
            _state.update { st -> st.copy(rows = st.rows + Row(result.ip, true, null)) }
            onChoiceChanged?.invoke()
        }
        return result
    }

    fun removeCustom(context: Context, ip: String) {
        val s = _state.value
        if (ip !in s.custom) return
        val custom = s.custom - ip
        val edit = prefs(context).edit().putString(KEY_CUSTOM, custom.joinToString(","))
        val manual = if (s.manual == ip) null else s.manual
        if (manual == null) edit.remove(KEY_MANUAL)
        edit.apply()
        _state.update { st -> st.copy(rows = st.rows.filterNot { it.custom && it.ip == ip }, manual = manual) }
        onChoiceChanged?.invoke()
    }

    /** Fetches the newest official resolver pack (which carries the Unlock list) and reloads. */
    suspend fun refreshList(context: Context): ListResult {
        _state.update { it.copy(listBusy = true, listResult = null) }
        val result = try {
            val latest = runCatching {
                com.example.update.UpdateNetworkPolicy.requireAllowed(context)
                com.example.update.AppUpdateChecker.fetchLatest()
            }.getOrNull()
            val pack = latest?.resolverPack
            if (pack == null) ListResult.NOT_PUBLISHED
            else if (runCatching { com.example.update.ResolverPackUpdater.refresh(context, pack) }.isSuccess) ListResult.UPDATED
            else ListResult.FAILED
        } finally {
            _state.update { it.copy(listBusy = false) }
        }
        load(context)
        _state.update { it.copy(listResult = result) }
        if (result == ListResult.UPDATED) onChoiceChanged?.invoke()
        return result
    }

    /** The running session's server, for Home and the sheet; null clears it (and the badges). */
    fun setActive(ip: String?, latencyMs: Long?) {
        _state.update {
            if (ip == null) it.copy(active = null, activeMs = null, services = emptyMap())
            else it.copy(active = ip, activeMs = latencyMs,
                services = if (ip == it.active) it.services else UnlockDnsHealth.Service.entries.associateWith { null })
        }
    }

    /** Home badges: each service resolved through [server] and answered on 443. */
    suspend fun verifyServices(server: String) = withContext(Dispatchers.IO) {
        val p = probe()
        UnlockDnsHealth.Service.entries.forEach { service ->
            val ok = runCatching { UnlockDnsHealth.verify(server, service, p) }.getOrDefault(false)
            _state.update { s -> if (s.active != server) s else s.copy(services = s.services + (service to ok)) }
        }
    }
}
