package com.example.vpn.smart

/**
 * The tunnel MTU a connect starts from, before [TunMtuTrial] adjusts it per network.
 *
 * Iranian mobile networks black-hole full 1500-byte packets: the handshake and small requests pass and a
 * transfer stops at its first full-size packet. So on mobile data ("cell" / "cell:<carrier>" network keys) the
 * app's default 1500 becomes [CELLULAR_MTU]; a path that carries WireGuard or MASQUE (WARP / Aether, the
 * MASQUE engine, WireGuard profiles) adds its own encapsulation on top and starts at [WIREGUARD_MTU] there.
 * A value the user lowered below these is kept. Wi-Fi and Ethernet keep the setting unchanged.
 *
 * The result is the "configured" MTU the trial works from: a stall at 1400 tests [TunMtuTrial.LOW_MTU], and at
 * 1280 there is nothing lower to test, so no trial starts.
 */
object TunMtuDefaults {
    const val CELLULAR_MTU = 1400
    const val WIREGUARD_MTU = TunMtuTrial.LOW_MTU
    const val MIN_MTU = 1280
    const val MAX_MTU = 1500

    fun isCellular(network: String?): Boolean = network == "cell" || network?.startsWith("cell:") == true

    /** The base MTU for [configured] (the setting) on [network], [wireGuardPath] when WireGuard or MASQUE carries it. */
    fun base(configured: Int, network: String?, wireGuardPath: Boolean): Int {
        val c = configured.coerceIn(MIN_MTU, MAX_MTU)
        if (!isCellular(network)) return c
        return if (wireGuardPath) minOf(c, WIREGUARD_MTU) else minOf(c, CELLULAR_MTU)
    }
}
