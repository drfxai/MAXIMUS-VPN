package com.example.vpn.safety

import com.example.data.model.OperationalMode

/**
 * Owns the one decision that matters for leaks: whether traffic must stay inside the VPN (the tunnel,
 * or the blocking interface while there is none). Protection is requested by every connect, reconnect,
 * failover and Always-on start, and is released only for the reasons in [Release]; anything else
 * leaves the block in place.
 */
class MaximusVpnSupervisor(private val log: (String) -> Unit = {}) {
    enum class Release {
        /** The user pressed Disconnect. */
        USER_DISCONNECT,
        /** Android or another VPN app took the VPN away; the interface is gone either way. */
        REVOKED,
        /** The service is being destroyed. */
        SERVICE_DESTROYED,
        /** See FailClosedPolicy: an unusable profile the user picked in DAILY mode. */
        INVALID_PROFILE_BY_USER
    }

    /** What Android does with traffic when this app's process dies. */
    enum class Lockdown {
        /** Always-on with "Block connections without VPN": traffic stays blocked. */
        ON,
        /** Always-on without blocking: the VPN restarts, but traffic goes direct until it does. */
        ALWAYS_ON_ONLY,
        /** Neither: traffic goes direct until the user reconnects. */
        OFF,
        /** Android 9 and older cannot tell. */
        UNKNOWN
    }

    @Volatile var protectionRequested: Boolean = false
        private set

    @Volatile var lastRelease: Release? = null
        private set

    fun requestProtection() {
        protectionRequested = true
    }

    fun release(reason: Release) {
        if (protectionRequested) log("Traffic protection released: ${reason.name.lowercase().replace('_', ' ')}")
        protectionRequested = false
        lastRelease = reason
    }

    companion object {
        fun lockdownOf(sdkInt: Int, alwaysOn: () -> Boolean, lockdown: () -> Boolean): Lockdown = when {
            sdkInt < 29 -> Lockdown.UNKNOWN
            runCatching(lockdown).getOrDefault(false) -> Lockdown.ON
            runCatching(alwaysOn).getOrDefault(false) -> Lockdown.ALWAYS_ON_ONLY
            else -> Lockdown.OFF
        }

        /**
         * The VPN engine runs inside the app; if Android kills the app, only system lockdown keeps
         * traffic blocked. GOD MODE asks for it; DAILY mentions it.
         */
        fun lockdownAdvice(lockdown: Lockdown, mode: OperationalMode): String? = when {
            lockdown == Lockdown.ON -> null
            mode == OperationalMode.GOD_MODE ->
                "GOD MODE: turn on Always-on VPN and \"Block connections without VPN\" in Android's VPN settings, " +
                    "or traffic can leave unprotected if Android stops the app"
            lockdown == Lockdown.OFF || lockdown == Lockdown.ALWAYS_ON_ONLY ->
                "Android's \"Block connections without VPN\" is off: traffic is unprotected while the app restarts"
            else -> null
        }
    }
}
