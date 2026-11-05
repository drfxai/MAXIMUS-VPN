package com.example.vpn.connections

import android.content.Context
import com.example.data.model.VlessProfile
import com.example.vpn.sidecar.AetherSettings
import com.example.vpn.sidecar.AetherSidecar
import com.example.vpn.sidecar.ServerlessSidecar

/** Built-ins are kept out of the saved-server race: Direct must never become an implicit VPN exit. */
object BuiltinConnections {
    const val AETHER = "maximus-aether"
    const val DIRECT = "maximus-serverless"
    fun resolve(context: Context, id: String): VlessProfile? {
        if (id != AETHER && id != DIRECT) return null
        val p = context.getSharedPreferences("galaxy_connections", Context.MODE_PRIVATE)
        val family = p.getString(if (id == DIRECT) "direct_family" else "aether_family", p.getString("family", "auto")).takeIf { it in AetherSidecar.families } ?: "auto"
        return if (id == AETHER) AetherSidecar.profile(
            p.getString("transport", "auto").takeIf { it in AetherSidecar.transports } ?: "auto",
            family, p.getBoolean("fragment", true), AetherSettings.read(org.json.JSONObject(p.getString("aether_advanced", "{}") ?: "{}")))
        else ServerlessSidecar.profile(p.getString("variant", "auto").takeIf { it in ServerlessSidecar.variants } ?: "auto", family, p.getBoolean("vpnFallback", false), p.getString("direct_resolver", "auto").takeIf { it in ServerlessSidecar.resolvers } ?: "auto")
    }
}
