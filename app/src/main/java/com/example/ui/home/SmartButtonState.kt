package com.example.ui.home

import com.example.data.model.ConnectionState
import com.example.data.model.ConnectionStatus

/**
 * The Smart connect button's three states, in the main power button's colours: off (lavender),
 * connecting (gold) and on (green), only for a session Smart Connect started. While connecting or on,
 * a tap stops the connection. Pure, so the
 * mapping is unit-tested.
 */
enum class SmartButtonState {
    OFF, CONNECTING, ON,
    /**
     * Build 179 device test (N9): during the user's own pick the button still offered "Auto Connect", as if nothing ran.
     * It now says the user's pick is running; a tap stops it.
     */
    MANUAL;

    /** A tap stops (true) or starts (false) a connection. */
    val tapStops: Boolean get() = this != OFF

    companion object {
        fun of(state: ConnectionState): SmartButtonState = when {
            // A session the power button (or a tile) started belongs to the power button, not to this one.
            !state.smartSession && state.status != ConnectionStatus.DISCONNECTING && (state.isBusy || state.isTunnelUp) -> MANUAL
            !state.smartSession -> OFF
            state.status == ConnectionStatus.CONNECTED || state.status == ConnectionStatus.DEGRADED -> ON
            state.status == ConnectionStatus.DISCONNECTING -> OFF
            state.isBusy || state.isTunnelUp -> CONNECTING
            else -> OFF
        }
    }
}
