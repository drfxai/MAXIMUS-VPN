package com.example.vpn

/**
 * The user's own pick of one connection runs that connection and nothing else.
 *
 * 2026-11-25 (V1.0.4 build 176): picking Psiphon from the engine list and letting it fail ended on Psiphon over Lantern,
 * because the connect deadline ([ConnectDeadline]) handed every timed-out attempt to the next built-in. The rule now:
 *
 *  - Smart Connect (the automatic button, or the Smart Connect button on the active card) is the only session that may
 *    choose, change or chain engines: deadline fallbacks, Psiphon to Shir o Khorshid / Psiphon over Lantern, Aether to
 *    saved servers or recovery engines, WARP through Lantern or through a saved server.
 *  - A manual pick (an engine tapped in Home's list, the Connect button on an engine's own page, the power button with
 *    "Power button fallback" off) is exact: it is tested and run as picked. When it carries no verified traffic within
 *    its deadline it ends FAILED with a reason and stays stopped. The only way to another connection is the user's own
 *    tap (a "Connect with …" offer, "Try another server", Smart Connect).
 *  - The same engine may still retry itself: Psiphon's region rotation and TCP-only step, Aether's own carriers,
 *    a pinned restart of the same config after its tunnel stopped carrying traffic.
 *
 * Pure, so the rules are unit-tested.
 */
object ManualEnginePick {
    /** Whether a Connect is the user's own exact pick: asked as exact, and not Smart Connect. */
    fun manual(smart: Boolean, exact: Boolean): Boolean = exact && !smart

    /** Whether automatic logic may move this session to another engine, chain or path. Never for a manual pick. */
    fun mayLeaveEngine(manual: Boolean): Boolean = !manual

    /**
     * Whether Aether may carry WARP through the Lantern (Radiance) hop: a second engine in front of the pick, so never
     * for a manual pick.
     */
    fun lanternHop(manual: Boolean, lanternAvailable: Boolean): Boolean = lanternAvailable && mayLeaveEngine(manual)

    /** The saved server Aether may carry WARP through; none for a manual pick (it would be another path). */
    fun savedServerRelay(manual: Boolean, exitNode: String?): String? = exitNode.takeIf { mayLeaveEngine(manual) }
}
