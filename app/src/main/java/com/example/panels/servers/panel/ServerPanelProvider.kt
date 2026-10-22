package com.example.panels.servers.panel

import com.example.panels.servers.ActionProgress
import com.example.panels.servers.InstallOutcome
import com.example.panels.servers.ManagedServer
import com.example.panels.servers.ServerFacts

/** What a managed panel on one server is doing, from a read-only check. */
enum class PanelState {
    NOT_INSTALLED,
    /** Its installer is running right now (it holds its own lock). */
    INSTALLING,
    /** Every service of the panel is active. */
    RUNNING,
    /** Some of its services are active, others are not. */
    DEGRADED,
    /** Installed, no service active. */
    STOPPED,
    /** The check could not run or could not read the panel. */
    UNKNOWN
}

/** The operations a panel can be asked for. Each provider says which ones it really supports. */
enum class PanelOperation { DETECT, INSTALL, STATUS, UPDATE, BACKUP, RESTART, START, STOP, LOGS, UNINSTALL }

data class PanelStatus(
    val providerId: String,
    val state: PanelState,
    /** Installed version as the panel reports it, or null when it could not be read. */
    val version: String? = null,
    /** systemd unit -> active state, for every unit of the panel found on the server. */
    val services: Map<String, String> = emptyMap(),
    /** Plain words for the state, never a secret. */
    val detail: String = "",
    val checkedAt: Long = System.currentTimeMillis()
) {
    /** True when the version the app pins is newer than the one installed. */
    fun updateAvailable(pinned: String?): Boolean =
        pinned != null && version != null && compareVersions(pinned, version) > 0

    companion object {
        /** Compares dotted versions ("14.0.5" vs "v14.0.10"); a leading v is ignored, missing parts count as 0. */
        fun compareVersions(a: String, b: String): Int {
            fun parts(v: String) = v.trim().removePrefix("v").split('.', '-').map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
            val x = parts(a); val y = parts(b)
            for (i in 0 until maxOf(x.size, y.size)) {
                val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }
    }
}

/**
 * One kind of server panel (3x-ui, Hiddify...) behind the same operations, so the Panels screens
 * and the SSH plumbing are shared and only the panel-specific scripts live in each adapter. Every
 * call is blocking and runs over the server's pinned SSH session; call it off the main thread.
 * Calls a provider does not list in [operations] throw [UnsupportedOperationException].
 */
interface ServerPanelProvider {
    val id: String
    val title: String
    val operations: Set<PanelOperation>
    /** The panel version this app installs and updates to, or null when the panel's own installer decides. */
    val pinnedVersion: String?

    /** Why the panel cannot be installed on this server, or null when it can. Read-only. */
    fun installBlocker(server: ManagedServer, facts: ServerFacts): String?

    /** Read-only: is it there, which version, are its services running. */
    fun status(server: ManagedServer): PanelStatus

    fun install(server: ManagedServer, progress: ActionProgress): InstallOutcome
    fun update(server: ManagedServer, progress: ActionProgress): PanelStatus
    /** Makes a backup on the server and returns its path there (never its contents). */
    fun backup(server: ManagedServer, progress: ActionProgress): String
    fun restart(server: ManagedServer, progress: ActionProgress): PanelStatus
    fun start(server: ManagedServer, progress: ActionProgress): PanelStatus
    fun stop(server: ManagedServer, progress: ActionProgress): PanelStatus
    fun logs(server: ManagedServer, lines: Int = 40): List<String>
    fun uninstall(server: ManagedServer, progress: ActionProgress): ManagedServer
}

/** Every provider the app has, by id. */
object ServerPanels {
    fun all(): List<ServerPanelProvider> = listOf(XuiPanelProvider(), HiddifyPanelProvider())
    fun byId(id: String): ServerPanelProvider? = all().firstOrNull { it.id == id }
}

/** Collects a script's log lines, for logs() calls. */
internal class LineCollector : ActionProgress {
    val lines = mutableListOf<String>()
    override fun step(index: Int) = Unit
    override fun log(line: String) { lines += line }
}

/** Reads `##RESULT SVC_<unit>=<state>` results into a unit -> state map. */
internal fun servicesOf(r: Map<String, String>): Map<String, String> =
    r.filterKeys { it.startsWith("SVC_") }.mapKeys { it.key.removePrefix("SVC_") }

/** The state of a panel from whether it is installed and the states of its units. */
internal fun stateOf(installed: Boolean, installing: Boolean, services: Map<String, String>): PanelState = when {
    installing -> PanelState.INSTALLING
    !installed -> PanelState.NOT_INSTALLED
    services.isEmpty() -> PanelState.UNKNOWN
    services.values.all { it == "active" } -> PanelState.RUNNING
    services.values.any { it == "active" } -> PanelState.DEGRADED
    else -> PanelState.STOPPED
}
