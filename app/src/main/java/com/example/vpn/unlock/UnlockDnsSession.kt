package com.example.vpn.unlock

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import com.example.vpn.packet.IPv4Header
import com.example.vpn.safety.UnlockDnsRoutePolicy
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A running Unlock mode session on its DNS-only TUN ([UnlockDnsRoutePolicy]).
 *
 * One reader thread takes the DNS questions apps send to the TUN's DNS address; a small bounded pool asks the
 * current Unlock server ([UnlockDnsWire.exchange]: protected UDP/53, TCP/53 when UDP is silent or truncated) and
 * writes each answer back as an IPv4/UDP packet. Nothing else ever enters this TUN. After [FAILOVER_AFTER]
 * unanswered questions in a row, an auto-picked session moves to the next usable server of the last check.
 */
class UnlockDnsSession(
    private val tun: ParcelFileDescriptor,
    initialServer: String,
    initialChecks: List<ServerCheck>,
    @Volatile var autoPick: Boolean,
    private val protector: UnlockDnsWire.Protector,
    private val log: (String) -> Unit,
    /** The session moved to another server (failover); the service updates its state and notification. */
    private val onSwitch: (server: String, latencyMs: Long?) -> Unit
) {
    @Volatile var server: String = initialServer
        private set
    @Volatile var checks: List<ServerCheck> = UnlockDnsHealth.rank(initialChecks)
        private set
    /** Smoothed upstream round trip of answered questions, for the Home pill. */
    @Volatile var latencyMs: Long? = initialChecks.firstOrNull { it.ip == initialServer }?.latencyMs
        private set
    @Volatile private var running = false
    private var reader: Thread? = null
    private val failures = AtomicInteger()
    private val dnsAddress = IPv4Header.parseIp(UnlockDnsRoutePolicy.DNS_SERVER)
    private val output = FileOutputStream(tun.fileDescriptor)
    private val workers = ThreadPoolExecutor(2, WORKERS, 30, TimeUnit.SECONDS, ArrayBlockingQueue(QUEUE),
        ThreadPoolExecutor.DiscardPolicy()).apply { allowCoreThreadTimeOut(true) }

    fun start() {
        running = true
        reader = Thread({ readLoop() }, "unlock-dns-reader").apply { isDaemon = true; start() }
        log("Unlock DNS: answering lookups through $server.")
    }

    fun stop() {
        running = false
        reader?.interrupt()
        reader = null
        workers.shutdownNow()
    }

    /** Uses [ip] from now on (the user's manual choice, or a re-check found a better one). */
    fun switchTo(ip: String, latency: Long?) {
        if (ip == server) return
        server = ip
        latencyMs = latency
        failures.set(0)
        log("Unlock DNS: now using $ip.")
        onSwitch(ip, latency)
    }

    /** New health results (network change, or the user re-checked); drops a dead server when auto-picking. */
    fun updateChecks(fresh: List<ServerCheck>, manual: String?) {
        checks = UnlockDnsHealth.rank(fresh)
        val best = UnlockDnsHealth.pick(checks, autoPick, manual) ?: return
        val current = checks.firstOrNull { it.ip == server }
        if (best != server && (!autoPick || current == null || !current.usable)) {
            switchTo(best, checks.firstOrNull { it.ip == best }?.latencyMs)
        } else if (current?.latencyMs != null) {
            latencyMs = current.latencyMs
        }
    }

    private fun readLoop() {
        val tunFd = tun.fileDescriptor
        val buffer = ByteArray(UnlockDnsRoutePolicy.MTU)
        val poll = StructPollfd()
        poll.fd = tunFd
        poll.events = OsConstants.POLLIN.toShort()
        while (running) {
            try {
                poll.revents = 0
                if (Os.poll(arrayOf(poll), POLL_MS) == 0) continue
                if (poll.revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0) break
                val n = Os.read(tunFd, buffer, 0, buffer.size)
                if (n <= 0) continue
                val query = UnlockDnsWire.parseQuery(buffer, n, dnsAddress) ?: continue
                workers.execute { answer(query) }
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) continue
                break
            } catch (_: InterruptedException) {
                break
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                break
            }
        }
    }

    private fun answer(query: UnlockDnsWire.Query) {
        val asked = server
        val exchange = UnlockDnsWire.exchange(asked, query.payload, UPSTREAM_TIMEOUT_MS, protector)
            ?.takeIf { !it.intercepted }
        if (exchange == null) {
            onUnanswered(asked)
            UnlockDnsWire.servfail(query.payload)?.let { write(UnlockDnsWire.reply(query, it)) }
            return
        }
        failures.set(0)
        latencyMs = latencyMs?.let { (it * 3 + exchange.ms) / 4 } ?: exchange.ms
        if (exchange.response.size + PACKET_OVERHEAD > UnlockDnsRoutePolicy.MTU) {
            UnlockDnsWire.servfail(query.payload)?.let { write(UnlockDnsWire.reply(query, it)) }
            return
        }
        write(UnlockDnsWire.reply(query, exchange.response))
    }

    private fun onUnanswered(asked: String) {
        if (failures.incrementAndGet() < FAILOVER_AFTER || !autoPick || asked != server) return
        val next = UnlockDnsHealth.next(checks, asked) ?: return
        log("Unlock DNS: $asked stopped answering; moving to $next.")
        checks = UnlockDnsHealth.rank(checks.map { if (it.ip == asked) it.copy(health = ServerHealth.BLOCKED, reason = BlockReason.NO_ANSWER) else it })
        switchTo(next, checks.firstOrNull { it.ip == next }?.latencyMs)
    }

    private fun write(packet: ByteArray) {
        if (!running) return
        try {
            synchronized(output) { output.write(packet) }
        } catch (_: IOException) {
            // The TUN closed under us (Disconnect or a mode change): the session is ending.
        }
    }

    companion object {
        const val UPSTREAM_TIMEOUT_MS = 2_500
        const val FAILOVER_AFTER = 3
        private const val WORKERS = 8
        private const val QUEUE = 256
        private const val POLL_MS = 500
        private const val PACKET_OVERHEAD = 28
    }
}
