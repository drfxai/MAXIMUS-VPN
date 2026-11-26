package com.example.vpn.connectivity

import java.net.Inet6Address
import java.net.InetAddress

/**
 * What a recovery run knows about the network it runs on. Some recipes only make sense on one kind of
 * firewall, on a network with IPv6, or on the patterniha Xray fork; [RecoveryProfile.allowedIn] keeps them out
 * everywhere else. Anything not known counts as "no": a gated recipe is never tried on a guess.
 *
 * Recipes are generated automatically only by Smart Connect's recovery stages (never for a manual pick, whose
 * exit is locked) and by the user's own LAB "Revive configs" and FIX BPB buttons. A saved config is never changed.
 */
data class RecoveryContext(
    /** The firewall type (from the network key, or the user's choice in LAB); null when not known. */
    val firewall: NetworkFirewalls.Firewall? = null,
    /** True when the phone's own network has a global IPv6 address; null when not known (treated as no). */
    val ipv6: Boolean? = null,
    /** True when the patterniha Xray fork (the Serverless program) is in this build and can run a candidate. */
    val forkAvailable: Boolean = false
) {
    companion object {
        /** Nothing known: every gated recipe stays out. */
        val UNKNOWN = RecoveryContext()

        /**
         * True when one of [addresses] is a global IPv6 address: not loopback, link-local (fe80::/10), site-local
         * (fec0::/10), unique-local (fc00::/7), multicast or the documentation range. Pure, for the phone's link addresses.
         */
        fun hasGlobalIpv6(addresses: List<InetAddress>): Boolean = addresses.any { a ->
            a is Inet6Address && !a.isLoopbackAddress && !a.isLinkLocalAddress && !a.isSiteLocalAddress &&
                !a.isMulticastAddress && !a.isAnyLocalAddress && (a.address[0].toInt() and 0xfe) != 0xfc &&
                !(a.address[0].toInt() == 0x20 && a.address[1].toInt() == 0x01 && (a.address[2].toInt() and 0xff) == 0x0d &&
                    (a.address[3].toInt() and 0xff) == 0xb8)
        }
    }
}
