package com.example.panels.servers

/**
 * Pure admission policy for a future DNS gateway. Only protocols with a confirmed UDP DNS wire
 * interface may participate; an engine's undocumented inner socket must never be guessed.
 * The gateway itself is NOT installed by this class.
 */
object DnsGatewayCompatibility {
    data class Route(val engine: String, val zone: String, val upstream: String)

    data class Plan(val routes: List<Route>, val blockers: List<String>) {
        val eligibleForPrototype: Boolean get() = blockers.isEmpty()
    }

    private val dnsEngines = setOf(ServerToolCatalog.DNSTT, ServerToolCatalog.SOAR, ServerToolCatalog.COTTEN)
    private val zonePattern = Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+$")
    private val loopbackTarget = Regex("^127\\.0\\.0\\.1:(?:[1-9][0-9]{0,4})$")

    fun plan(routes: List<Route>, facts: ServerFacts?, now: Long, checkedAt: Long): Plan {
        val blockers = mutableListOf<String>()
        if (routes.isEmpty()) blockers += "No DNS tunnel routes requested."
        if (facts == null || !facts.socketInspectionAvailable || checkedAt <= 0 ||
            now < checkedAt || now - checkedAt > 60_000L) {
            blockers += "Authenticated socket inspection is missing or older than 60 seconds."
        }
        val normalized = routes.map { it.copy(zone = it.zone.trim().lowercase().trimEnd('.')) }
        if (facts != null && 53 in facts.udpPorts) {
            blockers += "Public UDP/53 is already occupied; a gateway must not replace it."
        }
        for (route in normalized) {
            if (route.engine !in dnsEngines)
                blockers += "${route.engine} does not have a supported DNS tunnel contract."
            if (!zonePattern.matches(route.zone) || route.zone.split('.').any {
                it.length > 63 || it.startsWith('-') || it.endsWith('-')
            }) blockers += "Invalid authoritative domain suffix for ${route.engine}."
            if (!loopbackTarget.matches(route.upstream) ||
                route.upstream.substringAfter(':').toIntOrNull() !in 1..65535)
                blockers += "DNS gateway upstream must be a valid loopback UDP endpoint."
        }
        normalized.forEachIndexed { i, a ->
            normalized.drop(i + 1).forEach { b ->
                if (a.zone == b.zone || a.zone.endsWith("." + b.zone) ||
                    b.zone.endsWith("." + a.zone)) {
                    blockers += "Overlapping authoritative DNS suffixes require explicit routing validation: ${a.zone}, ${b.zone}."
                }
                if (a.upstream == b.upstream && a.engine != b.engine) {
                    blockers += "Independent DNS engines must not claim one backend socket."
                }
            }
        }
        // We don't know whether the corresponding server binaries support binding only loopback
        // on the requested upstream ports; that must be established before deployment.
        return Plan(normalized, blockers.distinct())
    }

    fun match(name: String, routes: List<Route>): Route? {
        val fqdn = name.trim().lowercase().trimEnd('.')
        return routes.filter {
            val suffix = it.zone.trim().lowercase().trimEnd('.')
            fqdn == suffix || fqdn.endsWith(".$suffix")
        }.maxByOrNull { it.zone.length }
    }
}
