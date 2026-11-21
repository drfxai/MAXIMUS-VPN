package com.example.vpn.verify

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * TUN-side evidence for a session whose app is excluded from its own VPN (every separate engine program).
 *
 * The TUN is read by the in-process Xray core, whose only outbound for such a session is a SOCKS connection to the
 * engine program's loopback port. This meter is a transparent byte relay placed on that hop: Xray dials
 * 127.0.0.1:[port], the meter dials the engine's port and copies bytes both ways, counting them. The app's own checks
 * go straight to the engine's port and never pass through here, so everything counted is traffic the TUN forwarded
 * for other apps. Only TCP is relayed (SOCKS UDP datagrams go to the engine's own UDP port and are not counted).
 *
 * The relay adds no protocol of its own and holds no state beyond the counters: if it cannot start, the session
 * runs without it (`tun.forwarded` is then "unavailable").
 */
class TunForwardMeter private constructor(
    private val server: ServerSocket,
    private val upstream: InetSocketAddress,
    private val connectTimeoutMs: Int,
    private val clock: () -> Long
) : Closeable {

    /** Cumulative counters since the meter started. down = engine → TUN, up = TUN → engine. */
    data class Snapshot(
        val at: Long,
        val startedAt: Long,
        val downBytes: Long,
        val upBytes: Long,
        /** TCP connections the TUN opened to the engine. */
        val flows: Int,
        val activeFlows: Int,
        /** Flows that brought back more than a SOCKS handshake (> [DATA_FLOW_BYTES] down). */
        val dataFlows: Int,
        /** The engine's port refused or did not answer: the TUN → engine hop is broken. */
        val engineUnreachable: Int,
        /** A relayed flow ended with an I/O error (resets happen in normal browsing; informational). */
        val resets: Int
    ) {
        /** What happened between [earlier] and this snapshot. */
        operator fun minus(earlier: Snapshot): Snapshot = copy(
            startedAt = earlier.at,
            downBytes = (downBytes - earlier.downBytes).coerceAtLeast(0),
            upBytes = (upBytes - earlier.upBytes).coerceAtLeast(0),
            flows = (flows - earlier.flows).coerceAtLeast(0),
            dataFlows = (dataFlows - earlier.dataFlows).coerceAtLeast(0),
            engineUnreachable = (engineUnreachable - earlier.engineUnreachable).coerceAtLeast(0),
            resets = (resets - earlier.resets).coerceAtLeast(0)
        )

        val windowMs: Long get() = (at - startedAt).coerceAtLeast(0)
    }

    val port: Int get() = server.localPort

    private val startedAt = clock()
    private val down = AtomicLong()
    private val up = AtomicLong()
    private val flows = AtomicInteger()
    private val active = AtomicInteger()
    private val dataFlows = AtomicInteger()
    private val unreachable = AtomicInteger()
    private val resets = AtomicInteger()
    private val closed = AtomicBoolean(false)
    private val open = ConcurrentHashMap.newKeySet<Socket>()
    private val pool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "tun-forward-meter").apply { isDaemon = true }
    }

    fun snapshot(): Snapshot = Snapshot(clock(), startedAt, down.get(), up.get(), flows.get(), active.get(),
        dataFlows.get(), unreachable.get(), resets.get())

    val isClosed: Boolean get() = closed.get()

    private fun acceptLoop() {
        while (!closed.get()) {
            val client = try { server.accept() } catch (_: IOException) { break }
            open += client
            try {
                pool.execute { relay(client) }
            } catch (_: Exception) {
                open -= client
                runCatching { client.close() }
            }
        }
    }

    private fun relay(client: Socket) {
        flows.incrementAndGet()
        active.incrementAndGet()
        val engine = Socket()
        open += engine
        try {
            try {
                engine.tcpNoDelay = true
                engine.connect(upstream, connectTimeoutMs)
            } catch (e: IOException) {
                if (!closed.get()) unreachable.incrementAndGet()
                return
            }
            client.tcpNoDelay = true
            val flowDown = AtomicLong()
            val failed = AtomicBoolean(false)
            // An I/O error on either side ends the whole flow, as a direct connection would.
            val abort = {
                failed.set(true)
                runCatching { client.close() }
                runCatching { engine.close() }
                Unit
            }
            // TUN → engine on a second thread; engine → TUN on this one.
            val upTask = try {
                pool.submit(Runnable { pump(client.getInputStream(), engine.getOutputStream(), up, null, abort) { runCatching { engine.shutdownOutput() } } })
            } catch (_: Exception) { null } ?: return
            pump(engine.getInputStream(), client.getOutputStream(), down, flowDown, abort) { runCatching { client.shutdownOutput() } }
            if (flowDown.get() > DATA_FLOW_BYTES) dataFlows.incrementAndGet()
            // The engine closed its side: give the upload a moment to finish, then end the flow.
            runCatching { upTask.get(FLOW_LINGER_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }
            if (failed.get() && !closed.get()) resets.incrementAndGet()
        } catch (_: Exception) {
            if (!closed.get()) resets.incrementAndGet()
        } finally {
            active.decrementAndGet()
            runCatching { client.close() }
            runCatching { engine.close() }
            open -= client
            open -= engine
        }
    }

    private fun pump(from: InputStream, to: OutputStream, total: AtomicLong, flow: AtomicLong?, abort: () -> Unit, onEof: () -> Unit) {
        val buffer = ByteArray(BUFFER)
        try {
            while (true) {
                val n = from.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                to.write(buffer, 0, n)
                total.addAndGet(n.toLong())
                flow?.addAndGet(n.toLong())
            }
            onEof()
        } catch (_: IOException) {
            abort()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        open.toList().forEach { runCatching { it.close() } }
        open.clear()
        pool.shutdownNow()
    }

    companion object {
        const val BUFFER = 32 * 1024
        /** More than the SOCKS replies (greeting, auth, CONNECT reply: well under 64 B) came back. */
        const val DATA_FLOW_BYTES = 256L
        const val FLOW_LINGER_MS = 15_000L

        /**
         * Starts a meter in front of the engine's loopback [enginePort]; null when it cannot listen (the session
         * then runs without it).
         */
        fun start(enginePort: Int, connectTimeoutMs: Int = 3_000, clock: () -> Long = System::currentTimeMillis): TunForwardMeter? {
            if (enginePort !in 1..65535) return null
            val loopback = InetAddress.getByName("127.0.0.1")
            val server = runCatching { ServerSocket(0, 128, loopback) }.getOrNull() ?: return null
            val meter = TunForwardMeter(server, InetSocketAddress(loopback, enginePort), connectTimeoutMs, clock)
            Thread({ meter.acceptLoop() }, "tun-forward-accept").apply { isDaemon = true }.start()
            return meter
        }

        /**
         * The engine profile Xray runs with its SOCKS outbound moved to the meter's [meterPort]; null when the
         * profile is not a plain loopback SOCKS forward to an engine (it then runs unchanged, without a meter).
         */
        fun rewire(profile: com.example.data.model.VlessProfile, meterPort: Int): com.example.data.model.VlessProfile? {
            if (Socks5.endpointOf(profile) == null) return null
            val config = runCatching { org.json.JSONObject(profile.rawConfig) }.getOrNull() ?: return null
            val outbounds = config.optJSONArray("outbounds") ?: return null
            if (outbounds.length() != 1) return null
            val server = outbounds.optJSONObject(0)?.optJSONObject("settings")?.optJSONArray("servers")?.optJSONObject(0) ?: return null
            server.put("address", "127.0.0.1").put("port", meterPort)
            return profile.copy(rawConfig = config.toString())
        }
    }
}

/**
 * The verdict on the Android TUN (device) path from what the TUN forwarded for other apps ([TunForwardMeter]).
 * Informational only: it never changes the connection state, starts a failover or marks a server.
 */
object TunForwarded {
    enum class State(val word: String) {
        /** Other apps' traffic flowed both ways through the TUN to the engine, with no engine errors. */
        VERIFIED("verified"),
        /** Not enough traffic from other apps yet to say anything. Not a failure. */
        WAITING("waiting for traffic from other apps"),
        /** Flows reached the TUN but the engine's port refused them (or errors with nothing carried). */
        FAILED("failed"),
        /** No meter for this session (in-process engine, or the meter could not start). */
        UNAVAILABLE("not measured")
    }

    const val MIN_DOWN_BYTES = 32L * 1024
    /** Upstream payload beyond the SOCKS handshakes: [SOCKS_UP_ALLOWANCE] per flow is subtracted first. */
    const val MIN_UP_BYTES = 1024L
    const val SOCKS_UP_ALLOWANCE = 300L
    const val EVENT = "tun.forwarded"

    fun upPayload(s: TunForwardMeter.Snapshot): Long = (s.upBytes - s.flows * SOCKS_UP_ALLOWANCE).coerceAtLeast(0)

    fun verdict(window: TunForwardMeter.Snapshot?): State = when {
        window == null -> State.UNAVAILABLE
        window.engineUnreachable > 0 && (window.downBytes < MIN_DOWN_BYTES || window.dataFlows == 0) -> State.FAILED
        window.engineUnreachable == 0 && window.downBytes >= MIN_DOWN_BYTES && upPayload(window) >= MIN_UP_BYTES &&
            window.dataFlows > 0 -> State.VERIFIED
        else -> State.WAITING
    }

    fun describe(window: TunForwardMeter.Snapshot?): String {
        if (window == null) return "not measured"
        return "down ${kb(window.downBytes)} / up ${kb(window.upBytes)} in ${window.flows} flow(s)" +
            " (${window.dataFlows} with data)" +
            (if (window.engineUnreachable > 0) ", engine refused ${window.engineUnreachable}" else "") +
            (if (window.resets > 0) ", ${window.resets} reset" else "") +
            " over ${window.windowMs / 1000} s"
    }

    fun attributes(window: TunForwardMeter.Snapshot?, state: State = verdict(window)): Map<String, String> = buildMap {
        put("result", state.name.lowercase())
        if (window != null) {
            put("rx.bytes", window.downBytes.toString())
            put("tx.bytes", window.upBytes.toString())
            put("flows", window.flows.toString())
            put("flows.data", window.dataFlows.toString())
            put("engine.unreachable", window.engineUnreachable.toString())
            put("resets", window.resets.toString())
            put("window.ms", window.windowMs.toString())
        }
        put("detail", describe(window))
    }

    private fun kb(b: Long) = if (b >= 1024 * 1024) "%.1f MB".format(java.util.Locale.US, b / 1048576.0) else "${b / 1024} KB"
}

/**
 * The live device-path facts of the current session, for the diagnostic report: whether the app is excluded from
 * its own VPN and the meter in front of the engine, if any.
 */
object TunPathStatus {
    @Volatile var selfExcluded: Boolean = false
    @Volatile var meter: TunForwardMeter? = null
    /** The latest verdict window (set by the session's tun.forwarded watch); null before it ran. */
    @Volatile var lastWindow: TunForwardMeter.Snapshot? = null

    data class View(val selfExcluded: Boolean, val cumulative: TunForwardMeter.Snapshot?, val state: TunForwarded.State)

    fun view(): View {
        val m = meter?.takeUnless { it.isClosed }
        val cumulative = m?.snapshot()
        return View(selfExcluded, cumulative, TunForwarded.verdict(cumulative))
    }

    fun reset() {
        meter?.close()
        meter = null
        selfExcluded = false
        lastWindow = null
    }
}
