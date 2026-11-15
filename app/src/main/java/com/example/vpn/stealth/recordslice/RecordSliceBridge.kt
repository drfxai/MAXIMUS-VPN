package com.example.vpn.stealth.recordslice

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * A loopback TCP bridge for one server: Xray dials `127.0.0.1:[port]` (see [RecordSliceConfig.apply]), the bridge
 * dials [host]:[targetPort] on a protected socket, re-frames the first record Xray sends (the ClientHello) with
 * [TlsRecordSlicer] and then relays both directions byte for byte. TLS itself stays end to end inside Xray: the
 * bridge never sees keys, and anything that is not a whole ClientHello is forwarded unchanged.
 *
 * With [RecordSliceConfig.Spec.mss] set (Uplink Shield) it also opens the server socket with TCP_MAXSEG and writes
 * the first [UplinkShield.WINDOW_BYTES] of the upload in MSS-sized pieces.
 *
 * Architecture after Alan-RIP-Dpi's FullDuplexMutatorBridge (DrAlanK / LabAlan, MIT; see THIRD_PARTY_NOTICES.md).
 */
class RecordSliceBridge(
    val host: String,
    val targetPort: Int,
    val spec: RecordSliceConfig.Spec,
    private val hooks: Hooks = Hooks()
) : AutoCloseable {
    /**
     * [protect] keeps a new, unconnected socket off the VPN (false = refuse to dial); [resolve] turns the server
     * name into addresses outside the VPN; [setMss] applies TCP_MAXSEG to an unconnected socket (Android only).
     */
    class Hooks(
        val protect: ((Socket) -> Boolean)? = null,
        val resolve: ((String) -> List<InetAddress>)? = null,
        val setMss: ((Socket, Int) -> Boolean)? = null,
        val log: (String) -> Unit = {},
        val random: Random = Random.Default,
        val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        val firstRecordWaitMs: Int = FIRST_RECORD_WAIT_MS,
        val burstGapMs: Long = BURST_GAP_MS
    )

    private val server = ServerSocket(0, 64, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort
    @Volatile private var closed = false
    @Volatile var lastUsed: Long = System.currentTimeMillis(); private set
    val sliced = AtomicInteger()
    val passedThrough = AtomicInteger()

    fun start(): RecordSliceBridge {
        POOL.execute {
            while (!closed) {
                val client = try { server.accept() } catch (_: IOException) { break }
                lastUsed = System.currentTimeMillis()
                POOL.execute { handle(client) }
            }
        }
        return this
    }

    val isOpen: Boolean get() = !closed && !server.isClosed

    override fun close() {
        closed = true
        runCatching { server.close() }
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        try {
            client.tcpNoDelay = true
            val first = readFirstRecord(client)
            upstream = dial()
            val up = upstream.getOutputStream()
            val pieces = TlsRecordSlicer.slice(first.first, first.second, spec.vector, spec.bursts, hooks.random)
            val uploaded: Int
            if (pieces != null) {
                sliced.incrementAndGet()
                pieces.bursts.forEachIndexed { i, b ->
                    if (i > 0 && hooks.burstGapMs > 0) Thread.sleep(hooks.burstGapMs)
                    up.write(b); up.flush()
                }
                if (pieces.trailing.isNotEmpty()) { up.write(pieces.trailing); up.flush() }
                uploaded = pieces.wire.size
            } else {
                passedThrough.incrementAndGet()
                if (first.second > 0) { up.write(first.first, 0, first.second); up.flush() }
                uploaded = first.second
            }
            val target = upstream
            val down = POOL.submit { pump(target.getInputStream(), client.getOutputStream(), null, 0); halfClose(client) }
            pump(client.getInputStream(), up, spec.mss, uploaded)
            halfClose(target)
            runCatching { down.get() }
        } catch (e: Exception) {
            if (!closed) hooks.log("record-slice · ${spec.vector.key} · ${e.javaClass.simpleName}")
        } finally {
            runCatching { client.close() }
            runCatching { upstream?.close() }
        }
    }

    /** Up to one whole first record (or whatever came within [Hooks.firstRecordWaitMs]). */
    private fun readFirstRecord(client: Socket): Pair<ByteArray, Int> {
        val buf = ByteArray(TlsRecordSlicer.HEADER + TlsRecordSlicer.MAX_RECORD)
        val input = client.getInputStream()
        var n = 0
        val deadline = System.currentTimeMillis() + hooks.firstRecordWaitMs
        client.soTimeout = hooks.firstRecordWaitMs
        try {
            while (n < buf.size) {
                val need = if (n >= TlsRecordSlicer.HEADER) TlsRecordSlicer.firstRecordLength(buf, n) ?: break else TlsRecordSlicer.HEADER
                if (n >= need) break
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                client.soTimeout = left.toInt().coerceAtLeast(1)
                val r = input.read(buf, n, need - n)
                if (r < 0) break
                n += r
            }
        } catch (_: SocketTimeoutException) {
        } finally {
            client.soTimeout = 0
        }
        return buf to n
    }

    private fun dial(): Socket {
        val addresses = (hooks.resolve?.invoke(host) ?: InetAddress.getAllByName(host).toList())
            .sortedBy { if (it is Inet4Address) 0 else 1 }
        var failure: Exception? = null
        for (address in addresses) {
            val s = Socket()
            try {
                hooks.protect?.let { protect -> check(protect(s)) { "socket protection refused" } }
                spec.mss?.let { mss -> hooks.setMss?.invoke(s, mss) }
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(address, targetPort), hooks.connectTimeoutMs)
                return s
            } catch (e: Exception) {
                runCatching { s.close() }
                if (failure == null) failure = e else failure.addSuppressed(e)
            }
        }
        throw failure ?: IOException("no address for the server")
    }

    /** Copies [from] to [to]; with [mss], the upload up to [UplinkShield.WINDOW_BYTES] leaves in MSS-sized writes. */
    private fun pump(from: InputStream, to: OutputStream, mss: Int?, alreadySent: Int) {
        val buf = ByteArray(32 * 1024)
        var sent = alreadySent
        while (true) {
            val r = try { from.read(buf) } catch (_: IOException) { -1 }
            if (r < 0) return
            lastUsed = System.currentTimeMillis()
            if (mss != null && sent < UplinkShield.WINDOW_BYTES) {
                var off = 0
                for (size in UplinkShield.chunkSizes(r, mss, hooks.random)) {
                    to.write(buf, off, size); to.flush()
                    off += size
                }
            } else {
                to.write(buf, 0, r); to.flush()
            }
            sent += r
        }
    }

    private fun halfClose(s: Socket) {
        runCatching { if (!s.isClosed && !s.isOutputShutdown) s.shutdownOutput() }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val FIRST_RECORD_WAIT_MS = 1_500
        const val BURST_GAP_MS = 3L

        private val POOL: ExecutorService = Executors.newCachedThreadPool { r ->
            Thread(r, "record-slice").apply { isDaemon = true }
        }
    }
}

