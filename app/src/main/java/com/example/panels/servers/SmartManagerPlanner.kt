package com.example.panels.servers

/**
 * Read-only admission control for Smart Manager. This does NOT perform provisioning.
 * An eligible recommendation only means that the existing, user-approved single-tool
 * wizard may be opened; a new remote inspection must be performed before any mutation.
 */
object SmartManagerPlanner {
    enum class Status { INSTALLED, READY_FOR_WIZARD, BLOCKED, NEEDS_INSPECTION, UNVERIFIED }
    data class Candidate(
        val id: String,
        val title: String,
        val status: Status,
        val reason: String,
        val tcpPorts: Set<Int> = emptySet(),
        val udpPorts: Set<Int> = emptySet()
    )
    data class Inspection(
        val serverId: String,
        val checkedAt: Long,
        val candidates: List<Candidate>,
        val limitations: List<String>
    ) {
        fun candidate(id: String): Candidate? = candidates.firstOrNull { it.id == id }
    }

    private val dnsEngines = setOf(ServerToolCatalog.DNSTT, ServerToolCatalog.SOAR, ServerToolCatalog.COTTEN)
    const val PRIVATE_PSIPHON = "private-psiphon"

    fun inspect(server: ManagedServer, now: Long = System.currentTimeMillis()): Inspection {
        val facts = server.facts
        val fresh = facts != null && server.checkedAt > 0 &&
            now >= server.checkedAt && now - server.checkedAt <= 60_000L
        val existingDns = server.tools.map { it.id }.filter { it in dnsEngines }
        val supported = listOf(ServerToolCatalog.XUI, ServerToolCatalog.DNSTT,
            ServerToolCatalog.SOAR, ServerToolCatalog.COTTEN, ServerToolCatalog.HIDDIFY)
        val candidates = supported.map { id ->
            val tool = requireNotNull(ServerToolCatalog.byId(id))
            val existing = server.tool(id) != null ||
                (id == ServerToolCatalog.XUI && facts?.xuiInstalled == true) ||
                (id == ServerToolCatalog.HIDDIFY && facts?.hiddifyInstalled == true)
            val tcp = when (id) {
                ServerToolCatalog.HIDDIFY -> setOf(80, 443)
                ServerToolCatalog.COTTEN -> setOf(53)
                else -> emptySet()
            }
            val udp = if (id in dnsEngines) setOf(53) else emptySet()
            val condition = when {
                existing -> Status.INSTALLED to "Detected on this server; ownership and health require verification."
                !fresh -> Status.NEEDS_INSPECTION to "Refresh authenticated SSH inspection before planning."
                facts == null -> Status.NEEDS_INSPECTION to "No server facts are available."
                !facts.socketInspectionAvailable -> Status.BLOCKED to "Socket inventory unavailable (ss/iproute2); cannot plan ports safely."
                facts.blocker() != null -> Status.BLOCKED to facts.blocker()!!
                !tool.fitsOn(server.location) -> Status.BLOCKED to "This engine requires an exit VPS abroad."
                tool.aptOnly && !facts.usesApt -> Status.BLOCKED to "Ubuntu/Debian apt is required."
                id == ServerToolCatalog.HIDDIFY && (server.tools.isNotEmpty() || facts.xuiInstalled || facts.tunnelInstalled) ->
                    Status.BLOCKED to "Hiddify needs a dedicated VPS; existing tools must be preserved."
                id == ServerToolCatalog.XUI && facts.hiddifyInstalled ->
                    Status.BLOCKED to "Hiddify already controls this VPS."
                id in dnsEngines && existingDns.isNotEmpty() ->
                    Status.BLOCKED to "DNS listener is already assigned to ${existingDns.joinToString()}; multiplexing is unverified."
                id in dnsEngines && (53 in facts.udpPorts || (id == ServerToolCatalog.COTTEN && 53 in facts.tcpPorts)) ->
                    Status.BLOCKED to "Public DNS port 53 is occupied. Never replace the listener automatically."
                id == ServerToolCatalog.HIDDIFY && (80 in facts.tcpPorts || 443 in facts.tcpPorts) ->
                    Status.BLOCKED to "Hiddify requires free TCP 80 and 443."
                else -> Status.READY_FOR_WIZARD to "Eligible for the existing per-engine approval wizard; deployment and reachability are not verified."
            }
            Candidate(id, tool.title, condition.first, condition.second, tcp, udp)
        } + Candidate(
            PRIVATE_PSIPHON, "Private Psiphon",
            Status.UNVERIFIED,
            if (facts?.psiphonInstalled == true) "psiphond detected, but client activation and tunnel transfer are unverified." else "Blocked until pinned psiphond provisioning, client bootstrap and real tunnel transfer are validated."
        )
        return Inspection(server.id, server.checkedAt, candidates, listOf(
            "DNS gateway multiplexing is NOT certified; DNSTT, SOAR and Cotten require separate listeners or verified isolation.",
            "Hiddify must remain on a dedicated VPS.",
            "No installation, Cloudflare mutation or node activation is performed by an inspection.",
            "A listening port and a generated profile do not prove end-to-end VPN connectivity."
        ))
    }

    /**
     * Check a proposed bundle conservatively. The current implementation deliberately refuses
     * automatic multi-engine execution; only independently approved existing wizards may run.
     */
    fun bundleBlockers(inspection: Inspection, requested: Set<String>): List<String> = buildList {
        if (requested.isEmpty()) add("Select at least one engine.")
        requested.forEach { id ->
            val candidate = inspection.candidate(id)
            if (candidate == null) add("Unknown engine: $id")
            else if (candidate.status != Status.READY_FOR_WIZARD)
                add("${candidate.title}: ${candidate.reason}")
        }
        if ((requested intersect dnsEngines).size > 1)
            add("Multiple DNS engines cannot share public port 53 without validated routing or separate IPs.")
        if (ServerToolCatalog.HIDDIFY in requested && requested.size > 1)
            add("Hiddify must run on its own dedicated VPS.")
    }
}
