package com.example.vpn.connectivity

/**
 * Which filtering a mobile network is known for, so recovery recipes are tried in the order that
 * network's users report working. It is read from the network the phone is on (the network key's
 * MCC+MNC), not from the SIM, and Wi-Fi or an unknown network gets no preference.
 *
 * What the community reports (2026-10): fragmenting the TLS ClientHello still works on Irancell,
 * with an empty first record, the Go TLS stack and Firefox's cipher list; on Hamrah-e-Aval it is
 * blocked outright (IPv6 too), and on Hamrah-e-Aval and Rightel ECH with a Chrome fingerprint works.
 * UAC SNI Spoofer (2026-11) adds the exception that a ClientHello split right before the SNI, on Go's TLS
 * stack with a fixed cipher list, still connects on Hamrah-e-Aval ([IranHelloSplit]); it is tried first there.
 * patterniha (2026-11, Serverless-for-Iran v65) adds the CDN/Worker recipes per firewall type: on Irancell "F&F"
 * (the empty-record fragment, Go TLS with the semi-python ciphers, ALPN by transport, ECH removed, optionally a clean
 * Cloudflare IPv4 such as 188.114.98.0), tried first there; on Hamrah-e-Aval, whose IPv6 is filtered by blacklist,
 * a plain Chrome ClientHello to a Cloudflare IPv6 edge when the network has IPv6.
 * Since 2026-11 a behaviour probe ([FirewallBehaviour]) can name the firewall type when the network code cannot (Wi-Fi,
 * fixed ISPs) or names the other operator (regions where one operator's SIM sits behind the other's firewall); it
 * applies only when its result is confident, and the network code stays the fallback.
 * The preference only orders and skips recipes; every recipe still passes the security gate and a
 * real request, and the ledger's own results on this network outweigh it once there are some.
 */
object NetworkFirewalls {
    enum class Firewall(val title: String, val note: String) {
        IRANCELL("Irancell", "F&F (fragment + Go TLS) first, then other fragment recipes"),
        MCI("Hamrah-e-Aval", "Clean Chrome over Cloudflare IPv6 first (with IPv6), then the Iran ClientHello split and ECH; other fragment recipes are skipped"),
        RIGHTEL("Rightel", "ECH first, fragment last"),
        OTHER("Other network", "Every recipe, in the usual order")
    }

    private val CODES = mapOf("43235" to Firewall.IRANCELL, "43211" to Firewall.MCI, "43220" to Firewall.RIGHTEL)

    /** patterniha's oob6 recipe for Hamrah-e-Aval IPv6, on the fork only (see [RecoveryProfiles]). */
    const val MCI_V6_OOB_PROFILE = "mci-v6-oob"

    /**
     * The firewall of the network named by a network key such as "cell:43235": the behaviour probe's confident
     * result for this network when there is one ([FirewallBehaviour], [FirewallProbe]), else the network code.
     */
    fun detect(networkKey: String?): Firewall = detection(networkKey).firewall

    /** The firewall the network code alone names (MCC+MNC of the serving network). */
    fun byNetworkCode(networkKey: String?): Firewall =
        networkKey?.takeIf { it.startsWith("cell:") }?.let { CODES[it.removePrefix("cell:")] } ?: Firewall.OTHER

    enum class Source { BEHAVIOUR, NETWORK_CODE, NONE }

    /** What [detect] decided and why: [byCode] is the network-code guess, shown next to a behaviour result. */
    data class Detection(val firewall: Firewall, val source: Source, val byCode: Firewall)

    /**
     * The behaviour probe's confident firewall for a network key, or null. Set by the app ([FirewallProbe.store]);
     * null in tests and before the app starts, so detection is the network code alone.
     */
    @Volatile var behaviour: ((String?) -> Firewall?)? = null

    fun detection(networkKey: String?): Detection {
        val code = byNetworkCode(networkKey)
        val probed = runCatching { behaviour?.invoke(networkKey) }.getOrNull()
        return when {
            probed != null -> Detection(probed, Source.BEHAVIOUR, code)
            code != Firewall.OTHER -> Detection(code, Source.NETWORK_CODE, code)
            else -> Detection(Firewall.OTHER, Source.NONE, code)
        }
    }

    /** The profile tuned for Irancell (see [RecoveryProfiles]). */
    const val IRANCELL_PROFILE = "irancell-fragment"

    /** patterniha's Irancell F&F for CDN/Worker configs (see [RecoveryProfiles]). */
    const val IRANCELL_FF_PROFILE = "irancell-ff"

    /** [IRANCELL_FF_PROFILE] dialled at a clean Cloudflare IPv4 edge. */
    const val IRANCELL_FF_IP_PROFILE = "irancell-ff-ip"

    /** patterniha's Hamrah-e-Aval recipe: plain Chrome to a Cloudflare IPv6 edge, on networks with IPv6. */
    const val MCI_V6_CLEAN_PROFILE = "mci-v6-clean"

    /**
     * How much to move [profile] up (positive) or down on [firewall], or null to skip it there.
     * Added to the profile's measured success rate, which is 0 to 1.
     */
    fun bias(firewall: Firewall, profile: RecoveryProfile): Double? {
        val fragment = RecoveryProfile.Strategy.FRAGMENT in profile.networkConditions
        val ech = RecoveryProfile.Strategy.ECH in profile.networkConditions
        return when (firewall) {
            Firewall.IRANCELL -> when {
                // patterniha's F&F leads on Irancell, the copy at a clean Cloudflare IPv4 right behind it.
                profile.profileId == IRANCELL_FF_PROFILE -> 0.65
                profile.profileId == IRANCELL_FF_IP_PROFILE -> 0.6
                // F&F is this recipe with patterniha's exact cipher list, ALPN by transport and ECH removed, so it is not
                // tried twice on Irancell (that also keeps UAC's split inside revival's 10 recipes).
                profile.profileId == "bpb-fragment-original" -> null
                profile.profileId == IRANCELL_PROFILE -> 0.5
                // UAC's first Irancell route is its deep fragment (maxSplit 100).
                profile.profileId == IranHelloSplit.DEEP_PROFILE_ID -> 0.3
                // UAC tries the split ClientHello on Irancell after ECH: right behind the first Cloudflare ECH
                // recipe (0.55), ahead of the backup ECH resolvers, so it stays inside revival's 10 recipes.
                profile.profileId == IranHelloSplit.PROFILE_ID -> 0.04
                fragment -> 0.2
                else -> 0.0
            }
            Firewall.MCI -> when {
                // patterniha: Hamrah-e-Aval filters IPv6 by blacklist, so clean Chrome to a Cloudflare IPv6 edge goes first
                // (the profile itself is only offered on networks with IPv6).
                profile.profileId == MCI_V6_CLEAN_PROFILE -> 0.6
                // UAC SNI Spoofer's main Hamrah-e-Aval recipe: the one fragment that still passes there.
                profile.profileId == IranHelloSplit.PROFILE_ID -> 0.5
                // patterniha's oob (fork, IPv6 only): right after UAC's split, ahead of the ECH recipes.
                profile.profileId == MCI_V6_OOB_PROFILE -> 0.52
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
