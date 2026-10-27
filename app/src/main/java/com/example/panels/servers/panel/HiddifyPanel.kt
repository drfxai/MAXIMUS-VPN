package com.example.panels.servers.panel

import com.example.panels.servers.ActionProgress
import com.example.panels.servers.CheckItem
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

    /** Registered Hiddify units (including disabled ones); no unrelated units. */
    private const val UNITS = "systemctl list-unit-files --type=service --no-legend 'hiddify-*' 2>/dev/null | " +
        "awk '{sub(/[.]service§/, \"\", §1); print §1}' | grep -E '^hiddify-[A-Za-z0-9_.@-]+§' || true"

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

    /** Read the same durable panel_links array used by pinned Hiddify's print_panel_links.
     * Unlike `hiddify admin`, this does not reload configs, install packages or restart services. */
    private val READ_ADMIN = """
        url=§(timeout 10s §SUDO python3 - <<'READ_LINK'
        import ipaddress, json, re, sys
        from urllib.parse import urlsplit
        try:
            with open('/opt/hiddify-manager/data/current.json', 'rb') as f:
                raw = f.read(1048577)
            if len(raw) > 1048576:
                raise ValueError()
            links = json.loads(raw).get('panel_links', [])
            if not isinstance(links, list):
                raise ValueError()
            candidates = []
            for link in links:
                if not isinstance(link, str) or len(link) > 2048 or any(ord(c) <= 32 or ord(c) >= 127 for c in link):
                    continue
                u = urlsplit(link)
                parts = [x for x in u.path.split('/') if x]
                if (u.scheme != 'https' or not u.hostname or u.username is not None or u.password is not None
                    or u.query or u.fragment or u.port not in (None, 443) or '%' in u.path
                    or len(parts) < 2 or any(not re.fullmatch(r'[A-Za-z0-9_-]{1,256}', x) for x in parts)):
                    continue
                try:
                    ipaddress.ip_address(u.hostname)
                    numeric = 1
                except ValueError:
                    numeric = 0
                candidates.append((numeric, link))
            if not candidates:
                raise ValueError()
            print(sorted(candidates)[0][1])
        except Exception:
            sys.exit(56)
        READ_LINK
        ) || url=''
    """.trimIndent()

    fun adminUrl(): ToolScript = ToolScripts.script(
        """
        step 1
        if ! §SUDO test -d $DIR; then echo 'Hiddify is not installed' >&2; exit 53; fi
        @@READ_ADMIN@@
        if [ -z "§url" ]; then
          echo 'No valid HTTPS admin link in Hiddify current.json; complete initial setup or refresh panel links in Hiddify first.' >&2
          exit 56
        fi
        result ADMIN_URL "§url"
        """,
        listOf("Retrieve Hiddify admin link"),
        mapOf("READ_ADMIN" to READ_ADMIN)
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
            @@READ_ADMIN@@
            if [ -n "§url" ]; then result ADMIN_URL "§url"; fi
            """,
            INSTALL_STEPS,
            mapOf("FETCH" to fetch, "GUARD" to guard, "READ_ADMIN" to READ_ADMIN)
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
            if [ -z "§args" ]; then
              echo 'Hiddify files detected but no registered services found; installation health is unverified.'
              args="-u hiddify-panel"
            fi
            §SUDO systemctl show hiddify-panel -p LoadState -p ActiveState -p SubState -p Result -p ExecMainStatus 2>&1 || true
            §SUDO journalctl §args -n $lines --no-pager -o short-iso 2>&1 | tail -n $lines | $REDACT
            """,
            listOf("Read logs")
        )
    }

    /** Non-purging removal: only Hiddify units are stopped. Incomplete, data-free files are
     * quarantined atomically. Never execute a partially installed remote uninstaller or stop netdata. */
    fun uninstall(purge: Boolean): ToolScript = ToolScripts.script(
        """
        step 1

        if §SUDO test -L $DIR; then echo 'Refusing to operate on a symlinked Hiddify folder' >&2; exit 57; fi
        unit_files=§(systemctl list-unit-files --type=service --no-legend 'hiddify-*' 2>/dev/null) || { echo 'Could not inspect Hiddify services; no files changed.' >&2; exit 57; }
        unit_instances=§(systemctl list-units --all --plain --type=service --no-legend 'hiddify-*' 2>/dev/null) || { echo 'Could not inspect running Hiddify services; no files changed.' >&2; exit 57; }
        units=§(printf '%s\n%s\n' "§unit_files" "§unit_instances" | awk '{sub(/[.]service§/, "", §1); print §1}' | grep -E '^hiddify-[A-Za-z0-9_.@-]+§' | sort -u || true)
        if [ -n "§units" ]; then
          for u in §units; do
            §SUDO systemctl stop "§u" || exit 57
            §SUDO systemctl disable "§u" || exit 57
            ${if (purge) "if §SUDO systemctl is-active --quiet \"§u\"; then echo 'Service still active; no data deleted' >&2; exit 57; fi" else ""}
          done
          ${if (purge) "" else "echo 'Hiddify services stopped and disabled. Files and databases were retained.'"}
          ${if (purge) "" else "result UNINSTALLED services\nexit 0"}
        fi
        ${if (purge) """
        # Explicit complete removal from the app. Only Hiddify-owned paths are removed.
        for u in §units; do
          for base in /etc/systemd/system /lib/systemd/system /usr/lib/systemd/system; do
            §SUDO rm -f -- "§base/§u.service"
            §SUDO rm -rf -- "§base/§u.service.d"
          done
        done
        §SUDO rm -rf -- $DIR
        §SUDO systemctl daemon-reload
        for u in §units; do
          if §SUDO systemctl is-active --quiet "§u"; then echo 'A Hiddify service is still active; removal incomplete' >&2; exit 57; fi
          §SUDO systemctl reset-failed "§u" >/dev/null 2>&1 || true
        done
        §SUDO test ! -e $DIR || exit 57
        result UNINSTALLED purged
        exit 0
        """.trimIndent() else ""}
        if ! §SUDO test -d $DIR; then result UNINSTALLED absent; exit 0; fi
        if §SUDO test -e $DIR/data/current.json || §SUDO test -x $DIR/.venv313/bin/hiddify-panel-cli || §SUDO test -x $DIR/.venv/bin/hiddify-panel-cli; then
          echo 'Hiddify application or configuration data exists without managed services; removal is refused to preserve it. Inspect and back it up first.' >&2; exit 57
        fi
        if §SUDO test -d $DIR/data && [ -n "§(§SUDO find $DIR/data -type f -print -quit)" ]; then
          echo 'Hiddify data files exist; partial cleanup is refused. Back up and inspect this installation first.' >&2; exit 57
        fi
        backup="$DIR.incomplete-§(date -u +%Y%m%dT%H%M%SZ)-§§"
        if §SUDO test -e "§backup"; then echo 'Backup destination already exists; no files changed.' >&2; exit 57; fi
        §SUDO mv -- $DIR "§backup"
        result BACKUP "§backup"
        echo 'Incomplete Hiddify files moved to a recoverable backup; no other service was changed.'
        result UNINSTALLED incomplete
        """,
        listOf("Remove Hiddify safely")
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

    override fun installBlocker(server: ManagedServer, facts: ServerFacts): String? =
        facts.blocker() ?: checks(server, facts).firstOrNull { it.blocking }?.detail

    private fun read(server: ManagedServer): Map<String, String> = connect(server).use { it.runTool(HiddifyScripts.status(), LineCollector(), 60_000L) }

    override fun status(server: ManagedServer): PanelStatus {
        val r = runCatching { read(server) }.getOrElse {
            return PanelStatus(id, PanelState.UNKNOWN, detail = com.example.panels.servers.ServerLogSanitizer.redact(it.message.orEmpty()).take(2000))
        }
        val services = servicesOf(r)
        val installed = r["INSTALLED"] == "1" || services.isNotEmpty()
        val state = if (installed && services.isEmpty()) PanelState.STOPPED else stateOf(installed, false, services)
        return PanelStatus(id, state, r["VERSION"]?.ifBlank { null }, services, when (state) {
            PanelState.NOT_INSTALLED -> "not on this server"
            PanelState.RUNNING -> "all ${services.size} services active"
            PanelState.DEGRADED -> services.filterValues { it != "active" }.keys.joinToString(prefix = "not active: ")
            PanelState.STOPPED -> if (services.isEmpty()) "Hiddify files found, but no services detected; installation is incomplete or unverified" else "installed, no service active"
            else -> "services could not be read"
        })
    }

    override fun install(server: ManagedServer, progress: ActionProgress): InstallOutcome {
        val facts = connect(server).use { ServerFacts.parse(it.run(ServerFacts.SCRIPT, 60_000L).output) }
        installBlocker(server, facts)?.let { error(it) }
        val r = connect(server).use { it.runTool(HiddifyScripts.install(update = false), progress, INSTALL_TIMEOUT_MS) }
        return outcome(server, r, "Hiddify ${r["VERSION"].orEmpty()} installed")
    }

    /**
     * Records a Hiddify that is already on [server] (installed by hand or by another tool) without
     * changing anything there. Fails when there is none.
     */
    fun import(server: ManagedServer): InstallOutcome {
        val st = status(server)
        check(st.state != PanelState.NOT_INSTALLED) { "No Hiddify files found on this server" }
        check(st.state != PanelState.UNKNOWN) { "Could not inspect Hiddify over SSH: ${st.detail}" }
        val tool = InstalledTool(id, settings = mapOfNotNull(K_VERSION to st.version, K_IMPORTED to "1"))
        return InstallOutcome(server.withTool(tool), tool, summary = "Hiddify ${st.version.orEmpty()} found and added")
    }

    /**
     * Recover a missing admin link on an already installed server without reinstalling Hiddify.
     * The returned ManagedServer must be persisted in the encrypted ServerStore.
     */
    fun restoreAdminUrl(server: ManagedServer): ManagedServer {
        val tool = requireNotNull(server.tool(id)) { "Hiddify is not recorded on this server" }
        val result = connect(server).use {
            it.runTool(HiddifyScripts.adminUrl(), LineCollector(), 30_000L)
        }
        val url = validatedAdminUrl(result["ADMIN_URL"])
            ?: error("Hiddify returned no valid HTTPS admin link. Complete Hiddify setup and refresh its panel links first.")
        return server.withTool(tool.copy(settings = tool.settings + (K_ADMIN_URL to url)))
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

    /** User-confirmed deletion of Hiddify units, installation and panel data. */
    override fun uninstall(server: ManagedServer, progress: ActionProgress): ManagedServer {
        connect(server).use { it.runTool(HiddifyScripts.uninstall(purge = true), progress, 5 * 60_000L) }
        return server.withoutTool(id)
    }

    private fun outcome(server: ManagedServer, r: Map<String, String>, summary: String): InstallOutcome {
        val tool = InstalledTool(id, port = 443, settings = mapOfNotNull(K_VERSION to r["VERSION"], K_ADMIN_URL to validatedAdminUrl(r["ADMIN_URL"])))
        val fresh = runCatching {
            connect(server).use { shell ->
                val result = shell.run(ServerFacts.SCRIPT, 60_000L)
                check(result.exitCode == 0 && result.output.contains(ServerFacts.BEGIN)) { "Could not refresh Hiddify service state" }
                ServerFacts.parse(result.output)
            }
        }.getOrNull()
        return InstallOutcome(server.withTool(tool).copy(facts = fresh,
            checkedAt = if (fresh == null) 0L else System.currentTimeMillis()), tool, summary = summary)
    }

    companion object {
        /** Admin links contain a secret path and ID. Reject plain HTTP, credentials in authority,
         * non-panel links, and invalid URIs before persisting/opening them. */
        fun validatedAdminUrl(raw: String?): String? {
            val url = raw?.trim() ?: return null
            if (url.length > 2048 || url.any { it.code <= 32 || it.code >= 127 }) return null
            val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
            if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() ||
                uri.userInfo != null || uri.fragment != null || uri.rawQuery != null || uri.port !in listOf(-1, 443)) return null
            val parts = uri.rawPath.orEmpty().split('/').filter { it.isNotBlank() }
            if (parts.size < 2 || parts.any { !it.matches(Regex("[A-Za-z0-9_-]{1,256}")) }) return null
            return url
        }

        /** Memory below this refuses the install (DrFX, 2026-10-22: servers under 1 GB may install). */
        const val MIN_RAM_MB = 500L
        /** Below this the install goes ahead with a warning to add swap first. */
        const val LOW_RAM_MB = 1000L
        const val MIN_DISK_FREE_MB = 5_000L

        /**
         * The read-only checks before an install, shown in the wizard; any blocking item refuses
         * the install. Hiddify goes only on a server of its own (DrFX, 2026-10-22).
         */
        fun checks(server: ManagedServer, facts: ServerFacts): List<CheckItem> = buildList {
            add(CheckItem("Ubuntu", facts.osId == "ubuntu",
                if (facts.osId == "ubuntu") "${facts.osName.ifBlank { "Ubuntu" }} (22.04 is the tested release)"
                else "Hiddify needs Ubuntu; this server runs ${facts.osName.ifBlank { "another system" }}"))
            val other = when {
                facts.hiddifyInstalled || server.tool(ServerToolCatalog.HIDDIFY) != null ->
                    "Hiddify is already here; add it from the server page instead"
                facts.xuiInstalled || server.tool(ServerToolCatalog.XUI) != null -> "This server runs 3X-UI"
                facts.tunnelInstalled || server.tool(ServerToolCatalog.MAXIMUS_TUNNEL) != null -> "This server runs Maximus Tunnel"
                else -> null
            }
            add(CheckItem("A server of its own", other == null, other?.let { "$it. Hiddify needs a server of its own." } ?: "no other panel or tunnel"))
            val busy = listOf(80, 443).filter { it in facts.tcpPorts }
            add(CheckItem("Ports 80 and 443", busy.isEmpty(),
                if (busy.isEmpty()) "free" else "${busy.joinToString(" and ")} in use. Hiddify needs both, on a server of its own."))
            add(when (facts.ramMb) {
                in 1 until MIN_RAM_MB -> CheckItem("Memory", false, "Hiddify needs at least $MIN_RAM_MB MB; this server has ${facts.ramMb} MB")
                in MIN_RAM_MB until LOW_RAM_MB -> CheckItem("Memory", false,
                    "${facts.ramMb} MB. Under 1 GB the install is slow and the panel may run short; add Swap from the Install tab first.", blocking = false)
                else -> CheckItem("Memory", true, "${facts.ramMb} MB")
            })
            add(CheckItem("Free disk for Hiddify", facts.diskFreeMb !in 1 until MIN_DISK_FREE_MB,
                if (facts.diskFreeMb in 1 until MIN_DISK_FREE_MB) "Hiddify needs about 5 GB free; this server has ${facts.diskFreeMb} MB"
                else "${facts.diskFreeMb / 1024} GB free"))
        }

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
