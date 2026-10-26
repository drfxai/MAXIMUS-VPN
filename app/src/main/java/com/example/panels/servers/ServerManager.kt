package com.example.panels.servers

import com.example.data.model.VlessProfile
import com.example.panels.ManagedPanel
import com.example.panels.PanelProvisioner
import com.example.panels.XuiInstallRequest
import com.example.panels.servers.panel.HiddifyPanelProvider
import com.example.panels.servers.panel.HiddifyScripts
import com.example.panels.servers.panel.ServerPanelProvider
import com.example.panels.servers.panel.XuiPanelProvider
import com.example.panels.servers.panel.mapOfNotNull

/** Options the install wizard's settings step collects. */
data class InstallOptions(
    val baseDomain: String = "",
    val tunnelLabel: String = "t",
    /** DNS tunnel backup domains (base domains, like [baseDomain]). */
    val backupBases: List<String> = emptyList(),
    val port: Int? = null,
    val swapMb: Int = 1024,
    /** Replace keys or secrets that already exist on the server. */
    val renew: Boolean = false
)

/** What an install produced. */
data class InstallOutcome(
    val server: ManagedServer,
    val tool: InstalledTool?,
    val profile: VlessProfile? = null,
    val link: String? = null,
    val panel: ManagedPanel? = null,
    val summary: String = ""
)

/** Live progress of a server action. */
interface ActionProgress {
    fun step(index: Int)
    fun log(line: String)
}

/**
 * Runs a [ToolScript], streaming its steps and log lines to [progress], and returns its results.
 * A non-zero exit becomes an error with the script's last two log lines.
 */
internal fun RemoteShell.runTool(script: ToolScript, progress: ActionProgress, timeoutMs: Long = 15 * 60_000L): Map<String, String> {
    val reader = ScriptProgress(onStep = { progress.step(it) }, onLog = { progress.log(it) })
    val result = run(script.text, timeoutMs) { reader.line(it) }
    if (result.exitCode != 0) {
        val tail = result.output.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("##") }.toList().takeLast(2).joinToString(" · ")
        error(ServerLogSanitizer.redact(tail.ifBlank { "The server reported an error (exit ${result.exitCode})" }).take(2000))
    }
    return reader.results
}

/**
 * Everything the Panels server screens do on a server, over pinned SSH. Blocking: call it off the
 * main thread. It returns updated [ManagedServer] values and never stores them itself.
 */
