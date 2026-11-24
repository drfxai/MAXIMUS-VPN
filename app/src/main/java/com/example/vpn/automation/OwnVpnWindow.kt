package com.example.vpn.automation

/**
 * Lets an upkeep run measure the phone's own network while this app's VPN service holds only its traffic-blocking
 * interface.
 *
 * Build 174 report (Irancell cell:43235): after a failed connect the service keeps that interface up (fail-closed)
 * until the user presses Disconnect, and every upkeep run (CONNECT_FAILED, VPN_STOPPED, NETWORK_CHANGED) saw "a VPN"
 * and skipped every measuring task with VPN_ACTIVE, exactly when fresh resolvers and DNS-tunnel paths were needed.
 *
 * While the window is open the service swaps the blocking interface for one that leaves this app's own sockets out
 * (the same exemption its engine tests use): other apps stay blocked throughout, and the ordinary blocking interface
 * comes back when the run ends. A connect, a Disconnect or a revoke ends the window early; the run then stops and
 * records nothing measured after that ([Host.outside]'s `intact`).
 */
object OwnVpnWindow {
    interface Host {
        /** This app's VPN holds only its blocking interface: no tunnel, no engine, no connect under way. */
        fun blockOnly(): Boolean

        /**
         * Runs [block] with this app's sockets outside the blocking interface; `intact` says whether the window is
         * still open. Returns null when the window could not be opened (a connect is under way, or the block is gone).
         */
        suspend fun <T : Any> outside(block: suspend (intact: () -> Boolean) -> T): T?
    }

    /** Set by the VPN service while it runs (same process); null otherwise. */
    @Volatile var host: Host? = null

    fun blockOnly(): Boolean = runCatching { host?.blockOnly() == true }.getOrDefault(false)

    suspend fun <T : Any> run(block: suspend (intact: () -> Boolean) -> T): T? = host?.outside(block)
}
