package com.example.ui.lab

import com.example.vpn.lab.ConfigRevival
import com.example.vpn.lab.RevivalTriage

/**
 * The line under each config in "Revive configs": why it failed as it was (read from its error before any
 * recipe ran) and what was done about it. Pure; the screen only picks the badge's color from [Tone].
 */
object ReviveLines {
    enum class Tone { GOOD, INFO, WARN, BAD, MUTED }

    data class Line(val text: String, val badge: String, val tone: Tone)

    /** The cause in a few words, in front of what was done. */
    fun cause(c: RevivalTriage.Cause): String = when (c) {
        RevivalTriage.Cause.NAME_NOT_RESOLVED -> "Name does not resolve"
        RevivalTriage.Cause.ADDRESS_UNREACHABLE -> "Address refuses connections or has no route"
        RevivalTriage.Cause.CONNECTION_CUT -> "Cut during the handshake"
        RevivalTriage.Cause.CERTIFICATE_REFUSED -> "Certificate refused"
        RevivalTriage.Cause.UNKNOWN -> "Cause not clear"
    }

    fun of(r: ConfigRevival.Result): Line {
        val c = r.cause
        return when (r.outcome) {
            ConfigRevival.Outcome.ALREADY_WORKING -> Line(sentence(r.detail), r.outcome.title, Tone.INFO)
            ConfigRevival.Outcome.REVIVED -> Line(c?.let { "${cause(it)} → ${r.detail}" } ?: sentence(r.detail), r.outcome.title, Tone.GOOD)
            ConfigRevival.Outcome.NOT_TESTED -> Line(c?.let { "${cause(it)} · ${r.detail}" } ?: sentence(r.detail), r.outcome.title, Tone.MUTED)
            ConfigRevival.Outcome.NOT_REVIVED -> when {
                // Only another address could help and none is validated here: nothing was tried, on purpose.
                r.tried == 0 && c == RevivalTriage.Cause.NAME_NOT_RESOLVED ->
                    Line("The name does not resolve here. $ONLY_ADDRESS", "Skipped", Tone.WARN)
                r.tried == 0 && c == RevivalTriage.Cause.ADDRESS_UNREACHABLE ->
                    Line("The address refuses connections or has no route. $ONLY_ADDRESS", "Skipped", Tone.WARN)
                r.tried > 0 && c != null -> Line("${cause(c)} · ${tried(r.tried)}, none got a request through", r.outcome.title, Tone.BAD)
                c != null -> Line("${cause(c)} · ${r.detail}", r.outcome.title, Tone.BAD)
                else -> Line(sentence(r.detail), r.outcome.title, Tone.BAD)
            }
        }
    }

    private const val ONLY_ADDRESS = "Only another address could help, and none is validated on this network."

    private fun tried(n: Int) = if (n == 1) "1 recipe tried" else "$n recipes tried"

    private fun sentence(s: String) = s.replaceFirstChar { it.uppercase() }

    const val FOOTER = "Each config's error is read first, so recipes that cannot help are not tried. " +
        "Saved configs are never changed, and certificates are always checked."
}
