package com.example.panels

enum class PanelPlatform { SERVER, CLOUDFLARE }

data class PanelTemplate(
    val id: String,
    val title: String,
    val subtitle: String,
    val platform: PanelPlatform,
    val panelType: PanelType,
    val available: Boolean = true
)

/**
 * Catalog-driven surface for panel installers. Adding a future server panel or
 * Cloudflare application only requires a catalog entry plus its provisioner.
 */
object PanelCatalog {
    val serverTemplates = listOf(
        PanelTemplate(
            id = "3x-ui",
            title = "3X-UI",
            subtitle = "Multi-protocol Xray management panel",
            platform = PanelPlatform.SERVER,
            panelType = PanelType.XUI
        )
    )

    val cloudflareTemplates = listOf(
        PanelTemplate(
            id = "bpb-worker",
            title = "BPB Worker",
            subtitle = "Cloudflare Worker + KV private panel",
            platform = PanelPlatform.CLOUDFLARE,
            panelType = PanelType.BPB_WORKER
        )
    )

    val all: List<PanelTemplate> get() = serverTemplates + cloudflareTemplates
}
