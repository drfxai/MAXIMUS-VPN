package com.example.panels.servers

/**
 * Pure helpers behind the Smart Manager screen and its ViewModel actions. Kept free of
 * Android types so the rules are unit-tested.
 */
object SmartManagerState {
    private val unresolved = setOf(
        SmartOperation.Status.RUNNING, SmartOperation.Status.VERIFYING, SmartOperation.Status.UNCERTAIN)

    /**
     * Applies a read-only SSH inspection taken from [snapshot] onto the server as it is NOW.
     * An inspection runs for seconds; meanwhile an install may have added a tool, the user may
     * have edited the sign-in, or removed the VPS. Saving the snapshot wholesale would drop that
     * tool record or resurrect a removed server. Returns null when the inspection no longer
     * describes [current] (removed, or another host/port/user/host key).
     */
    fun mergeInspection(current: ManagedServer?, snapshot: ManagedServer, checked: ManagedServer): ManagedServer? {
        if (current == null || current.id != checked.id) return null
        if (current.host != snapshot.host || current.sshPort != snapshot.sshPort ||
            current.username != snapshot.username || current.hostKeySha256 != snapshot.hostKeySha256) return null
        // Never move the inspection time backwards (a newer inspection already landed).
        if (current.checkedAt > checked.checkedAt) return current
        return current.copy(facts = checked.facts, checkedAt = checked.checkedAt)
    }

    /**
     * The journal the screen shows for one VPS: every unresolved operation (they block all
     * further installs until reconciled, so they must never scroll out of view) plus the
     * latest [recent] entries, newest first.
     */
    fun operationsFor(all: List<SmartOperation>, serverId: String, recent: Int = 10): List<SmartOperation> {
        val mine = all.filter { it.serverId == serverId }
        val latest = mine.takeLast(recent)
        val blocking = mine.filter { it.status in unresolved && it !in latest }
        return (blocking + latest).reversed()
    }

    /** systemd units a managed tool runs as, including the private Psiphon service. */
    fun unitsFor(toolId: String): List<String>? = ToolScripts.UNITS[toolId]
        ?: if (toolId == PrivatePsiphonServerScripts.TOOL_ID) listOf(PrivatePsiphonServerScripts.UNIT) else null

    /** Tools whose journal the Logs tab can read; others would only fail with "No logs for …". */
    fun hasLogs(toolId: String): Boolean = toolId == ServerToolCatalog.HIDDIFY || unitsFor(toolId) != null

    /** True only when every unit of [toolId] is active; DNSTT needs both its socks and dnstt units. */
    fun allUnitsActive(facts: ServerFacts?, toolId: String): Boolean {
        val units = unitsFor(toolId)
        return facts != null && !units.isNullOrEmpty() && units.all { facts.active(it) }
    }

    /**
     * Whether a public listener holds port 53. The local systemd-resolved stub (127.0.0.53)
     * is not a conflict, matching [SmartPortPlanner]. Null when the inventory is unavailable.
     */
    fun publicPort53Occupied(facts: ServerFacts?): Boolean? {
        if (facts == null || !facts.socketInspectionAvailable) return null
        val udp = facts.publicUdpPorts ?: facts.udpPorts
        val tcp = facts.publicTcpPorts ?: facts.tcpPorts
        return 53 in udp || 53 in tcp
    }
}
