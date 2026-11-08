package com.example.data.model

import com.example.vpn.engine.ProfileExtras

/** Display only the resolved runtime profile, never a guessed Auto transport. */
object ConnectionRoute {
    fun label(profile: VlessProfile): String {
        val e = ProfileExtras.read(profile)
        val method = when (e.optString("engine")) {
            "serverless" -> "Direct Serverless · " + when (e.optString("variant")) {
                "oob" -> "OOB"
                "fragA" -> "Fragment A"
                "fragB" -> "Fragment B"
                else -> "method not reported"
            }
            "aether" -> "Aether WARP · " + when (e.optString("carrier")) {
                "h2" -> "MASQUE HTTP/2"
                "h3" -> "MASQUE HTTP/3"
                "wireguard" -> "WireGuard"
                "wiw" -> "WARP-in-WARP"
                "mim" -> "MASQUE-in-MASQUE"
                "gool" -> "WireGuard over MASQUE"
                else -> "transport not reported"
            }
            "tor" -> "Tor"
            "psiphon" -> "Psiphon"
            "sing-box" -> "sing-box · ${profile.protocolType}"
            "" -> "${profile.protocolType} · ${profile.transport}"
            else -> e.optString("engine")
        }
        return method
    }
}
