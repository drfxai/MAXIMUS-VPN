package com.example.vpn.sidecar

import com.example.core.SecretRedactor
import com.example.xray.XrayLogManager
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/** One running engine program. */
class SidecarProcess(
    private val name: String,
    private val process: Process,
    val socksPort: Int,
    private val readyLine: Regex? = null,
    private val logOutput: Boolean = true
) : RunningEngine {
    @Volatile private var sawReadyLine = readyLine == null
    @Volatile private var stopping = false
    @Volatile private var ready = false
    @Volatile private var failureHint: String? = null
    @Volatile override var startupFailure: String? = null
        private set

    private val logThread = Thread({
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                // Engines can print server addresses and keys; only redacted, bounded lines reach the log.
                lines.forEachIndexed { index, line ->
                    if (!sawReadyLine && readyLine?.containsMatchIn(line) == true) sawReadyLine = true
                    // Categorize first: a summary must not freeze an earlier, less specific cause.
                    if (isFailureLine(line)) failureHint = preferFailure(failureHint, line)
                    if ((line.contains("registration", true) && isFailureLine(line)) || startupFailure?.startsWith("WARP API registration failed") == true) {
                        startupFailure = "WARP API registration failed (${failureHint ?: failureCategory(line)})"
                    }
                    if (logOutput && index < MAX_LOG_LINES) XrayLogManager.d(name, SecretRedactor.redact(line).take(300))
                }
            }
        } catch (_: Exception) {
        }
        // Output ends when the program exits: record whether that was asked for.
        val code = runCatching { process.waitFor() }.getOrNull()
        com.example.vpn.diagnostics.events.RuntimeHealth.engineTerminated(name, code, expected = stopping, startup = !ready)
    }, "sidecar-$name-log").apply { isDaemon = true; start() }

    // Process.isAlive, waitFor(timeout) and destroyForcibly need API 26; the app supports 24.
    override val isAlive: Boolean get() = try {
        process.exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }

    /** Waits until the SOCKS port accepts connections, the program exits, or [timeoutMs] passes. */
    override fun awaitReady(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isAlive) {
                logThread.join(200) // Let the bounded diagnostic reader publish the exit reason.
                if (startupFailure == null) startupFailure = "Engine process exited before local readiness " +
                    "(exit ${runCatching { process.exitValue() }.getOrNull() ?: "unknown"}, " +
                    "${failureHint ?: "no diagnostic category"}); inspect redacted engine logs."
                return false
            }
            if (sawReadyLine && portOpen(socksPort)) { ready = true; return true }
            Thread.sleep(100)
        }
        if (startupFailure == null) startupFailure = if (portOpen(socksPort) && !sawReadyLine)
            "Local proxy socket opened, but the required transport readiness marker was not observed within ${timeoutMs / 1000}s; traffic is unverified."
        else if (readyLine != null) "Engine never reported a connected session with its server within ${timeoutMs / 1000}s: " +
            "no reply came back through its transport (for a DNS tunnel: check the NS delegation and the resolvers); inspect engine logs."
        else "Engine did not open a ready local proxy within ${timeoutMs / 1000}s; inspect engine logs."
        return false
    }

    override fun stop() {
        stopping = true
        EngineProcessStop.stop(process)
    }

    companion object {
        fun isFailureLine(line: String): Boolean = listOf("error", "failed", "no such file", "unknown argument",
            "timed out", "timeout", "did not answer", "deadline has elapsed", "connection refused", "connection reset",
            "unexpected eof", "caused by:").any { line.contains(it, true) }

        fun failureCategory(line: String): String = when {
            line.contains("timed out", true) || line.contains("timeout", true) || line.contains("did not answer", true) || line.contains("deadline has elapsed", true) -> "network operation timed out"
            line.contains("certificate_verify_failed", true) || line.contains("certificate verify failed", true) || line.contains("hostname mismatch", true) -> "certificate trust or hostname verification failed"
            line.contains("certificate", true) -> "certificate processing failed"
            line.contains("tls", true) || line.contains("handshake", true) -> "TLS handshake failed"
            line.contains("connection refused", true) || line.contains("connection reset", true) || line.contains("unexpected eof", true) -> "account network connection closed or refused"
            line.contains("registration", true) || line.contains("enroll", true) -> "WARP registration failed"
            line.contains("permission denied", true) -> "file permission denied"
            line.contains("no such file", true) || line.contains("not found", true) -> "required file missing"
            line.contains("unknown argument", true) || line.contains("unrecognized option", true) -> "unsupported command-line option"
            line.contains("config", true) || line.contains("toml", true) -> "configuration rejected"
            else -> "engine reported an error"
        }

        fun preferFailure(previous: String?, line: String): String {
            val next = failureCategory(line)
            fun rank(value: String?) = when (value) {
                "certificate trust or hostname verification failed", "unsupported command-line option", "required file missing", "file permission denied" -> 4
                "certificate processing failed", "TLS handshake failed", "configuration rejected" -> 3
                "network operation timed out", "account network connection closed or refused" -> 2
                else -> 0
            }
            return if (previous != null && rank(previous) > rank(next)) previous else next
        }

        private const val MAX_LOG_LINES = 2_000

        fun start(name: String, launch: SidecarLaunch, workDir: File, socksPort: Int): SidecarProcess {
            val builder = ProcessBuilder(launch.command).directory(workDir).redirectErrorStream(true)
            builder.environment().putAll(launch.environment)
            val process = builder.start()
            process.outputStream.close() // Never leave a non-interactive engine waiting for terminal input.
            return SidecarProcess(name, process, socksPort, launch.readyLine, launch.logOutput)
        }

        fun portOpen(port: Int): Boolean = try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200); true }
        } catch (_: Exception) {
            false
        }
    }
}
