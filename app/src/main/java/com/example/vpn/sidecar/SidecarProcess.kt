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
    @Volatile override var transportGeneration: Long = 0
        private set
    @Volatile private var stopping = false
    @Volatile private var ready = false
    @Volatile private var failureHint: String? = null
    @Volatile override var startupStage: String? = null
        private set
    @Volatile override var transportStageSinceMs: Long? = null
        private set
    private val psiphonStartup = PsiphonSidecar.StartupProgress()
    @Volatile override var startupFailure: String? = null
        private set

    /** Psiphon only: the last notices before readiness (type + sanitized fields), for the startup-failure event. */
    fun psiphonNotices(): String? = psiphonStartup.recentNotices()

    /** Psiphon only: what this start dialed (counts and protocol names), for the TCP retry decision and diagnostics. */
    fun psiphonDials(): PsiphonSidecar.StartupDials = psiphonStartup.dials()

    /** Psiphon only: how far the start got (0 nothing, 1 region list, 2 dialing, 3 server connected). */
    fun psiphonStage(): Int = psiphonStartup.progressStage

    /** Psiphon only: the regions its AvailableEgressRegions notice listed; null until one arrives. */
    fun psiphonAvailableRegions(): List<String>? = psiphonStartup.availableRegions()

    /** Psiphon only: the region of the server its tunnel connected to. */
    fun psiphonConnectedRegion(): String? = psiphonStartup.connectedRegion()

    private val logThread = Thread({
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                // Engines can print server addresses and keys; only redacted, bounded lines reach the log.
                lines.forEachIndexed { index, line ->
                    EngineSignals.publish(name, line)
                    reportApiTlsEvent(line)
                    // ConnectedServerRegion can follow the Tunnels notice that makes the start ready.
                    if (psiphonFamily(name) && (!ready || line.contains("ConnectedServerRegion")))
                        psiphonStartup.observe(line)
                    if (name == AetherSidecar.ID) aetherStage(line)?.let { stage ->
                        if (startupStage != stage) {
                            // An account retry warning is no longer the failure once
                            // identity loading completed and gateway work started.
                            startupFailure = null
                            failureHint = null
                        }
                        sawReadyLine = false
                        transportGeneration++
                        startupStage = stage
                        // The first time a gateway was picked: how long the tunnel has had to start serving.
                        if (stage == "transport" && !ready && transportStageSinceMs == null)
                            transportStageSinceMs = System.currentTimeMillis()
                        XrayLogManager.i("BUILTIN", "aether · $stage · pending; traffic unverified")
                    }
                    if (name == AetherSidecar.ID) aetherScanLine(line)?.let { XrayLogManager.i("BUILTIN", "aether · scan · $it") }
                    if (name == AetherSidecar.ID && !ready && startupStage == "scan" &&
                        line.trim() == "MAXIMUS_AETHER_SCAN_EXHAUSTED") {
                        startupFailure = AETHER_SCAN_EXHAUSTED
                    }
                    if (!sawReadyLine && readyLine?.containsMatchIn(line) == true) sawReadyLine = true
                    // Categorize first: a summary must not freeze an earlier, less specific cause.
                    if (name == RadianceSidecar.id) {
                        // The Android wrapper emits these fixed messages without route or account data.
                        when (val reason = line.trim()) {
                            "missing authenticated loopback settings",
                            "missing app-private Radiance data directory",
                            "Radiance initialization failed",
                            "Radiance connection was canceled",
                            "Radiance connection timed out",
                            "Radiance could not resolve a connection host",
                            "Radiance loopback port is already in use",
                            "Radiance connection permission denied",
                            "Radiance Android network monitor failed",
                            "Radiance could not build route options",
                            "Radiance could not initialize the tunnel backend",
                            "Radiance tunnel backend could not connect",
                            "Radiance could not start a Lantern route" -> startupFailure = "Radiance startup failed: $reason"
                        }
                    }
                    if (isFailureLine(line)) failureHint = preferFailure(failureHint, line)
                    if (name == AetherSidecar.ID && ((line.contains("registration", true) && isFailureLine(line)) ||
                            startupFailure?.startsWith("WARP API registration failed") == true)) {
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
    override fun awaitReady(timeoutMs: Long): Boolean = awaitReadyWhile(timeoutMs) { true }

    override fun awaitReadyUnless(timeoutMs: Long, giveUp: () -> Boolean): Boolean =
        awaitReadyGated(timeoutMs, { true }, giveUp)

    /** A helper exiting invalidates readiness immediately; do not wait for a child that cannot start. */
    internal fun awaitReadyWhile(timeoutMs: Long, prerequisitesAlive: () -> Boolean): Boolean =
        awaitReadyGated(timeoutMs, prerequisitesAlive) { false }

    /**
     * [awaitReadyWhile], and [giveUp] ends the wait early (the caller decided the failure already reported is
     * final); the reported failure is kept.
     */
    internal fun awaitReadyGated(timeoutMs: Long, prerequisitesAlive: () -> Boolean, giveUp: () -> Boolean): Boolean {
        val started = System.nanoTime()
        while ((System.nanoTime() - started) / 1_000_000 < timeoutMs) {
            if (!prerequisitesAlive()) {
                startupFailure = "Required account or exit helper stopped before engine readiness; traffic is unverified."
                return false
            }
            if (stopping) {
                // The app stopped this program (deadline, Stop, newer Connect) while it was starting: whatever exit code
                // follows (143 SIGTERM, 141 SIGPIPE once the stop closed its output pipe) is not the engine's failure.
                if (startupFailure == null) startupFailure = STOPPED_BEFORE_READY
                return false
            }
            if (!isAlive) {
                logThread.join(200) // Let the bounded diagnostic reader publish the exit reason.
                if (startupFailure == null) startupFailure = exitedBeforeReady(
                    runCatching { process.exitValue() }.getOrNull(), failureHint, stopping)
                return false
            }
            if (sawReadyLine && portOpen(socksPort)) { ready = true; return true }
            if (giveUp()) {
                if (startupFailure == null) startupFailure = "Engine start abandoned early by the connection plan; traffic is unverified."
                return false
            }
            Thread.sleep(100)
        }
        if (startupFailure == null) startupFailure = if (name == AetherSidecar.ID && !sawReadyLine)
            aetherTimeout(startupStage, failureHint, timeoutMs)
        else if (psiphonFamily(name) && !sawReadyLine)
            psiphonStartup.failureAfter(timeoutMs, label = if (name.removePrefix("lab-") == ShirokhorshidSidecar.ID) "Shir o Khorshid" else "Psiphon")
        else if (portOpen(socksPort) && !sawReadyLine)
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
        /**
         * Psiphon and its Shir o Khorshid fork print the same notices (the fork's ConsoleClient and notice.go match
         * upstream's, readiness included: `Tunnels {"count":1}`). Build 174: the fork's notices were not read, so its two
         * 120 s failures said only "readiness marker was not observed" with no notice, dial or verdict. Pure.
         */
        internal fun psiphonFamily(name: String): Boolean =
            name.removePrefix("lab-").let { it == PsiphonSidecar.ID || it == ShirokhorshidSidecar.ID }

        /** The app stopped the program before it was ready (not an engine failure). */
        const val STOPPED_BEFORE_READY = "Engine stopped by the app before it was ready (the connection attempt ended); not an engine crash."

        /** Whether a startup failure only says the app stopped the program. Pure. */
        fun stoppedByApp(failure: String?): Boolean = failure == STOPPED_BEFORE_READY

        /**
         * Why a program exited before readiness. Exit 141 is SIGPIPE (128 + 13): the program wrote to an output pipe that
         * was already closed, which happens when the app stops it ([stopping]) since stopping closes the pipe; 143 is
         * SIGTERM. Both are the app's stop when [stopping]. Pure.
         */
        fun exitedBeforeReady(code: Int?, hint: String?, stopping: Boolean): String {
            if (stopping && (code == null || code == 141 || code == 143 || code == 137 || code == 0)) return STOPPED_BEFORE_READY
            val signal = when (code) {
                141 -> " SIGPIPE: its output pipe closed"
                143 -> " SIGTERM"
                137 -> " SIGKILL"
                else -> ""
            }
            return "Engine process exited before local readiness (exit ${code ?: "unknown"}$signal, " +
                "${hint ?: "no diagnostic category"}); inspect redacted engine logs."
        }

        internal const val AETHER_SCAN_EXHAUSTED =
            "Aether gateway scan completed without a verified endpoint; traffic is unverified."

        /** Fixed native events only: never expose identity IDs, API bodies, endpoints or keys. */
        internal fun aetherStage(line: String): String? = line.trim()
            .removePrefix("MAXIMUS_AETHER_STAGE:")
            .takeIf { line.trim().startsWith("MAXIMUS_AETHER_STAGE:") &&
                it in setOf("account", "identity", "scan", "transport") }

        private val SCAN_FIELD = Regex("^(candidates|family|sni|result|failed_tcp|failed_tls|failed_h2|failed_timeout|failed_other)=([A-Za-z0-9.\\-]{1,253})$")

        /**
         * Build 180 (A7): Aether's fixed `MAXIMUS_AETHER_SCAN:` lines (harden-aether.py) as a log line: how many gateways a
         * scan tried, its family and SNI, and where the failed probes stopped. Only whitelisted key=value pairs pass. Pure.
         */
        internal fun aetherScanLine(line: String): String? {
            val body = line.trim().takeIf { it.startsWith("MAXIMUS_AETHER_SCAN:") }?.removePrefix("MAXIMUS_AETHER_SCAN:") ?: return null
            val words = body.split(' ').filter { it.isNotBlank() }
            val start = words.firstOrNull() == "start"
            val fields = words.drop(if (start) 1 else 0).mapNotNull { w -> SCAN_FIELD.matchEntire(w)?.let { it.groupValues[1] to it.groupValues[2] } }
            if (fields.isEmpty()) return null
            val map = fields.toMap()
            return if (start) "trying ${map["candidates"] ?: "?"} gateway(s) over ${map["family"] ?: "?"}, SNI ${map["sni"] ?: "?"}"
            else "result ${map["result"] ?: "?"}; failed probes: TCP ${map["failed_tcp"] ?: 0}, TLS/pin ${map["failed_tls"] ?: 0}, " +
                "HTTP/2 ${map["failed_h2"] ?: 0}, timeout ${map["failed_timeout"] ?: 0}, other ${map["failed_other"] ?: 0}"
        }

        internal fun aetherTimeout(stage: String?, hint: String?, timeoutMs: Long): String {
            val detail = hint?.let { "; $it" }.orEmpty()
            return when (stage) {
                "account" -> "WARP API registration failed (account bootstrap or refresh timed out after ${timeoutMs / 1000}s$detail); traffic is unverified."
                "identity" -> "Aether identity setup did not reach gateway scan within ${timeoutMs / 1000}s$detail; traffic is unverified."
                "scan" -> "Aether gateway scan did not reach a serving tunnel within ${timeoutMs / 1000}s$detail; traffic is unverified."
                "transport" -> "Aether transport did not start serving within ${timeoutMs / 1000}s$detail; traffic is unverified."
                else -> "Aether readiness marker missing after ${timeoutMs / 1000}s (native startup stage unavailable$detail); traffic is unverified."
            }
        }

        fun reportApiTlsEvent(line: String) {
            if (line.trim() == "MAXIMUS_AETHER_API_TLS:compatible-retry")
                XrayLogManager.i("BUILTIN", "aether/api · retrying TLS before sending HTTP with compatible API settings; CA, hostname and ECH checks preserved")
            com.example.vpn.connections.WarpApiLane.lane(line)?.let { lane ->
                XrayLogManager.i("BUILTIN", "aether/api · " + com.example.vpn.connections.WarpApiLane.describe(lane))
                com.example.vpn.diagnostics.events.EventLog.event("aether.api_lane", attributes = mapOf("lane" to lane),
                    result = if (lane == "none" || lane == "relay-invalid") "FAIL" else "PASS")
            }
        }

        fun isFailureLine(line: String): Boolean = listOf("error", "failed", "no such file", "unknown argument",
            "timed out", "timeout", "did not answer", "deadline has elapsed", "connection refused", "connection reset",
            "unexpected eof", "caused by:", "HTTP 429", "HTTP 403", "HTTP 401",
            "status 429", "status 403", "status 401").any { line.contains(it, true) }

        fun failureCategory(line: String): String = when {
            (line.contains("HTTP 429", true) || line.contains("status 429", true)) -> "WARP API rate limited (HTTP 429)"
            (line.contains("HTTP 403", true) || line.contains("status 403", true)) -> "WARP API returned HTTP 403"
            (line.contains("HTTP 401", true) || line.contains("status 401", true)) -> "WARP API returned HTTP 401"
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
                "certificate trust or hostname verification failed", "unsupported command-line option",
                "required file missing", "file permission denied", "WARP API rate limited (HTTP 429)",
                "WARP API returned HTTP 403", "WARP API returned HTTP 401" -> 4
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
