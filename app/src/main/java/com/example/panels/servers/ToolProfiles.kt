package com.example.panels.servers

import com.example.data.model.VlessProfile
import com.example.vpn.engine.ProtocolLinks
import com.example.vpn.sidecar.DnsttSidecar
import java.net.URLEncoder

/** The VPN profiles and share links for tools installed on the user's servers. */
object ToolProfiles {
    const val DNSTT_DOMAIN = "domain"
    const val DNSTT_BASE = "baseDomain"
    const val DNSTT_LABEL = "label"
    const val DNSTT_PUBKEY = "pubkey"
    const val DNSTT_PORT = "dnsPort"
    /** Backup base domains as typed, and the backup tunnel names under them, comma-separated. */
    const val DNSTT_BACKUP_BASES = "backupBases"
    const val DNSTT_BACKUPS = "backupDomains"
    const val SOCKS_PORT = "socksPort"
    const val HY_AUTH = "auth"
    const val HY_OBFS = "obfs"
    const val HY_PIN = "certSha256"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun backupBases(tool: InstalledTool): List<String> = split(tool.settings[DNSTT_BACKUP_BASES])
    fun backupDomains(tool: InstalledTool): List<String> = split(tool.settings[DNSTT_BACKUPS])
    private fun split(v: String?) = v.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun dnsttProfile(server: ManagedServer, tool: InstalledTool): VlessProfile =
        DnsttSidecar.profile(
            pubkey = tool.settings[DNSTT_PUBKEY].orEmpty(),
            domain = tool.settings[DNSTT_DOMAIN].orEmpty(),
            name = "DNS tunnel · ${server.displayName}",
            backupDomains = backupDomains(tool)
        )

    fun dnsttLink(server: ManagedServer, tool: InstalledTool): String =
        "dnstt://${tool.settings[DNSTT_PUBKEY]}@${tool.settings[DNSTT_DOMAIN]}?doh=${enc(DnsttSidecar.DEFAULT_DOH)}" +
            backupDomains(tool).takeIf { it.isNotEmpty() }?.let { "&domains=${enc(it.joinToString(","))}" }.orEmpty() +
            "#${enc("DNS tunnel · ${server.displayName}")}"

    fun hysteriaLink(server: ManagedServer, tool: InstalledTool): String {
        val host = server.host
        val authority = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        val s = tool.settings
        val query = listOf(
            "sni=${enc(host)}", "alpn=h3",
            "obfs=salamander", "obfs-password=${enc(s[HY_OBFS].orEmpty())}",
            "pinSHA256=${enc(s[HY_PIN].orEmpty())}"
        ).joinToString("&")
        return "hysteria2://${enc(s[HY_AUTH].orEmpty())}@$authority:${tool.port}/?$query#${enc("Hysteria2 · ${server.displayName}")}"
    }

    fun hysteriaProfile(server: ManagedServer, tool: InstalledTool): VlessProfile =
        ProtocolLinks.parseHysteria2(hysteriaLink(server, tool)).copy(name = "Hysteria2 · ${server.displayName}")
}
