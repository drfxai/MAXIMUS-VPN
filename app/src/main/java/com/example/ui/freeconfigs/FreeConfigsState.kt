package com.example.ui.freeconfigs

import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import java.util.Locale

enum class FreeSort(val label: String) { FASTEST("Fastest"), STABLE("Most stable"), COUNTRY("By country") }

/** Where a server stands after the last test on this network. */
enum class NodeHealth { FAST, SLOW, OFFLINE, QUEUED }

/** One server of the free list as the screen shows it. */
data class FreeNode(
    val profile: VlessProfile,
    /** ISO country code where the server's address is registered, or null. */
    val country: String?,
    val latencyMs: Long?,
    val health: NodeHealth,
    /** Successful tests out of all tests run on this network since the screen opened. */
    val passes: Int = 0,
    val runs: Int = 0,
    /**
     * Which of YouTube, Telegram and X ("YT", "TG", "X") the server's exit opened when the list was built.
     * The builder runs outside Iran: this is global reachability, not proof the server works here.
     */
    val sites: Set<String> = emptySet(),
    /** What this phone's own measurements say about the server (see FreeConfigLifecycle). */
    val lifecycle: com.example.vpn.hub.FreeConfigLifecycle = com.example.vpn.hub.FreeConfigLifecycle.GLOBAL_VERIFIED
) {
    val online: Boolean get() = health == NodeHealth.FAST || health == NodeHealth.SLOW
    val protocol: String get() = profile.protocolType.displayName

    /** Security and transport, the way users compare servers: REALITY, TLS, WS, QUIC... */
    val tags: List<String>
        get() = buildList {
            when (profile.protocolType) {
                ProtocolType.HYSTERIA2 -> { add("QUIC"); add("UDP") }
                ProtocolType.WIREGUARD -> add("UDP")
                ProtocolType.SHADOWSOCKS -> {
                    if (profile.encryption.startsWith("2022")) add("2022")
                    add(transportTag(profile.transport))
                }
                else -> {
                    profile.security.lowercase(Locale.ROOT).takeIf { it == "tls" || it == "reality" }?.let { add(it.uppercase(Locale.ROOT)) }
                    add(transportTag(profile.transport))
                }
            }
        }.distinct()

    private fun transportTag(transport: String): String = when (transport.lowercase(Locale.ROOT)) {
        "ws", "websocket" -> "WS"
        "grpc", "gun" -> "gRPC"
        "xhttp", "splithttp" -> "XHTTP"
        "httpupgrade" -> "HTTPUpgrade"
        "h2", "http" -> "H2"
        "quic" -> "QUIC"
        "kcp", "mkcp" -> "mKCP"
        else -> "TCP"
    }

    companion object {
        /** Up to this, a server counts as fast; above it, as slow. */
        const val FAST_MS = 800L

        fun healthOf(latencyMs: Long?): NodeHealth = when {
            latencyMs == null -> NodeHealth.QUEUED
            latencyMs <= FAST_MS -> NodeHealth.FAST
            else -> NodeHealth.SLOW
        }
    }
}

/** What one refresh did to the list. */
data class RefreshSummary(val candidates: Int, val added: Int, val kept: Int, val retained: Int, val removed: Int, val at: Long)

