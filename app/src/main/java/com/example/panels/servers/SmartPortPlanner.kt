package com.example.panels.servers

/**
 * Read-only, protocol-aware port plan. A proposal is NOT a socket reservation and cannot be used
 * as evidence of real reachability. The installer must obtain a second authenticated snapshot,
 * lock per-VPS changes, and bind services before treating it as allocated.
 */
object SmartPortPlanner {
    enum class Protocol { TCP, UDP }

    data class Binding(
        val serviceId: String,
        val protocol: Protocol,
        val port: Int,
        /** "*" means all interfaces; exact IPv4/IPv6 address means one particular interface. */
        val address: String = "*"
    ) {
        init {
            require(serviceId.isNotBlank())
            require(port in 1..65535)
            require(address.isNotBlank())
        }
    }

    data class Result(
        val proposed: List<Binding>,
        val conflicts: List<String>,
        val verifiedAt: Long
    ) { val eligible: Boolean get() = conflicts.isEmpty() }

    fun propose(facts: ServerFacts?, inspectedAt: Long, requested: List<Binding>,
                now: Long = System.currentTimeMillis()): Result {
        val conflicts = mutableListOf<String>()
        if (facts == null || !facts.socketInspectionAvailable || inspectedAt <= 0 ||
            now < inspectedAt || now - inspectedAt > 60_000L) {
            conflicts += "Fresh TCP/UDP socket inventory over pinned SSH is required."
            return Result(requested, conflicts, inspectedAt)
        }
        val tcp = facts.tcpPorts
        val udp = facts.udpPorts
        for (b in requested) {
            // The current ServerFacts inventory is aggregated across all interfaces. Treat any
            // listening socket as a conflict, rather than guessing that another IP can share it.
            if (b.port in (if (b.protocol == Protocol.TCP) tcp else udp)) {
                conflicts += "${b.protocol} ${b.port} already has a listener; ${b.serviceId} cannot claim it."
            }
            if (b.port == 22 && b.protocol == Protocol.TCP) {
                conflicts += "TCP 22 is reserved for SSH; changing SSH must be an independent reviewed operation."
            }
        }
        requested.forEachIndexed { index, current ->
            requested.drop(index + 1).forEach { other ->
                if (overlaps(current, other)) {
                    conflicts += "${current.serviceId} and ${other.serviceId} both request ${current.protocol}:${current.port} on overlapping interfaces."
                }
            }
        }
        return Result(requested, conflicts.distinct(), inspectedAt)
    }

    fun overlaps(a: Binding, b: Binding): Boolean =
        a.protocol == b.protocol && a.port == b.port &&
            (a.address == "*" || b.address == "*" || a.address == b.address)

    /** These are only the public listener requirements confirmed by existing installers. */
    fun knownPublicBindings(toolId: String): List<Binding> = when (toolId) {
        ServerToolCatalog.DNSTT, ServerToolCatalog.SOAR -> listOf(
            Binding(toolId, Protocol.UDP, 53))
        ServerToolCatalog.COTTEN -> listOf(
            Binding(toolId, Protocol.UDP, 53),
            Binding(toolId, Protocol.TCP, 53))
        ServerToolCatalog.HIDDIFY -> listOf(
            Binding(toolId, Protocol.TCP, 80),
            Binding(toolId, Protocol.TCP, 443))
        else -> emptyList()
    }
}
