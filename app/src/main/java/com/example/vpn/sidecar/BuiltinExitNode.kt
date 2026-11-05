package com.example.vpn.sidecar

import com.example.data.model.AppSettings
import com.example.data.model.ProfileType
import com.example.data.model.VlessProfile
import com.example.vpn.engine.RuntimeCapabilities
import com.example.vpn.safety.DnsResolvers
import com.example.vpn.tunnel.ProxyDnsTransport
import com.example.xray.XrayConfigBuilder
import com.example.xray.XrayConfigParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The optional network-side Xray hop used by Aether and Direct, outside their loopback inlet. */
object BuiltinExitNode {
    @Volatile var loadProfile: ((String) -> VlessProfile?)? = null

    fun eligible(profile: VlessProfile): Boolean = profile.profileType != ProfileType.XRAY_JSON &&
        !Sidecars.isEngineProfile(profile) && profile.address.isNotBlank() &&
        RuntimeCapabilities.unsupportedReason(profile) == null

    fun needed(options: AetherSettings) = options.exitNode.isNotBlank() ||
        options.exitFinalMask.isNotBlank() || options.exitDialMode.isNotBlank()

    fun selected(options: AetherSettings, settings: AppSettings): JSONObject? {
        if (options.exitNode.isBlank()) return null
        val saved = loadProfile?.invoke(options.exitNode) ?: error("Selected exit-node is no longer available")
        require(eligible(saved)) { "The exit-node must be a supported individual proxy" }
        val profile = if (ProxyDnsTransport.isLiteralAddress(saved.address)) saved else {
            val resolved = com.example.vpn.EndpointResolver.resolve(saved.address, system = { emptyList() },
                open = { it.openConnection() as java.net.HttpURLConnection }, private = true)
            com.example.vpn.smart.RouteRecipe.pinEndpoint(saved, resolved.address)
        }
        val root = JSONObject(XrayConfigParser.sanitizeForExecution(XrayConfigBuilder.buildJson(profile, settings), settings))
        return root.getJSONArray("outbounds").getJSONObject(0).apply { put("tag", "builtin-exit") }
    }

    fun freedom(options: AetherSettings): JSONObject = JSONObject().apply {
        put("tag", "builtin-exit")
        put("protocol", "freedom")
        put("settings", JSONObject().put("domainStrategy", "UseIP"))
        val stream = JSONObject()
        if (options.exitFinalMask.isNotBlank()) stream.put("finalmask", JSONObject(options.exitFinalMask))
        if (options.exitDialMode.isNotBlank()) stream.put("sockopt", JSONObject().put("dialMode", options.exitDialMode))
        put("streamSettings", stream)
    }

    data class Started(val running: RunningEngine, val upstream: String)

    fun start(options: AetherSettings, settings: AppSettings, parent: SidecarContext): Started? {
        if (!needed(options)) return null
        val executable = Sidecars.executable(ServerlessSidecar) ?: error("The exit-node Xray runtime is missing")
        val context = parent.copy(workDir = File(parent.workDir, "exit").apply { mkdirs() },
            executable = executable, socksPort = Sidecars.freeLoopbackPort(),
            socksUser = Sidecars.randomToken(9), socksPass = Sidecars.randomToken())
        val outbound = selected(options, settings) ?: freedom(options)
        val inlet = JSONObject().put("listen", "127.0.0.1").put("port", context.socksPort).put("protocol", "socks")
            .put("settings", JSONObject().put("auth", "password").put("udp", true).put("accounts",
                JSONArray().put(JSONObject().put("user", context.socksUser).put("pass", context.socksPass))))
        val root = JSONObject().put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", JSONArray().put(inlet)).put("outbounds", JSONArray().put(outbound))
            .put("dns", JSONObject().put("servers", JSONArray().put(DnsResolvers.literal(settings.dnsServer)))
                .put("tag", "exit-dns-query").put("disableFallback", true))
            .put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", JSONArray().put(
                JSONObject().put("type", "field").put("network", "tcp,udp").put("outboundTag", "builtin-exit"))))
        XrayConfigBuilder.stripSocketMarks(root)
        val file = File(context.workDir, "exit.json").apply { writeText(root.toString()) }
        val launch = SidecarLaunch(listOf(executable.absolutePath, "run", "-c", file.absolutePath), logOutput = false)
        val process = SidecarProcess.start("builtin-exit", launch, context.workDir, context.socksPort)
        try {
            check(process.awaitReady(15_000)) { "The selected exit-node runtime could not start" }
            return Started(process, "socks5://${context.socksUser}:${context.socksPass}@127.0.0.1:${context.socksPort}")
        } catch (e: Throwable) {
            process.stop()
            throw e
        }
    }
}
