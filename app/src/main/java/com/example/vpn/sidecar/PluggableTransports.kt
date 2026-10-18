package com.example.vpn.sidecar

import com.example.core.SecretRedactor
import com.example.xray.XrayLogManager
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Runs lyrebird (Tor's pluggable transports) as the app's own process instead of letting Tor start
 * it. Tor 0.4.9 in tor-android aborts in its managed-proxy code (transports.c
 * assert_unconfigured_count_ok) on phones, so Tor is given ready SOCKS ports instead
 * (`ClientTransportPlugin snowflake socks5 127.0.0.1:port`) and never manages a proxy itself.
 * lyrebird speaks the standard managed-proxy protocol on stdout (VERSION, CMETHOD, CMETHODS DONE).
 */
class PluggableTransports private constructor(
    private val process: Process,
    /** Transport name to the loopback SOCKS port lyrebird opened for it. */
    val methods: Map<String, Int>
) {
    val isAlive: Boolean get() = try { process.exitValue(); false } catch (_: IllegalThreadStateException) { true }

    fun stop() {
        EngineProcessStop.stop(process)
    }

    companion object {
        private const val TAG = "TOR"

        /**
         * Starts [binary] for [transports] and waits until it reports its SOCKS ports. Throws with
         * lyrebird's own reason when it cannot offer them.
         */
        fun start(binary: File, transports: Set<String>, stateDir: File, upstreamSocks: Int?, timeoutMs: Long = 15_000): PluggableTransports {
            require(transports.isNotEmpty())
            stateDir.mkdirs()
            val builder = ProcessBuilder(binary.absolutePath).directory(stateDir).redirectErrorStream(true)
            builder.environment().apply {
                put("TOR_PT_MANAGED_TRANSPORT_VER", "1")
                put("TOR_PT_CLIENT_TRANSPORTS", transports.joinToString(","))
                put("TOR_PT_STATE_LOCATION", stateDir.absolutePath + "/")
                put("TOR_PT_EXIT_ON_STDIN_CLOSE", "1")
                if (upstreamSocks != null) put("TOR_PT_PROXY", "socks5://127.0.0.1:$upstreamSocks")
            }
            val process = builder.start()
            val lines = LinkedBlockingQueue<String>(128)
            val collecting = java.util.concurrent.atomic.AtomicBoolean(true)
            Thread({
                try {
                    process.inputStream.bufferedReader().useLines { seq ->
                        seq.forEachIndexed { i, line ->
                            if (collecting.get()) lines.offer(line)
                            if (i < 500) logLine(line)
                        }
                    }
                } catch (_: Exception) {
                }
                lines.offer(EOF)
            }, "lyrebird-out").apply { isDaemon = true; start() }

            try {
            val methods = mutableMapOf<String, Int>()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val left = deadline - System.currentTimeMillis()
                val line = if (left > 0) lines.poll(left, TimeUnit.MILLISECONDS) else null
                if (line == null || line == EOF) {
                    EngineProcessStop.stop(process)
                    error(if (line == null) "Tor's bridge program did not report in time" else "Tor's bridge program stopped before it was ready")
                }
                when (val r = parse(line)) {
                    is Reply.Method -> methods[r.transport] = r.port
                    is Reply.Failed -> { EngineProcessStop.stop(process); error("Tor's bridge program: ${r.reason.take(200)}") }
                    Reply.Done -> break
                    null -> Unit
                }
            }
            if (methods.isEmpty()) { EngineProcessStop.stop(process); error("Tor's bridge program offered none of: ${transports.joinToString()}") }
            XrayLogManager.i(TAG, "Bridge program ready: ${methods.keys.joinToString()}")
            return PluggableTransports(process, methods)
            } catch (t: Throwable) {
                EngineProcessStop.stop(process)
                throw t
            } finally {
                collecting.set(false)
                lines.clear()
            }
        }

        private const val EOF = "\u0000eof"

        private fun logLine(line: String) {
            // Only the protocol's status and log lines are worth showing; they can name bridge addresses.
            val text = when {
                line.startsWith("LOG ") -> line.substringAfter("MESSAGE=", line).trim('"')
                line.contains("ERROR") -> line
                else -> return
            }
            XrayLogManager.i(TAG, "Bridge program: " + SecretRedactor.redact(text.take(240)))
        }

        sealed class Reply {
            data class Method(val transport: String, val port: Int) : Reply()
            data class Failed(val reason: String) : Reply()
            object Done : Reply()
        }

        /** One stdout line of the managed-proxy protocol; null for lines that need no action. */
        fun parse(line: String): Reply? {
            val parts = line.trim().split(' ')
            return when (parts.firstOrNull()) {
                "CMETHOD" -> {
                    // CMETHOD <transport> socks5 127.0.0.1:<port>
                    if (parts.size < 4 || parts[2] != "socks5") return null
                    val host = parts[3].substringBeforeLast(':')
                    val port = parts[3].substringAfterLast(':').toIntOrNull() ?: return null
                    if (host != "127.0.0.1" || port !in 1..65_535) return null
                    Reply.Method(parts[1], port)
                }
                "CMETHODS" -> if (parts.getOrNull(1) == "DONE") Reply.Done else null
                // One transport failing is logged; the others may still work.
                "ENV-ERROR", "VERSION-ERROR", "PROXY-ERROR" -> Reply.Failed(parts.drop(1).joinToString(" "))
                else -> null
            }
        }
    }
}
