package com.example.vpn.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.example.data.repository.SettingsRepository
import com.example.vpn.RayVpnService
import com.example.vpn.VpnController
import com.example.xray.XrayLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/*
 * Connect on boot. Adapted from ZedSecure (https://github.com/CluvexStudio/ZedSecure, commit 201e82ce18a4,
 * app/src/main/java/dev/cluvex/zedsecure/core/BootReceiver.kt), AGPL-3.0. Reads Maximus's existing
 * `autoConnectOnBoot` setting, which nothing used before.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext
        val settings = SettingsRepository(app).getSettings()
        val state = RayVpnService.vpnState.value
        val decision = BootPolicy.decide(
            autoConnectOnBoot = settings.autoConnectOnBoot,
            permissionGranted = VpnController.isPermissionGranted(app),
            alwaysOnIsThisApp = alwaysOnApp(app) == app.packageName,
            tunnelUpOrBusy = state.isTunnelUp || state.isBusy,
        )
        if (decision != BootPolicy.Decision.CONNECT) {
            if (decision != BootPolicy.Decision.OFF) XrayLogManager.i("VPN", "Connect on boot skipped: $decision")
            return
        }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val outcome = runCatching { QuickConnect.startSelected(app, reason = "connect on boot") }
                    .onFailure { XrayLogManager.w("VPN", "Connect on boot failed to start: ${it.message}") }
                    .getOrNull()
                if (outcome != null && outcome != QuickConnect.Outcome.STARTED) {
                    XrayLogManager.i("VPN", "Connect on boot: $outcome")
                }
            } finally {
                pending.finish()
            }
        }
    }

    /** The package Android's Always-on VPN is set to, when the platform lets apps read it. */
    private fun alwaysOnApp(context: Context): String? =
        runCatching { Settings.Secure.getString(context.contentResolver, "always_on_vpn_app") }.getOrNull()
}
