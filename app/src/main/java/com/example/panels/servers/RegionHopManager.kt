package com.example.panels.servers

import com.example.vpn.sidecar.PsiphonSidecar
import org.json.JSONObject
import java.util.UUID

/** Managed RegionHop is optional after 3X-UI. Its panel/SOCKS ports remain loopback-only. */
class RegionHopManager(
    private val asset: (String) -> ByteArray,
    private val connect: (ManagedServer) -> RemoteShell = { SshShell.open(it) }
) {
    data class Location(val name: String, val region: String, val port: Int, val outbound: String, val state: String = "unknown")
    data class Snapshot(val installed: Boolean, val version: String = "", val locations: List<Location> = emptyList(),
        val detail: String = "", val statuses: String = "")
    fun install(server: ManagedServer, config: String, progress: ActionProgress): Snapshot {
        require(server.username == "root" && server.canSignIn) { "Pinned root SSH is required." }
        PsiphonSidecar.validateNetworkSettings(config)?.let { error(it) }
        connect(server).use { shell ->
            val facts = shell.run(ServerFacts.SCRIPT, 60_000)
            check(facts.exitCode == 0 && facts.output.lineSequence().any { it.trim() == ServerFacts.BEGIN }) { "Fresh SSH inspection failed" }
            val parsed = ServerFacts.parse(facts.output)
            check(parsed.xuiInstalled && parsed.systemd && parsed.socketInspectionAvailable) { "Install 3X-UI and inspect this systemd VPS first." }
            val dir = stage(shell)
            try {
                for (name in listOf("install.sh", "control.py", "pins.json")) shell.upload(asset("regionhop/$name"), "$dir/$name")
                shell.upload(config.toByteArray(), "$dir/Psiphon.config")
                shell.runTool(ToolScript("bash '$dir/install.sh'", listOf("Verify pinned RegionHop", "Install loopback services")), progress)
            } finally { shell.run("rm -rf -- '$dir'", 20_000) }
        }
        return action(server, "status")
    }
    fun uninstall(server: ManagedServer, progress: ActionProgress): Snapshot {
        action(server, "prepare-remove")
        connect(server).use { shell ->
            shell.runTool(ToolScript("""
                bash <<'MXREMOVE'
                set -euo pipefail
                test -f /opt/maximus-regionhop/control.json
                source /opt/maximus-regionhop/source/install.sh
                uninstall_all <<<YES
                rm -rf -- /opt/maximus-regionhop
                MXREMOVE
            """.trimIndent(), listOf("Remove linked outbounds", "Remove managed RegionHop")), progress)
        }
        return Snapshot(false, detail = "Managed RegionHop removed. 3X-UI and imported phone settings were preserved.")
    }

    fun action(server: ManagedServer, operation: String, data: JSONObject = JSONObject()): Snapshot {
        require(server.username == "root" && server.canSignIn) { "Pinned root SSH is required." }
        require(operation in setOf("status", "add", "start", "stop", "restart", "remove", "test", "sync", "prepare-remove"))
        if (operation == "add") require(data.optString("region") in REGIONS)
        if (operation in setOf("start", "stop", "restart", "remove", "test")) require(validName(data.optString("name")))
        connect(server).use { shell ->
            if (operation == "status" && shell.run("test -f /opt/maximus-regionhop/control.json", 20_000).exitCode != 0)
                return Snapshot(false, detail = "Not installed by Maximus; existing RegionHop installations are preserved.")
            val dir = stage(shell)
            try {
                shell.upload(JSONObject().put("operation", operation).put("data", data).toString().toByteArray(), "$dir/request.json")
                val reply = shell.run(InstallerShellLock.wrap("python3 /opt/maximus-regionhop/control.py '$dir/request.json'"), 120_000)
                val json = runCatching { JSONObject(reply.output.trim()) }.getOrNull()
                    ?: error("RegionHop did not return a valid response; inspect the VPS service.")
                check(reply.exitCode == 0 && !json.has("error")) {
                    ServerLogSanitizer.redact(json.optString("error", "RegionHop did not confirm the operation.")).take(300)
                }
                if (operation == "prepare-remove") check(json.optBoolean("readyToRemove")) { "RegionHop cleanup was not confirmed." }
                val rows = json.optJSONArray("locations")
                val locations = (0 until (rows?.length() ?: 0)).map { i -> rows!!.getJSONObject(i).let {
                    Location(it.getString("name"), it.getString("region"), it.getInt("port"), it.getString("outbound"), it.optString("state", "unknown"))
                } }
                return Snapshot(json.optBoolean("installed"), json.optString("version"), locations,
                    if (json.has("test")) json.getJSONObject("test").let { result ->
                        if (result.optBoolean("ok")) "${data.optString("name")}: VPS proxy verified · ${result.optInt("latencyMs")} ms · exit ${result.optString("ip").ifBlank { "not measured" }}"
                        else "${data.optString("name")}: VPS proxy test failed · ${ServerLogSanitizer.redact(result.optString("error", "No verified response")).take(250)}"
                    } else "Server services inspected; phone tunnel traffic not tested.",
                    json.opt("statuses")?.toString().orEmpty())
            } finally { shell.run("rm -rf -- '$dir'", 20_000) }
        }
    }
    private fun stage(shell: RemoteShell): String {
        val dir = "/tmp/maximus-regionhop-${UUID.randomUUID().toString().replace("-", "")}"
        check(shell.run("umask 077; mkdir -m 700 '$dir'", 20_000).exitCode == 0)
        return dir
    }
    companion object {
        const val ID = "regionhop"
        val REGIONS = "AT BE BG CZ DK EE FI FR DE IE IT LT NL NO PL RO RS ES SE CH GB IN ID JP SG CA US BR AU".split(' ')
        fun validName(name: String) = name.matches(Regex("[a-z0-9][a-z0-9-]{1,30}"))
    }
}
