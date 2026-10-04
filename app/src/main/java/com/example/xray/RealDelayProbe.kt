package com.example.xray

import com.example.data.model.AppSettings
import com.example.data.model.VlessProfile
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Proxy

/**
 * "Real delay": sends an actual HTTP request through the profile's proxy outbound with the bundled
 * Xray-core (libXray pingBatch), so a result means the server carried traffic.
 *
 * A TCP, TLS or WebSocket handshake alone proves nothing for Cloudflare Worker panels such as BPB:
 * the Cloudflare edge always accepts the connection and the Worker upgrades the WebSocket before it
 * reads the VLESS/Trojan header, so a config with a wrong UUID, a dead proxy IP or a broken TLS
 * fragment setting still "pings".
 *
 * libXray runs the probe in its own core instance and refuses while the VPN's Xray instance is
 * running; [Outcome.NotRun] is returned then (and when libXray is not available, as in unit tests).
 */
object RealDelayProbe {
    const val PROBE_URL = "https://www.gstatic.com/generate_204"

    /** libXray accepts at most five configs per pingBatch call. */
    const val MAX_BATCH = 5

    sealed class Outcome {
        data class Delay(val latencyMs: Long) : Outcome()
        data class Failed(val reason: String) : Outcome()
        data class NotRun(val reason: String) : Outcome()
    }

    /** Sends the libXray invoke request and returns its JSON response; replaceable in tests. */
    @Volatile
    internal var invoker: (String) -> String = ::invokeLibXray

    /** Called for every socket the probe opens; the VPN service installs VpnService.protect here. */
    @Volatile
    var socketProtector: ((Int) -> Boolean)? = null

    fun measure(profile: VlessProfile, timeoutSec: Int): Outcome = measure(listOf(profile), timeoutSec).first()

    fun measure(profiles: List<VlessProfile>, timeoutSec: Int): List<Outcome> =
        profiles.chunked(MAX_BATCH).flatMap { measureBatch(it, timeoutSec) }

    private fun measureBatch(profiles: List<VlessProfile>, timeoutSec: Int): List<Outcome> {
        // Only the proxy outbound is used, so the user's DNS and routing settings do not matter.
        val configs = profiles.map { profile ->
            runCatching { XrayConfigBuilder.buildJson(profile, AppSettings()) }
        }
        val runnable = configs.mapIndexedNotNull { index, config -> config.getOrNull()?.let { index to it } }
        val results = arrayOfNulls<Outcome>(profiles.size)
        configs.forEachIndexed { index, config ->
            config.exceptionOrNull()?.let { results[index] = Outcome.Failed(it.message ?: "Invalid configuration") }
        }
        if (runnable.isNotEmpty()) {
            val outcomes = try {
                parseResponse(invoker(buildRequest(runnable.map { it.second }, timeoutSec).toString()), runnable.size)
            } catch (e: Exception) {
                List(runnable.size) { Outcome.NotRun(e.message ?: e.javaClass.simpleName) }
            }
            runnable.forEachIndexed { i, (index, _) -> results[index] = outcomes[i] }
        }
        return results.map { it ?: Outcome.NotRun("not measured") }
    }

    internal fun buildRequest(configs: List<String>, timeoutSec: Int, url: String = PROBE_URL): JSONObject {
        val items = JSONArray()
        configs.forEach { items.put(JSONObject().put("xrayJson", it).put("outboundTag", "proxy")) }
        val payload = JSONObject().put("configs", items).put("timeout", timeoutSec.coerceAtLeast(1)).put("url", url)
        return JSONObject().put("apiVersion", 3).put("method", "pingBatch").put("payload", payload)
    }

    /**
     * A failed call as a whole (core busy, bad request) is [Outcome.NotRun]: nothing was measured.
     * A failed item is [Outcome.Failed]: the request did not get through that proxy.
     */
    internal fun parseResponse(response: String, count: Int): List<Outcome> {
        val json = JSONObject(response)
        if (!json.optBoolean("success", false)) {
            val reason = json.optString("error").ifBlank { "libXray pingBatch failed" }
            return List(count) { Outcome.NotRun(reason) }
        }
        val items = json.optJSONObject("data")?.optJSONArray("results") ?: JSONArray()
        return List(count) { i ->
            val item = items.optJSONObject(i)
            when {
                item == null -> Outcome.NotRun("libXray returned no result")
                item.optBoolean("success", false) -> Outcome.Delay(item.optLong("delay").coerceAtLeast(1))
                else -> Outcome.Failed(item.optString("error").ifBlank { "no response through the proxy" })
            }
        }
    }

    private fun invokeLibXray(request: String): String {
        val cls = Class.forName("libXray.LibXray")
        val invoke = cls.methods.first { it.name.equals("invoke", ignoreCase = true) && it.parameterTypes.size == 1 }
        val call = { body: String -> invoke.invoke(null, body) as? String ?: error("libXray returned no response") }
        // Never replace the dialer controller of a running VPN core.
        val state = JSONObject(call(JSONObject().put("apiVersion", 3).put("method", "getXrayState").toString()))
        if (state.optJSONObject("data")?.optBoolean("running", false) == true) {
            return JSONObject().put("success", false).put("error", "the VPN's Xray core is running").toString()
        }
        registerDialerController(cls)
        return call(request)
    }

    /**
     * The controller registered by the last VPN session may point at a stopped service. Replace it with
     * one that protects through the VPN service while it exists, so probe sockets bypass the VPN.
     */
    private fun registerDialerController(libXray: Class<*>) {
        val controllerClass = Class.forName("libXray.DialerController")
        val controller = Proxy.newProxyInstance(controllerClass.classLoader, arrayOf(controllerClass)) { _, method, args ->
            if (method.name.equals("protectFd", ignoreCase = true)) {
                val fd = (args?.firstOrNull() as? Number)?.toInt() ?: -1
                socketProtector?.invoke(fd) ?: true
            } else null
        }
        libXray.methods.first { it.name.equals("registerDialerController", ignoreCase = true) && it.parameterTypes.size == 1 }
            .invoke(null, controller)
    }
}
