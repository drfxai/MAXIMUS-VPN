package com.example.vpn.sidecar

import com.example.data.model.AppSettings
import org.json.JSONObject
import java.io.File

/**
 * Lantern (Radiance) as the relay hop under Aether: WARP's MASQUE HTTP/2 tunnel is dialled through Radiance's local
 * SOCKS5 port (Aether's own `AETHER_UPSTREAM`, the mechanism its `--psiphon` / `--tor` chain modes use), so the
 * gateway scan, the TLS to Cloudflare and the tunnel itself leave the phone inside Lantern's protocol instead of as
 * Cloudflare TLS that the carrier filters.
 *
 * Build 170 on Irancell (metered, cf_quick = filtered): every direct gateway scan ended "completed without a verified
 * endpoint" while Lantern, Serverless and Tor carried traffic on the same network. The session runner uses this hop
 * only for the user's own Aether run when Cloudflare is filtered and no saved server is verified here for the
 * saved-server relay ([com.example.vpn.connections.ConnectionEngineRunner.lanternRelayEligible]).
 *
 * HTTP/2 only: Radiance's SOCKS port is not known to carry UDP, which MASQUE H3 and WireGuard would need.
 * The hop is a helper of the Aether process: it is stopped with it.
 */
object LanternRelayHop {
    /** Extras key, set only by the session runner on the relayed HTTP/2 candidate. */
    const val EXTRA = "lanternHop"

    const val RUNTIME_MISSING = "Lantern relay hop is not part of this build"
    const val NOT_READY = "Lantern relay hop did not become ready"
    const val PROCESS_FAILED = "Lantern relay hop could not start"
    /** Fixed, non-secret reasons: safe to show and log verbatim. */
    val REASONS = setOf(RUNTIME_MISSING, NOT_READY, PROCESS_FAILED)

    fun requested(extras: JSONObject): Boolean = extras.optBoolean(EXTRA)

    /** Whether this build carries the Radiance program for this phone; replaced in tests. */
    @Volatile var available: () -> Boolean = { Sidecars.isBundled(RadianceSidecar) }

    /** Aether's upstream setting for Radiance's authenticated loopback SOCKS5 port (tokens are URL-safe base64). */
    fun upstream(user: String, pass: String, port: Int): String = "socks5://$user:$pass@127.0.0.1:$port"

    /** Starts Radiance on its own loopback port and work folder and waits until it can carry traffic. */
    fun start(settings: AppSettings, parent: SidecarContext): BuiltinExitNode.Started {
        val executable = Sidecars.executable(RadianceSidecar) ?: throw BuiltinExitNode.ExitNodeFailure(RUNTIME_MISSING)
        val context = parent.copy(workDir = File(parent.workDir, "lantern-hop").apply { mkdirs() },
            executable = executable, socksPort = Sidecars.freeLoopbackPort(),
            socksUser = Sidecars.randomToken(9), socksPass = Sidecars.randomToken(), upstreamSocks = null)
        val launch = RadianceSidecar.prepare(RadianceSidecar.profile(), settings, context)
        val process = try { SidecarProcess.start("lantern-hop", launch, context.workDir, context.socksPort) } catch (e: java.io.IOException) {
            throw BuiltinExitNode.ExitNodeFailure(PROCESS_FAILED, e)
        }
        try {
            if (!process.awaitReady(launch.readyTimeoutMs)) throw BuiltinExitNode.ExitNodeFailure(NOT_READY)
            return BuiltinExitNode.Started(process, upstream(context.socksUser, context.socksPass, context.socksPort))
        } catch (e: Throwable) {
            process.stop()
            throw e
        }
    }
}
