package com.example.vpn.routing

import org.json.JSONArray
import org.json.JSONObject

/*
 * Per-app routing. Ported from WhiteAestherMobile (https://github.com/WhiteDNS/WhiteAestherMobile,
 * commit 6e2ade9e86c5, app/src/main/java/com/whitedns/whiteaesther/data/SplitTunnel.kt), AGPL-3.0.
 * Changes: plain-English labels instead of string resources, Maximus package, and the
 * self-in-tunnel rule from ZedSecure (CluvexStudio/ZedSecure 201e82ce18a4, AGPL-3.0) in [SplitTunnelPlan].
 */

enum class SplitTunnelMode(val title: String, val description: String) {
    /** Everything on the phone goes through the tunnel. */
    ALL("All apps", "Every app goes through Maximus. What most people want."),

    /** Only the chosen apps do. Anything installed later stays outside. */
    ONLY("Only selected apps", "Only the apps you pick use the tunnel; apps installed later stay outside."),

    /** Everything except the chosen apps. Anything installed later goes in. */
    EXCEPT("All apps except selected", "Everything except what you pick. For banking and local apps that refuse VPNs."),
}

/**
 * Which apps the tunnel carries.
 *
 * Android takes this as either an allow list or a deny list and refuses both at once, so the mode decides
 * which call is made rather than being a filter applied here.
 */
data class SplitTunnel(
    val mode: SplitTunnelMode = SplitTunnelMode.ALL,
    val packages: Set<String> = emptySet(),
) {
    /** The packages actually handed to Android, with this app removed (its own routing is decided separately). */
    fun effectivePackages(self: String): Set<String> = packages - self

    /**
     * True when the rules change nothing, so the tunnel is built without per-app calls.
     * An empty allow list is not "no rules": it is a tunnel that carries nothing.
     */
    fun isEffectivelyEverything(self: String): Boolean = when (mode) {
        SplitTunnelMode.ALL -> true
        SplitTunnelMode.ONLY -> false
        SplitTunnelMode.EXCEPT -> effectivePackages(self).isEmpty()
    }

    /** Why this cannot be used as chosen, or null. */
    fun validationError(self: String): String? = when {
        mode == SplitTunnelMode.ONLY && effectivePackages(self).isEmpty() ->
            "Choose at least one app, or every app is routed as before."
        else -> null
    }

    /** One line for Settings and the connection log. English on purpose: it ends up in reports. */
    fun summary(): String = when {
        mode == SplitTunnelMode.ALL -> "Every app on this phone"
        packages.isEmpty() && mode == SplitTunnelMode.ONLY -> "No apps chosen yet"
        packages.isEmpty() -> "Every app on this phone"
        mode == SplitTunnelMode.ONLY -> "${packages.size} app${plural()} only"
        else -> "Every app except ${packages.size}"
    }

    private fun plural() = if (packages.size == 1) "" else "s"

    fun encode(): String = JSONObject()
        .put("mode", mode.name)
        .put("packages", JSONArray().apply { packages.sorted().forEach { put(it) } })
        .toString()

    companion object {
        fun decode(raw: String?): SplitTunnel {
            if (raw.isNullOrBlank()) return SplitTunnel()
            return runCatching {
                val json = JSONObject(raw)
                val list = json.optJSONArray("packages")
                SplitTunnel(
                    mode = SplitTunnelMode.entries.firstOrNull { it.name == json.optString("mode") } ?: SplitTunnelMode.ALL,
                    packages = buildSet {
                        for (index in 0 until (list?.length() ?: 0)) {
                            list?.optString(index)?.takeIf { it.isNotBlank() }?.let(::add)
                        }
                    },
                )
            }.getOrDefault(SplitTunnel())
        }
    }
}

/**
 * What the VPN builder is told. Exactly one of [allowed] / [disallowed] is non-empty (or both empty),
 * because Android throws when both kinds of call are made on one builder.
 */
data class SplitTunnelPlan(
    val allowed: List<String> = emptyList(),
    val disallowed: List<String> = emptyList(),
    val note: String,
) {
    companion object {
        /**
         * @param excludeSelf the app's own traffic must stay outside the tunnel (a separate engine program is
         *   running and cannot protect its own sockets). When false, the app stays inside the tunnel exactly as
         *   before per-app routing existed, including in "only selected" mode.
         * @param isInstalled drops packages uninstalled since they were chosen; refusing the whole connection
         *   over an app the user no longer has would be wrong.
         */
        fun of(rules: SplitTunnel, self: String, excludeSelf: Boolean, isInstalled: (String) -> Boolean): SplitTunnelPlan {
            val selfOut = if (excludeSelf) listOf(self) else emptyList()
            if (rules.isEffectivelyEverything(self)) return SplitTunnelPlan(disallowed = selfOut, note = "Every app")
            val chosen = rules.effectivePackages(self).filter(isInstalled).sorted()
            return when (rules.mode) {
                SplitTunnelMode.ONLY -> if (chosen.isEmpty()) {
                    SplitTunnelPlan(disallowed = selfOut, note = "None of the chosen apps are installed; routing every app")
                } else {
                    SplitTunnelPlan(
                        allowed = if (excludeSelf) chosen else chosen + self,
                        note = "Only ${chosen.size} chosen app${if (chosen.size == 1) "" else "s"}"
                    )
                }
                else -> SplitTunnelPlan(
                    disallowed = chosen + selfOut,
                    note = if (chosen.isEmpty()) "Every app" else "Every app except ${chosen.size}"
                )
            }
        }
    }
}
