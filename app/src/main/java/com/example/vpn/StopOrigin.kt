package com.example.vpn

/**
 * Who asked for a Stop. Build 178 field report (Irancell LTE): four sessions went PREPARING → DISCONNECTING about
 * 1-1.7 s after the Connect tap (Smart Connect leading with Lantern or Psiphon, and a Lantern pick) and a manual Tor
 * session went VPN_INTERFACE_ESTABLISHED → DISCONNECTING after 11 s, each with no failure reason. Every DISCONNECTING
 * comes from the service's Stop (ACTION_DISCONNECT) or a mode switch to or from Unlock, neither of which said who asked.
 * Every Stop now carries its origin, the service records it ("session.stop" and the state's [stopReason]) and the
 * diagnostics show it.
 */
enum class StopOrigin(val key: String, val label: String, val uiTap: Boolean) {
    POWER_BUTTON("power_button", "the power button", true),
    SMART_BUTTON("smart_button", "the Smart Connect button", true),
    CONFIG_FREE_BUTTON("config_free_button", "the Connect without a config button", true),
    SMART_SHEET("smart_sheet", "Stop in the Smart Connect sheet", false),
    ENGINE_PAGE("engine_page", "the engine page's Stop button", true),
    TRADE_CARD("trade_card", "the Trade mode card", true),
    TILE("tile", "the Quick Settings tile", true),
    WIDGET("widget", "the home-screen widget", true),
    NOTIFICATION("notification", "Disconnect in the notification", false),
    GHOST_PANIC("ghost_panic", "Ghost Rider panic", false),
    MODE_SWITCH("mode_switch", "a mode switch", false),
    UNKNOWN("unknown", "an unnamed caller", false);

    companion object {
        fun of(key: String?): StopOrigin = entries.firstOrNull { it.key == key } ?: UNKNOWN
    }
}

/**
 * A second tap on a Connect/Stop control right after the Connect tap is read as part of the same tap, not as a Stop.
 * The field report's self-teardowns all came 0.9-1.7 s after the tap that started them, before any engine could report
 * anything, so the user never saw a state worth stopping. A deliberate Stop after [DOUBLE_TAP_MS] works as before; a
 * Stop from the notification, the Smart Connect sheet's explicit Stop or a panic is never held back. Pure.
 */
object StopGuard {
    const val DOUBLE_TAP_MS = 2_000L

    @Volatile private var connectTapAt = 0L

    /** The user's Connect tap (any control that starts a session) at [now]. */
    fun markConnectTap(now: Long = System.currentTimeMillis()) {
        connectTapAt = now
    }

    /** Milliseconds since the last Connect tap, or null when there was none. */
    fun sinceConnectTap(now: Long = System.currentTimeMillis()): Long? = connectTapAt.takeIf { it > 0 }?.let { now - it }

    /** Whether a Stop from [origin] at [now] is the tail of the Connect tap made at [tapAt]. */
    fun swallow(origin: StopOrigin, tapAt: Long, now: Long): Boolean =
        origin.uiTap && tapAt > 0 && now - tapAt in 0 until DOUBLE_TAP_MS

    fun swallow(origin: StopOrigin, now: Long = System.currentTimeMillis()): Boolean = swallow(origin, connectTapAt, now)

    /** The diagnostics line for a Stop: who asked, from which state and how long after the Connect tap. */
    fun reason(origin: StopOrigin, fromStatus: String, sinceConnectMs: Long?): String =
        "Stopped by ${origin.label} while ${fromStatus.lowercase().replace('_', ' ')}" +
            (sinceConnectMs?.takeIf { it >= 0 }?.let { " (${formatSeconds(it)} after the Connect tap)" } ?: "")

    private fun formatSeconds(ms: Long): String =
        if (ms < 10_000) String.format(java.util.Locale.US, "%.1f s", ms / 1000.0) else "${ms / 1000} s"

    internal fun resetForTest() {
        connectTapAt = 0L
    }
}
