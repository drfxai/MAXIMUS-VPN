package com.example.vpn.boot

import android.content.Context
import com.example.data.database.AppDatabase
import com.example.data.model.VlessProfile
import com.example.data.repository.ServerRepository
import com.example.data.repository.SettingsRepository
import com.example.vpn.RayVpnService
import com.example.vpn.VpnController
import com.example.vpn.safety.PowerButtonPolicy
import com.example.xray.XrayLogManager

/**
 * Starting the selected config from outside the app (boot, home-screen widget), the same way the Quick
 * Settings tile does: the config selected in Nodes, or the first saved one, honouring the power-button
 * fallback setting.
 */
object QuickConnect {
    enum class Outcome { STARTED, STOPPED, NEEDS_PERMISSION, NO_PROFILE, BUSY }

    suspend fun toggle(context: Context): Outcome {
        val state = RayVpnService.vpnState.value
        return when {
            state.isTunnelUp -> { VpnController.stopVpn(context); Outcome.STOPPED }
            // Never start a second operation while a connect, reconnect or teardown owns the service.
            state.isBusy -> Outcome.BUSY
            else -> startSelected(context, reason = "widget")
        }
    }

    suspend fun startSelected(context: Context, reason: String): Outcome {
        if (!VpnController.isPermissionGranted(context)) return Outcome.NEEDS_PERMISSION
        val app = context.applicationContext
        val settings = SettingsRepository(app).getSettings()
        val profile = selectedProfile(app, settings.selectedProfileId) ?: return Outcome.NO_PROFILE
        XrayLogManager.i("VPN", "Starting ${profile.name} ($reason)")
        VpnController.startVpn(app, profile, exact = PowerButtonPolicy.exact(settings))
        return Outcome.STARTED
    }

    private suspend fun selectedProfile(context: Context, id: String?): VlessProfile? {
        val repo = ServerRepository(AppDatabase.getInstance(context).serverProfileDao())
        return id?.let { repo.getProfileById(it) } ?: repo.getAllProfilesOnce().firstOrNull()
    }
}
