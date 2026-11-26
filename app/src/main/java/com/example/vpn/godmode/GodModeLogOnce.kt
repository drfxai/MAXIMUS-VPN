package com.example.vpn.godmode

import java.util.concurrent.atomic.AtomicBoolean

/**
 * GOD mode's bridges and LAN mesh are not available in this build. Build 177 (B16) logged "bridges paused" and "Mesh
 * stopped" on every mode tap although neither had run: what is unavailable is said once per process. Pure.
 */
object GodModeLogOnce {
    /** True the first time it is called with [flag], false after. */
    fun first(flag: AtomicBoolean): Boolean = flag.compareAndSet(false, true)

    /** Whether tapping [next] while [current] is selected changes the mode (a re-tap starts or stops nothing). */
    fun <T> changes(current: T, next: T): Boolean = current != next
}
