package com.example.panels.servers

import java.util.UUID

/** Where a server is. Iranian servers show the Iranian flag, servers abroad the German flag. */
enum class ServerLocation(val label: String) { IRAN("Iran"), ABROAD("Abroad") }

/**
 * A server the user owns and manages from Panels. Sign-in details are kept only on this device,
 * encrypted with the Android Keystore (see [ServerStore]); they are never sent to the AI or logged.
 */
data class ManagedServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val sshPort: Int = 22,
    val username: String = "root",
    val location: ServerLocation,
    /** The SSH host key the user confirmed when adding the server ("SHA256:..."); pinned from then on. */
    val hostKeySha256: String = "",
    val password: String = "",
    /** Private key created on this phone for key login (PEM). */
    val privateKey: String = "",
    /** The matching authorized_keys line. */
    val publicKey: String = "",
    /** True once password login was turned off on the server after key login was verified. */
    val passwordLoginOff: Boolean = false,
    val tools: List<InstalledTool> = emptyList(),
    val facts: ServerFacts? = null,
    val checkedAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
) {
    val usesKey: Boolean get() = privateKey.isNotBlank()
    val canSignIn: Boolean get() = hostKeySha256.isNotBlank() && (password.isNotBlank() || privateKey.isNotBlank())
    val displayName: String get() = name.ifBlank { host }

    fun tool(id: String): InstalledTool? = tools.firstOrNull { it.id == id }
    fun withTool(tool: InstalledTool): ManagedServer = copy(tools = tools.filterNot { it.id == tool.id } + tool)
    fun withoutTool(id: String): ManagedServer = copy(tools = tools.filterNot { it.id == id })
}

/** A tool installed on a server through the Install Center, with what the app needs to manage it. */
data class InstalledTool(
    val id: String,
    val installedAt: Long = System.currentTimeMillis(),
    /** The public port the tool listens on, when it has one. */
    val port: Int? = null,
    /** Tool-specific values (dnstt domain and public key, Hysteria2 passwords, certificate pin...). */
    val settings: Map<String, String> = emptyMap(),
    /** The VPN profile created for this tool, when it has one. */
    val profileId: String? = null,
    /** The 3X-UI panel record (PanelStore) this tool belongs to. */
    val panelId: String? = null
)
