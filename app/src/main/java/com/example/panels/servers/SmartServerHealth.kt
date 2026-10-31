package com.example.panels.servers

/**
 * Read-only state evaluation for Smart Manager. Monitoring must never equate an open socket or an
 * active systemd service with a verified end-to-end VPN tunnel.
 */
object SmartServerHealth {
    enum class Level { UNKNOWN, INSPECTION_REQUIRED, SERVICE_FAILURE, RESOURCE_PRESSURE, SERVICES_ACTIVE_UNVERIFIED }

    data class Summary(val level: Level, val concerns: List<String>, val inspectedAt: Long)

    fun evaluate(server: ManagedServer, now: Long = System.currentTimeMillis()): Summary {
        val f = server.facts ?: return Summary(Level.UNKNOWN, listOf("No authenticated server inspection."), 0L)
        if (server.checkedAt <= 0 || now < server.checkedAt || now - server.checkedAt > 60_000L)
            return Summary(Level.INSPECTION_REQUIRED,
                listOf("Server state is stale; run another pinned-SSH inspection."), server.checkedAt)
        val concerns = mutableListOf<String>()
        val managedUnits = server.tools.flatMap { tool ->
            when (tool.id) {
                PrivatePsiphonServerScripts.TOOL_ID -> listOf(PrivatePsiphonServerScripts.UNIT)
                else -> ToolScripts.UNITS[tool.id].orEmpty()
            }
        }.distinct()
        val failed = managedUnits.filter { f.services[it] == "failed" || f.services[it] == "inactive" }
        if (failed.isNotEmpty()) concerns += "Managed services not active: ${failed.joinToString()}."
        if (f.diskMb > 0 && f.diskFreeMb < 512) concerns += "Less than 512 MB free on root filesystem."
        if (f.ramMb > 0 && f.ramAvailableMb < 128) concerns += "Less than 128 MB available RAM."
        if (!f.socketInspectionAvailable) concerns += "TCP/UDP inventory unavailable."
        val level = when {
            failed.isNotEmpty() -> Level.SERVICE_FAILURE
            concerns.isNotEmpty() -> Level.RESOURCE_PRESSURE
            else -> Level.SERVICES_ACTIVE_UNVERIFIED
        }
        return Summary(level, concerns.ifEmpty {
            listOf("No service/resource alarms in the last inspection. Actual tunnel traffic is not verified.")
        }, server.checkedAt)
    }
}
