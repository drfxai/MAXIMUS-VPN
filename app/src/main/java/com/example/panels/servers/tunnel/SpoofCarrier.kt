package com.example.panels.servers.tunnel

import com.example.panels.servers.*
import com.example.vpn.experimental.FrontedTunnel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Server-to-server carrier. Hysteria2 inside the pipe authenticates and encrypts all application traffic. */
data class SpoofCarrierSpec(
    val iranSources: List<String>,
    val abroadSources: List<String>,
    val upload: String = "tcp",
    val download: String = "tcp",
    val port: Int = 18443,
    val localPort: Int = 18500
) {
    fun validate(): SpoofCarrierSpec = apply {
        require(upload in TRANSPORTS && download in TRANSPORTS) { "Invalid spoof carrier" }
        require(port in 1024..65535 && localPort in 1024..65535 && port != localPort) { "Choose distinct carrier ports above 1023" }
        for (sources in listOf(iranSources, abroadSources)) {
            require(sources.size in 1..16 && sources.distinct().size == sources.size) { "Choose 1–16 different source IPs per server" }
            sources.forEach { require(ipv4(it) == it) { "Use canonical IPv4 addresses" } }
        }
    }
    fun settings(): Map<String, String> = mapOf("spoofIran" to iranSources.joinToString(","),
        "spoofAbroad" to abroadSources.joinToString(","), "spoofUpload" to upload, "spoofDownload" to download,
        "spoofPort" to port.toString(), "spoofLocalPort" to localPort.toString())
    companion object {
        val TRANSPORTS = listOf("tcp", "udp", "icmp", "icmpv6")
        fun ipv4(value: String): String = FrontedTunnel.publicAddress(value).also { require(!it.contains(':')) { "Spoof carrier requires public IPv4 endpoints" } }
        fun sources(text: String): List<String> = text.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.map(::ipv4).distinct()
        fun fromSettings(s: Map<String, String>): SpoofCarrierSpec? {
            if (s["spoofIran"].isNullOrBlank()) return null
            return SpoofCarrierSpec(sources(s.getValue("spoofIran")), sources(s.getValue("spoofAbroad")),
                s.getValue("spoofUpload"), s.getValue("spoofDownload"), s.getValue("spoofPort").toInt(), s.getValue("spoofLocalPort").toInt()).validate()
        }
    }
}

/** Pinned upstream executable; no scripts from the network are executed. */
object SpoofCarrierScripts {
    const val VERSION = "v3.1.0-beta.0"
    const val BIN = "/usr/local/lib/maximus/spoof"
    const val DIR = "/etc/maximus/spoof"
    const val UNIT = "maximus-spoof"
    val hashes = mapOf("amd64" to "4ba5597568607e864fb6d5ba4f13bac91a50456da5696d99463d6375ce2a3697",
        "arm64" to "ab7e0a58aa37c63a36127feb99ef73cd498523c99d22146feb1a93dd97c9d97b")

    fun prepare(arch: String) = ToolScripts.script("""
        MX_BASE=https://github.com/ParsaKSH/spoof-tunnel/releases/download/$VERSION
        fetch_bin spoof-linux-${ToolScripts.arch(arch)} ${hashes.getValue(arch)} $BIN
        have timeout || { echo 'Install coreutils (timeout)' >&2; exit 20; }
        §SUDO test -d $DIR || §SUDO install -d -m 0700 $DIR
    """, emptyList())

    fun probe(sources: List<String>, peer: String, port: Int, receiver: Boolean, protocol: String): ToolScript {
        require(protocol in setOf("tcp", "icmp"))
        require(sources.size in 1..16)
        sources.forEach { SpoofCarrierSpec.ipv4(it) }
        SpoofCarrierSpec.ipv4(peer); ToolScripts.port(port, "probe")
        return ToolScripts.script("""
            tmp=§(mktemp -d)
            trap '§SUDO rm -rf "§tmp"' EXIT HUP INT TERM
            cat > "§tmp/sources" <<'MX_SOURCES'
            @@SOURCES@@
            MX_SOURCES
            §SUDO env TMPDIR="§tmp" timeout 25 $BIN tester --mode ${if (receiver) "receiver" else "sender"} --protocol $protocol --src-list "§tmp/sources" --dst-ip $peer --dst-port $port --timeout 15 --packet-count 4 --max-loss 25 --concurrency 1
        """, emptyList(), mapOf("SOURCES" to sources.joinToString("\n")))
    }

