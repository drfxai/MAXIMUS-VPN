package com.example.panels.servers.panel

import com.example.panels.servers.ActionProgress
import com.example.panels.servers.InstallOutcome
import com.example.panels.servers.InstalledTool
import com.example.panels.servers.ManagedServer
import com.example.panels.servers.RemoteShell
import com.example.panels.servers.ServerFacts
import com.example.panels.servers.ServerToolCatalog
import com.example.panels.servers.SshShell
import com.example.panels.servers.ToolScript
import com.example.panels.servers.ToolScripts
import com.example.panels.servers.runTool

/**
 * Scripts for Hiddify Manager (GPL-3.0, github.com/hiddify/Hiddify-Manager). The app never ships
 * Hiddify: it runs Hiddify's own official installer on the user's server, pinned to [VERSION].
 * The two installer files are checked against [INSTALLER_SHA256] before they run; what they fetch
 * afterwards (Python packages, the panel) is pinned by tag only, as Hiddify's installer does it.
 *
 * Hiddify takes over ports 80 and 443 with its own web stack, so it is only installed on a server
 * of its own: [installBlocker] refuses a server with 3x-ui, Maximus Tunnel or anything on 80/443,
 * and an existing Hiddify is detected and managed, never installed over.
 */
object HiddifyScripts {
    const val VERSION = "v14.0.5"
    /** The commit the tag pointed to when [INSTALLER_SHA256] was recorded. */
    const val VERSION_COMMIT = "331c1114d4bb0bcf62e9ac719932ae904e88cf9c"
    const val DIR = "/opt/hiddify-manager"
    const val BASE_URL = "https://raw.githubusercontent.com/hiddify/Hiddify-Manager/refs/tags/$VERSION/scripts/common"

    val INSTALLER_SHA256: Map<String, String> = linkedMapOf(
        "hiddify_installer.sh" to "03a60aaa97608bd42b4952eaedcc17ab5a3c4e4c6ff4a49f22a617323983d6c0",
        "utils.sh" to "0c3ea9b8f883faa3eb9498eaf819d642e5fd20fa19df080c081e0f36340e534b"
    )

    /** Exit code: Hiddify is already on the server (manage or import it instead). */
    const val EXIT_ALREADY = 50
    /** Exit code: something else listens on 80 or 443. */
    const val EXIT_PORTS = 51
    const val EXIT_OS = 52

    /** Enabled Hiddify units, one per line; nothing else is ever restarted or stopped. */
    private const val UNITS = "systemctl list-unit-files --type=service --no-legend 'hiddify-*' 2>/dev/null | " +
        "awk '§2==\"enabled\"{sub(/\\.service§/, \"\", §1); print §1}' | grep -E '^[A-Za-z0-9_.@-]+§' || true"

    /** Masks URL paths (Hiddify's secret admin and user paths) in installer output before it reaches the log. */
    private const val REDACT = "sed -u -E 's#(https?://[^/ \"]+)/[^ \"]*#\\1/…#g'"

    private val CLI = "cli=§(command -v hiddify-panel-cli 2>/dev/null || echo $DIR/.venv313/bin/hiddify-panel-cli)"

    /** Read-only: installed or not, version, and the state of each enabled Hiddify unit. */
    fun status(): ToolScript = ToolScripts.script(
        """
        step 1
        if §SUDO test -d $DIR; then result INSTALLED 1; else result INSTALLED 0; fi
        result VERSION "§(§SUDO cat $DIR/VERSION 2>/dev/null | head -n1 | tr -cd '0-9A-Za-z.-')"
        for u in §($UNITS); do result "SVC_§u" "§(systemctl is-active "§u" 2>/dev/null || true)"; done
        """,
        listOf("Read Hiddify")
    )

    val INSTALL_STEPS = listOf("Check the server", "Download the official installer", "Install Hiddify", "Verify the panel")

