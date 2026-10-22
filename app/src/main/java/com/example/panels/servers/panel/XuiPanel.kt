package com.example.panels.servers.panel

import com.example.panels.PanelProvisioner
import com.example.panels.servers.ActionProgress
import com.example.panels.servers.InstallOptions
import com.example.panels.servers.InstallOutcome
import com.example.panels.servers.ManagedServer
import com.example.panels.servers.RemoteShell
import com.example.panels.servers.ServerFacts
import com.example.panels.servers.ServerManager
import com.example.panels.servers.ServerToolCatalog
import com.example.panels.servers.SshShell
import com.example.panels.servers.ToolScript
import com.example.panels.servers.ToolScripts
import com.example.panels.servers.runTool

/** The 3x-ui scripts that ToolScripts does not already have (status, start/stop, backup). */
object XuiScripts {
    const val BIN = "/usr/local/x-ui/x-ui"
    const val DB = "/etc/x-ui/x-ui.db"
    /** Root-only folder for database copies; never leaves the server. */
    const val BACKUP_DIR = "/root/maximus-backups"

    /** Read-only: installed or not, version, and the x-ui unit's state. */
    fun status(): ToolScript = ToolScripts.script(
        """
        step 1
        if §SUDO test -x $BIN || systemctl cat x-ui >/dev/null 2>&1; then result INSTALLED 1; else result INSTALLED 0; fi
        result VERSION "§(§SUDO $BIN -v 2>/dev/null | head -n1 | tr -cd '0-9A-Za-z.-')"
        if systemctl cat x-ui >/dev/null 2>&1; then result SVC_x-ui "§(systemctl is-active x-ui 2>/dev/null || true)"; fi
        """,
        listOf("Read 3X-UI")
    )

    fun service(action: String): ToolScript {
        require(action in setOf("start", "stop")) { "Unknown action $action" }
        return ToolScripts.script(
            "step 1\n§SUDO systemctl $action x-ui\nresult DONE $action",
            listOf(action.replaceFirstChar { it.uppercase() })
        )
    }

    /** A copy of the panel database in [BACKUP_DIR] (mode 600). Result: BACKUP (its path). */
    fun backup(): ToolScript = ToolScripts.script(
        """
        step 1
        if ! §SUDO test -s $DB; then echo '3X-UI database not found' >&2; exit 55; fi
        f=$BACKUP_DIR/x-ui-§(date -u +%Y%m%d-%H%M%S).db
        §SUDO install -d -m 700 $BACKUP_DIR
        if command -v sqlite3 >/dev/null 2>&1; then §SUDO sqlite3 $DB ".backup '§f'"; else §SUDO cp $DB "§f"; fi
        §SUDO chmod 600 "§f"
        result BACKUP "§f"
        """,
        listOf("Back up 3X-UI")
    )
}

/**
 * 3x-ui behind [ServerPanelProvider]. Install and uninstall stay in [ServerManager] (signed
 * installer, HTTPS with a pinned certificate, panel API sign-in); this adapter adds status,
 * start/stop and backup. Updating is not offered: the installer leaves a working panel alone.
 */
class XuiPanelProvider(
    private val manager: ServerManager = ServerManager(),
    private val connect: (ManagedServer) -> RemoteShell = { SshShell.open(it) }
) : ServerPanelProvider {
    override val id = ServerToolCatalog.XUI
    override val title = "3X-UI"
    override val operations = PanelOperation.entries.toSet() - PanelOperation.UPDATE
    override val pinnedVersion = PanelProvisioner.XUI_VERSION

    override fun installBlocker(server: ManagedServer, facts: ServerFacts): String? = when {
        facts.blocker() != null -> facts.blocker()
        server.tool(ServerToolCatalog.HIDDIFY) != null || facts.hiddifyInstalled -> "This server runs Hiddify, which needs the server to itself."
        else -> null
    }

    override fun status(server: ManagedServer): PanelStatus {
        val r = runCatching { connect(server).use { it.runTool(XuiScripts.status(), LineCollector(), 60_000L) } }.getOrElse {
            return PanelStatus(id, PanelState.UNKNOWN, detail = it.message.orEmpty().take(160))
        }
        val services = servicesOf(r)
        val state = stateOf(r["INSTALLED"] == "1", false, services)
        return PanelStatus(id, state, r["VERSION"]?.ifBlank { null }, services, when (state) {
            PanelState.NOT_INSTALLED -> "not on this server"
            PanelState.RUNNING -> "panel service active"
            PanelState.STOPPED -> "installed, panel service not active"
            else -> "panel service could not be read"
        })
    }

    override fun install(server: ManagedServer, progress: ActionProgress): InstallOutcome =
        manager.install(server, id, InstallOptions(), progress)

    override fun update(server: ManagedServer, progress: ActionProgress): PanelStatus =
        throw UnsupportedOperationException("3X-UI updates are not offered")

    override fun backup(server: ManagedServer, progress: ActionProgress): String =
        connect(server).use { it.runTool(XuiScripts.backup(), progress, 2 * 60_000L) }["BACKUP"].orEmpty()

    override fun restart(server: ManagedServer, progress: ActionProgress): PanelStatus {
        manager.restart(server, id, progress)
        return status(server)
    }

    private fun service(server: ManagedServer, action: String, progress: ActionProgress): PanelStatus {
        connect(server).use { it.runTool(XuiScripts.service(action), progress, 2 * 60_000L) }
        return status(server)
    }

    override fun start(server: ManagedServer, progress: ActionProgress) = service(server, "start", progress)
    override fun stop(server: ManagedServer, progress: ActionProgress) = service(server, "stop", progress)
    override fun logs(server: ManagedServer, lines: Int): List<String> {
        val out = LineCollector()
        connect(server).use { it.runTool(ToolScripts.logs(id, lines), out, 60_000L) }
        return out.lines
    }
    override fun uninstall(server: ManagedServer, progress: ActionProgress): ManagedServer = manager.uninstall(server, id, progress)
}
