package com.example.vpn.sidecar

import com.example.data.model.AppSettings
import com.example.xray.XrayLogManager
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Registers WARP identities through an engine that is already carrying traffic (Psiphon or Tor).
 *
 * On a network where Cloudflare's account API is filtered (cf=0), Aether can only start WireGuard with an
 * identity it saved earlier: registration itself needs that API. While the phone is connected through
 * Psiphon or Tor, the API is reachable through that engine's local SOCKS port, so the WireGuard identities
 * (outer + inner, which also cover WARP-in-WARP) are registered there in the background. Later cf=0 sessions
 * then find an identity ready instead of failing fast with "no saved identity".
 *
 * Only missing identity files are created: existing files are carried into the new generation unchanged
 * (Aether's --register keeps a file that is already there), and the generation becomes visible with the same
 * atomic pointer rename as the manual renewal. At most one attempt per [COOLDOWN_MS], whatever its result.
 */
object AetherIdentityRegistrar {
    /** Shared with the manual "Get new WARP keys" renewal: one registration at a time. */
    val lock = kotlinx.coroutines.sync.Mutex()

    /** WireGuard outer + inner identity: what cf=0 sessions use (WireGuard first, WARP-in-WARP as follow-up). */
    const val TUNNEL_KIND = "gool-classic"

    /** Engines whose verified SOCKS port carries the registration. */
    val CARRIER_ENGINES = setOf(PsiphonSidecar.ID, TorSidecar.ID)

    const val COOLDOWN_MS = 6 * 60 * 60 * 1000L
    /** Wait after the connection is verified before registering, so start-up traffic goes first. */
    const val SETTLE_MS = 30_000L
    const val TIMEOUT_MS = 180_000L
    private const val ATTEMPT_FILE = "tunnel-registration-attempt"

    fun shouldRegister(engineId: String, hasIdentity: Boolean, lastAttemptMs: Long, nowMs: Long): Boolean =
        engineId in CARRIER_ENGINES && !hasIdentity &&
            (lastAttemptMs <= 0L || nowMs < lastAttemptMs || nowMs - lastAttemptMs >= COOLDOWN_MS)

    /** The upstream Aether's account calls use: the carrier's loopback SOCKS port, with its login when it has one. */
    fun upstream(port: Int, user: String?, pass: String?): String {
        require(port in 1..65535)
        val login = if (!user.isNullOrEmpty() && !pass.isNullOrEmpty()) {
            require(user.all { it.isLetterOrDigit() || it in "-_" } && pass.all { it.isLetterOrDigit() || it in "-_" })
            "$user:$pass@"
        } else ""
        return "socks5://${login}127.0.0.1:$port"
    }

    fun lastAttempt(root: File): Long =
        runCatching { File(root, ATTEMPT_FILE).readText().trim().toLong() }.getOrDefault(0L)

    private fun markAttempt(root: File, nowMs: Long) {
        runCatching { File(root, ATTEMPT_FILE).writeText(nowMs.toString()) }
    }

    /** Copies every identity file of the active generation, so --register only adds the missing ones. */
    fun retainExisting(root: File, staging: File) {
        for (name in AetherIdentityStore.files) {
            val old = File(AetherIdentityStore.current(root), name)
            if (old.isFile) old.copyTo(File(staging, name), overwrite = true)
        }
    }

    /**
     * Blocking. Registers [TUNNEL_KIND] through [carrierUpstream] when it is missing and the cooldown allows.
     * [stillValid] is polled while waiting; when it turns false (the carrier stopped or the connection
     * changed) the registration is abandoned and nothing is published. Returns true when a generation was
     * published.
     */
    fun registerThroughCarrier(engineId: String, carrierUpstream: String, settings: AppSettings,
                               stillValid: () -> Boolean, nowMs: Long = System.currentTimeMillis()): Boolean {
        val root = AetherSidecar.identityDirectory?.invoke() ?: return false
        root.mkdirs()
        if (!shouldRegister(engineId, AetherIdentityStore.hasStored(root, TUNNEL_KIND), lastAttempt(root), nowMs)) return false
        val executable = Sidecars.executable(AetherSidecar) ?: return false
        markAttempt(root, nowMs)
        val token = Sidecars.randomToken(9)
        val staging = File(root, "pending-$token").apply { mkdirs() }
        retainExisting(root, staging)
        var process: Process? = null
        try {
            val context = SidecarContext(staging, executable, Sidecars.freeLoopbackPort(), Sidecars.randomToken(9),
                Sidecars.randomToken(), settings.operationalMode)
            val launch = AetherSidecar.prepare(AetherSidecar.profile("h2"), settings, context)
            val args = launch.command.toMutableList()
            for ((flag, name) in listOf("--config" to "base.toml", "--masque-config" to "identity.toml", "--wg-config" to "wireguard.toml")) {
                val at = args.indexOf(flag)
                check(at >= 0) { "Unexpected Aether command" }
                args[at + 1] = File(staging, name).absolutePath
            }
            args += listOf("--register", TUNNEL_KIND)
            XrayLogManager.i("BUILTIN", "aether/keys · $TUNNEL_KIND · registering through $engineId (no saved WireGuard identity)")
            process = ProcessBuilder(args).directory(staging).redirectErrorStream(true).apply {
                environment().putAll(launch.environment)
                environment()["MAXIMUS_API_UPSTREAM"] = carrierUpstream
            }.start()
            process.outputStream.close()
            val child = process
            val failure = AtomicReference("WARP registration did not complete")
            val reader = Thread({
                runCatching { child.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (SidecarProcess.isFailureLine(line)) failure.set(SidecarProcess.preferFailure(failure.get(), line))
                    }
                } }
            }, "aether-identity-carrier").apply { isDaemon = true; start() }
            val deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000
            while (running(child) && System.nanoTime() < deadline) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                if (!stillValid()) {
                    XrayLogManager.i("BUILTIN", "aether/keys · $TUNNEL_KIND · $engineId stopped before registration finished; nothing saved")
                    return false
                }
                Thread.sleep(250)
            }
            if (running(child)) {
                XrayLogManager.w("BUILTIN", "aether/keys · $TUNNEL_KIND · through $engineId · FAIL · timed out")
                return false
            }
            reader.join(500)
            if (child.exitValue() != 0) {
                XrayLogManager.w("BUILTIN", "aether/keys · $TUNNEL_KIND · through $engineId · FAIL · ${failure.get()}")
                return false
            }
            if (!stillValid()) return false
            AetherIdentityStore.publish(root, staging, TUNNEL_KIND, token)
            XrayLogManager.i("BUILTIN", "aether/keys · $TUNNEL_KIND · through $engineId · PASS (ready for WireGuard on filtered networks)")
            return true
        } finally {
            try { process?.let { if (running(it)) EngineProcessStop.stop(it) } } finally { staging.deleteRecursively() }
        }
    }

    /** Process.isAlive needs API 26; minSdk is 24. */
    private fun running(process: Process) = try { process.exitValue(); false } catch (_: IllegalThreadStateException) { true }
}
