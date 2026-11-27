package com.example.ui.home

import com.example.data.model.ConnectionState
import com.example.data.model.ConnectionStatus
import com.example.data.model.TrafficStats
import com.example.data.model.VlessProfile
import java.util.Locale

/**
 * What the home screen's main card shows: the exit country, the exit IP, ping and speeds while a
 * tunnel is up; the config selected in Nodes otherwise. Pure, so every state is unit-tested. The
 * country and IP come from the exit check through the tunnel, never from the server's address.
 */
data class ActiveCardModel(
    val live: Boolean,
    /** "In use now" or "Selected in Nodes". */
    val label: String,
    /** The config's name, shown at the top right while live. */
    val configName: String?,
    val flag: String,
    val title: String,
    val subtitle: String,
    val exitIp: String,
    val ping: String,
    val status: String,
    val tone: Tone,
    val download: String,
    val upload: String,
    /** 0 when not measured, else 1 to 4 by ping (see [signalBars]). */
    val bars: Int = 0,
    /**
     * Smart Connect (its button, or the power button with no server) is still searching: the card shows Smart
     * Connect's own state (localized by the card), never the built-in profile the search starts from as if it were
     * "Selected in Nodes" (build 177: "Aether / WARP · Selected in Nodes · not tested yet").
     */
    val smartSearch: Boolean = false,
    /**
     * Build 179 device test (N9): nothing is selected in Nodes; the card shows the connection tried last, labelled as
     * such, never as "Selected in Nodes" (the power button said no server was selected while the card claimed one).
     */
    val lastTried: Boolean = false,
    /** The off card can be cleared back to "No server selected" (build 179: no way to clear it). */
    val clearable: Boolean = false
) {
    enum class Tone { OK, WARN, MUTED }

    companion object {
        const val DASH = "—"

        fun of(state: ConnectionState, selected: VlessProfile?,
               /** This connection's last result on the current network (EngineLedger.pathResult), if any. */
               result: (VlessProfile) -> com.example.vpn.engine.registry.EngineLedger.PathResult? = { null },
               now: Long = System.currentTimeMillis()): ActiveCardModel? {
            val up = state.isTunnelUp
            val profile = (if (up) state.activeProfile else null) ?: selected ?: state.activeProfile ?: return null
            if (!up && state.smartSession && state.isBusy) {
                return ActiveCardModel(
                    live = false,
                    label = "Smart Connect",
                    configName = null,
                    flag = flag(null),
                    title = "Smart Connect",
                    subtitle = state.recoveryStage ?: "Searching for a connection that works on this network",
                    exitIp = "Shown once connected",
                    ping = DASH,
                    status = "Searching",
                    tone = Tone.MUTED,
                    download = DASH,
                    upload = DASH,
                    smartSearch = true
                )
            }
            if (!up) {
                val lastTried = selected == null
                val fallback = profile.lastLatencyMs?.takeIf { it > 0 }?.let { "Last test $it ms" } ?: "Not tested yet"
                return ActiveCardModel(
                    live = false,
                    label = if (lastTried) LAST_TRIED else SELECTED,
                    configName = null,
                    flag = flag(profile.countryCode),
                    title = profile.name,
                    // Build 179 (N4): "not tested yet" was shown for a pick that had just failed here.
                    subtitle = com.example.vpn.engine.registry.NetworkResults.cardLine(
                        runCatching { result(profile) }.getOrNull(), now, fallback),
                    lastTried = lastTried,
                    clearable = !state.isBusy,
                    exitIp = "Shown once connected",
                    ping = DASH,
                    status = "Off",
                    tone = Tone.MUTED,
                    download = DASH,
                    upload = DASH
                )
            }
            val code = state.exitCountryCode?.uppercase(Locale.US)?.takeIf { it.matches(Regex("[A-Z][A-Z0-9]")) }
            val (status, tone) = when (state.status) {
                ConnectionStatus.CONNECTED -> "Verified" to Tone.OK
                ConnectionStatus.DEGRADED -> "Degraded" to Tone.WARN
                ConnectionStatus.TUNNEL_STARTED_CONNECTIVITY_UNVERIFIED -> "Not verified" to Tone.WARN
                else -> "Checking" to Tone.MUTED
            }
            val verified = state.status == ConnectionStatus.CONNECTED || state.status == ConnectionStatus.DEGRADED
            return ActiveCardModel(
                live = true,
                label = "In use now",
                configName = profile.name,
                flag = flag(code),
                title = code?.let(::countryName) ?: "Exit not measured yet",
                subtitle = if (code != null) "Exit seen by websites" else "Checked through the tunnel",
                exitIp = state.exitIp ?: if (verified) "Measuring…" else DASH,
                ping = state.pingMs?.takeIf { it > 0 }?.let { "$it ms" } ?: DASH,
                status = status,
                tone = tone,
                download = if (verified) TrafficStats.formatSpeed(state.downloadSpeedBps) else DASH,
                upload = if (verified) TrafficStats.formatSpeed(state.uploadSpeedBps) else DASH,
                bars = signalBars(state.pingMs)
            )
        }

        /** Signal bars for a measured ping: 4 under 300 ms, 3 under 700, 2 under 1500, else 1; 0 when unmeasured. */
        fun signalBars(pingMs: Long?): Int = when {
            pingMs == null || pingMs <= 0 -> 0
            pingMs < 300 -> 4
            pingMs < 700 -> 3
            pingMs < 1500 -> 2
            else -> 1
        }

        /** The off-state subtitle on one line: where the config comes from and its last test. */
        fun offLine(model: ActiveCardModel): String =
            if (model.smartSearch) model.subtitle else model.label + " · " + model.subtitle.replaceFirstChar { it.lowercase() }

        const val SELECTED = "Selected in Nodes"
        const val LAST_TRIED = "Last tried"

        /** The country's English name, or the code itself when the JVM does not know it. */
        fun countryName(code: String): String =
            com.example.data.model.ConnectionRoute.exitName(code)
                ?: Locale("", code).getDisplayCountry(Locale.ENGLISH).takeIf { it.isNotBlank() && it != code } ?: code

        /** The flag emoji for a two-letter code; a globe when there is none. */
        fun flag(code: String?): String {
            val c = code?.uppercase(Locale.US)?.takeIf { it.matches(Regex("[A-Z]{2}")) } ?: return "🌐"
            return c.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
        }
    }
}