/**
 * The bridges in use, one per server and slicing spec, shared by the running core and the real-request probes
 * (both build their config with [com.example.xray.XrayConfigBuilder.buildJson]). At most [MAX_BRIDGES]; the one
 * used longest ago is closed first. The VPN service sets the hooks while it runs.
 */
object RecordSliceBridges {
    const val MAX_BRIDGES = 8

    @Volatile var protect: ((Socket) -> Boolean)? = null
    @Volatile var resolve: ((String) -> List<InetAddress>)? = null
    @Volatile var setMss: ((Socket, Int) -> Boolean)? = null
    @Volatile var log: (String) -> Unit = {}

    private data class Key(val host: String, val port: Int, val spec: RecordSliceConfig.Spec)
    private val bridges = LinkedHashMap<Key, RecordSliceBridge>()

    /** The loopback port of a running bridge to [host]:[port] for [spec], started on first use. */
    @Synchronized
    fun portFor(host: String, port: Int, spec: RecordSliceConfig.Spec): Int {
        val key = Key(host.trim().removePrefix("[").removeSuffix("]").lowercase(), port, spec)
        bridges[key]?.takeIf { it.isOpen }?.let { return it.port }
        bridges.remove(key)?.close()
        val bridge = RecordSliceBridge(key.host, port, spec, RecordSliceBridge.Hooks(
            protect = { s -> protect?.invoke(s) ?: true },
            resolve = { h -> resolve?.invoke(h) ?: InetAddress.getAllByName(h).toList() },
            setMss = { s, m -> setMss?.invoke(s, m) ?: false },
            log = { line -> log(line) }
        )).start()
        bridges[key] = bridge
        while (bridges.size > MAX_BRIDGES) {
            val oldest = bridges.entries.minByOrNull { it.value.lastUsed } ?: break
            oldest.value.close()
            bridges.remove(oldest.key)
        }
        log("record-slice · bridge ${spec.vector.key}${spec.mss?.let { " + uplink shield (MSS $it)" } ?: ""} on 127.0.0.1:${bridge.port}")
        return bridge.port
    }

    @Synchronized
    fun closeAll() {
        bridges.values.forEach { it.close() }
        bridges.clear()
    }
}
