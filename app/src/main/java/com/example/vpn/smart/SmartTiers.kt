package com.example.vpn.smart

import com.example.vpn.connections.BuiltinConnections.ConfigFreeMethod
import com.example.vpn.connectivity.NetworkFirewalls

/**
 * Build 181 (3.6): which built-in connections Smart Connect hands over to after a deadline, by how hard the network is
 * filtered. Only Smart Connect uses it: a manual pick runs only its own engine ([com.example.vpn.ManualEnginePick]), and
 * the Lantern-carried variants run only as their own labelled picks or here, inside Smart Connect.
 *
 * Basis: Build 180 Irancell test (files/engine-test-b180) and the serverless research (Build 181):
 *  - HEAVY (Irancell-type: Cloudflare SNI filtered, UDP data filtered): Lantern worked in 11 s, Psiphon over Lantern too;
 *    WARP only through Lantern; Snowflake connected (slowly); Serverless only partly (and it exits at the ISP address).
 *  - WHITELIST (only domestic sites answer): Snowflake through the Google-fronted AMP cache, then Psiphon's in-proxy
 *    conduits (Shir o Khorshid, Iranian conduits allowed in this tier), then Lantern.
 *  - NEAR_SHUTDOWN (nothing answers, not even domestic): the same short list, tried slowly, with a clear status.
 *
 * Demoted (moved to the end, never removed): WARP WireGuard / Gool / MASQUE-in-MASQUE / Aether → Tor when Cloudflare is
 * filtered; MASQUE HTTP/3 when UDP is filtered; direct Psiphon on Irancell (every direct and fronted dial failed in b180).
 * The IPv6 lane without IPv6 is dropped inside the Aether runner (Build 181 A2). Pure, so the rules are unit-tested.
 */
object SmartTiers {
    enum class Tier(val title: String) {
        NORMAL("normal"),
        HEAVY("heavy filtering"),
        WHITELIST("whitelist (only domestic sites answer)"),
        NEAR_SHUTDOWN("near shutdown (nothing answers)")
    }

    data class Signals(
        val cloudflareFiltered: Boolean = false,
        val udpFiltered: Boolean = false,
        val ipv6: Boolean? = null,
        val irancell: Boolean = false,
        val sniFiltered: Boolean = false,
        val internationalReachable: Boolean? = null,
        val domesticReachable: Boolean? = null,
        val dnsWorking: Boolean? = null
    )

    /** Between two rounds on a near-shutdown network: slow, so the phone is not kept busy on a dead link. */
    const val NEAR_SHUTDOWN_RETRY_MS = 5 * 60_000L

    fun signals(measured: NetworkCapabilityProfile?, networkKey: String?, cloudflareFiltered: Boolean): Signals = Signals(
        cloudflareFiltered = cloudflareFiltered,
        udpFiltered = measured?.udpAvailable == false || measured?.quicAvailable == false,
        ipv6 = measured?.ipv6Available,
        irancell = NetworkFirewalls.detect(networkKey) == NetworkFirewalls.Firewall.IRANCELL,
        sniFiltered = measured?.sniFiltered == true,
        internationalReachable = measured?.internationalReachable,
        domesticReachable = measured?.domesticReachable,
        dnsWorking = measured?.dnsWorking
    )

    fun tier(s: Signals): Tier = when {
        s.internationalReachable == false && (s.domesticReachable == false || s.dnsWorking == false) -> Tier.NEAR_SHUTDOWN
        s.internationalReachable == false && s.domesticReachable == true -> Tier.WHITELIST
        s.cloudflareFiltered || s.sniFiltered || s.irancell -> Tier.HEAVY
        else -> Tier.NORMAL
    }

    private val HEAVY = listOf(
        ConfigFreeMethod.RADIANCE,                // Lantern
        ConfigFreeMethod.PSIPHON_RADIANCE,        // over-Lantern chains
        ConfigFreeMethod.AETHER_LANTERN,
        ConfigFreeMethod.SHIROKHORSHID_RADIANCE,
        ConfigFreeMethod.AETHER_H2,               // short WARP MASQUE HTTP/2 with the SNI pre-check's name
        ConfigFreeMethod.TOR_SNOWFLAKE,           // AMP / SQS / Netlify / CDN77 rendezvous ladder
        ConfigFreeMethod.SHIROKHORSHID,
        ConfigFreeMethod.PSIPHON,
        ConfigFreeMethod.AETHER_WARP
    )

    private val WHITELIST = listOf(
        ConfigFreeMethod.TOR_SNOWFLAKE,           // AMP cache (Google-fronted) first
        ConfigFreeMethod.SHIROKHORSHID,           // Psiphon in-proxy conduits, Iranian conduits allowed in this tier
        ConfigFreeMethod.RADIANCE,
        ConfigFreeMethod.PSIPHON_RADIANCE,
        ConfigFreeMethod.PSIPHON
    )

    /** The ordered built-in ids for [s]; [normal] is the pre-Build-181 order, kept for unfiltered networks. Pure. */
    fun candidates(s: Signals, normal: List<String>): List<String> {
        val base = when (tier(s)) {
            Tier.NORMAL -> normal
            Tier.HEAVY -> HEAVY.map { it.id }
            Tier.WHITELIST, Tier.NEAR_SHUTDOWN -> WHITELIST.map { it.id }
        }
        return demote(base, s)
    }

    /** Ids that go last on this network (kept, in their order). Pure. */
    fun demoted(id: String, s: Signals): Boolean = when (id) {
        ConfigFreeMethod.AETHER_WG.id, ConfigFreeMethod.AETHER_GOOL.id, ConfigFreeMethod.AETHER_GOOL_CLASSIC.id,
        ConfigFreeMethod.AETHER_MIM.id, ConfigFreeMethod.AETHER_TOR.id -> s.cloudflareFiltered
        ConfigFreeMethod.AETHER_H3.id -> s.udpFiltered
        ConfigFreeMethod.PSIPHON.id -> s.irancell
        else -> false
    }

    fun demote(ids: List<String>, s: Signals): List<String> = ids.filterNot { demoted(it, s) } + ids.filter { demoted(it, s) }

    /** Iranian in-proxy conduits are allowed only on whitelist / near-shutdown networks (Build 181 3.3). Pure. */
    fun allowIranianConduits(tier: Tier): Boolean = tier == Tier.WHITELIST || tier == Tier.NEAR_SHUTDOWN

    /** The status line for a near-shutdown network after a round failed. Pure. */
    fun nearShutdownStatus(retryMs: Long = NEAR_SHUTDOWN_RETRY_MS): String =
        "This network answers almost nothing (international and domestic checks failed). " +
            "Smart Connect keeps trying Snowflake and Psiphon conduits slowly, about every ${retryMs / 60_000} min."
}
