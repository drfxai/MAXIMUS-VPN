package com.example.vpn.routing

import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService
import com.example.xray.XrayLogManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the per-app rules live. Its own preferences file, so it never collides with the main settings keys.
 * Rules apply the next time the tunnel is built (connect or reconnect).
 */
object SplitTunnelStore {
    private const val PREFS = "maximus_split_tunnel"
    private const val KEY = "rules"

    private val state = MutableStateFlow<SplitTunnel?>(null)

    fun flow(context: Context): StateFlow<SplitTunnel?> {
        if (state.value == null) state.value = load(context)
        return state.asStateFlow()
    }

    fun load(context: Context): SplitTunnel = SplitTunnel.decode(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
    )

    fun save(context: Context, rules: SplitTunnel) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, rules.encode()).apply()
        state.value = rules
    }

    /** Applies the saved rules to a VPN builder. Never throws: a failed call skips that one package. */
    fun apply(service: VpnService, builder: VpnService.Builder, excludeSelf: Boolean, interfaceName: String) {
        val rules = load(service)
        val pm = service.packageManager
        val plan = SplitTunnelPlan.of(rules, service.packageName, excludeSelf) { pkg ->
            runCatching { pm.getApplicationInfo(pkg, 0) }.isSuccess
        }
        var failed = 0
        plan.allowed.forEach { pkg ->
            runCatching { builder.addAllowedApplication(pkg) }.onFailure { failed++ }
        }
        plan.disallowed.forEach { pkg ->
            runCatching { builder.addDisallowedApplication(pkg) }.onFailure { e ->
                failed++
                if (e !is PackageManager.NameNotFoundException) {
                    XrayLogManager.w("VPN", "Per-app routing: could not leave $pkg out: ${e.message}")
                }
            }
        }
        if (rules.mode != SplitTunnelMode.ALL || failed > 0) {
            XrayLogManager.i("VPN", "Per-app routing ($interfaceName): ${plan.note}" + if (failed > 0) "; $failed skipped" else "")
        }
    }
}
