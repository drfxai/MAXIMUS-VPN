package com.example.vpn

import java.util.Locale

/**
 * Text of the live notification shown while connected: the server as the title, the engine and a running
 * timer in the header, live speeds, and (expanded) the session totals.
 */
object ConnectedNotification {

    /** The engine that carries the traffic, as the short name shown next to the timer. */
    fun coreLabel(kotlinTunnel: Boolean, sidecarId: String?, chain: Boolean): String = when {
        chain -> "Engine chain"
        sidecarId != null -> SIDECAR_NAMES[sidecarId] ?: sidecarId.replaceFirstChar { it.titlecase(Locale.US) }
        kotlinTunnel -> "Maximus"
        else -> "Xray"
    }

    fun speedLine(downBps: Long, upBps: Long): String = "↓ ${size(downBps)}/s ↑ ${size(upBps)}/s"

    fun sessionLine(downBytes: Long, upBytes: Long): String = "Session: ${size(downBytes)} down · ${size(upBytes)} up"

    fun expandedText(downBps: Long, upBps: Long, downBytes: Long, upBytes: Long): String =
        speedLine(downBps, upBps) + "\n" + sessionLine(downBytes, upBytes)

    /** 89 B, 152 KB, 8.4 KB, 88.4 MB, 1.2 GB: short enough for one notification line. */
    fun size(bytes: Long): String {
        val b = bytes.coerceAtLeast(0L)
        if (b < 1024) return "$b B"
        var value = b / 1024.0
        var unit = 0
        while (value >= 1024 && unit < UNITS.lastIndex) {
            value /= 1024
            unit++
        }
        // Kilobytes move fast on a live line, so they drop the decimal once past 10.
        val whole = value >= 100 || (unit == 0 && value >= 10)
        return if (whole) "${value.toLong()} ${UNITS[unit]}" else String.format(Locale.US, "%.1f %s", value, UNITS[unit])
    }

    private val UNITS = arrayOf("KB", "MB", "GB", "TB")

    private val SIDECAR_NAMES = mapOf(
        "psiphon" to "Psiphon", "tor" to "Tor", "dns-tunnel" to "DNS tunnel", "cotten-dns" to "Cotten",
        "soar" to "Soar", "slipstream" to "Slipstream", "mihomo" to "Mihomo", "masque" to "MASQUE",
        "byedpi" to "ByeDPI", "matrix-transport" to "Matrix",
    )
}
