import com.example.data.model.AppSettings
import com.example.data.model.VlessProfile
import com.example.vpn.engine.UniversalImportEngine
import com.example.vpn.stealth.StealthPathFinder
import com.example.xray.RealDelayProbe
import com.example.xray.XrayConfigBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Connects every simulated profile under the censor that is running, the way `main` does (the saved
 * profile only) and the way this branch does (StealthPathFinder: saved profile, stealth alternates,
 * other kinds on the same server), and prints one JSON line per attempt.
 *
 * A real Xray process with a SOCKS inbound stands in for libXray's pingBatch; it adds roughly 0.1 s of
 * process start-up per probe that the app does not have.
 *
 * Usage: SimRunner <xray binary> <sim.json> <host ip> <scenario name> <trials>
 *        SimRunner <xray binary> <sim.json> <host ip> soak <before|after> <censor modes file>
 */
fun main(args: Array<String>) {
    val (xray, simJson, host, scenario) = args
    val links = JSONObject(File(simJson).readText()).getJSONObject("links")
    val profiles = links.keys().asSequence().toList().sorted().map { name ->
        UniversalImportEngine.importText(links.getString(name)).validProfiles.single().copy(id = name, name = name)
    }
    val probe = XrayProbe(xray, host)
    if (scenario == "soak") {
        soak(xray, probe, profiles, host, File(args[5]), SOAK_TIMELINE, phase4 = args[4] == "after")
        probe.close()
        return
    }
    val trials = args[4].toInt()
    if (scenario.endsWith("+lab") || System.getenv("LAB") == "1") {
        failureLab(probe, profiles, scenario.removeSuffix("+lab"), trials)
        probe.close()
        return
    }
    if (scenario == "variants") {
        // Every stealth alternate must still work when nothing is filtered.
        for (profile in profiles) {
            val variants = com.example.vpn.stealth.StealthVariants.of(profile)
            // One at a time: parallel WireGuard handshakes with one key knock each other out.
            variants.map { probe.measure(listOf(it.profile), 5).single() }.zip(variants).forEach { (o, v) ->
                println(JSONObject().put("profile", profile.name).put("variant", v.key).put("outcome", o.toString()))
            }
        }
        probe.close()
        return
    }
    repeat(trials) { trial ->
        for (profile in profiles) {
            // main: the saved profile, nothing else, until the failover watchdog acts (>= 36 s later).
            val t0 = System.nanoTime()
            val base = probe.measure(listOf(profile), StealthPathFinder.FIRST_TIMEOUT_SEC).single()
            val baseMs = (System.nanoTime() - t0) / 1_000_000
            // main refuses VLESS Encryption before connecting ("requires a native Xray core").
            val mainRuns = profile.encryption.isBlank() || profile.encryption == "none"
            emit(scenario, trial, profile.name, "main", mainRuns && base is RealDelayProbe.Outcome.Delay, baseMs,
                if (mainRuns) "as saved" else "refused by main")

            // this branch: first connect, nothing remembered.
            StealthPathFinder.memory.clear()
            val t1 = System.nanoTime()
            val choice = StealthPathFinder(probe = { p, t -> probe.measure(p, t) }, log = {})
                .choose(profile, { it }, profiles)
            val ms = (System.nanoTime() - t1) / 1_000_000
            emit(scenario, trial, profile.name, "branch", choice.latencyMs != null, ms, choice.label)
        }
    }
    phase4Trials(probe, profiles, scenario, trials, host)
    probe.close()
}

fun emit(scenario: String, trial: Int, profile: String, mode: String, ok: Boolean, ms: Long, path: String) =
    println(JSONObject().put("scenario", scenario).put("trial", trial).put("profile", profile).put("mode", mode)
        .put("ok", ok).put("ms", ms).put("path", path))

class XrayProbe(private val xray: String, private val host: String) {
    private val pool = Executors.newCachedThreadPool()
    private val nextPort = AtomicInteger(42000)

    fun measure(profiles: List<VlessProfile>, timeoutSec: Int): List<RealDelayProbe.Outcome> =
        profiles.map { p -> pool.submit(Callable { one(p, timeoutSec) }) }.map { it.get() }

    private fun one(profile: VlessProfile, timeoutSec: Int): RealDelayProbe.Outcome {
        val port = nextPort.getAndIncrement().let { if (it > 60000) { nextPort.set(42000); 42000 } else it }
        val proxy = JSONObject(XrayConfigBuilder.buildJson(profile, AppSettings())).getJSONArray("outbounds").getJSONObject(0)
        // SO_MARK is only meaningful on the phone.
        proxy.optJSONObject("streamSettings")?.optJSONObject("sockopt")?.remove("mark")
        val config = JSONObject()
            .put("log", JSONObject().put("loglevel", "none"))
            .put("inbounds", JSONArray().put(JSONObject().put("listen", "127.0.0.1").put("port", port).put("protocol", "socks")
                .put("settings", JSONObject().put("udp", false))))
            .put("outbounds", JSONArray().put(proxy))
        val file = File.createTempFile("probe", ".json").apply { writeText(config.toString()); deleteOnExit() }
        val process = ProcessBuilder(xray, "run", "-c", file.path).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        try {
            val ready = System.nanoTime() + 3_000_000_000L
            while (System.nanoTime() < ready) {
                if (runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 100) } }.isSuccess) break
                Thread.sleep(20)
            }
            val start = System.nanoTime()
            val connection = URL("http://$host:18080/generate_204")
                .openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))) as HttpURLConnection
            connection.connectTimeout = timeoutSec * 1000
            connection.readTimeout = timeoutSec * 1000
            return try {
                if (connection.responseCode == 204) RealDelayProbe.Outcome.Delay(((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1))
                else RealDelayProbe.Outcome.Failed("HTTP ${connection.responseCode}")
            } catch (e: Exception) {
                RealDelayProbe.Outcome.Failed(e.javaClass.simpleName)
            } finally {
                connection.disconnect()
            }
        } finally {
            process.destroyForcibly()
            file.delete()
        }
    }

    fun close() = pool.shutdownNow()
}

/** Seconds from the start and what the censor blocks from then on: a very bad day, compressed. */
val SOAK_TIMELINE = listOf(
    0 to "none",
    60 to "sni",
    150 to "sni,udp-dpi",
    240 to "sni,fe,udp-dpi",
    330 to "udp-block",
    420 to "none",
    480 to "end"
)
