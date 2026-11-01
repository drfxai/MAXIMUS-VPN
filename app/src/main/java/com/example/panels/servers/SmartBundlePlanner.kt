package com.example.panels.servers

/**
 * Conservative admission for one-confirmation sequential VPS installation.
 *
 * This is NOT an atomic transaction: previously completed tools are preserved when
 * any later tool fails. Every tool must pass a fresh SSH preflight before execution.
 * In this first supported bundle, the only combinations are one DNS engine plus
 * 3X-UI on a VPS abroad; all other combinations stay in the individual wizards.
 */
object SmartBundlePlanner {
    private val dnsEngines = setOf(ServerToolCatalog.DNSTT, ServerToolCatalog.SOAR, ServerToolCatalog.COTTEN)

    data class Plan(val order: List<String>, val blockers: List<String>) {
        val eligible: Boolean get() = blockers.isEmpty() && order.size == 2
    }

    fun review(
        inspection: SmartManagerPlanner.Inspection,
        selected: Set<String>,
        domain: String,
        tunnelLabel: String
    ): Plan {
        val blockers = SmartManagerPlanner.bundleBlockers(inspection, selected).toMutableList()
        val dns = selected.intersect(dnsEngines)
        if (selected.size != 2 || ServerToolCatalog.XUI !in selected || dns.size != 1) {
            blockers += "A reviewed batch supports exactly 3X-UI and one DNS tunnel. Use the individual wizard for other tools."
        }
        if (dns.size == 1) DnsDelegation.problem(domain, tunnelLabel)?.let { blockers += it }
        // DNS first: the server's public port 53 is checked again before the panel
        // is installed. Later failures never delete or overwrite an earlier tool.
        val order = if (blockers.isEmpty()) listOf(dns.single(), ServerToolCatalog.XUI) else emptyList()
        return Plan(order, blockers.distinct())
    }
}
