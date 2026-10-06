package com.example.vpn.subscription

/**
 * The steps of a free list refresh, for the screen. The saved list stays in use throughout: it is
 * replaced in one transaction at the end, or not at all.
 */
data class FreeRefreshProgress(val stage: Stage, val candidates: Int = 0, val valid: Int = 0) {
    enum class Stage {
        /** Fetching the list and its signature from the first source that answers. */
        DOWNLOADING,
        /** Signature valid and every config parsed and security-checked; swapping into the saved list. */
        SWAPPING
    }

    /** How far along the bar is. */
    val fraction: Float get() = when (stage) { Stage.DOWNLOADING -> 0.35f; Stage.SWAPPING -> 0.85f }
}
