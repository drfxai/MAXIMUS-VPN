package com.example.panels.servers.ech

import android.content.Context
import com.example.data.model.VlessProfile
import com.example.data.security.SecureStorage
import com.example.panels.ManagedPanel
import com.example.panels.PanelStore
import com.example.panels.PinnedTls
import com.example.panels.ThreeXUiConfigGenerator
import com.example.panels.ThreeXUiProtocol
import com.example.panels.ThreeXUiSecurity
import com.example.panels.XuiApiClient
import com.example.panels.servers.ActionProgress
import com.example.panels.servers.ManagedServer
import com.example.panels.servers.ServerManager
import com.example.panels.servers.ServerToolCatalog
import com.example.vpn.smartsni.SmartSni

/** What an ECH action produced: the server to save, and a profile created for a new TLS inbound (if one was made). */
data class EchOutcome(val server: ManagedServer, val createdProfile: VlessProfile? = null, val summary: String = "")

/**
 * Build 181 (Phase 6): the Panels → My Servers ECH actions on a server with 3X-UI. Builds on what the section already
 * does: the 3X-UI panel API (pinned HTTPS, the panel's own Xray makes the keys) and, when the phone can sign in, the
 * existing SSH database backup ([ServerManager.backupPanel]). Blocking; call off the main thread. Returns updated
 * [ManagedServer] values and never stores them itself (the screen's view model does, encrypted in ServerStore).
 */
class ServerEchManager(
    context: Context,
    private val manager: ServerManager = ServerManager(),
    private val generator: ThreeXUiConfigGenerator = ThreeXUiConfigGenerator()
) {
    private val app = context.applicationContext
    private val secure by lazy { SecureStorage(app) }

    /** Outer-name suggestions for the current network (names Smart SNI saw get through here, else a random-style name). */
    fun suggestions(network: String?): List<String> = ServerEch.suggestions(
        runCatching { SmartSni.ranked(network) }.getOrDefault(emptyList()),
        runCatching { SmartSni.cooling(network) }.getOrDefault(emptySet()),
        { SmartSni.randomName() })

    private fun panelOf(server: ManagedServer): ManagedPanel {
        val tool = requireNotNull(server.tool(ServerToolCatalog.XUI)) { "Install 3X-UI on this server first; ECH is set up through its panel." }
        val id = requireNotNull(tool.panelId) { "This server's 3X-UI panel is not linked on this phone." }
        return requireNotNull(PanelStore(app).load().firstOrNull { it.id == id }) { "This server's 3X-UI panel is not saved on this phone." }
    }

    private fun api(panel: ManagedPanel) = XuiApiClient(panel.url, panel.apiToken, panel.username, panel.password,
        pinnedCertSha256 = panel.certSha256)

    private fun backupKey(server: ManagedServer) = "server_ech_backup_${server.id}"

    private fun provisioner(server: ManagedServer, panel: ManagedPanel, progress: ActionProgress) = ServerEchProvisioner(
        XuiEchApi(api(panel)),
        // The inbounds before the change (they hold the previous ECH private keys): Keystore-encrypted, never logged.
        backup = { json -> runCatching { secure.encryptAndSave(backupKey(server), json.orEmpty()) } },
        pinFor = { inb ->
            val cert = ServerEch.obj(inb.opt("streamSettings"))?.optJSONObject("tlsSettings")?.optJSONArray("certificates")
                ?.optJSONObject(0)?.optString("certificateFile").orEmpty()
            if (cert.contains(ThreeXUiConfigGenerator.SELF_SIGNED_CERT_DIR)) PinnedTls.normalize(panel.certSha256) else ""
        },
        log = { progress.log(it) }
    )

    /** A database copy on the server before a change, over the existing SSH backup, when the phone can sign in. */
    private fun sshBackup(server: ManagedServer, progress: ActionProgress): ManagedServer {
        if (!server.canSignIn) { progress.log("No SSH sign-in on this phone: only the in-app copy of the inbounds is kept"); return server }
        return runCatching { manager.backupPanel(server, ServerToolCatalog.XUI, progress) }
            .onSuccess { progress.log("3X-UI database backup saved on the server: ${it.second}") }
            .onFailure { progress.log("SSH backup unavailable (${com.example.panels.servers.ServerLogSanitizer.redact(it.message.orEmpty()).take(120)}); the in-app copy is kept") }
            .getOrNull()?.first ?: server
    }

    private fun withRecord(server: ManagedServer, record: ServerEch.Record?): ManagedServer {
        val tool = server.tool(ServerToolCatalog.XUI)!!
        return server.withTool(tool.copy(settings = ServerEch.withRecord(tool.settings, record)))
    }

    /**
     * Turns ECH on with [outer] (or rotates the key when it is on: same flow, the previous key stays served beside the
     * new one). With [createInbound], a server with no TLS inbound gets one (RAW + TLS; the self-signed panel
     * certificate is pinned by clients when the server has no domain certificate).
     */
    fun enable(server: ManagedServer, outer: String, rotateDays: Int, createInbound: Boolean, progress: ActionProgress): EchOutcome {
        val panel = panelOf(server)
        ServerEch.outerProblem(outer)?.let { throw IllegalArgumentException(it) }
        val backed = sshBackup(server, progress)
        val prov = provisioner(backed, panel, progress)
        var created: VlessProfile? = null
        val record = try {
            prov.enable(server.host, outer, rotateDays)
        } catch (e: NoEchInboundException) {
            if (!createInbound) throw e
            progress.log("Creating a TLS inbound for ECH")
            val result = generator.generateInbound(panel, ThreeXUiProtocol.RAW, ThreeXUiSecurity.TLS,
                customRemark = "Maximus-ECH-TLS", allowPinnedPanelCert = true)
            created = result.profile
            prov.enable(server.host, outer, rotateDays)
        }
        val rotated = ServerEch.record(server.tool(ServerToolCatalog.XUI)?.settings.orEmpty()) != null
        return EchOutcome(withRecord(backed, record), created,
            (if (rotated) "New ECH key installed" else "ECH is on") + " · outer name ${record.outer} · ${record.inbounds.size} inbound(s)")
    }

    /** A new key with the current outer name (manual button or a due periodic rotation). */
    fun rotate(server: ManagedServer, progress: ActionProgress, outer: String? = null): EchOutcome {
        val r = requireNotNull(ServerEch.record(server.tool(ServerToolCatalog.XUI)?.settings.orEmpty())) { "ECH is not on for this server" }
        return enable(server, outer ?: r.outer, r.rotateDays, createInbound = false, progress = progress)
    }

    fun disable(server: ManagedServer, progress: ActionProgress): EchOutcome {
        val panel = panelOf(server)
        val backed = sshBackup(server, progress)
        provisioner(backed, panel, progress).disable()
        return EchOutcome(withRecord(backed, null), summary = "ECH is off; clients use plain TLS to this server")
    }

    /** Periodic rotation every [days] days (0 = off). Local only. */
    fun setRotation(server: ManagedServer, days: Int): ManagedServer {
        require(days in ServerEch.ROTATE_CHOICES) { "Unsupported rotation interval" }
        val tool = requireNotNull(server.tool(ServerToolCatalog.XUI)) { "Install 3X-UI first" }
        val r = ServerEch.record(tool.settings)
        val settings = if (r != null) ServerEch.withRecord(tool.settings, r.copy(rotateDays = days))
            else tool.settings + (ServerEch.K_ROTATE_DAYS to days.toString())
        return server.withTool(tool.copy(settings = settings))
    }
}
