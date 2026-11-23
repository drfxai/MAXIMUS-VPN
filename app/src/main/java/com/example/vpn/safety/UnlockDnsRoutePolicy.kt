package com.example.vpn.safety

/**
 * The one Maximus TUN that does not capture everything: Unlock mode's DNS-only interface.
 *
 * Unlock mode changes only which resolver the phone uses. Its interface takes the same private address and
 * in-tunnel DNS address as every other Maximus TUN ([VpnRoutePolicy]), but routes only that DNS address
 * into the TUN; all other IPv4 and all IPv6 traffic stays on the phone's own network ("allowFamily" for
 * IPv6, which would otherwise be blocked because the interface has no IPv6 address). Apps on the VPN
 * network ask [DNS_SERVER], the app answers from the chosen Unlock server over a protected socket.
 *
 * It is never used by the tunnel modes, and it never stands in for the traffic-blocking interface: Unlock
 * mode does not hold the traffic block (see MaximusVpnSupervisor.Release.UNLOCK_MODE).
 */
object UnlockDnsRoutePolicy {
    val ADDRESS: VpnRoutePolicy.Address = VpnRoutePolicy.ADDRESSES.first { !it.address.contains(':') }
    const val DNS_SERVER = VpnRoutePolicy.DNS_SERVER
    /** Only the in-tunnel DNS address is routed into the TUN. */
    val ROUTES = listOf(VpnRoutePolicy.Address(DNS_SERVER, 32))
    /** Large enough for a 4 KiB DNS answer (EDNS) plus IPv4/UDP headers in one packet. */
    const val MTU = 4200

    /** True when [routes] send only the DNS address into the TUN (never a default route of either family). */
    fun dnsOnly(routes: List<VpnRoutePolicy.Address>): Boolean =
        routes.isNotEmpty() && routes.all { it.address == DNS_SERVER && it.prefix == 32 } &&
            !VpnRoutePolicy.capturesEverything(routes)
}
