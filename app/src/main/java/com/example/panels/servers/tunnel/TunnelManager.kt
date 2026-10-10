package com.example.panels.servers.tunnel

import com.example.data.model.VlessProfile
import com.example.panels.servers.ActionProgress
import com.example.panels.servers.CheckItem
import com.example.panels.servers.InstalledTool
import com.example.panels.servers.ManagedServer
import com.example.panels.servers.RemoteShell
import com.example.panels.servers.ScriptProgress
import com.example.panels.servers.ServerBinaries
import com.example.panels.servers.ServerFacts
import com.example.panels.servers.ServerLocation
import com.example.panels.servers.ServerRandom
import com.example.panels.servers.ServerToolCatalog
import com.example.panels.servers.SshShell
import com.example.panels.servers.ToolScript
import com.example.panels.servers.ToolScripts
import java.security.MessageDigest
import java.util.Base64
import kotlin.math.abs

/** What the read-only check of both servers found. */
data class TunnelChecks(
    val iran: ManagedServer,
    val abroad: ManagedServer,
    val items: List<CheckItem>,
    val iranSites: List<String>,
    val abroadSites: List<String>,
    val iranReachesGithub: Boolean,
    /** Difference between the two servers' clocks in seconds. */
    val clockSkewSec: Long,
    /** Whether each server could open a TCP connection to the other; null when it could not be tested. */
    val iranReachesAbroad: Boolean? = null,
    val abroadReachesIran: Boolean? = null
) {
    /** The ways across that can work between these two servers. */
    val recommended: Set<TunnelTransport> get() = TunnelTransport.recommended(iranReachesAbroad, abroadReachesIran)
    val blocked: Boolean get() = items.any { !it.ok && it.blocking }
}

/** A finished setup. */
data class TunnelOutcome(
    val iran: ManagedServer,
    val abroad: ManagedServer,
    val spec: TunnelSpec,
    val profile: VlessProfile,
    val link: String,
    val paths: Map<String, Long?>
)

/** The live state of a tunnel, read on the Iran server. */
data class TunnelStatus(
    /** Outbound tag ("t-reality-now", "t-hy2"...) -> latency in ms, or null when that path failed. */
    val paths: Map<String, Long?>,
    val ports: List<Int>,
    val running: Boolean,
    val serverTime: Long,
    val checkedAt: Long = System.currentTimeMillis()
) {
    val working: Int get() = paths.values.count { it != null }
    val best: String? get() = paths.filterValues { it != null }.minByOrNull { it.value!! }?.key
}

/**
 * Sets up and manages Maximus Tunnel over pinned SSH: the phone connects to the Iran server, which
 * forwards to the server abroad over every chosen transport and lets Xray pick the fastest one that
 * works. Blocking: call it off the main thread. [fetchProgram] downloads a server program on the
 * phone (for an Iran server that cannot reach GitHub); its hash is checked before it is sent.
 */
