package com.example.vpn.connections

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Screen-on / network-change prewarm of the Aether path, while the VPN is idle (not up, not connecting).
 * Adapted from UAC SNI Spoofer's screen-on warm-up (UacVpnService.maybePrewarm) and its clean-HTTP/2
 * "speed pick" (Floxu1/UAC-SNI-Spoofer-Android, see THIRD_PARTY_NOTICES.md). UAC warms a connected Xray
 * path; MAXIMUS warms the *next connect* instead: it races a bare TCP connect to the clean H2 peers
 * remembered for the current network ([CleanH2PeerMemory]) and reorders them by connect time, so the
 * first Aether attempt dials the gateway that answers fastest right now and a dead one is moved back.
 *
 * Kept deliberately small for battery and metered data (Irancell cell:43235 is metered): at most
 * [MAX_PEERS] TCP handshakes (no TLS, no payload: a few hundred bytes), never while the VPN is up or busy,
 * never in battery saver, never with nothing remembered for this network, and at most once per
 * [METERED_GAP_MS] on a metered link ([UNMETERED_GAP_MS] otherwise) per network key.
 */
object AetherPrewarm {
    const val METERED_GAP_MS = 20 * 60 * 1000L
    const val UNMETERED_GAP_MS = 5 * 60 * 1000L
    const val CONNECT_TIMEOUT_MS = 2_500
    const val MAX_PEERS = 3
    /** Network callbacks fire several times while a link settles; wait before reading its key. */
    const val NETWORK_SETTLE_MS = 4_000L

    enum class Skip { VPN_ACTIVE, POWER_SAVE, NO_NETWORK, NOTHING_REMEMBERED, TOO_SOON }

    private val lastRun = mutableMapOf<String, Long>()

    /** Why a prewarm would not run now, or null when it should. Pure apart from the per-network gap. */
    @Synchronized
    fun skip(network: String?, vpnActive: Boolean, powerSave: Boolean, metered: Boolean, peers: Int,
             now: Long): Skip? = when {
        vpnActive -> Skip.VPN_ACTIVE
        powerSave -> Skip.POWER_SAVE
        network.isNullOrBlank() -> Skip.NO_NETWORK
        peers == 0 -> Skip.NOTHING_REMEMBERED
        lastRun[network]?.let { now - it in 0 until (if (metered) METERED_GAP_MS else UNMETERED_GAP_MS) } == true -> Skip.TOO_SOON
        else -> null
    }

    @Synchronized
    internal fun markRun(network: String, now: Long) { lastRun[network] = now }

    @Synchronized
    internal fun resetForTest() = lastRun.clear()

    data class Outcome(val network: String, val probed: Int, val answered: Int, val bestMs: Long?, val best: String?)

    /**
     * Runs one prewarm for [network] when [skip] allows it. [connect] returns the TCP connect time to an
     * "ip:port" or null; it runs for every peer in parallel. Returns null when skipped.
     */
    fun run(network: String?, reason: String, vpnActive: Boolean, powerSave: Boolean, metered: Boolean,
            now: Long = System.currentTimeMillis(), connect: (String) -> Long? = ::tcpConnectMs,
            log: (String) -> Unit = { com.example.xray.XrayLogManager.i("BUILTIN", it) }): Outcome? {
        val peers = CleanH2PeerMemory.peers(network, now).take(MAX_PEERS)
        skip(network, vpnActive, powerSave, metered, peers.size, now)?.let { return null }
        markRun(network!!, now)
        val pool = Executors.newFixedThreadPool(peers.size)
        val rtt = try {
            peers.map { p -> p.address to pool.submit(Callable { runCatching { connect(p.address) }.getOrNull() }) }
                .associate { (address, future) ->
                    address to runCatching { future.get(CONNECT_TIMEOUT_MS + 1_000L, TimeUnit.MILLISECONDS) }.getOrNull()
                }
        } finally { pool.shutdownNow() }
        val ordered = CleanH2PeerMemory.applyPrewarm(network, rtt, now)
        val answered = rtt.values.count { it != null }
        val best = ordered.firstOrNull()?.takeIf { it.rttMs != null && it.misses == 0 }
        log("aether/prewarm · $network · $reason · $answered/${peers.size} clean H2 peer(s) answer" +
            (best?.let { " · best ${CleanH2PeerMemory.masked(it.address)} ${it.rttMs} ms; next connect tries it first" }
                ?: " · none answered; next connect scans as usual") + (if (metered) " · metered link" else ""))
        return Outcome(network, peers.size, answered, best?.rttMs, best?.address)
    }

    /** One TCP handshake, no payload. */
    fun tcpConnectMs(address: String): Long? {
        val host = if (address.startsWith("[")) address.substringAfter('[').substringBefore(']') else address.substringBeforeLast(':')
        val port = address.substringAfterLast(':').toIntOrNull() ?: return null
        val start = System.nanoTime()
        return runCatching {
            Socket().use { it.connect(InetSocketAddress(java.net.InetAddress.getByName(host), port), CONNECT_TIMEOUT_MS) }
            (System.nanoTime() - start) / 1_000_000
        }.getOrNull()
    }
}
