package com.example.panels.cloudflare

import com.example.panels.servers.DnsDelegation

/** Validated, DNS-only Cloudflare records for DNSTT's authoritative delegation. */
object DnsTunnelProvisioning {
    data class Target(val zone: SmartDomains.Zone, val record: SmartDomains.Record)

    fun targets(records: List<DnsDelegation.Record>, zones: List<SmartDomains.Zone>): List<Target> {
        require(records.isNotEmpty() && records.size % 2 == 0) { "No valid DNS delegation records" }
        val active = zones.filter { it.status == "active" }
        require(active.isNotEmpty()) { "No active Cloudflare domain is available to this token" }
        require(records.size == records.distinctBy { it.type to it.name.lowercase() }.size) { "Duplicate DNS records" }
        return records.map { r ->
            val name = SmartDomains.normalize(r.name)
            require(SmartDomains.isHost(name)) { "Invalid DNS record name" }
            val zone = SmartDomains.zoneFor(name, active)
                ?: error("Cloudflare token cannot manage the domain for " + name)
            val value = r.value.trim().trimEnd('.').lowercase()
            val want = when (r.type) {
                "A", "AAAA" -> SmartDomains.desired(name, value, SmartDomains.Use.DNS_TUNNEL)
                    .also { require(it.type == r.type) { "Server address is not an " + r.type + " address" } }
                "NS" -> {
                    require(SmartDomains.isHost(value)) { "Invalid name server name" }
                    SmartDomains.Record(null, name, "NS", value, proxied = false)
                }
                else -> error("Unsupported DNS delegation record type")
            }
            Target(zone, want)
        }
    }

    /**
     * A nameserver may have both A and AAAA, but NS delegation must not share its label with
     * another record type. Never change the proxy state of existing user-owned DNS records.
     */
    fun decide(want: SmartDomains.Record, existing: List<SmartDomains.Record>): SmartDomains.Action {
        val atName = existing.filter { SmartDomains.normalize(it.name) == want.name }
        val incompatible = atName.firstOrNull {
            if (want.type == "NS") it.type != "NS" else it.type !in setOf("A", "AAAA")
        }
        if (incompatible != null) return SmartDomains.Action.Conflict(incompatible,
            "An incompatible DNS record already uses " + want.name)
        val planned = SmartDomains.plan(want, atName)
        return if (planned is SmartDomains.Action.Update) {
            SmartDomains.Action.Conflict(atName.first { it.id == planned.id },
                "Existing record must be reviewed manually before changing proxy mode")
        } else planned
    }
}
