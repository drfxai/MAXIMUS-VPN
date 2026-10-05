package com.example.vpn.sidecar

import com.example.core.SecretRedactor
import com.example.xray.XrayLogManager
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/** One running engine program. */
class SidecarProcess(
    private val name: String,
    private val process: Process,
    val socksPort: Int,
    private val readyLine: Regex? = null
) {
    @Volatile private var sawReadyLine = readyLine == null

    private val logThread = Thread({
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                // Engines can print server addresses and keys; only redacted, bounded lines reach the log.
                lines.forEachIndexed { index, line ->
                    if (!sawReadyLine && readyLine?.containsMatchIn(line) == true) sawReadyLine = true
                    if (index < MAX_LOG_LINES) XrayLogManager.d(name, SecretRedactor.redact(line.take(300)))
                }
            }
        } catch (_: Exception) {
        }
    }, "sidecar-$name-log").apply { isDaemon = true; start() }

    val isAlive: Boolean get() = process.isAlive

    /** Waits until the SOCKS port accepts connections, the program exits, or [timeoutMs] passes. */
    fun awaitReady(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) return false
            if (sawReadyLine && portOpen(socksPort)) return true
            Thread.sleep(100)
        }
        return false
    }

    fun stop() {
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
    }

    companion object {
        private const val MAX_LOG_LINES = 2_000

        fun start(name: String, launch: SidecarLaunch, workDir: File, socksPort: Int): SidecarProcess {
            val builder = ProcessBuilder(launch.command).directory(workDir).redirectErrorStream(true)
            builder.environment().putAll(launch.environment)
            return SidecarProcess(name, builder.start(), socksPort, launch.readyLine)
        }

        fun portOpen(port: Int): Boolean = try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200); true }
        } catch (_: Exception) {
            false
        }
    }
}
