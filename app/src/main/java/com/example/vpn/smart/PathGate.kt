package com.example.vpn.smart

/**
 * Whether a connect may start the tunnel, decided from what the pre-flight actually measured.
 *
 * A server whose real request just failed on this network is not started on its own: starting the
 * core and the TUN for a path already proven dead wastes the user's time and hides the failure behind
 * a "connected" interface. Only a verified path, a recovery engine that can only be judged after it
 * starts (Psiphon, Tor; verified right after), an untested path (the probe could not run, so nothing
 * is proven) or the user's explicit "Connect anyway" may proceed.
 */
object PathGate {
    enum class Decision {
        /** A real request went through this path during the pre-flight. */
        VERIFIED,
        /** A recovery engine that cannot be measured before it starts; traffic is verified after start. */
        ENGINE_UNVERIFIED,
        /** No pre-flight could run (no test core, nothing to compare): nothing is proven either way. */
        UNTESTED,
        /** The path failed its real test, and the user explicitly chose to connect anyway. */
        FORCED,
        /** The path failed its real test and nothing else carried traffic: do not start it. */
        NO_VERIFIED_PATH
    }

    /**
     * [verified]: a real request succeeded on the path that will be used. [provenDead]: the selected path
     * failed a real request on this network during this connect. [engineAdopted]: an unmeasurable
     * recovery engine was chosen. [forced]: the user pressed "Connect anyway".
     */
    fun decide(verified: Boolean, provenDead: Boolean, engineAdopted: Boolean, forced: Boolean): Decision = when {
        verified -> Decision.VERIFIED
        engineAdopted -> Decision.ENGINE_UNVERIFIED
        !provenDead -> Decision.UNTESTED
        forced -> Decision.FORCED
        else -> Decision.NO_VERIFIED_PATH
    }

    fun mayStart(decision: Decision): Boolean = decision != Decision.NO_VERIFIED_PATH
}
