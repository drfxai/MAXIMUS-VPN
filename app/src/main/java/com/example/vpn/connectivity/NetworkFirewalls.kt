package com.example.vpn.connectivity

/**
 * Which filtering a mobile network is known for, so recovery recipes are tried in the order that
 * network's users report working. It is read from the network the phone is on (the network key's
 * MCC+MNC), not from the SIM, and Wi-Fi or an unknown network gets no preference.
 *
 * What the community reports (2026-10): fragmenting the TLS ClientHello still works on Irancell,
 * with an empty first record, the Go TLS stack and Firefox's cipher list; on Hamrah-e-Aval it is
 * blocked outright (IPv6 too), and on Hamrah-e-Aval and Rightel ECH with a Chrome fingerprint works.
 * The preference only orders and skips recipes; every recipe still passes the security gate and a
 * real request, and the ledger's own results on this network outweigh it once there are some.
 */
object NetworkFirewalls {
    enum class Firewall(val title: String, val note: String) {
        IRANCELL("Irancell", "Fragment recipes first"),
        MCI("Hamrah-e-Aval", "ECH first; fragment is blocked on this network, so it is skipped"),
        RIGHTEL("Rightel", "ECH first, fragment last"),
        OTHER("Other network", "Every recipe, in the usual order")
    }

    private val CODES = mapOf("43235" to Firewall.IRANCELL, "43211" to Firewall.MCI, "43220" to Firewall.RIGHTEL)

    /** The firewall of the network named by a network key such as "cell:43235". */
    fun detect(networkKey: String?): Firewall =
        networkKey?.takeIf { it.startsWith("cell:") }?.let { CODES[it.removePrefix("cell:")] } ?: Firewall.OTHER

    /** The profile tuned for Irancell (see [RecoveryProfiles]). */
    const val IRANCELL_PROFILE = "irancell-fragment"

    /**
     * How much to move [profile] up (positive) or down on [firewall], or null to skip it there.
     * Added to the profile's measured success rate, which is 0 to 1.
     */
    fun bias(firewall: Firewall, profile: RecoveryProfile): Double? {
        val fragment = RecoveryProfile.Strategy.FRAGMENT in profile.networkConditions
        val ech = RecoveryProfile.Strategy.ECH in profile.networkConditions
        return when (firewall) {
            Firewall.IRANCELL -> when {
                profile.profileId == IRANCELL_PROFILE -> 0.5
                fragment -> 0.2
                else -> 0.0
            }
            Firewall.MCI -> when {
                fragment -> null
                ech -> 0.4
                else -> 0.0
            }
            Firewall.RIGHTEL -> when {
                ech -> 0.4
                fragment -> -0.3
                else -> 0.0
            }
            Firewall.OTHER -> 0.0
        }
    }
}
