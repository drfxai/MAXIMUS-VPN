package com.example.vpn.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.example.vpn.connectivity.FirewallBehaviour.Outcome
import com.example.vpn.connectivity.FirewallBehaviour.Probe
import com.example.vpn.smart.NetworkCapabilityDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs [FirewallBehaviour]'s three handshakes on the phone's own network (never through the VPN), at Connect only,
 * at most once per network per [FirewallBehaviour.TTL_MS], in the background: a Connect never waits for it, and the
 * result feeds [NetworkFirewalls.detect] only when it is confident. Never on a timer and never while connected.
 *
 * The handshakes are reachability checks only: the client sends one ClientHello and reads the first record back, then
 * closes. No key exchange finishes, no data is sent, and nothing here trusts or skips a certificate.
 */
object FirewallProbe {
    /** TCP connect limit per handshake. */
    const val CONNECT_TIMEOUT_MS = 2_500
    /** How long to wait for the server's first record after the hello. */
    const val REPLY_TIMEOUT_MS = 3_000
    /** Gap between the two record writes of the split hello. */
    private const val SPLIT_DELAY_MS = 2L

    /** Cloudflare anycast addresses that serve every Cloudflare name (any one edge answers any `*.workers.dev`). */
    private val CLOUDFLARE_V4 = byteArrayOf(104.toByte(), 16, 124, 96)
    private val CLOUDFLARE_V6 = byteArrayOf(0x26, 0x06, 0x47, 0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0x68, 0x10, 0x7c, 0x60)

    @Volatile var store: FirewallBehaviour.Store? = null
    private val running = AtomicBoolean(false)

    /** The phone moved to another network (or lost it): forget a result measured elsewhere. No probe. */
    fun onNetworkChanged(context: Context) {
        val s = store ?: return
        s.onNetwork(runCatching { com.example.vpn.smart.NetworkIdentity.current(context) }.getOrNull())
    }

    /**
     * At Connect: forget a result from another network at once (so this Connect falls back to the network code), and
     * start one probe round in [scope] when none is fresh for this network. Returns immediately.
     */
    fun atConnect(context: Context, scope: CoroutineScope) {
        val s = store ?: return
        val app = context.applicationContext
        val identity = runCatching { com.example.vpn.smart.NetworkIdentity.current(app) }.getOrNull() ?: return
        s.onNetwork(identity)
        if (identity == "other" || !s.due(identity) || !running.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                val entry = runRound(app, identity) ?: return@launch
                // A network change while probing makes the round worthless.
                if (runCatching { com.example.vpn.smart.NetworkIdentity.current(app) }.getOrNull() != identity) return@launch
                s.record(entry)
                com.example.vpn.diagnostics.events.EventLog.event("firewall.probe", attributes = mapOf(
                    "verdict" to entry.verdict.key, "confident" to entry.confident.toString(),
                    "outcomes" to entry.summary(), "reason" to entry.reason))
            } catch (_: Exception) {
            } finally {
                running.set(false)
            }
        }
    }

    private suspend fun runRound(context: Context, identity: String): FirewallBehaviour.Entry? = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@withContext null
        val network = NetworkCapabilityDetector.physical(cm) ?: return@withContext null
        val addresses = cm.getLinkProperties(network)?.linkAddresses.orEmpty().map { it.address }
        val hasV4 = addresses.any { it is Inet4Address }
        val hasV6 = addresses.any { it is Inet6Address && !it.isLinkLocalAddress && !it.isSiteLocalAddress && !it.isLoopbackAddress }
        val random = SecureRandom()
        val name = FirewallBehaviour.probeServerName(random)
        val v4 = InetAddress.getByAddress(CLOUDFLARE_V4)
        val v6 = InetAddress.getByAddress(CLOUDFLARE_V6)
        val outcomes = coroutineScope {
            listOf(
                async { Probe.V4_CHROME to (if (hasV4) handshake(network, v4, listOf(FirewallBehaviour.chromeHello(name, random))) else Outcome.NOT_RUN) },
                async { Probe.V4_SPLIT to (if (hasV4) handshake(network, v4, FirewallBehaviour.splitSmallHello(name, 6 + random.nextInt(27), random)) else Outcome.NOT_RUN) },
                async { Probe.V6_CHROME to (if (hasV6) handshake(network, v6, listOf(FirewallBehaviour.chromeHello(name, random))) else Outcome.NOT_RUN) }
            ).awaitAll().toMap()
        }
        val c = FirewallBehaviour.classify(outcomes)
        FirewallBehaviour.Entry(identity, com.example.vpn.smart.NetworkKey.current(context), System.currentTimeMillis(),
            c.verdict, c.confident, c.reason, outcomes)
    }

    /** One TCP connection: send [writes] in order (separate TCP writes), read the first bytes back. */
    private fun handshake(network: Network, address: InetAddress, writes: List<ByteArray>): Outcome {
        val socket: Socket = try {
            network.socketFactory.createSocket()
        } catch (_: Exception) {
            return Outcome.NOT_RUN
        }
        return socket.use { s ->
            try {
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(address, 443), CONNECT_TIMEOUT_MS)
            } catch (_: SecurityException) {
                return@use Outcome.NOT_RUN
            } catch (_: Exception) {
                return@use Outcome.UNREACHABLE
            }
            try {
                s.soTimeout = REPLY_TIMEOUT_MS
                val out = s.getOutputStream()
                writes.forEachIndexed { i, w ->
                    out.write(w); out.flush()
                    if (i < writes.lastIndex) Thread.sleep(SPLIT_DELAY_MS)
                }
                val head = ByteArray(3)
                var n = 0
                val input = s.getInputStream()
                while (n < head.size) {
                    val r = input.read(head, n, head.size - n)
                    if (r < 0) break
                    n += r
                    FirewallBehaviour.readReply(head, n)?.let { return@use it }
                }
                FirewallBehaviour.readReply(head, n) ?: Outcome.CUT
            } catch (_: SocketTimeoutException) {
                Outcome.CUT
            } catch (_: IOException) {
                Outcome.CUT
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); Outcome.NOT_RUN
            }
        }
    }
}
