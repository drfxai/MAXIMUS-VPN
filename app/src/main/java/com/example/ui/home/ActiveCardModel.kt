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
    val upload: String
) {
    enum class Tone { OK, WARN, MUTED }

    companion object {
        const val DASH = "—"

        fun of(state: ConnectionState, selected: VlessProfile?): ActiveCardModel? {
            val up = state.isTunnelUp
            val profile = (if (up) state.activeProfile else null) ?: selected ?: state.activeProfile ?: return null
            if (!up) {
                return ActiveCardModel(
                    live = false,
                    label = "Selected in Nodes",
                    configName = null,
                    flag = flag(profile.countryCode),
                    title = profile.name,
                    subtitle = profile.lastLatencyMs?.takeIf { it > 0 }?.let { "Last test $it ms" } ?: "Not tested yet",
                    exitIp = "Shown once connected",
                    ping = DASH,
                    status = "Off",
                    tone = Tone.MUTED,
                    download = DASH,
                    upload = DASH
                )
            }
            val code = state.exitCountryCode?.uppercase(Locale.US)?.takeIf { it.matches(Regex("[A-Z]{2}")) }
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
                upload = if (verified) TrafficStats.formatSpeed(state.uploadSpeedBps) else DASH
            )
        }

        /** The country's English name, or the code itself when the JVM does not know it. */
        fun countryName(code: String): String =
            Locale("", code).getDisplayCountry(Locale.ENGLISH).takeIf { it.isNotBlank() && it != code } ?: code

        /** The flag emoji for a two-letter code; a globe when there is none. */
        fun flag(code: String?): String {
            val c = code?.uppercase(Locale.US)?.takeIf { it.matches(Regex("[A-Z]{2}")) } ?: return "🌐"
            return c.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
        }
    }
}
