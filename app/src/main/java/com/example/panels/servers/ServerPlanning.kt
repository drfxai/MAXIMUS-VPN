package com.example.panels.servers

import java.security.SecureRandom

/** Random ports and secrets for server tools. */
object ServerRandom {
    private val random = SecureRandom()
    private const val ALPHANUM = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"

    /** Well-known ports a random choice avoids, besides anything already listening. */
    private val AVOID = setOf(1080, 1194, 1723, 3128, 3306, 3389, 5060, 5432, 6379, 8080, 8443, 8888, 9050, 9150, 27017, 51820)

    /**
     * A random port in [range] that nothing on the server uses ([inUse]) and that is not a common
     * service port. High random ports are harder to block by port lists than 443 or 8443.
     */
    fun port(inUse: Set<Int>, range: IntRange = 20_000..59_999, exclude: Set<Int> = emptySet()): Int {
        val taken = inUse + exclude + AVOID
        repeat(500) {
            val p = range.first + random.nextInt(range.last - range.first + 1)
            if (p !in taken) return p
        }
        return range.first { it !in taken }
    }

    fun secret(length: Int = 24): String = buildString(length) { repeat(length) { append(ALPHANUM[random.nextInt(ALPHANUM.length)]) } }
}

/** One item of the read-only check that opens every install. */
data class CheckItem(val label: String, val ok: Boolean, val detail: String = "", val blocking: Boolean = !ok)

/** What the install wizard needs to know before it shows the settings step. */
object InstallChecks {
    fun forTool(tool: ServerTool, server: ManagedServer, facts: ServerFacts): List<CheckItem> = buildList {
        add(CheckItem("Signed in over SSH", true, "Host key ${server.hostKeySha256.removePrefix("SHA256:").take(12)}… matches"))
        add(CheckItem("Administrator access", facts.canAdmin,
            if (facts.root) "root" else if (facts.sudo) "passwordless sudo" else "this account needs root or passwordless sudo"))
        add(CheckItem("System", facts.systemd && facts.goArch != null,
            listOf(facts.osName.ifBlank { "Linux" }, facts.arch).filter { it.isNotBlank() }.joinToString(" · ") +
                if (!facts.systemd) " · no systemd" else ""))
        if (tool.aptOnly) add(CheckItem("Debian or Ubuntu", facts.usesApt, if (facts.usesApt) "apt" else "this tool needs apt"))
        if (!tool.fitsOn(server.location)) {
            add(CheckItem("Right place", false, "${tool.title} only helps on a server abroad"))
        }
        val freeMb = facts.diskFreeMb
        add(CheckItem("Free disk", freeMb >= 300, String.format(java.util.Locale.US, "%.1f GB free", freeMb / 1024.0), blocking = freeMb < 100))
        when (tool.id) {
            ServerToolCatalog.DNSTT -> {
                val dnsBusy = 53 in facts.udpPorts
                add(CheckItem("UDP 53", !dnsBusy,
                    if (dnsBusy) "another DNS server listens on 53; the tunnel will take over incoming DNS" else "free",
                    blocking = false))
            }
            ServerToolCatalog.SWAP -> add(CheckItem("Swap", facts.swapMb == 0L,
                if (facts.swapMb > 0) "already ${facts.swapMb} MB" else "none yet", blocking = false))
            ServerToolCatalog.BBR -> add(CheckItem("Congestion control", true, facts.congestionControl.ifBlank { "unknown" }, blocking = false))
            ServerToolCatalog.KEY_LOGIN -> add(CheckItem("Password login", true,
                when (facts.sshPasswordLogin) { true -> "on"; false -> "off"; null -> "unknown" }, blocking = false))
        }
    }
}

/** Advice shown on the server page and in the Install Center. It only suggests; nothing installs by itself. */
object ToolAdvisor {
    data class Advice(val toolId: String, val reason: String)

    fun suggest(server: ManagedServer, facts: ServerFacts?): List<Advice> {
        if (facts == null || facts.blocker() != null) return emptyList()
        val has = server.tools.map { it.id }.toSet()
        return buildList {
            if (!server.usesKey) add(Advice(ServerToolCatalog.KEY_LOGIN, "Sign in with a key instead of a password"))
            if (facts.usesApt && !facts.fail2ban && ServerToolCatalog.FAIL2BAN !in has && !server.passwordLoginOff)
                add(Advice(ServerToolCatalog.FAIL2BAN, "Password login is on; block guessing"))
            if (facts.congestionControl.isNotBlank() && facts.congestionControl != "bbr")
                add(Advice(ServerToolCatalog.BBR, "TCP uses ${facts.congestionControl}; BBR is faster on long routes"))
            if (facts.swapMb == 0L && facts.ramMb in 1..2047)
                add(Advice(ServerToolCatalog.SWAP, "${facts.ramMb} MB of memory and no swap"))
            if (server.location == ServerLocation.ABROAD && ServerToolCatalog.DNSTT !in has && 53 !in facts.udpPorts)
                add(Advice(ServerToolCatalog.DNSTT, "UDP 53 is free: a DNS tunnel survives IP blocking"))
            if (server.location == ServerLocation.ABROAD && ServerToolCatalog.HYSTERIA2 !in has)
                add(Advice(ServerToolCatalog.HYSTERIA2, "A fast UDP path next to your TCP configs"))
            if (facts.usesApt && !facts.autoUpdates) add(Advice(ServerToolCatalog.AUTO_UPDATES, "Security fixes are not installed automatically"))
        }
    }
}

/** Reads `##STEP` and `##RESULT` lines from a running script and keeps the visible log clean. */
class ScriptProgress(private val onStep: (Int) -> Unit, private val onLog: (String) -> Unit) {
    val results = linkedMapOf<String, String>()

    fun line(raw: String) {
        val line = raw.trimEnd('\r')
        when {
            line.startsWith(ToolScripts.STEP) -> line.removePrefix(ToolScripts.STEP).trim().toIntOrNull()?.let(onStep)
            line.startsWith(ToolScripts.RESULT) -> {
                val kv = line.removePrefix(ToolScripts.RESULT)
                val i = kv.indexOf('=')
                if (i > 0) results[kv.substring(0, i)] = kv.substring(i + 1).trim()
            }
            line.isBlank() -> Unit
            SECRET_LINE.containsMatchIn(line) -> Unit
            else -> onLog(line.take(160))
        }
    }

    companion object {
        private val SECRET_LINE = Regex("(pass(word)?|token|secret|private)", RegexOption.IGNORE_CASE)
    }
}
