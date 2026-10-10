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

    /** The [Outcome.NotRun] reason while the VPN's own core runs (libXray then refuses a probe). */
    const val CORE_RUNNING = "the VPN's Xray core is running"

    /** libXray accepts at most five configs per pingBatch call. */
    const val MAX_BATCH = 5

    sealed class Outcome {
        /**
         * A real request went through. [target] is the ProbeTargets id that answered; [targetsTried] counts the
         * targets asked (a fallback after one target's failure makes it 2).
         */
        data class Delay(val latencyMs: Long, val target: String = "", val targetsPassed: Int = 1, val targetsTried: Int = 1) : Outcome()
        /**
         * No request got through. [request] is false when no request was sent (the config could not be built);
         * [targetsTried] counts the independent targets that all failed through this candidate.
         */
        data class Failed(val reason: String, val request: Boolean = true, val targetsTried: Int = 1) : Outcome()
        data class NotRun(val reason: String) : Outcome()
    }

    /** Independent targets tried in a fast check before a candidate is called failed (one fallback). */
    const val FAST_TARGETS = 2

    /**
     * Every finished outcome of [measure], so one evidence model sees every real test the app runs (see
     * ConnectivityBrain). Never given credentials: the receiver gets the profile object and keeps only its
     * fingerprint and family.
     */
    @Volatile
    var observer: ((VlessProfile, Outcome) -> Unit)? = null

    /** HTTP requests actually sent through candidates, for the LAB's resource budget. */
    val requestsSent = java.util.concurrent.atomic.AtomicLong(0)

    /** Sends the libXray invoke request and returns its JSON response; replaceable in tests. */
    @Volatile
    internal var invoker: (String) -> String = ::invokeLibXray

    /** Called for every socket the probe opens; the VPN service installs VpnService.protect here. */
    @Volatile
    var socketProtector: ((Int) -> Boolean)? = null

    /**
     * Turns a server host name into an address before the probe; the app installs one that does not
     * trust a filtered network's DNS (see EndpointResolver). Identity by default.
     */
    @Volatile
    var endpointResolver: (VlessProfile) -> VlessProfile = { it }

    fun measure(profile: VlessProfile, timeoutSec: Int): Outcome = measure(listOf(profile), timeoutSec).first()

    /**
     * Fast check of each profile against the first independent target. A profile that failed is asked once more
     * through the next target (another provider) unless that first target is known to be up, either because
     * another profile in the same batch just passed through it or because one did recently (ProbeTargets.Health).
     * So one target's outage never marks a working candidate as failed, and a known-up target costs no extra request.
     */
    fun measure(profiles: List<VlessProfile>, timeoutSec: Int): List<Outcome> =
        profiles.chunked(MAX_BATCH).flatMap { batch ->
            measureFast(batch, timeoutSec).also { outcomes ->
                observer?.let { o -> batch.zip(outcomes).forEach { (p, r) -> if (r !is Outcome.NotRun) runCatching { o(p, r) } } }
            }
        }

    private fun measureFast(batch: List<VlessProfile>, timeoutSec: Int): List<Outcome> {
        val targets = ProbeTargets.independent(n = FAST_TARGETS).ifEmpty { ProbeTargets.BUILT_IN.take(1) }
        val results = measureBatch(batch, timeoutSec, targets[0]).toMutableList()
        var previous = targets[0]
        for (next in targets.drop(1)) {
            val someonePassed = results.any { it is Outcome.Delay }
            if (someonePassed || ProbeTargets.health.knownUp(previous)) break
            val retry = results.indices.filter { (results[it] as? Outcome.Failed)?.request == true }
            if (retry.isEmpty()) break
            val again = measureBatch(retry.map { batch[it] }, timeoutSec, next)
            retry.forEachIndexed { i, index ->
                val before = results[index] as Outcome.Failed
                results[index] = when (val o = again[i]) {
                    is Outcome.Delay -> o.copy(targetsTried = before.targetsTried + 1)
                    is Outcome.Failed -> before.copy(targetsTried = before.targetsTried + 1)
                    is Outcome.NotRun -> before
                }
            }
            previous = next
        }
        return results
    }

    /** Result of asking one candidate every target in [targets] (full verification). */
    data class MultiTarget(
        val passedTargets: List<String>,
        val failedTargets: Map<String, String>,
        val latencyMs: Long?,
        val notRun: String? = null
    ) {
        val tried: Int get() = passedTargets.size + failedTargets.size
        val verdict: ProbeTargets.Verdict get() = if (notRun != null && tried == 0) ProbeTargets.Verdict.NOT_RUN
            else ProbeTargets.verdict(passedTargets.size, tried)
    }

    /**
     * Full verification: one real request per independent target. One pass proves egress; fewer than all is
     * degraded; none is this candidate's failure only.
     */
    fun measureTargets(profile: VlessProfile, timeoutSec: Int, targets: List<ProbeTargets.Target> = ProbeTargets.independent(n = 3)): MultiTarget {
        val passed = mutableListOf<String>()
        val failed = linkedMapOf<String, String>()
        val latencies = mutableListOf<Long>()
        var notRun: String? = null
        for (t in targets) {
            when (val o = measureBatch(listOf(profile), timeoutSec, t).first()) {
                is Outcome.Delay -> { passed += t.id; latencies += o.latencyMs }
                is Outcome.Failed -> if (o.request) failed[t.id] = o.reason else return MultiTarget(passed, failed, latencies.minOrNull(), o.reason)
                is Outcome.NotRun -> { notRun = o.reason; break }
            }
        }
        val result = MultiTarget(passed, failed, latencies.minOrNull(), notRun)
        observer?.let { o ->
            val summary: Outcome? = when {
                passed.isNotEmpty() -> Outcome.Delay(latencies.min(), passed.first(), passed.size, result.tried)
                failed.isNotEmpty() -> Outcome.Failed(failed.values.first(), true, failed.size)
                else -> null
            }
            summary?.let { runCatching { o(profile, it) } }
        }
        return result
    }

    private fun measureBatch(profiles: List<VlessProfile>, timeoutSec: Int, target: ProbeTargets.Target): List<Outcome> {
        // Only the proxy outbound is used, so the user's DNS and routing settings do not matter.
        // Profiles carried by a separate engine program (Mihomo, Psiphon...) cannot be measured by the
        // Xray core alone; they are not failures, just not measured here.
        val external = profiles.map { com.example.vpn.sidecar.Sidecars.forProfile(it) != null }
        val configs = profiles.mapIndexed { index, profile ->
            if (external[index]) null
            else runCatching {
                // The tunnel strips SO_MARK the same way (XrayEngine); left in, every probe dial fails
                // with EPERM on a phone and each server reads as a timeout.
                XrayConfigBuilder.stripSocketMarks(JSONObject(XrayConfigBuilder.buildJson(endpointResolver(profile), AppSettings()))).toString()
            }
        }
        val runnable = configs.mapIndexedNotNull { index, config -> config?.getOrNull()?.let { index to it } }
        val results = arrayOfNulls<Outcome>(profiles.size)
        configs.forEachIndexed { index, config ->
            if (config == null) results[index] = Outcome.NotRun("carried by its own engine")
            config?.exceptionOrNull()?.let { results[index] = Outcome.Failed(it.message ?: "Invalid configuration", request = false) }
        }
        if (runnable.isNotEmpty()) {
            val outcomes = try {
                parseResponse(invoker(buildRequest(runnable.map { it.second }, target.timeoutSec ?: timeoutSec, target.url).toString()), runnable.size)
                    .also { list -> if (list.any { it !is Outcome.NotRun }) requestsSent.addAndGet(list.count { it !is Outcome.NotRun }.toLong()) }
                    .map { o -> if (o is Outcome.Delay) o.copy(target = target.id).also { ProbeTargets.health.recordPass(target) } else o }
            } catch (e: Exception) {
                List(runnable.size) { Outcome.NotRun(e.message ?: e.javaClass.simpleName) }
            } catch (e: LinkageError) {
                // libXray's classes without its native library (JVM unit tests) fail to link.
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
            return JSONObject().put("success", false).put("error", CORE_RUNNING).toString()
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