class TunnelManager(
    private val connect: (ManagedServer) -> RemoteShell = { SshShell.open(it) },
    private val fetchProgram: (String) -> ByteArray? = { null }
) {
    private fun run(shell: RemoteShell, script: ToolScript, progress: ActionProgress?, timeoutMs: Long = 15 * 60_000L): Map<String, String> {
        val reader = ScriptProgress(onStep = {}, onLog = { progress?.log(it) })
        val result = shell.run(script.text, timeoutMs) { reader.line(it) }
        if (result.exitCode != 0) {
            val tail = result.output.lineSequence().map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("##") }.toList().takeLast(2).joinToString(" · ")
            throw TunnelScriptException(result.exitCode, tail.ifBlank { "The server reported an error (exit ${result.exitCode})" }.take(220))
        }
        return reader.results
    }

    private fun facts(shell: RemoteShell) = ServerFacts.parse(shell.run(ServerFacts.SCRIPT, 60_000L).output)

    // ------------------------------------------------------------------ check

    fun check(iranServer: ManagedServer, abroadServer: ManagedServer): TunnelChecks {
        require(iranServer.location == ServerLocation.IRAN && abroadServer.location == ServerLocation.ABROAD) { "Pick one server in Iran and one abroad" }
        fun one(s: ManagedServer, sites: List<String>, peer: ManagedServer): Triple<ManagedServer, Map<String, String>, Long> = connect(s).use { shell ->
            val f = facts(shell)
            val before = System.currentTimeMillis() / 1000
            val r = if (f.blocker() == null) runCatching { run(shell, TunnelScripts.check(sites, peer.host, peer.sshPort), null, 3 * 60_000L) }.getOrDefault(emptyMap()) else emptyMap()
            Triple(s.copy(facts = f, checkedAt = System.currentTimeMillis()), r, before)
        }
        val (iran, ir, irPhone) = one(iranServer, TunnelScripts.IRAN_SNI, abroadServer)
        val (abroad, ab, abPhone) = one(abroadServer, TunnelScripts.ABROAD_SNI, iranServer)
        val irFacts = iran.facts!!
        val abFacts = abroad.facts!!
        val irOffset = ir["TIME"]?.toLongOrNull()?.minus(irPhone)
        val abOffset = ab["TIME"]?.toLongOrNull()?.minus(abPhone)
        val skew = if (irOffset != null && abOffset != null) abs(irOffset - abOffset) else 0L
        val iranSites = ir["SNI"].orEmpty().split(',').filter { it.isNotBlank() }
        val abroadSites = ab["SNI"].orEmpty().split(',').filter { it.isNotBlank() }
        val github = ir["GITHUB"] == "ok"
        fun reach(v: String?) = when (v) { "ok" -> true; "no" -> false; else -> null }
        val forward = reach(ir["REACH"])
        val backward = reach(ab["REACH"])
        val items = buildList {
            for ((label, s, f) in listOf(Triple("Iran server", iran, irFacts), Triple("Server abroad", abroad, abFacts))) {
                val blocker = f.blocker()
                add(CheckItem("$label · ${s.displayName}", blocker == null,
                    blocker ?: listOf(f.osName.ifBlank { "Linux" }, f.arch).filter { it.isNotBlank() }.joinToString(" · ")))
            }
            add(CheckItem("Clocks agree", skew <= 90, if (skew <= 90) "within ${skew}s" else "${skew}s apart; turn on NTP (timedatectl set-ntp true)", blocking = false))
            add(CheckItem("Camouflage site abroad", abroadSites.isNotEmpty(),
                abroadSites.firstOrNull() ?: "none answered with TLS 1.3; using ${TunnelScripts.ABROAD_SNI.first()}", blocking = false))
            add(CheckItem("Camouflage site in Iran", iranSites.isNotEmpty(),
                iranSites.firstOrNull() ?: "none answered with TLS 1.3; using ${TunnelScripts.IRAN_SNI.first()}", blocking = false))
            add(CheckItem("Iran server reaches GitHub", github,
                if (github) "downloads programs itself" else "blocked; this phone will send the verified programs", blocking = false))
            add(CheckItem("Iran server reaches the server abroad", forward != false, when (forward) {
                true -> "direct ways can be used"
                false -> "no connection; the reverse way will carry the tunnel"
                null -> "could not be tested; trying every way"
            }, blocking = false))
            add(CheckItem("Server abroad reaches the Iran server", backward != false, when (backward) {
                true -> "the reverse way can be used"
                false -> "no connection; the reverse way stays off"
                null -> "could not be tested; trying every way"
            }, blocking = forward == false && backward == false))
        }
        return TunnelChecks(iran, abroad, items, iranSites, abroadSites, github, skew, forward, backward)
    }

    // ------------------------------------------------------------------ plan

    /** A new tunnel for the checked servers, or an update of [previous] that keeps its keys and ports. */
    fun plan(
        checks: TunnelChecks,
        transports: Set<TunnelTransport>,
        rotationHours: Int,
        previous: TunnelSpec? = null
    ): TunnelSpec {
        val irFacts = checks.iran.facts!!
        val abFacts = checks.abroad.facts!!
        val abroadUsed = abFacts.tcpPorts + abFacts.udpPorts
        val iranUsed = irFacts.tcpPorts + irFacts.udpPorts
        val avoid = previous?.avoidPorts ?: (abroadUsed + iranUsed + setOf(checks.iran.sshPort, checks.abroad.sshPort))
        val entryPort = previous?.entryPort ?: ServerRandom.port(iranUsed)
        val hyPort = previous?.hyPort ?: ServerRandom.port(abroadUsed, exclude = avoid)
        val fixed = mutableMapOf<TunnelTransport, Int>()
        TunnelTransport.ROTATING.forEach { t ->
            fixed[t] = previous?.fixedPorts?.get(t) ?: ServerRandom.port(abroadUsed, exclude = avoid + hyPort + fixed.values)
        }
        return TunnelSpec(
            iranId = checks.iran.id, abroadId = checks.abroad.id,
            transports = transports, rotationHours = rotationHours,
            seed = previous?.seed ?: TunnelRandom.hex(16),
            avoidPorts = avoid, fixedPorts = fixed,
            entryPort = entryPort,
            entryUuid = previous?.entryUuid ?: TunnelRandom.uuid(),
            entrySni = previous?.entrySni ?: checks.iranSites.firstOrNull() ?: TunnelScripts.IRAN_SNI.first(),
            entryPublicKey = previous?.entryPublicKey.orEmpty(),
            entryShortId = previous?.entryShortId ?: TunnelRandom.hex(4),
            linkUuid = previous?.linkUuid ?: TunnelRandom.uuid(),
            exitSni = previous?.exitSni ?: checks.abroadSites.firstOrNull() ?: TunnelScripts.ABROAD_SNI.first(),
            exitPublicKey = previous?.exitPublicKey.orEmpty(),
            exitShortId = previous?.exitShortId ?: TunnelRandom.hex(4),
            xhttpPath = previous?.xhttpPath ?: TunnelRandom.path(),
            hyPort = hyPort,
            hyAuth = previous?.hyAuth ?: ServerRandom.secret(24),
            hyObfs = previous?.hyObfs ?: ServerRandom.secret(24),
            hyCertPem = previous?.hyCertPem.orEmpty(),
            hyLocalPort = previous?.hyLocalPort ?: ServerRandom.port(iranUsed, range = 10_000..19_999),
            reverseUuid = previous?.reverseUuid?.takeIf { it.isNotBlank() } ?: TunnelRandom.uuid(),
            createdAt = previous?.createdAt ?: System.currentTimeMillis()
        ).also { TunnelScripts.validate(it) }
    }

    // ------------------------------------------------------------------ install

    val steps = listOf(
        "Check both servers", "Set up the server abroad", "Get programs to the Iran server",
        "Set up the Iran server", "Test the paths between them", "Create your config"
    )

    /**
     * Sets up both servers: abroad first (it creates the keys the Iran server needs), then Iran.
     * [renewKeys] replaces the REALITY keys and the Hysteria2 certificate on both.
     */
    fun install(iranServer: ManagedServer, abroadServer: ManagedServer, planned: TunnelSpec, renewKeys: Boolean, progress: ActionProgress): TunnelOutcome {
        progress.step(1)
        var spec = planned
        val (iranFacts, abroadFacts) = connect(iranServer).use { facts(it) } to connect(abroadServer).use { facts(it) }
        iranFacts.blocker()?.let { error("Iran server: $it") }
        abroadFacts.blocker()?.let { error("Server abroad: $it") }
        val iranArch = iranFacts.goArch!!
        val abroadArch = abroadFacts.goArch!!

        progress.step(2)
        progress.log("Server abroad: ${abroadServer.host}")
        connect(abroadServer).use { shell ->
            val r = run(shell, TunnelScripts.abroadInstall(spec, abroadArch, abroadServer.host, renewKeys), progress)
            val pub = r["PUBKEY"].orEmpty()
            require(pub.matches(Regex("[A-Za-z0-9_-]{43}"))) { "The server abroad did not return its REALITY key" }
            spec = spec.copy(exitPublicKey = pub)
            if (TunnelTransport.HYSTERIA2 in spec.transports) {
                val pem = runCatching { String(Base64.getDecoder().decode(r["CERT"].orEmpty())) }.getOrDefault("")
                require(pem.contains("BEGIN CERTIFICATE")) { "The server abroad did not return its Hysteria2 certificate" }
                spec = spec.copy(hyCertPem = pem.trim())
            }
        }

        progress.step(3)
        val needed = buildList {
            add("xray-linux-$iranArch" to "${TunnelScripts.BIN}/xray")
            if (TunnelTransport.HYSTERIA2 in spec.transports) add("hysteria-linux-$iranArch" to "${TunnelScripts.BIN}/hysteria")
        }
        val iranScript = { s: TunnelSpec -> TunnelScripts.iranInstall(s, iranArch, abroadServer.host, renewKeys, iranServer.tool(ServerToolCatalog.MAXIMUS_TUNNEL)?.port) }
        var installed: Map<String, String>? = null
        connect(iranServer).use { shell ->
            progress.step(4)
            installed = try {
                run(shell, iranScript(spec), progress)
            } catch (e: TunnelScriptException) {
                if (e.exitCode != ToolScripts.EXIT_DOWNLOAD) throw e
                progress.step(3)
                progress.log("The Iran server cannot download from GitHub; sending the verified programs from this phone")
                needed.forEach { (name, path) -> relay(shell, name, if (iranArch == abroadArch) abroadServer to path else null, progress) }
                progress.step(4)
                run(shell, iranScript(spec), progress)
            }
        }
        val r = installed!!
        val entryPub = r["PUBKEY"].orEmpty()
        require(entryPub.matches(Regex("[A-Za-z0-9_-]{43}"))) { "The Iran server did not return its REALITY key" }
        spec = spec.copy(entryPublicKey = entryPub)

        progress.step(5)
        if (TunnelTransport.REVERSE in spec.transports) {
            progress.log("Linking the server abroad back to the Iran server")
            connect(abroadServer).use { run(it, TunnelScripts.abroadReverse(spec, iranServer.host), progress, 3 * 60_000L) }
            // The server abroad opens its first connections within a few seconds.
            Thread.sleep(REVERSE_SETTLE_MS)
        }
        val status = runCatching { status(iranServer, progress) }.getOrNull()
        status?.paths?.forEach { (tag, ms) -> progress.log("$tag: ${ms?.let { "$it ms" } ?: "no answer"}") }

        progress.step(6)
        val iranTool = InstalledTool(ServerToolCatalog.MAXIMUS_TUNNEL, port = spec.entryPort,
            profileId = iranServer.tool(ServerToolCatalog.MAXIMUS_TUNNEL)?.profileId, settings = spec.toSettings(TunnelSpec.ROLE_IRAN))
        val abroadTool = InstalledTool(ServerToolCatalog.MAXIMUS_TUNNEL, port = null, settings = spec.toSettings(TunnelSpec.ROLE_ABROAD))
        val now = System.currentTimeMillis()
        val iran = iranServer.withTool(iranTool).copy(facts = iranFacts, checkedAt = now)
        val abroad = abroadServer.withTool(abroadTool).copy(facts = abroadFacts, checkedAt = now)
        return TunnelOutcome(iran, abroad, spec, TunnelConfigs.entryProfile(spec, iran, abroad), TunnelConfigs.entryLink(spec, iran, abroad),
            status?.paths.orEmpty())
    }

    /**
     * Sends a pinned program to the Iran server's staging folder: downloaded on this phone, or else
     * copied from the server abroad. Either way its SHA-256 must match before it is sent, and the
     * server checks it again before installing it.
     */
    private fun relay(target: RemoteShell, name: String, fromAbroad: Pair<ManagedServer, String>?, progress: ActionProgress) {
        val expected = requireNotNull(ServerBinaries.SHA256[name]) { "No pinned hash for $name" }
        fun ok(b: ByteArray?) = b != null && sha256(b) == expected
        var data = runCatching { fetchProgram(name) }.getOrNull()
        if (ok(data)) progress.log("Downloaded $name on this phone (${data!!.size / 1_048_576} MB, checksum ok)")
        else if (fromAbroad != null) {
            progress.log("Copying $name from the server abroad")
            data = runCatching { connect(fromAbroad.first).use { it.download(fromAbroad.second) } }.getOrNull()
        }
        check(ok(data)) { "Could not get a verified copy of $name. Turn on a VPN on this phone and try again." }
        progress.log("Sending $name to the Iran server")
        target.upload(data!!, "${ToolScripts.STAGE_DIR}/$name")
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------------ manage

    fun status(iran: ManagedServer, progress: ActionProgress? = null): TunnelStatus = connect(iran).use { shell ->
        val r = run(shell, TunnelScripts.iranStatus(), progress, 3 * 60_000L)
        TunnelStatus(
            paths = r.filterKeys { it.startsWith("PATH_") }.map { (k, v) ->
                k.removePrefix("PATH_") to (if (v.startsWith("ok:")) v.removePrefix("ok:").toLongOrNull() else null)
            }.toMap(),
            ports = r["PORTS"].orEmpty().split(' ').mapNotNull { it.toIntOrNull() },
            running = r["SVC_maximus-tunnel"] == "active",
            serverTime = r["TIME"]?.toLongOrNull() ?: 0L
        )
    }

    /** Moves every rotating port right now with a new seed: abroad first, then Iran. */
    fun reseed(iran: ManagedServer, abroad: ManagedServer, spec: TunnelSpec): Pair<ManagedServer, ManagedServer> {
        val next = spec.copy(seed = TunnelRandom.hex(16))
        connect(abroad).use { run(it, TunnelScripts.reseed(next.seed), null) }
        connect(iran).use { run(it, TunnelScripts.reseed(next.seed), null) }
        return iran.withTool(iran.tool(ServerToolCatalog.MAXIMUS_TUNNEL)!!.copy(settings = next.toSettings(TunnelSpec.ROLE_IRAN))) to
            abroad.withTool(abroad.tool(ServerToolCatalog.MAXIMUS_TUNNEL)!!.copy(settings = next.toSettings(TunnelSpec.ROLE_ABROAD)))
    }

    fun restart(server: ManagedServer) { connect(server).use { run(it, TunnelScripts.restart(), null, 2 * 60_000L) } }

    fun logs(server: ManagedServer): List<String> = connect(server).use { shell ->
        val lines = mutableListOf<String>()
        run(shell, TunnelScripts.logs(), object : ActionProgress {
            override fun step(index: Int) = Unit
            override fun log(line: String) { lines += line }
        }, 60_000L)
        lines
    }

    /** Removes the tunnel from [server] (one side); the other side is removed by its own call. */
    fun uninstall(server: ManagedServer, spec: TunnelSpec?): ManagedServer {
        val role = if (server.location == ServerLocation.IRAN) TunnelSpec.ROLE_IRAN else TunnelSpec.ROLE_ABROAD
        connect(server).use {
            run(it, TunnelScripts.uninstall(role, spec?.entryPort, spec?.takeIf { TunnelTransport.HYSTERIA2 in it.transports }?.hyPort), null)
        }
        return server.withoutTool(ServerToolCatalog.MAXIMUS_TUNNEL)
    }
}

private const val REVERSE_SETTLE_MS = 4_000L

class TunnelScriptException(val exitCode: Int, message: String) : IllegalStateException(message)