class ServerManager(
    private val provisioner: PanelProvisioner = PanelProvisioner(),
    private val connect: (ManagedServer) -> RemoteShell = { SshShell.open(it) }
) {
    /** Hiddify goes through its panel provider; its scripts live there. */
    val hiddify by lazy { HiddifyPanelProvider(connect) }
    private val xui by lazy { XuiPanelProvider(this, connect) }

    fun discoverHostKey(host: String, port: Int): String = provisioner.discoverSshHostKey(host, port)

    /** Signs in once (proving the password or key and the pinned host key) and reads the server. */
    fun check(server: ManagedServer): ManagedServer = connect(server).use { shell ->
        val out = shell.run(ServerFacts.SCRIPT, 60_000L)
        val facts = ServerFacts.parse(out.output)
        server.copy(facts = facts, checkedAt = System.currentTimeMillis())
    }

    private fun runScript(shell: RemoteShell, script: ToolScript, progress: ActionProgress, timeoutMs: Long = 15 * 60_000L): Map<String, String> =
        shell.runTool(script, progress, timeoutMs)

    private fun factsOrCheck(server: ManagedServer): ServerFacts = server.facts ?: check(server).facts!!

    fun install(server: ManagedServer, toolId: String, options: InstallOptions, progress: ActionProgress): InstallOutcome {
        val tool = requireNotNull(ServerToolCatalog.byId(toolId)) { "Unknown tool $toolId" }
        require(!tool.comingLater) { "${tool.title} is not available yet" }
        val conflict = tool.conflicts.firstOrNull { server.tool(it) != null }
        check(conflict == null) { "This server already runs ${conflict ?: ""}; DNS tunnel engines cannot share the public DNS listener." }
        return when (toolId) {
            ServerToolCatalog.XUI -> {
                check(server.tool(ServerToolCatalog.HIDDIFY) == null) { "This server runs Hiddify, which needs the server to itself." }
                installXui(server, progress)
            }
            ServerToolCatalog.HIDDIFY -> if (server.tool(toolId) == null) hiddify.install(server, progress) else updateHiddify(server, progress)
            ServerToolCatalog.KEY_LOGIN -> addKeyLogin(server, progress)
            else -> connect(server).use { shell ->
                val facts = ServerFacts.parse(shell.run(ServerFacts.SCRIPT, 60_000L).output)
                facts.blocker()?.let { error(it) }
                val arch = facts.goArch!!
                when (toolId) {
                    ServerToolCatalog.SOAR, ServerToolCatalog.COTTEN -> {
                        DnsDelegation.problem(options.baseDomain, options.tunnelLabel)?.let { error(it) }
                        check(server.tool(toolId) == null) { "A safe in-place upgrade is not available yet; no changes were made." }
                        check(53 !in facts.udpPorts && (toolId != ServerToolCatalog.COTTEN || 53 !in facts.tcpPorts)) {
                            "Port 53 is occupied by another service. Stop or move it before installing a DNS tunnel."
                        }
                        val domain = DnsDelegation.tunnelDomain(options.baseDomain, options.tunnelLabel)
                        val script = if (toolId == ServerToolCatalog.SOAR) DnsServerScripts.soar(domain)
                            else DnsServerScripts.cotten(domain, arch)
                        val r = runScript(shell, script, progress, 20 * 60_000L)
                        val keyName = if (toolId == ServerToolCatalog.SOAR) ToolProfiles.SOAR_PUBKEY else ToolProfiles.COTTEN_KEY
                        val key = r[if (toolId == ServerToolCatalog.SOAR) "PUBKEY" else "COTTEN_KEY"].orEmpty()
                        if (toolId == ServerToolCatalog.SOAR)
                            require(runCatching { java.util.Base64.getDecoder().decode(key).size == 32 }.getOrDefault(false)) {
                                "Soar did not return its public key"
                            }
                        else require(key.matches(Regex("[0-9a-fA-F]{32}"))) { "CottenDNS did not return a valid shared key" }
                        val installed = InstalledTool(toolId, port = 53, settings = mapOf(
                            ToolProfiles.DNSTT_DOMAIN to domain,
                            ToolProfiles.DNSTT_BASE to DnsDelegation.normalizeBase(options.baseDomain),
                            ToolProfiles.DNSTT_LABEL to options.tunnelLabel,
                            keyName to key
                        ))
                        val updated = server.withTool(installed).copy(facts = facts, checkedAt = System.currentTimeMillis())
                        val profile = if (toolId == ServerToolCatalog.SOAR) ToolProfiles.soarProfile(updated, installed)
                            else ToolProfiles.cottenProfile(updated, installed)
                        val link = if (toolId == ServerToolCatalog.SOAR) ToolProfiles.soarLink(updated, installed)
                            else ToolProfiles.cottenLink(updated, installed)
                        InstallOutcome(updated, installed, profile, link, summary = "DNS tunnel server installed on $domain; verify DNS delegation and live egress from the phone")
                    }
                    ServerToolCatalog.DNSTT -> {
                        // A public UDP/53 listener belongs to another service. DNSTT's redirect
                        // would otherwise steal its incoming packets; never overwrite that traffic.
                        check(53 !in facts.udpPorts) {
                            "UDP 53 is occupied by an existing DNS service. Move or stop it before installing DNSTT."
                        }
                        DnsDelegation.problem(options.baseDomain, options.tunnelLabel)?.let { error(it) }
                        DnsDelegation.backupProblem(options.baseDomain, options.backupBases)?.let { error(it) }
                        val domain = DnsDelegation.tunnelDomain(options.baseDomain, options.tunnelLabel)
                        val backups = options.backupBases.map { DnsDelegation.tunnelDomain(it, options.tunnelLabel) }
                        val previous = server.tool(toolId)
                        val dnsPort = previous?.settings?.get(ToolProfiles.DNSTT_PORT)?.toIntOrNull()
                            ?: ServerRandom.port(facts.udpPorts + facts.tcpPorts)
                        val socksPort = previous?.settings?.get(ToolProfiles.SOCKS_PORT)?.toIntOrNull()
                            ?: ServerRandom.port(facts.udpPorts + facts.tcpPorts, exclude = setOf(dnsPort))
                        val r = runScript(shell, ToolScripts.dnsttInstall(domain, arch, dnsPort, socksPort, options.renew, backups), progress)
                        val pubkey = r["PUBKEY"].orEmpty()
                        require(pubkey.matches(Regex("[0-9a-fA-F]{32}"))) { "The server did not return the tunnel key" }
                        val installed = InstalledTool(
                            toolId, port = 53, profileId = previous?.profileId,
                            settings = mapOf(
                                ToolProfiles.DNSTT_DOMAIN to domain, ToolProfiles.DNSTT_BASE to DnsDelegation.normalizeBase(options.baseDomain),
                                ToolProfiles.DNSTT_LABEL to options.tunnelLabel, ToolProfiles.DNSTT_PUBKEY to pubkey.lowercase(),
                                ToolProfiles.DNSTT_PORT to dnsPort.toString(), ToolProfiles.SOCKS_PORT to socksPort.toString()
                            ) + (if (options.backupBases.isEmpty()) emptyMap() else mapOf(
                                ToolProfiles.DNSTT_BACKUP_BASES to options.backupBases.joinToString(","),
                                ToolProfiles.DNSTT_BACKUPS to backups.joinToString(",")
                            ))
                        )
                        val updated = server.withTool(installed).copy(facts = facts, checkedAt = System.currentTimeMillis())
                        InstallOutcome(updated, installed, ToolProfiles.dnsttProfile(updated, installed), ToolProfiles.dnsttLink(updated, installed),
                            summary = "DNS tunnel ready on $domain")
                    }
                    ServerToolCatalog.HYSTERIA2 -> {
                        val previous = server.tool(toolId)
                        val oldPort = previous?.port
                        val port = options.port ?: oldPort ?: ServerRandom.port(facts.udpPorts + facts.tcpPorts)
                        val auth = previous?.settings?.get(ToolProfiles.HY_AUTH)?.takeUnless { options.renew } ?: ServerRandom.secret(24)
                        val obfs = previous?.settings?.get(ToolProfiles.HY_OBFS)?.takeUnless { options.renew } ?: ServerRandom.secret(24)
                        val r = runScript(shell, ToolScripts.hysteriaInstall(arch, server.host, port, auth, obfs, newCert = false, oldPort = oldPort), progress)
                        val pin = r["CERT_SHA256"].orEmpty()
                        require(pin.matches(Regex("[0-9a-f]{64}"))) { "The server did not return the certificate fingerprint" }
                        val installed = InstalledTool(
                            toolId, port = port, profileId = previous?.profileId,
                            settings = mapOf(ToolProfiles.HY_AUTH to auth, ToolProfiles.HY_OBFS to obfs, ToolProfiles.HY_PIN to pin)
                        )
                        val updated = server.withTool(installed).copy(facts = facts, checkedAt = System.currentTimeMillis())
                        InstallOutcome(updated, installed, ToolProfiles.hysteriaProfile(updated, installed), ToolProfiles.hysteriaLink(updated, installed),
                            summary = "Hysteria2 ready on UDP $port")
                    }
                    else -> {
                        val script = when (toolId) {
                            ServerToolCatalog.FIREWALL -> {
                                val udp = server.tools.mapNotNull { t -> t.port.takeIf { t.id == ServerToolCatalog.DNSTT || t.id == ServerToolCatalog.HYSTERIA2 } }.toSet() +
                                    server.tools.filter { it.id == ServerToolCatalog.DNSTT }.mapNotNull { it.settings[ToolProfiles.DNSTT_PORT]?.toIntOrNull() }
                                ToolScripts.firewall(server.sshPort, emptySet(), udp)
                            }
                            ServerToolCatalog.FAIL2BAN -> ToolScripts.fail2ban(server.sshPort)
                            ServerToolCatalog.AUTO_UPDATES -> ToolScripts.autoUpdates()
                            ServerToolCatalog.BBR -> ToolScripts.bbr()
                            ServerToolCatalog.SWAP -> ToolScripts.swap(if (facts.diskFreeMb > 10_240) maxOf(options.swapMb, 2048) else options.swapMb)
                            else -> error("Unknown tool $toolId")
                        }
                        if (tool.aptOnly && !facts.usesApt) error("${tool.title} needs Debian or Ubuntu (apt)")
                        val r = runScript(shell, script, progress)
                        val installed = InstalledTool(toolId, settings = r)
                        val refreshed = ServerFacts.parse(shell.run(ServerFacts.SCRIPT, 60_000L).output)
                        val updated = server.withTool(installed).copy(facts = refreshed, checkedAt = System.currentTimeMillis())
                        InstallOutcome(updated, installed, summary = "${tool.title} is on")
                    }
                }
            }
        }
    }

    val xuiSteps = listOf("Connect", "Install 3X-UI", "Turn on HTTPS", "Verify the panel")

    private fun installXui(server: ManagedServer, progress: ActionProgress): InstallOutcome {
        progress.step(1)
        val panel = provisioner.installXui(
            XuiInstallRequest(server.host, server.sshPort, server.username, server.password, server.hostKeySha256, privateKey = server.privateKey)
        ) { line ->
            when {
                line.contains("Installing signed release") -> progress.step(2)
                line.contains("HTTPS", ignoreCase = true) && !line.startsWith("[WARN]") -> progress.step(3)
                line.contains("Waiting for the panel API") -> progress.step(4)
            }
            progress.log(line)
        }
        val installed = InstalledTool(ServerToolCatalog.XUI, panelId = panel.id,
            port = runCatching { java.net.URI(panel.url).port }.getOrNull()?.takeIf { it > 0 })
        val updated = server.withTool(installed)
        return InstallOutcome(updated, installed, panel = panel, summary = panel.healthNote.ifBlank { "3X-UI installed" })
    }

    /**
     * Adds a new phone key, then proves it by signing in with the key alone. The password stays
     * stored until password login is turned off, so a failed key can never lock the user out.
     */
    private fun addKeyLogin(server: ManagedServer, progress: ActionProgress): InstallOutcome {
        val keys = ServerKeys.generate()
        val passwordOnly = server.copy(privateKey = "", publicKey = "")
        progress.log("Created a new ECDSA key on this phone")
        connect(passwordOnly).use { shell -> runScript(shell, ToolScripts.keyLoginAdd(keys.publicKeyLine), progress) }
        val withKey = server.copy(privateKey = keys.privateKeyPem, publicKey = keys.publicKeyLine)
        progress.log("Signing in with the key alone")
        val checked = check(withKey)
        val installed = InstalledTool(ServerToolCatalog.KEY_LOGIN)
        val updated = checked.withTool(installed)
        return InstallOutcome(updated, installed, summary = "Key login works. You can now turn password login off.")
    }

    /** Turns SSH password login off (only with a working key) or back on. */
    fun setPasswordLogin(server: ManagedServer, off: Boolean, progress: ActionProgress): ManagedServer {
        if (off) require(server.usesKey) { "Add key login first" }
        val r = connect(server).use { runScript(it, ToolScripts.passwordLogin(off), progress) }
        val nowOff = r["PASSWORD_LOGIN"] == "no"
        if (off) check(nowOff) { "sshd still allows passwords; another setting overrides ours" }
        // With password login off the stored password has no use left; forget it.
        return check(server.copy(passwordLoginOff = nowOff, password = if (nowOff) "" else server.password))
    }

    fun uninstall(server: ManagedServer, toolId: String, progress: ActionProgress): ManagedServer {
        val tool = server.tool(toolId)
        if (toolId == ServerToolCatalog.HIDDIFY) {
            return hiddify.uninstall(server, progress).let { s -> runCatching { check(s) }.getOrDefault(s) }
        }
        if (toolId != ServerToolCatalog.KEY_LOGIN) {
            connect(server).use { runScript(it, ToolScripts.uninstall(toolId, tool?.port?.takeIf { p -> p != 53 }), progress) }
        }
        return server.withoutTool(toolId).let { s -> runCatching { check(s) }.getOrDefault(s) }
    }

    fun restart(server: ManagedServer, toolId: String, progress: ActionProgress): ManagedServer {
        if (toolId == ServerToolCatalog.HIDDIFY) { hiddify.restart(server, progress); return check(server) }
        connect(server).use { runScript(it, ToolScripts.restart(toolId), progress, 2 * 60_000L) }
        return check(server)
    }

    private fun panel(toolId: String): ServerPanelProvider = when (toolId) {
        ServerToolCatalog.HIDDIFY -> hiddify
        ServerToolCatalog.XUI -> xui
        else -> throw IllegalArgumentException("${ServerToolCatalog.byId(toolId)?.title ?: toolId} is not a panel")
    }

    /** Starts or stops a panel's services (3x-ui, Hiddify). */
    fun setPanelRunning(server: ManagedServer, toolId: String, run: Boolean, progress: ActionProgress): ManagedServer {
        if (run) panel(toolId).start(server, progress) else panel(toolId).stop(server, progress)
        return check(server)
    }

    /** A panel backup kept on the server; its time is kept on the tool, its path is returned. */
    fun backupPanel(server: ManagedServer, toolId: String, progress: ActionProgress): Pair<ManagedServer, String> {
        val path = panel(toolId).backup(server, progress)
        check(path.isNotBlank()) { "The server did not report where the backup went" }
        val tool = requireNotNull(server.tool(toolId)) { "Not added to this server" }
        return server.withTool(tool.copy(settings = tool.settings + (K_LAST_BACKUP to System.currentTimeMillis().toString()))) to path
    }

    /** Backs up, then updates Hiddify to the pinned version with its own installer. */
    fun updateHiddify(server: ManagedServer, progress: ActionProgress): InstallOutcome {
        val (backedUp, path) = backupPanel(server, ServerToolCatalog.HIDDIFY, progress)
        progress.log("Backup saved on the server: $path")
        val st = hiddify.update(backedUp, progress)
        val tool = backedUp.tool(ServerToolCatalog.HIDDIFY)!!.let { t -> t.copy(settings = t.settings + mapOfNotNull(HiddifyPanelProvider.K_VERSION to st.version)) }
        val checked = runCatching { check(backedUp.withTool(tool)) }.getOrDefault(backedUp.withTool(tool))
        return InstallOutcome(checked, tool, summary = "Hiddify updated to ${st.version ?: HiddifyScripts.VERSION}")
    }

    /** Adds a Hiddify that is already on the server, changing nothing there. */
    fun importHiddify(server: ManagedServer): ManagedServer = hiddify.import(server).server

    fun logs(server: ManagedServer, toolId: String): List<String> = if (toolId == ServerToolCatalog.HIDDIFY) hiddify.logs(server) else connect(server).use { shell ->
        val lines = mutableListOf<String>()
        runScript(shell, ToolScripts.logs(toolId), object : ActionProgress {
            override fun step(index: Int) = Unit
            override fun log(line: String) { lines += line }
        }, 60_000L)
        lines
    }

    /** A new random port (Hysteria2) with the same secrets. */
    fun rotatePort(server: ManagedServer, toolId: String, progress: ActionProgress): InstallOutcome {
        require(toolId == ServerToolCatalog.HYSTERIA2) { "Only Hysteria2 has a movable port" }
        val facts = factsOrCheck(server)
        val current = server.tool(toolId)?.port
        val port = ServerRandom.port(facts.udpPorts + facts.tcpPorts, exclude = setOfNotNull(current))
        return install(server, toolId, InstallOptions(port = port), progress)
    }

    /** New keys: a new dnstt key pair, or new Hysteria2 password and obfuscation key. */
    fun rotateKey(server: ManagedServer, toolId: String, progress: ActionProgress): InstallOutcome {
        val t = requireNotNull(server.tool(toolId)) { "Not installed" }
        val options = when (toolId) {
            ServerToolCatalog.DNSTT -> InstallOptions(t.settings[ToolProfiles.DNSTT_BASE].orEmpty(), t.settings[ToolProfiles.DNSTT_LABEL] ?: "t",
                backupBases = ToolProfiles.backupBases(t), renew = true)
            ServerToolCatalog.HYSTERIA2 -> InstallOptions(renew = true)
            else -> error("Nothing to rotate for $toolId")
        }
        return install(server, toolId, options, progress)
    }

    companion object {
        /** When the app last made a panel backup, in epoch milliseconds (tool setting). */
        const val K_LAST_BACKUP = "lastBackup"
    }
}


