package com.example.vpn.diagnostics

/**
 * When the tunnel checks read the exit (Cloudflare's trace through the engine), and what the Home card then shows.
 *
 * Build 177 (Lantern / Radiance, manual pick): the card said "Netherlands" for the whole session while every trace
 * made through the tunnel, by the app's Smart Connect runs and by the phone, said ip=163.123.193.23 loc=DE. The exit was
 * read once, right at connect (an engine like Radiance can still be settling on its proxy then), and the next read was
 * ten checks later. The country and the address could also come from two different reads.
 *
 *  - The exit is read until one read succeeds (at most [FIRST_TRIES] misses in a row), then again on the [EARLY_RECHECKS]
 *    healthy checks right after connect, then every [EVERY] checks.
 *  - The card's country and address always come from the same trace ([next]); a read without a country clears it
 *    rather than leaving an older read's country next to a new address.
 *
 * Pure, so the rule is unit-tested.
 */
object ExitTrace {
    const val FIRST_TRIES = 3
    val EARLY_RECHECKS = setOf(2, 5)
    const val EVERY = 10

    /** Whether this check reads the exit: [knownIp] null until one read succeeded, [okStreak] healthy checks so far. */
    fun due(knownIp: String?, misses: Int, okStreak: Int): Boolean =
        (knownIp == null && misses < FIRST_TRIES) || okStreak in EARLY_RECHECKS || okStreak % EVERY == EVERY - 1

    /** The (country, ip) the card shows after a check: from [fresh] when a trace was read, else the previous pair. */
    fun next(previousCountry: String?, previousIp: String?, fresh: LiveTunnelProbe.Exit?): Pair<String?, String?> =
        if (fresh == null || (fresh.country == null && fresh.ip == null)) previousCountry to previousIp
        else fresh.country to fresh.ip

    /** A log line when a later read found another exit than the one shown; null otherwise. */
    fun changed(previousCountry: String?, previousIp: String?, fresh: LiveTunnelProbe.Exit?): String? {
        if (fresh?.country == null || previousCountry == null || fresh.country == previousCountry) return null
        return "Exit changed: $previousCountry → ${fresh.country} (the engine moved to another exit); the card shows the latest trace."
    }
}
