package com.example.data.model

import com.example.vpn.engine.ProfileExtras
import java.util.Locale

/** Display only the resolved runtime profile, never a guessed Auto transport. */
object ConnectionRoute {
    /**
     * Cloudflare's trace reports a Tor exit as loc=T1 (not a country). Build 176 showed "Tor · Unknown country" for a
     * working Tor exit; it is named for what it is.
     */
    const val TOR_EXIT_CODE = "T1"
    const val TOR_EXIT_NAME = "Tor network"

    /** The exit's English name: a country, "Tor network" for T1, or null when unknown. Pure. */
    fun exitName(countryCode: String?): String? {
        val code = countryCode?.trim()?.uppercase(Locale.US) ?: return null
        if (code == TOR_EXIT_CODE) return TOR_EXIT_NAME
        return code.takeIf { it.length == 2 && it in Locale.getISOCountries() }
            ?.let { Locale.Builder().setRegion(it).build().getDisplayCountry(Locale.ENGLISH) }
    }

    fun withExitCountry(route: String, countryCode: String?): String =
        "$route · ${exitName(countryCode) ?: "Unknown country"}"

    /**
     * A chain of engines by its hops, entry hop first, the way the app names it ("Psiphon over Lantern / Radiance").
     * Build 176 labelled Psiphon over Lantern "Psiphon · Spain": the label was read from the entry hop alone. Pure.
     */
    fun chainLabel(hopTitles: List<String>): String = hopTitles.joinToString(" over ")

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
            "shirokhorshid" -> "Shir o Khorshid · " + when (e.optString("sokMode")) {
                "conduit" -> "Conduit"
                "direct" -> if (e.optBoolean("sokCdn", true)) "Direct + CDN fronting" else "Direct"
                else -> if (e.optBoolean("sokCdn", true)) "Auto + CDN fronting" else "Auto"
            }
            "sing-box" -> "sing-box · ${profile.protocolType}"
            "" -> "${profile.protocolType} · ${profile.transport}"
            else -> e.optString("engine")
        }
        val exit = if (e.optString("exitNode").isNotBlank()) " · custom exit" else ""
        val relays = listOf("tor" to "Tor", "psiphon" to "Psiphon").mapNotNull { (key, title) ->
            e.optString(key, "off").takeIf { it != "off" }?.let { "$title ($it)" }
        }
        return (listOf(method + exit) + relays).joinToString(" · ")
    }
}
