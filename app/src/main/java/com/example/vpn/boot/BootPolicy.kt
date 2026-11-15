package com.example.vpn.boot

/** Whether the app should connect by itself after the phone starts. Pure, so it is tested on the JVM. */
object BootPolicy {
    enum class Decision { CONNECT, OFF, ALWAYS_ON_HANDLES_IT, NEEDS_PERMISSION, ALREADY_RUNNING }

    fun decide(
        autoConnectOnBoot: Boolean,
        permissionGranted: Boolean,
        alwaysOnIsThisApp: Boolean,
        tunnelUpOrBusy: Boolean,
    ): Decision = when {
        !autoConnectOnBoot -> Decision.OFF
        // Android's Always-on VPN starts the service itself; a second start would race it.
        alwaysOnIsThisApp -> Decision.ALWAYS_ON_HANDLES_IT
        tunnelUpOrBusy -> Decision.ALREADY_RUNNING
        // VPN permission can only be granted from an activity; after a reboot it is still granted unless
        // another VPN app took it over, in which case the user has to open Maximus once.
        !permissionGranted -> Decision.NEEDS_PERMISSION
        else -> Decision.CONNECT
    }
}
