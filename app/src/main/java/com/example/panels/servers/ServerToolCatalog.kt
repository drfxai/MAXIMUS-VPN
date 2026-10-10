package com.example.panels.servers

enum class ToolGroup(val title: String) {
    PANELS("Panels"),
    TUNNELS("Tunnels"),
    PROTOCOLS("Fast protocols"),
    SECURITY("Security"),
    SPEED("Speed")
}

/**
 * One installable tool in the Install Center. [fits] says on which servers it makes sense: a DNS
 * tunnel or a Hysteria2 server inside Iran would still leave the internet inside Iran.
 */
data class ServerTool(
    val id: String,
    val title: String,
    val tagline: String,
    val description: String,
    val group: ToolGroup,
    val fits: Set<ServerLocation>,
    /** What the install changes on the server, shown on the summary step. */
    val changes: List<String>,
    val needsDomain: Boolean = false,
    /** Shown in the catalog but not installable yet. */
    val comingLater: Boolean = false,
    /** Only for servers that use apt (Debian, Ubuntu). */
    val aptOnly: Boolean = false
) {
    fun fitsOn(location: ServerLocation) = location in fits
}

object ServerToolCatalog {
    const val XUI = "3x-ui"
    const val DNSTT = "dnstt"
    const val HYSTERIA2 = "hysteria2"
    const val KEY_LOGIN = "key-login"
    const val FIREWALL = "firewall"
    const val FAIL2BAN = "fail2ban"
    const val AUTO_UPDATES = "auto-updates"
    const val BBR = "bbr"
    const val SWAP = "swap"
    const val MAXIMUS_TUNNEL = "maximus-tunnel"

    private val both = setOf(ServerLocation.IRAN, ServerLocation.ABROAD)
    private val abroad = setOf(ServerLocation.ABROAD)

    val all: List<ServerTool> = listOf(
        ServerTool(
            XUI, "3X-UI", "Multi-protocol Xray panel",
            "The panel behind VLESS, REALITY, Trojan and Hysteria2 configs. Abroad it is your exit; " +
                "in Iran it can relay to a server abroad.",
            ToolGroup.PANELS, both,
            listOf("Installs 3X-UI (signed release, pinned installer)", "Creates a random panel login and path",
                "Serves the panel over HTTPS with a pinned certificate", "Opens the panel port in the firewall")
        ),
        ServerTool(
            DNSTT, "DNS tunnel (dnstt)", "Works when only DNS gets through",
            "Carries your traffic inside DNS queries through the phone's own DNS resolvers, so it keeps " +
                "working when the server's IP is blocked. Slow, but hard to cut. Needs a domain.",
            ToolGroup.TUNNELS, abroad,
            listOf("Downloads dnstt-server and a loopback SOCKS helper (checksums pinned in the app)",
                "Creates a new tunnel key pair on the server", "Adds two systemd services under a locked system user",
                "Sends UDP 53 to the tunnel and opens it in the firewall"),
            needsDomain = true
        ),
        ServerTool(
            MAXIMUS_TUNNEL, "Maximus Tunnel", "Iran ↔ abroad, automatic",
            "Links your server in Iran to one abroad over REALITY, XHTTP and Hysteria2 at once, uses " +
                "the fastest open path and moves its ports on a schedule. Set up from the Tunnel tab.",
            ToolGroup.TUNNELS, both,
            listOf("Abroad: Xray and Hysteria2 under a locked user, keys made on the server, rotating ports",
                "Iran: Xray with one port for your phone that only forwards abroad")
        ),
        ServerTool(
            HYSTERIA2, "Hysteria2", "Very fast on bad networks (UDP)",
            "A QUIC server tuned for lossy links, with Salamander obfuscation so the traffic does not " +
                "look like QUIC. Uses a random UDP port.",
            ToolGroup.PROTOCOLS, abroad,
            listOf("Downloads the Hysteria2 server (checksum pinned in the app)",
                "Creates a certificate the app pins, a random password and an obfuscation key",
                "Adds a systemd service under a locked system user", "Opens the chosen UDP port in the firewall")
        ),
        ServerTool(
            KEY_LOGIN, "Key login", "Sign in without a password",
            "Creates a key on this phone and adds it to the server. After the key works you can turn " +
                "password login off, which stops password guessing completely.",
            ToolGroup.SECURITY, both,
            listOf("Adds this phone's public key to the account's authorized_keys",
                "Optional, after the key is verified: turns SSH password login off")
        ),
        ServerTool(
            FIREWALL, "Firewall", "Only the ports in use stay open",
            "Turns on UFW and allows SSH plus every port a service listens on right now. Re-apply it " +
                "after adding new 3X-UI inbounds.",
            ToolGroup.SECURITY, both,
            listOf("Installs UFW if needed", "Allows SSH first, then every listening port",
                "Blocks other incoming connections"),
            aptOnly = true
        ),
        ServerTool(
            FAIL2BAN, "Fail2ban", "Blocks password guessing",
            "Bans an address for an hour after five failed SSH logins in ten minutes.",
            ToolGroup.SECURITY, both,
            listOf("Installs fail2ban", "Adds an SSH jail for your SSH port"),
            aptOnly = true
        ),
        ServerTool(
            AUTO_UPDATES, "Security updates", "Installs security fixes daily",
            "Turns on unattended security upgrades. The server never reboots by itself.",
            ToolGroup.SECURITY, both,
            listOf("Installs unattended-upgrades", "Enables daily security updates (no automatic reboot)"),
            aptOnly = true
        ),
        ServerTool(
            BBR, "TCP BBR", "Better speed on long routes",
            "Switches TCP congestion control to BBR, which keeps speed up over long, lossy routes such " +
                "as Iran to Europe.",
            ToolGroup.SPEED, both,
            listOf("Loads the tcp_bbr module", "Sets fq + bbr in /etc/sysctl.d/60-maximus-bbr.conf")
        ),
        ServerTool(
            SWAP, "Swap", "Stops crashes on small servers",
            "Adds a swap file on servers with little memory, so a busy panel does not get killed.",
            ToolGroup.SPEED, both,
            listOf("Creates /swapfile (1 GB, or 2 GB with enough disk)", "Turns it on now and at boot", "Sets swappiness to 10")
        )
    )

    fun byId(id: String): ServerTool? = all.firstOrNull { it.id == id }
}
