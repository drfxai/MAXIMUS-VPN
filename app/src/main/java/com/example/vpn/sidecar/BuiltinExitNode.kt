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

    /**
     * A failure of the exit hop, with a fixed, non-secret reason (never an address, host name, key or a
     * library message) so it can be shown and written to a diagnostic report as it is.
     */
    class ExitNodeFailure(reason: String, cause: Throwable? = null) : IllegalStateException(reason, cause)

    const val RUNTIME_MISSING = "exit-node runtime missing"
    const val PROFILE_UNAVAILABLE = "exit-node profile unavailable"
    const val PROFILE_UNSUPPORTED = "exit-node profile unsupported"
    const val ADDRESS_UNRESOLVED = "exit-node address could not be resolved"
    const val ROUTE_UNAVAILABLE = "exit-node verified route no longer applies"
    const val CONFIG_REJECTED = "exit-node configuration rejected"
    const val PROCESS_FAILED = "exit-node runtime could not start"
    /** Every reason above: safe to show and log verbatim. */
    val REASONS = setOf(RUNTIME_MISSING, PROFILE_UNAVAILABLE, PROFILE_UNSUPPORTED, ADDRESS_UNRESOLVED,
        ROUTE_UNAVAILABLE, CONFIG_REJECTED, PROCESS_FAILED)

    /**
     * How the exit hop dials the saved server: the address that carried verified traffic on this network
     * (a [com.example.vpn.smart.RouteRecipe]'s pinned endpoint, e.g. a clean CDN address) and the stealth
     * alternate that carried it, so the hop dials exactly the way that verified instead of re-resolving the
     * name (and landing on a filtered address) or dropping the alternate.
     */
    data class Route(val endpoint: String? = null, val variantKey: String? = null) {
        fun write(json: JSONObject): JSONObject = json.apply {
            if (!endpoint.isNullOrBlank()) put(EXIT_ENDPOINT, endpoint) else remove(EXIT_ENDPOINT)
            if (!variantKey.isNullOrBlank()) put(EXIT_VARIANT, variantKey) else remove(EXIT_VARIANT)
        }
        companion object {
            fun read(json: JSONObject) = Route(json.optString(EXIT_ENDPOINT).ifBlank { null }, json.optString(EXIT_VARIANT).ifBlank { null })
        }
    }

    /** Extras keys (set only by the session runner for WARP-through-relay): see [Route]. */
    const val EXIT_ENDPOINT = "exitEndpoint"
    const val EXIT_VARIANT = "exitVariant"

    /** Resolves a saved server's host name outside the VPN; replaced in tests. */
    @Volatile var resolveAddress: (String) -> String = { host ->
        com.example.vpn.EndpointResolver.resolve(host, system = { emptyList() },
            open = { it.openConnection() as java.net.HttpURLConnection }, private = true).address
    }

    /**
     * The saved server [options] names, as the exit hop's single Xray outbound. Built from the app's own
     * builder and checked with [XrayConfigParser.requireExecutableOutbound]. It is deliberately not passed
     * through [XrayConfigParser.sanitizeForExecution] (that is for imported whole configs): the builder
     * already adds the private DNS outbound, and sanitizing adds it a second time, which its own guard
     * rejects. That made every saved-server exit node (WARP through a verified saved server, Direct's exit
     * node) fail before its process started.
     */
    fun selected(options: AetherSettings, settings: AppSettings, route: Route = Route()): JSONObject? {
        if (options.exitNode.isBlank()) return null
        val saved = try { loadProfile?.invoke(options.exitNode) } catch (e: Exception) { throw ExitNodeFailure(PROFILE_UNAVAILABLE, e) }
            ?: throw ExitNodeFailure(PROFILE_UNAVAILABLE)
        if (!eligible(saved)) throw ExitNodeFailure(PROFILE_UNSUPPORTED)
        val pinned = route.endpoint?.trim()?.removePrefix("[")?.removeSuffix("]")
            ?.takeIf { ProxyDnsTransport.isLiteralAddress(it) }
        val base = when {
            ProxyDnsTransport.isLiteralAddress(saved.address.trim().removePrefix("[").removeSuffix("]")) -> saved
            pinned != null -> com.example.vpn.smart.RouteRecipe.pinEndpoint(saved, pinned)
            else -> {
                val address = try { resolveAddress(saved.address.trim()) } catch (e: Exception) {
                    if (e is InterruptedException) throw e
                    throw ExitNodeFailure(ADDRESS_UNRESOLVED, e)
                }
                if (!ProxyDnsTransport.isLiteralAddress(address)) throw ExitNodeFailure(ADDRESS_UNRESOLVED)
                com.example.vpn.smart.RouteRecipe.pinEndpoint(saved, address)
            }
        }
        val profile = route.variantKey?.let { key ->
            com.example.vpn.stealth.StealthVariants.of(base).firstOrNull { it.key == key }?.profile
                ?: throw ExitNodeFailure(ROUTE_UNAVAILABLE)
        } ?: base
        return try {
            val root = JSONObject(XrayConfigBuilder.buildJson(profile, settings))
            val outbound = root.getJSONArray("outbounds").getJSONObject(0)
            XrayConfigParser.requireExecutableOutbound(outbound)
            outbound.apply { put("tag", "builtin-exit") }
        } catch (e: Exception) {
            throw ExitNodeFailure(CONFIG_REJECTED, e)
        }
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

    data class Started(val running: RunningEngine, val upstream: String, val apiHttp: String? = null, val apiToken: String? = null)

    fun start(options: AetherSettings, settings: AppSettings, parent: SidecarContext, route: Route = Route()): Started? {
        if (!needed(options)) return null
        val executable = Sidecars.executable(ServerlessSidecar) ?: throw ExitNodeFailure(RUNTIME_MISSING)
        val context = parent.copy(workDir = File(parent.workDir, "exit").apply { mkdirs() },
            executable = executable, socksPort = Sidecars.freeLoopbackPort(),
            socksUser = Sidecars.randomToken(9), socksPass = Sidecars.randomToken())
        val outbound = selected(options, settings, route) ?: freedom(options)
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
        val process = try { SidecarProcess.start("builtin-exit", launch, context.workDir, context.socksPort) } catch (e: java.io.IOException) {
            throw ExitNodeFailure(PROCESS_FAILED, e)
        }
        try {
            if (!process.awaitReady(15_000)) throw ExitNodeFailure(PROCESS_FAILED)
            return Started(process, "socks5://${context.socksUser}:${context.socksPass}@127.0.0.1:${context.socksPort}")
        } catch (e: Throwable) {
            process.stop()
            throw e
        }
    }
}
