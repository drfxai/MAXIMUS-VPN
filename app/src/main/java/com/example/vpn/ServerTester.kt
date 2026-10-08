package com.example.vpn

import com.example.data.model.ServerTestResult
import com.example.data.model.ServerTestStatus
import com.example.data.model.VlessProfile
import com.example.vless.VlessValidator
import com.example.xray.RealDelayProbe
import com.example.xray.XrayLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket

object ServerTester {

    /** A real request through a proxy takes several round trips; below this it is rated fast. */
    const val REAL_DELAY_FAST_MS = 1000L

    /**
     * Tests the server. When the VPN's Xray core is idle this is a real request through the proxy
     * ([RealDelayProbe]), so a dead config is reported as unavailable even if its server answers.
     * Otherwise it falls back to the transport probe below, which only shows the server is reachable.
     */
    suspend fun testServer(
        profile: VlessProfile,
        timeoutMs: Int = 4000,
        protectSocket: ((Socket) -> Boolean)? = null
    ): ServerTestResult = testServers(listOf(profile), timeoutMs, protectSocket).first()

    /** Batch form of [testServer]; real-delay probes run up to five configs at a time. */
    suspend fun testServers(
        profiles: List<VlessProfile>,
        timeoutMs: Int = 4000,
        protectSocket: ((Socket) -> Boolean)? = null
    ): List<ServerTestResult> = withContext(Dispatchers.IO) {
        val invalid = profiles.associate { it.id to validationError(it) }
        // Xray refuses plain VLESS to a public address; those servers run on the Kotlin tunnel, so
        // they get the reachability probe.
        val valid = profiles.filter {
            invalid[it.id] == null && !com.example.vpn.engine.EngineSelectionPolicy.usesKotlinPacketTunnel(it)
        }
        val outcomes = if (valid.isEmpty()) emptyList()
        else RealDelayProbe.measure(valid, realDelayTimeoutSec(timeoutMs))
        val real = valid.zip(outcomes).toMap()
        profiles.map { profile ->
            invalid[profile.id]?.let { msg ->
                XrayLogManager.w("SERVER", "Validation check failed for '${profile.name}': $msg")
                return@map ServerTestResult(serverId = profile.id, status = ServerTestStatus.InvalidConfig(msg))
            }
            when (val outcome = real[profile]) {
                is RealDelayProbe.Outcome.Delay -> {
                    val fast = outcome.latencyMs < REAL_DELAY_FAST_MS
                    XrayLogManager.i("SERVER", "Server '${profile.name}' carried a real request in ${outcome.latencyMs}ms (Status: ${if (fast) "EXCELLENT" else "SLOW"})")
                    ServerTestResult(profile.id, if (fast) ServerTestStatus.Available(outcome.latencyMs) else ServerTestStatus.Slow(outcome.latencyMs))
                }
                is RealDelayProbe.Outcome.Failed -> {
                    XrayLogManager.w("SERVER", "Server '${profile.name}' did not carry a request through the proxy: ${outcome.reason}")
                    ServerTestResult(profile.id, ServerTestStatus.Unavailable("No traffic through the proxy: ${outcome.reason}"))
                }
                is RealDelayProbe.Outcome.NotRun, null -> {
                    // An unprotected check while the VPN runs goes through the tunnel: it measures the
                    // current server's exit, which cannot reach many servers (a Cloudflare Worker exit
                    // cannot open Cloudflare addresses), so a working server would be reported as down.
                    if (protectSocket == null && (outcome as? RealDelayProbe.Outcome.NotRun)?.reason == RealDelayProbe.CORE_RUNNING) {
                        return@map whileTunnelRuns(profile, com.example.vpn.VpnController.connectionState.value)
                    }
                    XrayLogManager.d("SERVER", "Real-delay test not run for '${profile.name}' (${(outcome as? RealDelayProbe.Outcome.NotRun)?.reason}); checking reachability only.")
                    testTransport(profile, timeoutMs, protectSocket)
                }
            }
        }
    }

    /**
     * The result for [profile] while the VPN's core runs and no real test is possible: the server in use
     * reports the latency of the last request verified through the tunnel; any other server is not
     * measured ([ServerTestStatus.Idle]) rather than reported as down.
     */
    internal fun whileTunnelRuns(profile: VlessProfile, state: com.example.data.model.ConnectionState): ServerTestResult {
        val active = state.activeProfile
        val ping = state.pingMs
        if (state.isConnected && ping != null && active != null && active.effectiveFingerprint == profile.effectiveFingerprint) {
            return ServerTestResult(profile.id, if (ping < REAL_DELAY_FAST_MS) ServerTestStatus.Available(ping) else ServerTestStatus.Slow(ping))
        }
        com.example.vpn.diagnostics.ConnectionMetrics.notTestedWhileConnected.incrementAndGet()
        XrayLogManager.d("SERVER", "'${profile.name}' not tested while the VPN is on; disconnect to test it on this network.")
        return ServerTestResult(profile.id, ServerTestStatus.Idle)
    }

