package com.example.panels

enum class PanelType { XUI, BPB_WORKER }

data class ManagedPanel(
    val id: String,
    val type: PanelType,
    val name: String,
    val url: String,
    val username: String = "",
    val password: String = "",
    val apiToken: String = "",
    val host: String = "",
    val sshPort: Int = 22,
    val hostKeySha256: String = "",
    val accountId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /** BPB: UUID the worker accepts for VLESS WebSocket connections. */
    val vlessUuid: String = "",
    /** BPB: secret path segment of the panel/subscription URLs. */
    val securePath: String = "",
    /** Short human-readable result of the post-install checks. */
    val healthNote: String = "",
    /** 3X-UI: SHA-256 of the self-signed panel certificate created at install time. */
    val certSha256: String = ""
)

data class XuiInstallRequest(
    val host: String,
    val sshPort: Int,
    val username: String,
    val password: String,
    val expectedHostKeySha256: String = "",
    val allowTrustOnFirstUse: Boolean = false,
    /** PEM private key for key login (Panels > My Servers); used instead of [password] when set. */
    val privateKey: String = ""
)

data class CloudflareInstallRequest(
    val apiToken: String,
    val panelNamePrefix: String = "maximus-bpb",
    val accountId: String = "",
    val accountEmail: String = "",
    val panelPassword: String = ""
)

data class QuickConfigResult(
    val configUri: String,
    val remark: String,
    /** True when the config's port answered from this device right after creation. */
    val reachable: Boolean = true,
    val note: String = ""
)