    fun install(s: SpoofCarrierSpec, iran: Boolean, peer: String, hyPort: Int): ToolScript {
        s.validate(); SpoofCarrierSpec.ipv4(peer); ToolScripts.port(hyPort, "Hysteria2")
        val sources = if (iran) s.iranSources else s.abroadSources
        val send = if (iran) s.upload else s.download
        val recv = if (iran) s.download else s.upload
        val args = if (iran) "local --listen 127.0.0.1:${s.localPort} --remote $peer --remote-port ${s.port} --recv-port ${s.port}"
            else "remote --forward 127.0.0.1:$hyPort --client-ip $peer --client-port ${s.port} --listen-port ${s.port}"
        // Upstream requires uid 0; capability bounding removes every privilege except raw networking.
        val unit = """
            [Unit]
            Description=Maximus authenticated tunnel spoof carrier
            After=network-online.target
            Wants=network-online.target
            [Service]
            ExecStart=$BIN $args --spoof-ip-file $DIR/sources --spoof-port ${s.port} --send-transport $send --recv-transport $recv
            Restart=on-failure
            RestartSec=3
            User=root
            CapabilityBoundingSet=CAP_NET_RAW
            NoNewPrivileges=yes
            ProtectSystem=strict
            ProtectHome=yes
            PrivateTmp=yes
            PrivateDevices=yes
            ProtectKernelTunables=yes
            ProtectKernelModules=yes
            ProtectControlGroups=yes
            RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX AF_PACKET
            [Install]
            WantedBy=multi-user.target
        """.trimIndent()
        return ToolScripts.script("""
            # Dedicated files only; retain a rollback copy until both sides carry authenticated traffic.
            §SUDO test ! -f $DIR/pending || { echo 'Unfinished carrier transaction; restore it first' >&2; exit 22; }
            §SUDO rm -rf $DIR/previous
            §SUDO mkdir -p $DIR/previous
            if §SUDO test -f /etc/systemd/system/$UNIT.service; then
              §SUDO cp /etc/systemd/system/$UNIT.service $DIR/previous/unit
              §SUDO cp $DIR/sources $DIR/previous/sources
            fi
            §SUDO touch $DIR/pending
            write_file $DIR/sources 0600 <<'MX_SOURCES'
            @@SOURCES@@
            MX_SOURCES
            write_file /etc/systemd/system/$UNIT.service 0644 <<'MX_UNIT'
            @@UNIT@@
            MX_UNIT
            §SUDO systemctl daemon-reload
            §SUDO systemctl enable $UNIT >/dev/null
            §SUDO systemctl restart $UNIT
            wait_active $UNIT
        """, emptyList(), mapOf("SOURCES" to sources.joinToString("\n"), "UNIT" to unit))
    }

    fun finish(commit: Boolean) = ToolScripts.script(if (commit) "§SUDO rm -rf $DIR/previous $DIR/pending" else """
        if §SUDO test -f $DIR/pending; then
          §SUDO systemctl stop $UNIT || true
          if §SUDO test -f $DIR/previous/unit; then
            §SUDO cp $DIR/previous/unit /etc/systemd/system/$UNIT.service
            §SUDO cp $DIR/previous/sources $DIR/sources
            §SUDO systemctl daemon-reload
            §SUDO systemctl restart $UNIT
          else
            §SUDO systemctl disable $UNIT 2>/dev/null || true
            §SUDO rm -f /etc/systemd/system/$UNIT.service $DIR/sources
            §SUDO systemctl daemon-reload
          fi
          §SUDO rm -rf $DIR/previous $DIR/pending
        fi
    """, emptyList())

    fun remove() = ToolScripts.script("""
        §SUDO systemctl disable --now $UNIT 2>/dev/null || true
        §SUDO rm -f /etc/systemd/system/$UNIT.service
        §SUDO rm -rf $DIR
        §SUDO systemctl daemon-reload
    """, emptyList())

