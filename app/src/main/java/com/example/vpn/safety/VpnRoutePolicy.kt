package com.example.vpn.safety

/**
 * What every Maximus TUN interface captures, the working tunnel and the traffic-blocking one alike. Both
 * address families are always routed into the TUN, so IPv6 can never leave beside the VPN (an engine
 * that cannot carry IPv6 drops it inside the tunnel), and the only DNS server apps see is inside it.
 */
object VpnRoutePolicy {
    data class Address(val address: String, val prefix: Int)

    val ADDRESSES = listOf(Address("172.19.0.1", 30), Address("fdfe:dcba:9876::1", 126))
    val ROUTES = listOf(Address("0.0.0.0", 0), Address("::", 0))
    const val DNS_SERVER = "172.19.0.2"

    /** True when [routes] send every IPv4 and every IPv6 destination into the tunnel. */
    fun capturesEverything(routes: List<Address>): Boolean =
        routes.any { it.address == "0.0.0.0" && it.prefix == 0 } && routes.any { it.address == "::" && it.prefix == 0 }
}
