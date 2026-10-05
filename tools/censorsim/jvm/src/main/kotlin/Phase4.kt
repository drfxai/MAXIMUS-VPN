import com.example.data.model.AppSettings
import com.example.data.model.VlessProfile
import com.example.vpn.smart.NetworkMemory
import com.example.vpn.smart.ServerRace
import com.example.vpn.smart.WatchPolicy
import com.example.vpn.stealth.ConnectionKind
import com.example.vpn.stealth.StealthPathFinder
import com.example.xray.RealDelayProbe
import com.example.xray.XrayConfigBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * The connect pipeline before Phase 4 (path finder only, fixed time limits) and after it (the app's
 * RayVpnService steps: chosen server first, path finder, then a race of the other servers, with what
 * the network taught kept in NetworkMemory).
 */
class Pipeline(private val probe: XrayProbe, private val profiles: List<VlessProfile>, val phase4: Boolean) {
    val memory = NetworkMemory({ null }, {})
    private val phase3Memory = ConcurrentHashMap<String, String>()
    private val measure: (List<VlessProfile>, Int) -> List<RealDelayProbe.Outcome> = { p, t -> probe.measure(p, t) }

    data class Path(val profile: VlessProfile, val owner: VlessProfile, val works: Boolean)

    fun connect(requested: VlessProfile, smart: Boolean = false, exclude: Set<String> = emptySet()): Path {
        if (!phase4) {
            val c = StealthPathFinder(measure, log = {}, memory = phase3Memory).choose(requested, { it }, profiles)
            return Path(c.profile, c.owner, c.latencyMs != null)
        }
        val timeouts = memory.timeouts(NET)
        val finder = StealthPathFinder(measure, log = {}, memory = memory.variants(NET),
            firstTimeoutSec = timeouts.firstSec, alternateTimeoutSec = timeouts.alternateSec)
        fun race(ex: Set<String>, retryDisguised: Boolean): Path? {
            val candidates = ServerRace.rank(profiles, memory.workingKinds(NET), memory.recentFailures(NET), exclude = ex)
            val win = ServerRace(measure, log = {}).run(candidates, { it }, timeouts.alternateSec,
                onFailure = { memory.recordFailure(NET, ConnectionKind.of(it)) }, disguiseOnly = if (retryDisguised) profiles.filter { it.id in ex } else emptyList())
                ?: candidates.firstOrNull()?.let { best ->
                    finder.choose(best, { it }, profiles, firstFailed = true).takeIf { it.latencyMs != null }
                        ?.let { ServerRace.Winner(it.profile, it.owner, it.latencyMs!!) }
                }
            return win?.let {
                it.variantKey?.let { key -> memory.variants(NET)[it.owner.id] = key }
                memory.recordSuccess(NET, ConnectionKind.of(it.profile), it.latencyMs); Path(it.profile, it.owner, true) }
        }
        var firstFailed = false
        if (smart) {
            val first = finder.firstPath(requested, requested)
            when (val o = measure(listOf(first.profile), timeouts.firstSec).single()) {
                is RealDelayProbe.Outcome.Delay -> {
                    memory.recordSuccess(NET, ConnectionKind.of(first.profile), o.latencyMs)
                    return Path(first.profile, requested, true)
                }
                is RealDelayProbe.Outcome.Failed -> {
                    firstFailed = true
                    memory.recordFailure(NET, ConnectionKind.of(first.profile))
                    race(exclude + requested.id, retryDisguised = true)?.let { return it }
                }
                else -> Unit
            }
        }
        val c = finder.choose(requested, { it }, profiles, firstFailed)
        c.latencyMs?.let { memory.recordSuccess(NET, ConnectionKind.of(c.profile), it) }
        if (c.nothingWorked) memory.recordFailure(NET, ConnectionKind.of(requested))
        if (c.nothingWorked && !smart) race(exclude + requested.id, retryDisguised = false)?.let { return it }
        return Path(c.profile, c.owner, c.latencyMs != null)
    }

    companion object { const val NET = "sim" }
}

