package com.example.vpn.sidecar

import com.example.data.model.AppSettings
import com.example.vpn.verify.Socks5
import org.json.JSONObject
import java.io.File
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

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
    const val CLOUDFLARE_UNREACHABLE = "Lantern relay hop could not reach Cloudflare"
    /** Fixed, non-secret reasons: safe to show and log verbatim. */
    val REASONS = setOf(RUNTIME_MISSING, NOT_READY, PROCESS_FAILED, CLOUDFLARE_UNREACHABLE)

    /**
     * The host the preflight completes a TLS handshake with through Radiance: WARP's account API
     * ([com.example.vpn.warp.WarpRegistration.API_URL]), on Cloudflare's network like the MASQUE gateways. Aether's
     * registration calls, gateway scan and tunnel all go through the upstream, so an exit that cannot finish a Cloudflare
     * handshake cannot carry WARP either.
     */
    const val PREFLIGHT_HOST = "api.cloudflareclient.com"
    const val PREFLIGHT_TIMEOUT_MS = 12_000

    /**
     * Whether Radiance's exit completes a TLS handshake with Cloudflare ([preflight]); replaced in tests.
     * Checked once Radiance is ready and before Aether starts, so a Lantern exit that cannot reach Cloudflare costs
     * at most [PREFLIGHT_BUDGET_MS] instead of Aether's whole readiness window
     * ([com.example.vpn.connections.ConnectionEngineRunner.LANTERN_ATTEMPT_MS]).
     */
    @Volatile var cloudflareCheck: (Socks5.Endpoint) -> Boolean = { preflight(it).reached }

    /**
     * Hosts the preflight tries in order: the WARP account API, then Cloudflare's own site (another name on the same
     * edge, publicly trusted, hostname-verified). Either handshake proves Lantern's exit reaches Cloudflare.
     */
    val PREFLIGHT_HOSTS = listOf(PREFLIGHT_HOST, "www.cloudflare.com")
    const val PREFLIGHT_ATTEMPT_MS = 8_000
    const val PREFLIGHT_ROUNDS = 3
    const val PREFLIGHT_PAUSE_MS = 1_500L
    /** Whole preflight bound: a Lantern exit that cannot reach Cloudflare still costs well under Aether's window. */
    const val PREFLIGHT_BUDGET_MS = 26_000L

    /** One preflight: whether a handshake completed and each try as "host:ok|fail" (fixed text, no secrets). */
    data class Preflight(val reached: Boolean, val tries: List<String>)

    /** The last preflight's tries, for the diagnostics log of the Aether over Lantern pick. */
    @Volatile var lastPreflight: Preflight? = null

    /**
     * Build 180 on Irancell: Aether over Lantern failed in 22.5 s with "could not reach Cloudflare" while a curl through
     * Lantern to the same hosts completed TLS, and Build 179 had connected through the same code. One 12 s handshake
     * right after Radiance reports ready raced Lantern's own outbound selection. The preflight now tries
     * [PREFLIGHT_HOSTS] in up to [PREFLIGHT_ROUNDS] rounds within [PREFLIGHT_BUDGET_MS], pausing between rounds, and
     * stops at the first completed handshake. [check], [sleep] and [clock] are replaced in tests.
     */
    fun preflight(endpoint: Socks5.Endpoint,
                  check: (Socks5.Endpoint, String, Int) -> Boolean = { e, host, t -> reachesCloudflare(e, host, t) },
                  sleep: (Long) -> Unit = { Thread.sleep(it) },
                  clock: () -> Long = { System.currentTimeMillis() }): Preflight {
        val start = clock()
        val tries = mutableListOf<String>()
        for (round in 0 until PREFLIGHT_ROUNDS) {
            if (round > 0) {
                if (clock() - start + PREFLIGHT_PAUSE_MS >= PREFLIGHT_BUDGET_MS) break
                sleep(PREFLIGHT_PAUSE_MS)
            }
            for (host in PREFLIGHT_HOSTS) {
                val left = PREFLIGHT_BUDGET_MS - (clock() - start)
                if (left < 1_000) return Preflight(false, tries).also { lastPreflight = it }
                val ok = check(endpoint, host, minOf(PREFLIGHT_ATTEMPT_MS.toLong(), left).toInt())
                tries += "$host:${if (ok) "ok" else "fail"}"
                if (ok) return Preflight(true, tries).also { lastPreflight = it }
            }
        }
        return Preflight(false, tries).also { lastPreflight = it }
    }

    /**
     * SOCKS5 CONNECT to [host]:443 through [endpoint] (the name is resolved on Lantern's side, never on the filtered
     * network), then a verified TLS handshake with SNI [host]. Any failure is false; nothing is sent after the
     * handshake. [tls] is replaced in tests.
     */
    fun reachesCloudflare(endpoint: Socks5.Endpoint, host: String = PREFLIGHT_HOST, timeoutMs: Int = PREFLIGHT_TIMEOUT_MS,
                          tls: (Socket, String, Int) -> Unit = ::tlsHandshake): Boolean = try {
        Socks5.connect(endpoint, host, 443, timeoutMs).use { tls(it, host, timeoutMs); true }
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    } catch (_: Exception) {
        false
    }

    private fun tlsHandshake(raw: Socket, host: String, timeoutMs: Int) {
        val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, 443, false) as SSLSocket
        ssl.soTimeout = timeoutMs
        val params = ssl.sslParameters
        params.serverNames = listOf(SNIHostName(host))
        params.endpointIdentificationAlgorithm = "HTTPS"
        ssl.sslParameters = params
        ssl.startHandshake()
    }

    fun requested(extras: JSONObject): Boolean = extras.optBoolean(EXTRA)

    /** Whether this build carries the Radiance program for this phone; replaced in tests. */
    @Volatile var available: () -> Boolean = { Sidecars.isBundled(RadianceSidecar) }

    /** Aether's upstream setting for Radiance's authenticated loopback SOCKS5 port (tokens are URL-safe base64). */
    fun upstream(user: String, pass: String, port: Int): String = "socks5://$user:$pass@127.0.0.1:$port"

    /** Starts Radiance on its own loopback port and work folder and waits until it can carry traffic. */
    fun start(settings: AppSettings, parent: SidecarContext, checkCloudflare: Boolean = true): BuiltinExitNode.Started {
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
            val endpoint = Socks5.Endpoint("127.0.0.1", context.socksPort, context.socksUser, context.socksPass)
            if (checkCloudflare && !cloudflareCheck(endpoint)) throw BuiltinExitNode.ExitNodeFailure(CLOUDFLARE_UNREACHABLE)
            return BuiltinExitNode.Started(process, upstream(context.socksUser, context.socksPass, context.socksPort))
        } catch (e: Throwable) {
            process.stop()
            throw e
        }
    }
}