    /** A request through a Cloudflare Worker on a filtered network often needs several seconds. */
    internal fun realDelayTimeoutSec(timeoutMs: Int): Int = ((timeoutMs + 999) / 1000).coerceAtLeast(8)

    private fun validationError(profile: VlessProfile): String? = try {
        VlessValidator.validate(profile)
        com.example.vpn.engine.RuntimeCapabilities.requireSupported(profile)
        null
    } catch (e: Exception) {
        e.localizedMessage ?: "Invalid configuration"
    }

    /**
     * Reachability probe: DNS, TCP, TLS and WebSocket upgrade. It does not authenticate a proxy
     * request, so a Cloudflare Worker config passes it even when it carries no traffic.
     */
    private fun testTransport(
        profile: VlessProfile,
        timeoutMs: Int,
        protectSocket: ((Socket) -> Boolean)?
    ): ServerTestResult {
        if (profile.protocolType == com.example.data.model.ProtocolType.HYSTERIA2 ||
            profile.protocolType == com.example.data.model.ProtocolType.WIREGUARD
        ) {
            // A UDP server answers no TCP or TLS probe; only a request through the proxy tells.
            XrayLogManager.d("SERVER", "Skipping the reachability check for UDP server '${profile.name}'; it is tested with a real request while the VPN is off.")
            return ServerTestResult(serverId = profile.id, status = ServerTestStatus.Idle)
        }
        XrayLogManager.d("SERVER", "Initiating reachability check for '${profile.name}' (${profile.address}:${profile.port}, transport=${profile.transport}, sec=${profile.security})...")

        val startTime = System.nanoTime()
        var socket: Socket? = null
        var sslSocket: SSLSocket? = null

        return try {
            // Stage 1: DNS Resolution
            val inetAddress = InetAddress.getByName(profile.address)

            // Stage 2: TCP Handshake
            socket = Socket()
            check(protectSocket?.invoke(socket) != false) { "VPN socket protection failed" }
            socket.soTimeout = timeoutMs
            val socketAddress = InetSocketAddress(inetAddress, profile.port)
            socket.connect(socketAddress, timeoutMs)

            val tcpLatency = ((System.nanoTime() - startTime) / 1_000_000).coerceAtLeast(1)

            // Stage 3: TLS / Handshake Test if configured
            val sslLatency = if (profile.security.equals("tls", ignoreCase = true)) {
                val sslContext = SSLContext.getDefault()
                val sslFactory = sslContext.socketFactory
                val sniHost = profile.sni.ifBlank { profile.host.ifBlank { profile.address } }
                sslSocket = sslFactory.createSocket(socket, sniHost, profile.port, true) as SSLSocket
                sslSocket.soTimeout = timeoutMs

                val sslParams = SSLParameters().apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                    if (sniHost.isNotBlank() && !sniHost.contains(':') && !sniHost.matches(Regex("[0-9.]+"))) {
                        serverNames = listOf(SNIHostName(sniHost))
                    }
                }
                sslSocket.sslParameters = sslParams
                sslSocket.startHandshake()
                ((System.nanoTime() - startTime) / 1_000_000).coerceAtLeast(1)
            } else {
                tcpLatency
            }

            // Stage 4: WebSocket Handshake Validation if transport is WS
            val finalLatency = if (profile.transport.equals("ws", ignoreCase = true)) {
                com.example.vpn.tunnel.WebSocketHandshake.perform(sslSocket ?: socket, profile, timeoutMs)
                ((System.nanoTime() - startTime) / 1_000_000).coerceAtLeast(1)
            } else {
                sslLatency
            }

            val status = if (finalLatency < 350) {
                ServerTestStatus.Available(finalLatency)
            } else {
                ServerTestStatus.Slow(finalLatency)
            }

            XrayLogManager.i("SERVER", "Server '${profile.name}' is reachable in ${finalLatency}ms (handshake only, not a proxy request; Status: ${if (finalLatency < 350) "EXCELLENT" else "SLOW"})")

            ServerTestResult(serverId = profile.id, status = status)
        } catch (e: java.net.SocketTimeoutException) {
            val err = "Connection timed out (${timeoutMs}ms)"
            XrayLogManager.w("SERVER", "Health check timeout for '${profile.name}' (${profile.address}:${profile.port}) after ${timeoutMs}ms")
            ServerTestResult(
                serverId = profile.id,
                status = ServerTestStatus.Unavailable(err)
            )
        } catch (e: java.net.UnknownHostException) {
            val err = "DNS resolution failed for '${profile.address}'"
            XrayLogManager.w("SERVER", "Health check DNS resolution failure for '${profile.name}': $err")
            ServerTestResult(
                serverId = profile.id,
                status = ServerTestStatus.Unavailable(err)
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val err = e.localizedMessage ?: "Connection refused"
            XrayLogManager.w("SERVER", "Health check connection failed for '${profile.name}' (${profile.address}:${profile.port}): $err", e)
            ServerTestResult(
                serverId = profile.id,
                status = ServerTestStatus.Unavailable(err)
            )
        } finally {
            try { sslSocket?.close() } catch (_: Exception) {}
            try { socket?.close() } catch (_: Exception) {}
        }
    }

}
