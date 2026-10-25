package com.example.vpn.sidecar

/** Only standalone adapters audited to keep the same carrier and credentials across modes. */
object ModeSwitchReuse {
    fun allowed(engineId: String?, verified: Boolean, alive: Boolean, sameProfile: Boolean, sameNetwork: Boolean): Boolean =
        engineId in setOf("tor", "soar", "cotten-dns") && verified && alive && sameProfile && sameNetwork
}
