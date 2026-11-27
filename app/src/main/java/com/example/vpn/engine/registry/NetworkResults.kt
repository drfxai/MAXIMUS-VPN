package com.example.vpn.engine.registry

/**
 * What each connection did on the phone's current network, in words, for the screens that used to show nothing
 * (build 179 device test, N4): the LAB's "Best verified strategy" said "Nothing verified on this network yet" after
 * seven picks carried traffic on Irancell, Diagnostics named only the active engine, and the Home card said
 * "not tested yet" on picks that had just failed. Reads [EngineLedger.pathResults]; pure, so it is unit-tested.
 */
object NetworkResults {
    /** A path key ([com.example.vpn.diagnostics.EngineStages.engineOf]) as the picker names it. */
    fun title(path: String): String {
        val hops = path.split('→')
        val first = hops.first()
        if (first.startsWith("aether/")) {
            val carrier = aetherCarrier(first.substringAfter('/'))
            return when (hops.getOrNull(1)) {
                null -> carrier
                "radiance" -> "Aether over Lantern"
                "psiphon" -> "Aether → Psiphon"
                "tor" -> "Aether → Tor"
                else -> "$carrier → ${single(hops[1])}"
            }
        }
        if (hops.size == 1) return single(first)
        return hops.joinToString(" over ") { if (it == "radiance") "Lantern" else single(it) }
    }

    private fun aetherCarrier(carrier: String): String = when (carrier) {
        "h2" -> "MASQUE HTTP/2"
        "h3" -> "MASQUE HTTP/3"
        "wireguard" -> "WireGuard"
        "gool" -> "Gool"
        "wiw" -> "Gool Classic"
        "mim" -> "MASQUE-in-MASQUE"
        else -> "Aether / WARP"
    }

    private fun single(id: String): String = when (id) {
        "radiance" -> "Lantern / Radiance"
        "tor" -> "Tor / Snowflake"
        "serverless" -> "Serverless Direct"
        "psiphon" -> "Psiphon"
        "shirokhorshid" -> "Shir o Khorshid"
        "xray" -> "Saved config"
        "masque" -> "WARP MASQUE"
        else -> id
    }

    /** "3 min ago", "2 h ago". */
    fun ago(at: Long, now: Long): String {
        val min = ((now - at).coerceAtLeast(0)) / 60_000
        return if (min < 60) "$min min ago" else "${min / 60} h ago"
    }

    /** One line per path: "Lantern / Radiance · verified 3 min ago (2 of 2 attempts)". */
    fun line(r: EngineLedger.PathResult, now: Long): String {
        val total = r.verifiedCount + r.failedCount
        val count = if (total > 1) " (${r.verifiedCount} of $total verified)" else ""
        return "${title(r.path)} · ${if (r.verified) "verified" else "failed"} ${ago(r.at, now)}$count"
    }

    /** The paths whose last attempt on this network carried verified traffic, newest first. */
    fun verified(results: List<EngineLedger.PathResult>): List<EngineLedger.PathResult> = results.filter { it.verified }

    /**
     * The LAB's line under "Best verified strategy" when no Lab experiment verified anything here: what the app's own
     * connections verified on this network in the last 24 h, or null when nothing did.
     */
    fun labLine(results: List<EngineLedger.PathResult>, now: Long, max: Int = 4): String? {
        val ok = verified(results)
        if (ok.isEmpty()) return null
        val shown = ok.take(max).joinToString(", ") { "${title(it.path)} (${ago(it.at, now)})" }
        val more = if (ok.size > max) " and ${ok.size - max} more" else ""
        return "Verified on this network in the last 24 h: $shown$more."
    }

    /**
     * Build 180 device test (A6): the LAB tile said "Verified 0 profiles" beside a list of five connections verified
     * here. With no Lab-verified profile the tile counts the connections verified on this network (24 h). Pure.
     */
    fun verifiedMetric(profiles: Int, connectionsHere: Int): String = when {
        profiles == 1 -> "1 profile"
        profiles > 1 -> "$profiles profiles"
        connectionsHere == 1 -> "1 connection"
        connectionsHere > 1 -> "$connectionsHere connections"
        else -> "0 profiles"
    }

    /** The Home card's line for a connection that is not running: its last result here, else [fallback]. */
    fun cardLine(result: EngineLedger.PathResult?, now: Long, fallback: String): String = when {
        result == null -> fallback
        result.verified -> "Verified here ${ago(result.at, now)}"
        else -> "Failed here ${ago(result.at, now)}"
    }
}

/** [EngineLedger.pathResults] for the phone's current network, keyed the way the VPN service records them. */
object NetworkResultsReader {
    fun network(context: android.content.Context): String = runCatching { com.example.vpn.smart.NetworkIdentity.current(context) }
        .getOrElse { com.example.vpn.smart.NetworkKey.current(context) }

    fun current(context: android.content.Context): List<EngineLedger.PathResult> =
        runCatching { EngineLedger.shared.pathResults(network(context)) }.getOrDefault(emptyList())
}