    /**
     * Installs ([update] false) or updates ([update] true) Hiddify to [VERSION] with its official
     * installer. Results: VERSION, ADMIN_URL (secret; kept out of the log).
     */
    fun install(update: Boolean): ToolScript {
        val fetch = INSTALLER_SHA256.entries.joinToString("\n") { (f, sha) ->
            """
            curl -fsSL --proto '=https' --tlsv1.2 --retry 3 --connect-timeout 20 -o "§work/$f" "$BASE_URL/$f" || { echo 'Could not download the Hiddify installer' >&2; exit 31; }
            if [ "§(sha256sum "§work/$f" | awk '{print §1}')" != "$sha" ]; then echo "The Hiddify installer ($f) does not match the pinned checksum; refusing to run it" >&2; exit 30; fi
            """.trimIndent()
        }
        // A fresh install never runs over an existing Hiddify or another web server; an update needs one.
        val guard = if (update) "if ! §SUDO test -d $DIR; then echo 'Hiddify is not installed' >&2; exit 53; fi" else """
            if §SUDO test -d $DIR; then echo 'Hiddify is already on this server; manage it instead of installing again' >&2; exit $EXIT_ALREADY; fi
            for p in 80 443; do
              if have ss && ss -Hltn "sport = :§p" 2>/dev/null | grep -q .; then echo "Port §p is in use; Hiddify needs a server of its own" >&2; exit $EXIT_PORTS; fi
            done
            """.trimIndent()
        return ToolScripts.script(
            """
            step 1
            if [ -r /etc/os-release ]; then . /etc/os-release; fi
            if [ "§{ID:-}" != ubuntu ]; then echo 'Hiddify needs Ubuntu (22.04 is the tested release)' >&2; exit $EXIT_OS; fi
            @@GUARD@@
            have curl || pkg_install curl ca-certificates
            step 2
            work=§(mktemp -d)
            trap 'rm -rf "§work"' EXIT
            @@FETCH@@
            chmod 700 "§work"/*.sh
            step 3
            # Hiddify's own installer, pinned to $VERSION; its output is shown with URL paths masked.
            ( rc=0; §SUDO bash "§work/hiddify_installer.sh" $VERSION --no-gui || rc=§?; echo "§rc" > "§work/rc" ) 2>&1 | $REDACT
            rc=§(cat "§work/rc" 2>/dev/null || echo 1)
            if [ "§rc" != 0 ]; then echo "The Hiddify installer stopped (exit §rc)" >&2; exit 54; fi
            step 4
            i=0
            while ! systemctl is-active --quiet hiddify-panel; do
              i=§((i + 1)); if [ §i -ge 60 ]; then echo 'hiddify-panel did not start' >&2; exit 40; fi; sleep 2
            done
            result VERSION "§(§SUDO cat $DIR/VERSION 2>/dev/null | head -n1 | tr -cd '0-9A-Za-z.-')"
            $CLI
            url=§(§SUDO "§cli" all-configs 2>/dev/null | grep -o 'https://[^" ]*/admin/[^" ]*' | head -n1 || true)
            if [ -n "§url" ]; then result ADMIN_URL "§url"; fi
            """,
            INSTALL_STEPS,
            mapOf("FETCH" to fetch, "GUARD" to guard)
        )
    }

    /** systemctl [action] (restart, start, stop) on every enabled Hiddify unit. */
    fun service(action: String): ToolScript {
        require(action in setOf("restart", "start", "stop"))
        return ToolScripts.script(
            """
            step 1
            units=§($UNITS)
            if [ -z "§units" ]; then echo 'No Hiddify services found' >&2; exit 53; fi
            §SUDO systemctl $action §units
            result DONE $action
            """,
            listOf(action.replaceFirstChar { it.uppercase() })
        )
    }

    /** Hiddify's own backup (a full database dump, kept on the server). Result: BACKUP (its path). */
    fun backup(): ToolScript = ToolScripts.script(
        """
        step 1
        $CLI
        §SUDO "§cli" backup >/dev/null 2>&1 || { echo 'Hiddify could not make a backup' >&2; exit 55; }
        f=§(§SUDO sh -c 'ls -t $DIR/data/backup/* $DIR/hiddify-panel/backup/* 2>/dev/null | head -n1' || true)
        if [ -z "§f" ]; then echo 'The backup file was not found' >&2; exit 55; fi
        result BACKUP "§f"
        """,
        listOf("Back up Hiddify")
    )

