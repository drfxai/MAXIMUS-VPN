package com.example.vpn.experimental

import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Bounded TCP measurements, never proof of an IP-layer allowlist or a working proxy carrier. */
object ReachabilityScanner {
    data class Target(val address: String, val port: Int)
    data class Result(val target: Target, val network: String, val measuredAt: Long, val successes: Int, val medianMs: Long?) {
        val reachable: Boolean get() = successes > 0
    }
    fun targets(raw: String): List<Target> {
        val entries = raw.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        require(entries.size in 1..32) { "Enter 1 to 32 public IP endpoints, one per line" }
        return entries.map { text ->
            val pair = if (text.startsWith("[")) {
                require(text.contains(']')) { "Invalid IPv6 endpoint" }
                text.substringAfter('[').substringBefore(']') to text.substringAfter(']').removePrefix(":").ifBlank { "443" }
            } else if (text.count { it == ':' } == 1) text.substringBefore(':') to text.substringAfter(':')
            else text to "443"
            val port = requireNotNull(pair.second.toIntOrNull()) { "Invalid port" }
            require(port in 1..65535) { "Invalid port" }
            Target(FrontedTunnel.publicAddress(pair.first), port)
        }.distinct()
    }
    suspend fun scan(targets: List<Target>, network: String, socket: () -> Socket): List<Result> = withContext(Dispatchers.IO) {
        require(targets.size in 1..32)
        val limit = Semaphore(4)
        coroutineScope {
            targets.map { target -> async {
                limit.withPermit {
                    val timings = mutableListOf<Long>()
                    repeat(2) {
                        ensureActive()
                        val start = System.nanoTime()
                        runCatching { socket().use { s -> s.connect(InetSocketAddress(target.address, target.port), 1200) } }
                            .onSuccess { timings += (System.nanoTime() - start) / 1_000_000 }
                    }
                    ensureActive()
                    Result(target, network, System.currentTimeMillis(), timings.size, timings.sorted().getOrNull(timings.size / 2))
                }
            } }.awaitAll().sortedWith(compareByDescending<Result> { it.successes }.thenBy { it.medianMs ?: Long.MAX_VALUE })
        }
    }
}
