package com.example.vpn.safety

import com.example.data.model.AppSettings

/** What the power button (and the Quick Settings tile) starts. */
object PowerButtonPolicy {
    /**
     * True pins the session to the selected config only (RayVpnService.EXTRA_EXACT). With
     * [AppSettings.powerButtonFallback] on (the default) the selected config is still started first; failover
     * and the recovery engines only run when it carries no real traffic on this network, and Trade mode still
     * pins the exit (OperatingModePolicy.lockExit). Smart Connect remains the separate button that compares.
     */
    fun exact(settings: AppSettings): Boolean = !settings.powerButtonFallback

    /**
     * Nothing is selected in Nodes and no config is saved: the power button starts Smart Connect without a config
     * (build 176: it failed silently). Not in Trade mode or Ghost Rider, which connect only the selected server or route,
     * nor in Unlock mode, which has its own Connect. Pure.
     */
    fun configFreeWhenNoServer(mode: com.example.data.model.OperationalMode): Boolean =
        mode == com.example.data.model.OperationalMode.DAILY || mode == com.example.data.model.OperationalMode.GOD_MODE
}