/** Phase 4 connect times: learned time limits on a known network, and a server that is down. */
fun phase4Trials(probe: XrayProbe, profiles: List<VlessProfile>, scenario: String, trials: Int, host: String) {
    val p4 = Pipeline(probe, profiles, phase4 = true)
    // One connect teaches the network's latency, as on a phone that has been used there before.
    p4.connect(profiles.first())
    repeat(trials) { trial ->
        for (profile in profiles) {
            p4.memory.variants(Pipeline.NET).clear()
            val t = System.nanoTime()
            val path = p4.connect(profile)
            emit(scenario, trial, profile.name, "phase4", path.works, (System.nanoTime() - t) / 1_000_000, path.owner.name)
        }
    }
    // A saved server that no longer answers (blocked IP or dead host): packets vanish.
    Blackhole().use { hole ->
        val dead = profiles.first { it.security == "reality" }.copy(id = "dead", name = "Dead server", address = "127.0.0.2", port = hole.port)
        val all = profiles + dead
        repeat(trials) { trial ->
            val t3 = System.nanoTime()
            val before = Pipeline(probe, all, phase4 = false).connect(dead)
            // Before Phase 4 the dead path is started; the watchdog gives up after three 12 s checks,
            // then switches blindly to the first server of another kind and runs the path finder on it.
            val blind = all.first { ConnectionKind.of(it) != ConnectionKind.of(dead) }
            val after = Pipeline(probe, all, phase4 = false).connect(blind)
            val ms3 = (System.nanoTime() - t3) / 1_000_000 + 3 * WATCH_BEFORE_PHASE4_MS
            emit("$scenario+dead", trial, dead.name, "branch", !before.works && after.works, ms3, "after the watchdog: ${after.owner.name}")
            val t4 = System.nanoTime()
            val path = Pipeline(probe, all, phase4 = true).connect(dead)
            emit("$scenario+dead", trial, dead.name, "phase4", path.works, (System.nanoTime() - t4) / 1_000_000, path.owner.name)
        }
    }
}

/**
 * The failure lab: what the app does when things go wrong, under whatever the censor is blocking now.
 *
 * Each case records whether a working path was found and how long it took. The point of the last cases
 * is the opposite of the others: when nothing can work, the app must report failure instead of starting
 * a connection that carries nothing, because that is what keeps traffic blocked rather than leaking.
 */
fun failureLab(probe: XrayProbe, profiles: List<VlessProfile>, scenario: String, trials: Int) {
    repeat(trials) { trial ->
        // 1. Every saved server as the user picked it.
        for (profile in profiles) {
            val pipeline = Pipeline(probe, profiles, phase4 = true)
            val t = System.nanoTime()
            val path = pipeline.connect(profile, smart = true)
            emit("$scenario+lab", trial, profile.name, "lab-connect", path.works, (System.nanoTime() - t) / 1_000_000, path.owner.name)
        }
        // 2. One saved server, which is down, and no others: nothing can work.
        Blackhole().use { hole ->
            val dead = profiles.first().copy(id = "dead", name = "Dead server", address = "127.0.0.2", port = hole.port)
            val t = System.nanoTime()
            val path = Pipeline(probe, listOf(dead), phase4 = true).connect(dead, smart = true)
            emit("$scenario+lab", trial, "only server down", "lab-fail-closed", !path.works,
                (System.nanoTime() - t) / 1_000_000, if (path.works) "claimed a path" else "reported no path")
        }
        // 3. Every server down: the race and the disguises must all come back empty.
        Blackhole().use { hole ->
            val dead = profiles.mapIndexed { i, p -> p.copy(id = "dead$i", name = "Dead ${p.name}", address = "127.0.0.2", port = hole.port) }
            val t = System.nanoTime()
            val path = Pipeline(probe, dead, phase4 = true).connect(dead.first(), smart = true)
            emit("$scenario+lab", trial, "all servers down", "lab-fail-closed", !path.works,
                (System.nanoTime() - t) / 1_000_000, if (path.works) "claimed a path" else "reported no path")
        }
    }
}

const val WATCH_BEFORE_PHASE4_MS = 12_000L

/** Accepts TCP connections and never answers, like a blocked server address. */
class Blackhole : AutoCloseable {
    private val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.2", 0)) }
    val port = server.localPort
    private val held = mutableListOf<Socket>()
    private val acceptor = thread(isDaemon = true) {
        while (!server.isClosed) runCatching { synchronized(held) { held += server.accept() } }
    }
    override fun close() {
        server.close()
        synchronized(held) { held.forEach { runCatching { it.close() } } }
    }
}

/**
 * KPI 3: a connection held while the censor changes what it blocks, before and after Phase 4. A
 * request goes through the tunnel every second like a user's traffic; the watchdog checks the tunnel
 * as the app does and fails over; outages are runs of failed requests.
 */
