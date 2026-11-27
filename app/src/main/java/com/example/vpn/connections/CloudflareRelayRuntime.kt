package com.example.vpn.connections

import com.example.data.model.VlessProfile
import com.example.panels.cloudflare.relay.CloudflareRelay
import com.example.vpn.cleanip.SmartIpScan

/**
 * Build 181 Phase 7: the user's Cloudflare relay as the VPN side sees it (loaded from the encrypted store at app start
 * and after every change in Panels). The labelled pick "Cloudflare relay" and Smart Connect's tier entry read it here.
 * Clean-IP scanner (Phase 5 C): the best Cloudflare edge kept for this network is dialled, with the relay's own name as
 * SNI and Host (verified TLS); a failed connect drops that edge so the next one rescans. Smart ECH (Phase 5 B) applies
 * by itself: the relay is a Cloudflare-fronted Xray config (workers.dev or a zone on Cloudflare).
 */
object CloudflareRelayRuntime {
    @Volatile var relay: CloudflareRelay.Relay? = null
    @Volatile var quota: CloudflareRelay.Quota? = null
    /** Counts one relay connection on this phone (the quota estimate when the key cannot read analytics). */
    @Volatile var onSession: () -> CloudflareRelay.Quota? = { null }

    fun available(): Boolean = relay != null

    /** Smart Connect may use the relay: there is one, the user left "Used by Smart Connect" on, and the quota allows. */
    fun smartReady(now: Long = System.currentTimeMillis()): Boolean =
        relay?.smartUse == true && CloudflareRelay.smartAllowed(quota, now)

    fun cleanIp(network: String?): String? = runCatching {
        SmartIpScan.remembered(network).firstOrNull { it.kind == SmartIpScan.Kind.CDN }?.ip
    }.getOrNull()

    fun profile(network: String?): VlessProfile? {
        val r = relay ?: return null
        val ip = cleanIp(network)
        if (ip == null) scanSoon(network)
        return CloudflareRelay.profile(r, ip)
    }

    @Volatile private var scanning = false

    /**
     * No clean Cloudflare edge kept for this network: one background CDN scan (the Phase 5 scanner, bounded, verified
     * TLS probes), so the next relay connect dials a scanned edge. This one uses the relay's own name meanwhile.
     */
    private fun scanSoon(network: String?) {
        if (scanning || network.isNullOrBlank() || !SmartIpScan.needsScan(network, SmartIpScan.Kind.CDN)) return
        scanning = true
        Thread {
            try {
                SmartIpScan.scan(network, com.example.vpn.smartsni.SmartSni.pick(network), kinds = setOf(SmartIpScan.Kind.CDN))
            } catch (e: Throwable) {
            } finally {
                scanning = false
            }
        }.apply { isDaemon = true; name = "cf-relay-edge-scan" }.start()
    }

    fun handles(profile: VlessProfile): Boolean =
        profile.id == BuiltinConnections.ConfigFreeMethod.CLOUDFLARE_RELAY.id

    /** After the traffic check of a relay attempt: count it, and drop the clean edge it used when it failed. */
    fun onResult(network: String?, profile: VlessProfile, verified: Boolean, log: (String) -> Unit) {
        runCatching { onSession()?.let { q -> if (quota == null || quota?.estimate == true) quota = q } }
        val r = relay ?: return
        if (!verified && profile.address != r.host) {
            SmartIpScan.drop(network, profile.address)
            log("Cloudflare relay: the clean edge did not carry traffic here; it is dropped (next connect rescans)")
        } else if (verified) {
            log("Cloudflare relay: verified" + if (profile.address != r.host) " through a scanned clean edge" else "")
        }
    }
}