data class FreeConfigsUiState(
    /** False when this build has no key to check the list, so the list is never fetched. */
    val available: Boolean = true,
    /** The user's "Free configs" setting; off means the list is never fetched or shown. */
    val enabled: Boolean = true,
    /** The MAXIMUS Free subscription exists on this install. */
    val subscribed: Boolean = false,
    val loading: Boolean = true,
    val syncing: Boolean = false,
    val testing: Boolean = false,
    /** Servers finished and in total in the current test run. */
    val testDone: Int = 0,
    val testTotal: Int = 0,
    /** The last sync accepted a signed list. */
    val verified: Boolean = false,
    val syncError: String? = null,
    val lastUpdated: Long = 0L,
    val refreshIntervalMinutes: Int = 360,
    val nodes: List<FreeNode> = emptyList(),
    val sort: FreeSort = FreeSort.FASTEST,
    /** Protocol display name to show only, or null for all. */
    val protocol: String? = null,
    /** Show only servers that opened this site ("YT", "TG", "X"), or null for all. */
    val site: String? = null,
    val showHidden: Boolean = false,
    val message: String? = null,
    /** The running refresh's step, or null. The saved list stays usable while it runs. */
    val refreshProgress: com.example.vpn.subscription.FreeRefreshProgress? = null,
    /** What the last successful refresh of this session changed. */
    val lastRefresh: RefreshSummary? = null,
    /** When the last refresh failed (0 if it did not) and why; the saved list was kept. */
    val refreshFailedAt: Long = 0L,
    val refreshFailure: String? = null
) {
    /** Servers per lifecycle (what this phone's measurements say), for the summary card. */
    val verifiedHere: Int get() = nodes.count { it.lifecycle == com.example.vpn.hub.FreeConfigLifecycle.LOCAL_NETWORK_VERIFIED }
    val onTrial: Int get() = nodes.count { it.lifecycle == com.example.vpn.hub.FreeConfigLifecycle.LOCAL_PROBATION }
    val failingHere: Int get() = nodes.count {
        it.lifecycle == com.example.vpn.hub.FreeConfigLifecycle.DEGRADED || it.lifecycle == com.example.vpn.hub.FreeConfigLifecycle.DEAD
    }
    val checkedOutside: Int get() = total - verifiedHere - onTrial - failingHere

    val total: Int get() = nodes.size
    val online: List<FreeNode> get() = nodes.filter { it.online }
    val fast: Int get() = nodes.count { it.health == NodeHealth.FAST }
    val slow: Int get() = nodes.count { it.health == NodeHealth.SLOW }
    val offline: List<FreeNode> get() = nodes.filter { it.health == NodeHealth.OFFLINE }
    val queued: Int get() = nodes.count { it.health == NodeHealth.QUEUED }
    val tested: Int get() = total - queued
    val nextUpdate: Long get() = if (lastUpdated == 0L) 0L else lastUpdated + refreshIntervalMinutes * 60_000L

    /** Servers that have not failed a test here: online ones and untested ones. */
    val shown: List<FreeNode> get() = nodes.filter { it.health != NodeHealth.OFFLINE }

    /** Shown servers per protocol, most common first. */
    val protocolCounts: List<Pair<String, Int>>
        get() = shown.groupingBy { it.protocol }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }

    /** Servers per site tag, in the order YouTube, Telegram, X; sites no server opened are left out. */
    val siteCounts: List<Pair<String, Int>>
        get() = SITES.map { (tag, _) -> tag to shown.count { tag in it.sites } }.filter { it.second > 0 }

    /**
     * The servers listed on the screen: every server that has not failed a test here (untested ones too,
     * since tests run only when the user asks), filtered and in the chosen order, untested last.
     */
    val visible: List<FreeNode>
        get() {
            val list = nodes.filter {
                it.health != NodeHealth.OFFLINE && (protocol == null || it.protocol == protocol) && (site == null || site in it.sites)
            }
            val byLatency = compareBy<FreeNode> { it.latencyMs ?: Long.MAX_VALUE }
            return when (sort) {
                FreeSort.FASTEST -> list.sortedWith(byLatency)
                FreeSort.STABLE -> list.sortedWith(
                    compareByDescending<FreeNode> { if (it.runs == 0) 0.0 else it.passes.toDouble() / it.runs }
                        .thenByDescending { it.runs }
                        .then(byLatency)
                )
                FreeSort.COUNTRY -> list.sortedWith(compareBy<FreeNode> { countryName(it.country) ?: "￿" }.then(byLatency))
            }
        }

    /** The server "Connect to fastest" uses: the fastest online one, whatever the filter. */
    val best: FreeNode? get() = online.minByOrNull { it.latencyMs ?: Long.MAX_VALUE }

    companion object {
        /** The site tags the list's names carry, with the name the screen shows. */
        val SITES = listOf("YT" to "YouTube", "TG" to "Telegram", "X" to "X")

        /** English name of [code], or null for an unknown code. */
        fun countryName(code: String?): String? {
            if (code == null || code.length != 2) return null
            val name = Locale("", code.uppercase(Locale.ROOT)).getDisplayCountry(Locale.ENGLISH)
            return name.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }
        }

        /** The regional-indicator flag emoji for [code], or null. */
        fun flagOf(code: String?): String? {
            if (code == null || code.length != 2 || !code.all { it in 'A'..'Z' || it in 'a'..'z' }) return null
            val upper = code.uppercase(Locale.ROOT)
            return String(Character.toChars(0x1F1E6 + (upper[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (upper[1] - 'A')))
        }
    }
}
