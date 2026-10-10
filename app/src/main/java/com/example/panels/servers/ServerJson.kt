package com.example.panels.servers

import com.example.panels.ManagedPanel
import com.example.panels.PanelType
import org.json.JSONArray
import org.json.JSONObject

/** JSON form of the server list kept (encrypted) by [ServerStore]. Unknown fields are ignored. */
object ServerJson {
    fun encode(servers: List<ManagedServer>): String = JSONArray().apply {
        servers.forEach { s ->
            put(JSONObject()
                .put("id", s.id).put("name", s.name).put("host", s.host).put("sshPort", s.sshPort)
                .put("username", s.username).put("location", s.location.name)
                .put("hostKeySha256", s.hostKeySha256).put("password", s.password)
                .put("privateKey", s.privateKey).put("publicKey", s.publicKey)
                .put("passwordLoginOff", s.passwordLoginOff)
                .put("checkedAt", s.checkedAt).put("createdAt", s.createdAt)
                .put("tools", JSONArray().apply { s.tools.forEach { put(tool(it)) } })
                .apply { s.facts?.let { put("facts", facts(it)) } })
        }
    }.toString()

    fun decode(text: String): List<ManagedServer> {
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                val tools = o.optJSONArray("tools") ?: JSONArray()
                ManagedServer(
                    id = o.getString("id"),
                    name = o.optString("name"),
                    host = o.getString("host"),
                    sshPort = o.optInt("sshPort", 22),
                    username = o.optString("username", "root"),
                    location = runCatching { ServerLocation.valueOf(o.optString("location")) }.getOrDefault(ServerLocation.ABROAD),
                    hostKeySha256 = o.optString("hostKeySha256"),
                    password = o.optString("password"),
                    privateKey = o.optString("privateKey"),
                    publicKey = o.optString("publicKey"),
                    passwordLoginOff = o.optBoolean("passwordLoginOff"),
                    tools = (0 until tools.length()).map { tool(tools.getJSONObject(it)) },
                    facts = o.optJSONObject("facts")?.let(::facts),
                    checkedAt = o.optLong("checkedAt"),
                    createdAt = o.optLong("createdAt")
                )
            }.getOrNull()
        }
    }

    private fun tool(t: InstalledTool) = JSONObject()
        .put("id", t.id).put("installedAt", t.installedAt)
        .apply {
            t.port?.let { put("port", it) }
            t.profileId?.let { put("profileId", it) }
            t.panelId?.let { put("panelId", it) }
            put("settings", JSONObject(t.settings))
        }

    private fun tool(o: JSONObject): InstalledTool {
        val s = o.optJSONObject("settings") ?: JSONObject()
        return InstalledTool(
            id = o.getString("id"),
            installedAt = o.optLong("installedAt"),
            port = if (o.has("port")) o.optInt("port") else null,
            settings = s.keys().asSequence().associateWith { s.optString(it) },
            profileId = o.optString("profileId").ifBlank { null },
            panelId = o.optString("panelId").ifBlank { null }
        )
    }

    private fun facts(f: ServerFacts) = JSONObject()
        .put("osName", f.osName).put("osId", f.osId).put("osVersion", f.osVersion).put("arch", f.arch)
        .put("kernel", f.kernel).put("cpus", f.cpus).put("ramMb", f.ramMb).put("ramAvailableMb", f.ramAvailableMb)
        .put("swapMb", f.swapMb).put("diskMb", f.diskMb).put("diskFreeMb", f.diskFreeMb).put("uptimeSec", f.uptimeSec)
        .put("load1", f.load1).put("root", f.root).put("sudo", f.sudo).put("systemd", f.systemd)
        .put("packageManager", f.packageManager)
        .put("tcpPorts", JSONArray(f.tcpPorts.sorted())).put("udpPorts", JSONArray(f.udpPorts.sorted()))
        .put("congestionControl", f.congestionControl).put("firewall", f.firewall).put("fail2ban", f.fail2ban)
        .put("autoUpdates", f.autoUpdates).put("sshdDropIns", f.sshdDropIns)
        .apply { f.sshPasswordLogin?.let { put("sshPasswordLogin", it) } }
        .put("services", JSONObject(f.services))

    private fun facts(o: JSONObject): ServerFacts {
        fun ints(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optInt(it) }.toSet() } ?: emptySet()
        val svc = o.optJSONObject("services") ?: JSONObject()
        return ServerFacts(
            osName = o.optString("osName"), osId = o.optString("osId"), osVersion = o.optString("osVersion"),
            arch = o.optString("arch"), kernel = o.optString("kernel"), cpus = o.optInt("cpus"),
            ramMb = o.optLong("ramMb"), ramAvailableMb = o.optLong("ramAvailableMb"), swapMb = o.optLong("swapMb"),
            diskMb = o.optLong("diskMb"), diskFreeMb = o.optLong("diskFreeMb"), uptimeSec = o.optLong("uptimeSec"),
            load1 = o.optDouble("load1", 0.0), root = o.optBoolean("root"), sudo = o.optBoolean("sudo"),
            systemd = o.optBoolean("systemd"), packageManager = o.optString("packageManager"),
            tcpPorts = ints("tcpPorts"), udpPorts = ints("udpPorts"),
            congestionControl = o.optString("congestionControl"), firewall = o.optString("firewall"),
            fail2ban = o.optBoolean("fail2ban"), autoUpdates = o.optBoolean("autoUpdates"),
            sshPasswordLogin = if (o.has("sshPasswordLogin")) o.optBoolean("sshPasswordLogin") else null,
            sshdDropIns = o.optBoolean("sshdDropIns"),
            services = svc.keys().asSequence().associateWith { svc.optString(it) }
        )
    }
}

/** 3X-UI panels installed before servers existed, as servers abroad that need their password again. */
object ServerMigration {
    /** One server per 3X-UI host, carrying the confirmed host key; the SSH password was never stored. */
    fun fromPanels(panels: List<ManagedPanel>): List<ManagedServer> =
        panels.filter { it.type == PanelType.XUI && it.host.isNotBlank() }
            .groupBy { it.host.lowercase() to it.sshPort }
            .map { (_, group) ->
                val p = group.maxBy { it.createdAt }
                ManagedServer(
                    name = p.host,
                    host = p.host,
                    sshPort = p.sshPort,
                    location = ServerLocation.ABROAD,
                    hostKeySha256 = p.hostKeySha256,
                    tools = listOf(InstalledTool(ServerToolCatalog.XUI, installedAt = p.createdAt, panelId = p.id)),
                    createdAt = p.createdAt
                )
            }
}