    fun logs(lines: Int): ToolScript {
        require(lines in 1..200)
        return ToolScripts.script(
            """
            step 1
            args=""
            for u in §($UNITS); do args="§args -u §u"; done
            if [ -z "§args" ]; then echo 'No Hiddify services found' >&2; exit 53; fi
            §SUDO journalctl §args -n $lines --no-pager -o short-iso 2>&1 | tail -n $lines | $REDACT
            """,
            listOf("Read logs")
        )
    }

    /** Hiddify's own uninstaller: stops and disables its services; [purge] also deletes its files and data. */
    fun uninstall(purge: Boolean): ToolScript = ToolScripts.script(
        """
        step 1
        if ! §SUDO test -f $DIR/scripts/uninstall.sh; then echo 'Hiddify uninstaller not found' >&2; exit 53; fi
        §SUDO bash $DIR/scripts/uninstall.sh${if (purge) " purge" else ""} 2>&1 | tail -n 20 | $REDACT
        result UNINSTALLED ${if (purge) "purge" else "services"}
        """,
        listOf("Uninstall Hiddify")
    )
}

/** Hiddify Manager behind [ServerPanelProvider]. */
class HiddifyPanelProvider(
    private val connect: (ManagedServer) -> RemoteShell = { SshShell.open(it) }
) : ServerPanelProvider {
    override val id = ServerToolCatalog.HIDDIFY
    override val title = "Hiddify"
    override val operations = PanelOperation.entries.toSet()
    override val pinnedVersion = HiddifyScripts.VERSION

    /** Minimums from Hiddify's own guidance, rounded up for a working panel. */
    private val minRamMb = 1000L
    private val minDiskFreeMb = 5_000L

    override fun installBlocker(server: ManagedServer, facts: ServerFacts): String? = when {
        facts.blocker() != null -> facts.blocker()
        facts.osId != "ubuntu" -> "Hiddify needs Ubuntu (22.04 is the tested release); this server runs ${facts.osName.ifBlank { "another system" }}."
        server.tool(ServerToolCatalog.XUI) != null || facts.services.containsKey("x-ui") ->
            "This server runs 3x-ui. Hiddify is only installed on a server of its own."
        server.tool(ServerToolCatalog.MAXIMUS_TUNNEL) != null -> "This server runs Maximus Tunnel. Hiddify is only installed on a server of its own."
        80 in facts.tcpPorts || 443 in facts.tcpPorts -> "Ports 80 or 443 are in use. Hiddify needs both, on a server of its own."
        facts.ramMb in 1 until minRamMb -> "Hiddify needs at least 1 GB of memory; this server has ${facts.ramMb} MB."
        facts.diskFreeMb in 1 until minDiskFreeMb -> "Hiddify needs about 5 GB of free disk; this server has ${facts.diskFreeMb} MB."
        else -> null
    }

    private fun read(server: ManagedServer): Map<String, String> = connect(server).use { it.runTool(HiddifyScripts.status(), LineCollector(), 60_000L) }

    override fun status(server: ManagedServer): PanelStatus {
        val r = runCatching { read(server) }.getOrElse {
            return PanelStatus(id, PanelState.UNKNOWN, detail = it.message.orEmpty().take(160))
        }
        val installed = r["INSTALLED"] == "1"
        val services = servicesOf(r)
        val state = stateOf(installed, false, services)
        return PanelStatus(id, state, r["VERSION"]?.ifBlank { null }, services, when (state) {
            PanelState.NOT_INSTALLED -> "not on this server"
            PanelState.RUNNING -> "all ${services.size} services active"
            PanelState.DEGRADED -> services.filterValues { it != "active" }.keys.joinToString(prefix = "not active: ")
            PanelState.STOPPED -> "installed, no service active"
            else -> "services could not be read"
        })
    }

    override fun install(server: ManagedServer, progress: ActionProgress): InstallOutcome {
        val facts = connect(server).use { ServerFacts.parse(it.run(ServerFacts.SCRIPT, 60_000L).output) }
        installBlocker(server, facts)?.let { error(it) }
        val r = connect(server).use { it.runTool(HiddifyScripts.install(update = false), progress, INSTALL_TIMEOUT_MS) }
        return outcome(server, r, facts, "Hiddify ${r["VERSION"].orEmpty()} installed")
    }

    /**
     * Records a Hiddify that is already on [server] (installed by hand or by another tool) without
     * changing anything there. Fails when there is none.
     */
    fun import(server: ManagedServer): InstallOutcome {
        val st = status(server)
        check(st.state != PanelState.NOT_INSTALLED && st.state != PanelState.UNKNOWN) { "No Hiddify found on this server" }
        val tool = InstalledTool(id, settings = mapOfNotNull(K_VERSION to st.version, K_IMPORTED to "1"))
        return InstallOutcome(server.withTool(tool), tool, summary = "Hiddify ${st.version.orEmpty()} found and added")
    }

    override fun update(server: ManagedServer, progress: ActionProgress): PanelStatus {
        val before = status(server)
        check(before.state != PanelState.NOT_INSTALLED) { "Hiddify is not installed" }
        // The installer cannot go back to an older release.
        check(before.version == null || PanelStatus.compareVersions(HiddifyScripts.VERSION, before.version) > 0) {
            "Hiddify ${before.version} is not older than ${HiddifyScripts.VERSION}; nothing to update"
        }
        connect(server).use { it.runTool(HiddifyScripts.install(update = true), progress, INSTALL_TIMEOUT_MS) }
        return status(server)
    }

    override fun backup(server: ManagedServer, progress: ActionProgress): String =
        connect(server).use { it.runTool(HiddifyScripts.backup(), progress, 5 * 60_000L) }["BACKUP"].orEmpty()

    private fun service(server: ManagedServer, action: String, progress: ActionProgress): PanelStatus {
        connect(server).use { it.runTool(HiddifyScripts.service(action), progress, 3 * 60_000L) }
        return status(server)
    }

    override fun restart(server: ManagedServer, progress: ActionProgress) = service(server, "restart", progress)
    override fun start(server: ManagedServer, progress: ActionProgress) = service(server, "start", progress)
    override fun stop(server: ManagedServer, progress: ActionProgress) = service(server, "stop", progress)

    override fun logs(server: ManagedServer, lines: Int): List<String> {
        val out = LineCollector()
        connect(server).use { it.runTool(HiddifyScripts.logs(lines), out, 60_000L) }
        return out.lines
    }

    /** Stops and disables Hiddify; its files and database stay on the server. */
    override fun uninstall(server: ManagedServer, progress: ActionProgress): ManagedServer {
        connect(server).use { it.runTool(HiddifyScripts.uninstall(purge = false), progress, 5 * 60_000L) }
        return server.withoutTool(id)
    }

    private fun outcome(server: ManagedServer, r: Map<String, String>, facts: ServerFacts, summary: String): InstallOutcome {
        val tool = InstalledTool(id, port = 443, settings = mapOfNotNull(K_VERSION to r["VERSION"], K_ADMIN_URL to r["ADMIN_URL"]))
        return InstallOutcome(server.withTool(tool).copy(facts = facts, checkedAt = System.currentTimeMillis()), tool, summary = summary)
    }

    companion object {
        const val K_VERSION = "version"
        /** The panel's admin address with its secret path; kept only in the encrypted server store. */
        const val K_ADMIN_URL = "adminUrl"
        const val K_IMPORTED = "imported"
        /** Hiddify's installer builds Python packages and can take a long time on small servers. */
        const val INSTALL_TIMEOUT_MS = 45 * 60_000L
    }
}

internal fun mapOfNotNull(vararg pairs: Pair<String, String?>): Map<String, String> =
    pairs.mapNotNull { (k, v) -> v?.takeIf { it.isNotBlank() }?.let { k to it } }.toMap()