    fun received(output: String, allowed: List<String>): List<String> = output.lineSequence().mapNotNull { line ->
        val m = Regex("^(\\d+\\.\\d+\\.\\d+\\.\\d+) ([0-4])/4 ([0-9.]+)%$").matchEntire(line.trim()) ?: return@mapNotNull null
        m.groupValues[1].takeIf { it in allowed && m.groupValues[2].toInt() >= 3 && m.groupValues[3].toDoubleOrNull()?.let { loss -> loss in 0.0..25.0 } == true }
    }.distinct().toList()
}

class SpoofCarrierManager(private val connect: (ManagedServer) -> RemoteShell = { SshShell.open(it) }) {
    private fun run(server: ManagedServer, script: ToolScript): String = connect(server).use { shell ->
        val r = shell.run(script.text, 120_000)
        check(r.exitCode == 0) { "Carrier action failed on ${server.displayName}: ${r.output.takeLast(300)}" }
        r.output
    }

    /** Probe only explicitly entered sources against the other managed server, four packets per source. */
    fun scan(sender: ManagedServer, receiver: ManagedServer, sources: List<String>, port: Int, protocol: String): List<String> {
        val ready = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        val receive = pool.submit<String> {
            connect(receiver).use { shell ->
                val r = shell.run(SpoofCarrierScripts.probe(sources, sender.host, port, true, protocol).text, 30_000) {
                    if (it.contains("[tester-receiver] protocol=")) ready.countDown()
                }
                check(r.exitCode == 0) { "Carrier receiver failed on ${receiver.displayName}" }
                r.output
            }
        }
        try {
            check(ready.await(10, TimeUnit.SECONDS)) { "Carrier receiver did not become ready" }
            run(sender, SpoofCarrierScripts.probe(sources, receiver.host, port, false, protocol))
            return SpoofCarrierScripts.received(receive.get(25, TimeUnit.SECONDS), sources)
        } finally { receive.cancel(true); pool.shutdownNow() }
    }

    fun setup(iran: ManagedServer, abroad: ManagedServer, s: SpoofCarrierSpec, hyPort: Int, log: (String) -> Unit): SpoofCarrierSpec {
        s.validate(); SpoofCarrierSpec.ipv4(iran.host); SpoofCarrierSpec.ipv4(abroad.host)
        require(iran.host != abroad.host) { "Choose two distinct servers" }
        for (server in listOf(iran, abroad)) {
            val arch = requireNotNull(server.facts?.goArch) { "Check both servers first" }
            run(server, SpoofCarrierScripts.prepare(arch))
            run(server, SpoofCarrierScripts.finish(false))
        }
        // The upstream tester supports TCP and ICMP. UDP/ICMPv6 need the subsequent authenticated tunnel test.
        fun test(from: ManagedServer, to: ManagedServer, ips: List<String>, protocol: String): List<String> {
            if (protocol !in setOf("tcp", "icmp")) return ips
            log("Testing $protocol carrier ${from.displayName} → ${to.displayName}")
            return scan(from, to, ips, s.port, protocol).also {
                log("Observed ${it.size}/${ips.size} source candidates on ${to.displayName}; authenticated tunnel test follows")
                check(it.isNotEmpty()) { "No entered source reached ${to.displayName}; the provider may block source spoofing" }
            }
        }
        val selected = s.copy(iranSources = test(iran, abroad, s.iranSources, s.upload),
            abroadSources = test(abroad, iran, s.abroadSources, s.download))
        try {
            run(abroad, SpoofCarrierScripts.install(selected, false, iran.host, hyPort))
            run(iran, SpoofCarrierScripts.install(selected, true, abroad.host, hyPort))
        } catch (e: Exception) {
            rollback(iran, abroad)
            throw e
        }
        return selected
    }
    fun rollback(iran: ManagedServer, abroad: ManagedServer) {
        val failed = listOf(iran, abroad).filter { runCatching { run(it, SpoofCarrierScripts.finish(false)) }.isFailure }
        check(failed.isEmpty()) { "Carrier rollback needs attention on ${failed.joinToString { it.displayName }}" }
    }
    fun commit(iran: ManagedServer, abroad: ManagedServer) { listOf(iran, abroad).forEach { run(it, SpoofCarrierScripts.finish(true)) } }
    fun remove(server: ManagedServer) { run(server, SpoofCarrierScripts.remove()) }
}
