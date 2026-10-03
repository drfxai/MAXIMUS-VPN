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
    val createdAt: Long = System.currentTimeMillis()
)

data class XuiInstallRequest(
    val host: String,
    val sshPort: Int,
    val username: String,
    val password: String,
    val expectedHostKeySha256: String = "",
    val allowTrustOnFirstUse: Boolean = false
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
    val remark: String
)
