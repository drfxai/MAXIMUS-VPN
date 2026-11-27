package com.example.ui.home

import com.example.data.model.ConnectionState

/**
 * Clearing Home's selection (build 179 device test, N9): the card showed the last picked engine as "Selected in Nodes"
 * after every pick and could not be cleared. Pure, so the rules are unit-tested.
 */
object HomeSelection {
    /** Stored as the selected profile id when the user cleared the selection; never a profile id. */
    const val CLEARED = "__no-selection__"

    /**
     * The state after Clear: no last tried connection. A running session is left alone, and a failure keeps its status
     * and text (a Kill Switch block after a failure must stay visible).
     */
    fun cleared(state: ConnectionState): ConnectionState =
        if (state.isTunnelUp || state.isBusy) state else state.copy(activeProfile = null)
}