fun soak(xray: String, probe: XrayProbe, profiles: List<VlessProfile>, host: String, modesFile: File, timeline: List<Pair<Int, String>>, phase4: Boolean) {
    val pipeline = Pipeline(probe, profiles, phase4)
    val tunnel = AtomicReference<Tunnel?>(null)
    val start = System.nanoTime()
    fun now() = (System.nanoTime() - start) / 1_000_000_000.0
    val label = if (phase4) "phase4" else "branch"
    modesFile.writeText(timeline.first().second)
    Thread.sleep(800)

    fun up(path: Pipeline.Path) {
        tunnel.getAndSet(Tunnel(xray, path.profile))?.close()
        println(JSONObject().put("soak", label).put("t", now()).put("event", "connected").put("server", path.owner.name)
            .put("disguised", path.profile.finalMask != path.owner.finalMask || path.profile.fingerprint != path.owner.fingerprint)
            .put("works", path.works))
    }
    var current = profiles.first { it.security == "reality" }
    up(pipeline.connect(current))

    val end = timeline.last().first
    val censor = thread(isDaemon = true) {
        for ((at, modes) in timeline.drop(1).dropLast(1)) {
            while (now() < at) Thread.sleep(100)
            modesFile.writeText(modes)
            println(JSONObject().put("soak", label).put("t", now()).put("event", "censor").put("modes", modes))
        }
    }
    val user = thread(isDaemon = true) {
        var next = 1.0
        while (now() < end) {
            while (now() < next) Thread.sleep(20)
            next += 1.0
            val t = now()
            thread(isDaemon = true) {
                val ok = tunnel.get()?.request(host, 3000) != null
                println(JSONObject().put("soak", label).put("t", t).put("event", "request").put("ok", ok))
            }
        }
    }
    // The watchdog, as FailoverManager runs it.
    var failures = 0
    var lastSwitch = -1e9
    val failedKinds = mutableSetOf<String>()
    while (now() < end) {
        Thread.sleep(if (phase4) WatchPolicy.nextCheckDelayMs(failures) else WATCH_BEFORE_PHASE4_MS)
        val latency = tunnel.get()?.request(host, 5000)
        if (latency != null && latency < 3000) { failures = 0; failedKinds.clear(); continue }
        failures = (failures + 1).coerceAtMost(3)
        if (failures < 3 || now() - lastSwitch < 25) continue
        lastSwitch = now()
        failedKinds += ConnectionKind.of(current)
        if (phase4) pipeline.memory.recordFailure(Pipeline.NET, ConnectionKind.of(current))
        val others = profiles.filter { it.id != current.id }
        val blind = others.filter { ConnectionKind.of(it) !in failedKinds }.ifEmpty { others }.first()
        println(JSONObject().put("soak", label).put("t", now()).put("event", "failover").put("from", current.name).put("to", blind.name))
        tunnel.getAndSet(null)?.close()
        val path = pipeline.connect(blind, smart = phase4, exclude = setOf(current.id))
        current = path.owner
        failures = 0
        up(path)
    }
    user.join()
    censor.join(1000)
    Thread.sleep(3500)
    tunnel.getAndSet(null)?.close()
}

/** A running Xray client for one path, with a SOCKS inbound. */
class Tunnel(xray: String, profile: VlessProfile) : AutoCloseable {
    private val port = 46000 + (Math.random() * 9000).toInt()
    private val file = File.createTempFile("tunnel", ".json")
    private val process: Process

    init {
        val proxy = JSONObject(XrayConfigBuilder.buildJson(profile, AppSettings())).getJSONArray("outbounds").getJSONObject(0)
        proxy.optJSONObject("streamSettings")?.optJSONObject("sockopt")?.remove("mark")
        file.writeText(JSONObject()
            .put("log", JSONObject().put("loglevel", "none"))
            .put("inbounds", JSONArray().put(JSONObject().put("listen", "127.0.0.1").put("port", port).put("protocol", "socks")
                .put("settings", JSONObject().put("udp", false))))
            .put("outbounds", JSONArray().put(proxy)).toString())
        process = ProcessBuilder(xray, "run", "-c", file.path).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        val ready = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < ready) {
            if (runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 100) } }.isSuccess) break
            Thread.sleep(20)
        }
    }

    /** Latency of one request through the tunnel, or null when it failed. */
    fun request(host: String, timeoutMs: Int): Long? {
        val t = System.nanoTime()
        val c = URL("http://$host:18080/generate_204").openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))) as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        return try {
            if (c.responseCode == 204) (System.nanoTime() - t) / 1_000_000 else null
        } catch (_: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }

    override fun close() {
        process.destroyForcibly()
        file.delete()
    }
}
