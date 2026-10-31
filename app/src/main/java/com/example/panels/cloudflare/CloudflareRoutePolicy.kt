package com.example.panels.cloudflare

/**
 * Central Cloudflare transport compatibility policy. This does not issue API requests.
 * The existing Smart Domains wizard remains responsible for user preview and explicit approval.
 */
object CloudflareRoutePolicy {
    enum class Transport {
        CDN_HTTPS_WEBSOCKET, CDN_HTTPS_GRPC, CDN_XHTTP,
        VLESS_TCP, REALITY, HYSTERIA2_UDP, WIREGUARD_UDP,
        DNSTT, SOAR, COTTEN, PSIPHON_OSSH
    }

    data class Decision(val proxied: Boolean, val reason: String, val eligible: Boolean)

    fun assess(transport: Transport, port: Int, userApproved: Boolean): Decision {
        if (port !in 1..65535) return Decision(false, "Port must be 1–65535.", false)
        if (!userApproved) return Decision(false, "Review and approve DNS changes before writing.", false)
        return when (transport) {
            Transport.CDN_HTTPS_WEBSOCKET, Transport.CDN_HTTPS_GRPC, Transport.CDN_XHTTP ->
                if (port in SmartDomains.CDN_HTTPS_PORTS)
                    Decision(true, "HTTPS-compatible transport on Cloudflare-supported port.", true)
                else Decision(false, "Cloudflare does not proxy this TLS port.", false)
            Transport.DNSTT, Transport.SOAR, Transport.COTTEN ->
                Decision(false, "Authoritative DNS tunnel delegation must bypass Cloudflare's HTTP proxy.", true)
            Transport.PSIPHON_OSSH, Transport.REALITY, Transport.VLESS_TCP,
            Transport.HYSTERIA2_UDP, Transport.WIREGUARD_UDP ->
                Decision(false, "Non-HTTP tunnel: DNS-only exposes the origin IP; do not enable the HTTP proxy.", true)
        }
    }
}
