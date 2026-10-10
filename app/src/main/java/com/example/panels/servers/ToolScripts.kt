package com.example.panels.servers

/**
 * The server programs the Install Center downloads, with the SHA-256 of each file. They are built
 * reproducibly by scripts/server-tools/build.sh and published by the "Server tools" workflow; the
 * same hashes are in tools/server-tools/SHA256SUMS (a unit test keeps the two equal). A server
 * refuses any file whose hash differs, wherever it came from.
 */
object ServerBinaries {
    const val RELEASE_TAG = "server-tools-v1"
    const val BASE_URL = "https://github.com/drfxai/MAXIMUS-VPN/releases/download/$RELEASE_TAG"

    val SHA256: Map<String, String> = mapOf(
        "dnstt-server-linux-amd64" to "16c4df19255d7b63efcce8382d55d6da49272d9c5a4bfdf041c1f77c2cd17ad8",
        "dnstt-server-linux-arm64" to "45cee8cb7d959a69ac54014b40a68e72d7fc54b0d6f494d8de6075277447a788",
        "hysteria-linux-amd64" to "17d92c287c49f3eeffb0cfdbecc610e03164482fa8518d3b791a5d1df4dbc7c2",
        "hysteria-linux-arm64" to "c0788f9ae2ae91f05fc90265e4793963811c9d325ff5276eed9a3fe1b0e16534",
        "maximus-socks-linux-amd64" to "7628ca100349b4a2d7739a3c6427e6638443f73db4073118f3a460084458d19d",
        "maximus-socks-linux-arm64" to "5a42edbbc9c06017de567e91351b822c70749f4f66559ff774b7aa5bfcea21ab",
        "xray-linux-amd64" to "21bcb66e8740022fb4e27d5a4fc0af95ee5462ce1cfb4637a5d1551b91772fdf",
        "xray-linux-arm64" to "9503ec7ad90830e2da13e43d022c346b2e7d13814d2c4f6b9ea88969cf926887"
    )

    fun sha(name: String, arch: String): String =
        requireNotNull(SHA256["$name-linux-$arch"]) { "No $name build for $arch" }
}

/** A shell script for one server action, with the names of the steps it reports. */
data class ToolScript(val text: String, val steps: List<String>)

/**
 * Shell scripts for every Install Center action. They run over the pinned SSH session as the
 * signed-in account (with sudo when it is not root) and report progress with `##STEP n` lines and
 * results with `##RESULT KEY=VALUE` lines, which the app reads and keeps out of the visible log.
 *
 * Rules every script follows:
 * - Programs come only from [ServerBinaries] and are checked against the pinned SHA-256 before
 *   they are installed; nothing downloaded is ever piped into a shell.
 * - A failed first install undoes what it changed. Running an install again is harmless: it
 *   updates the same files and keeps keys unless asked to replace them.
 * - Services run as the locked `maximus` system user with systemd hardening.
 * - Every value placed in a script is checked against a strict pattern first.
 */
object ToolScripts {
    const val STEP = "##STEP "
    const val RESULT = "##RESULT "

    const val DNSTT_DIR = "/etc/maximus/dnstt"
    const val HYSTERIA_DIR = "/etc/maximus/hysteria"
    /** Where the app uploads a verified program when the server cannot download it itself. */
    const val STAGE_DIR = "/tmp/maximus-stage"
    /** Exit code of a script whose program download failed. */
    const val EXIT_DOWNLOAD = 31

    /** systemd units of each tool, for status, restart and logs. */
    val UNITS: Map<String, List<String>> = mapOf(
        ServerToolCatalog.XUI to listOf("x-ui"),
        ServerToolCatalog.DNSTT to listOf("maximus-socks", "maximus-dnstt"),
        ServerToolCatalog.HYSTERIA2 to listOf("maximus-hysteria"),
        ServerToolCatalog.MAXIMUS_TUNNEL to listOf("maximus-tunnel"),
        ServerToolCatalog.FAIL2BAN to listOf("fail2ban")
    )

    internal val DOMAIN = Regex("^(?=.{4,200}$)([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}$")
    internal val SECRET = Regex("^[A-Za-z0-9]{16,64}$")
    internal val HOST = Regex("^[A-Za-z0-9.:-]{1,253}$")
    private val ARCH = setOf("amd64", "arm64")
    private val PUBLIC_KEY = Regex("^(ecdsa-sha2-nistp256|ssh-ed25519|ssh-rsa) [A-Za-z0-9+/=]{40,1000}( [A-Za-z0-9@._-]{1,64})?$")

    internal fun port(p: Int, what: String): Int = p.also { require(it in 1..65535) { "Invalid $what port $it" } }
    internal fun arch(a: String): String = a.also { require(it in ARCH) { "Unsupported processor $it" } }

    /** Shared helpers. `§` stands for `$` in every script below. */
    private val PRELUDE = """
        set -eu
        umask 022
        SUDO=""
        if [ "§(id -u)" -ne 0 ]; then
          if command -v sudo >/dev/null 2>&1 && sudo -n true 2>/dev/null; then SUDO="sudo -n"
          else echo 'This account needs root or passwordless sudo' >&2; exit 20; fi
        fi
        MX_BASE="§{MX_BASE:-${ServerBinaries.BASE_URL}}"
        step() { echo "$STEP§1"; }
        result() { echo "$RESULT§1=§2"; }
        have() { command -v "§1" >/dev/null 2>&1; }
        pkg_install() {
          if have apt-get; then
            §SUDO env DEBIAN_FRONTEND=noninteractive apt-get install -y -q "§@" >/dev/null 2>&1 || {
              §SUDO env DEBIAN_FRONTEND=noninteractive apt-get update -q >/dev/null 2>&1 || true
              §SUDO env DEBIAN_FRONTEND=noninteractive apt-get install -y -q "§@" >/dev/null; }
          elif have dnf; then §SUDO dnf install -y -q "§@" >/dev/null
          elif have yum; then §SUDO yum install -y -q "§@" >/dev/null
          else echo "No package manager to install §*" >&2; return 1; fi
        }
        fw_allow() {
          if have ufw && §SUDO ufw status 2>/dev/null | grep -q 'Status: active'; then §SUDO ufw allow "§1/§2" >/dev/null; fi
          if have firewall-cmd && §SUDO firewall-cmd --state >/dev/null 2>&1; then
            §SUDO firewall-cmd --permanent --add-port="§1/§2" >/dev/null && §SUDO firewall-cmd --reload >/dev/null; fi
        }
        fw_close() {
          if have ufw && §SUDO ufw status 2>/dev/null | grep -q 'Status: active'; then §SUDO ufw delete allow "§1/§2" >/dev/null 2>&1 || true; fi
          if have firewall-cmd && §SUDO firewall-cmd --state >/dev/null 2>&1; then
            §SUDO firewall-cmd --permanent --remove-port="§1/§2" >/dev/null 2>&1 && §SUDO firewall-cmd --reload >/dev/null 2>&1 || true; fi
        }
        ensure_user() {
          if ! id maximus >/dev/null 2>&1; then
            nologin=/usr/sbin/nologin; [ -x §nologin ] || nologin=/sbin/nologin
            §SUDO useradd --system --no-create-home --home-dir /nonexistent --shell "§nologin" maximus
          fi
        }
        write_file() {
          wf=§(mktemp); cat > "§wf"; §SUDO install -m "§2" "§wf" "§1"; rm -f "§wf"
        }
        fetch_bin() {
          if §SUDO test -f "§3" && [ "§(§SUDO sha256sum "§3" | awk '{print §1}')" = "§2" ]; then return 0; fi
          fb=§(mktemp)
          if [ -f "$STAGE_DIR/§1" ]; then
            cp "$STAGE_DIR/§1" "§fb"; rm -f "$STAGE_DIR/§1"
          else
            if ! have curl && ! have wget; then pkg_install curl ca-certificates; fi
            if have curl; then curl -fsSL --proto '=https' --tlsv1.2 --retry 3 --connect-timeout 20 -o "§fb" "§MX_BASE/§1" || dl_failed "§1" "§fb"
            else wget --https-only -q -O "§fb" "§MX_BASE/§1" || dl_failed "§1" "§fb"; fi
          fi
          got=§(sha256sum "§fb" | awk '{print §1}')
          if [ "§got" != "§2" ]; then rm -f "§fb"; echo "Checksum mismatch for §1, refusing to install it" >&2; exit 30; fi
          §SUDO install -D -m 0755 "§fb" "§3"; rm -f "§fb"
        }
        dl_failed() { rm -f "§2"; echo "Could not download §1 from GitHub" >&2; exit 31; }
        wait_active() {
          for u in "§@"; do
            i=0
            while ! §SUDO systemctl is-active --quiet "§u"; do
              i=§((i + 1))
              if [ §i -ge 10 ]; then
                §SUDO journalctl -u "§u" -n 6 --no-pager 2>/dev/null | tail -n 6 >&2 || true
                echo "§u did not start" >&2; exit 40
              fi
              sleep 1
            done
          done
        }
    """.trimIndent()

    /**
     * Joins the prelude and [body]. Multi-line [inserts] replace `@@NAME@@` lines after the body is
     * de-indented, so heredoc terminators stay at the start of their lines.
     */
    internal fun script(body: String, steps: List<String>, inserts: Map<String, String> = emptyMap()): ToolScript {
        var text = PRELUDE + "\n" + body.trimIndent()
        inserts.forEach { (name, value) -> text = text.replace("@@$name@@", value) }
        return ToolScript(text.replace('§', '$'), steps)
    }

    /** The unit file text for a Maximus service; its lines are kept literal (no `$` allowed). */
    internal fun unit(description: String, exec: List<String>, extra: List<String> = emptyList(), after: String = "network-online.target"): String {
        require((exec + extra).none { it.contains('$') || it.contains('§') }) { "Unit lines must not contain $" }
        return (listOf("[Unit]", "Description=$description", "After=$after", "Wants=network-online.target", "",
            "[Service]", "User=maximus", "Group=maximus") + exec + listOf(
            "Restart=always", "RestartSec=3", "NoNewPrivileges=yes", "ProtectSystem=strict", "ProtectHome=yes",
            "PrivateTmp=yes", "PrivateDevices=yes", "ProtectKernelTunables=yes", "ProtectKernelModules=yes",
            "ProtectControlGroups=yes", "RestrictSUIDSGID=yes", "LockPersonality=yes"
        ) + extra + listOf("", "[Install]", "WantedBy=multi-user.target")).joinToString("\n")
    }

    // ---------------------------------------------------------------- dnstt

    val DNSTT_STEPS = listOf("Prepare the server", "Download verified programs", "Create the tunnel key",
        "Add the services", "Open UDP 53", "Start and verify")

    /**
     * Installs the DNS tunnel for [domain] (the delegated name, e.g. t.example.com). dnstt-server
     * listens on [dnsPort]; incoming UDP 53 is redirected there while the service runs, so it never
     * needs to run as root and does not collide with a local resolver on 127.0.0.53. Tunnel streams
     * go to the loopback SOCKS helper on [socksPort]. [newKey] replaces the key pair.
     */
    fun dnsttInstall(domain: String, arch: String, dnsPort: Int, socksPort: Int, newKey: Boolean): ToolScript {
        require(DOMAIN.matches(domain)) { "Invalid tunnel domain" }
        val a = arch(arch)
        port(dnsPort, "tunnel"); port(socksPort, "SOCKS")
        require(dnsPort != 53 && dnsPort != socksPort) { "The internal ports must differ from 53 and each other" }
        val socksUnit = unit(
            "Maximus loopback SOCKS helper for the DNS tunnel",
            listOf("ExecStart=/usr/local/bin/maximus-socks -listen 127.0.0.1:$socksPort")
        )
        val dnsttUnit = unit(
            "Maximus DNS tunnel (dnstt)",
            listOf(
                "ExecStartPre=+/usr/local/lib/maximus/dnstt-redirect add $dnsPort",
                "ExecStart=/usr/local/bin/maximus-dnstt-server -udp :$dnsPort -privkey-file $DNSTT_DIR/server.key ${domain.lowercase()} 127.0.0.1:$socksPort",
                "ExecStopPost=+/usr/local/lib/maximus/dnstt-redirect del $dnsPort"
            ),
            after = "network-online.target maximus-socks.service"
        )
        return script(
            """
            DIR=$DNSTT_DIR
            FRESH=1
            if §SUDO test -f /etc/systemd/system/maximus-dnstt.service; then FRESH=0; fi
            rollback() {
              §SUDO systemctl disable --now maximus-dnstt maximus-socks >/dev/null 2>&1 || true
              §SUDO rm -f /etc/systemd/system/maximus-dnstt.service /etc/systemd/system/maximus-socks.service \
                /usr/local/bin/maximus-dnstt-server /usr/local/bin/maximus-socks /usr/local/lib/maximus/dnstt-redirect
              §SUDO rm -rf "§DIR"
              §SUDO systemctl daemon-reload >/dev/null 2>&1 || true
            }
            trap 'rc=§?; if [ §rc -ne 0 ] && [ "§FRESH" = 1 ]; then echo "Install failed, undoing its changes" >&2; rollback; fi' EXIT

            step 1
            have systemctl || { echo 'systemd is required' >&2; exit 21; }
            ensure_user
            have iptables || pkg_install iptables
            §SUDO mkdir -p "§DIR" /usr/local/lib/maximus
            §SUDO chown root:maximus "§DIR"; §SUDO chmod 750 "§DIR"
            write_file /usr/local/lib/maximus/dnstt-redirect 0755 <<'HELPER'
            #!/bin/sh
            # Sends incoming UDP 53 to the DNS tunnel port while the service runs (add) and removes it (del).
            action="§1"; port="§2"
            for t in iptables ip6tables; do
              command -v "§t" >/dev/null 2>&1 || continue
              if [ "§action" = add ]; then
                "§t" -t nat -C PREROUTING -p udp --dport 53 -j REDIRECT --to-ports "§port" 2>/dev/null \
                  || "§t" -t nat -I PREROUTING -p udp --dport 53 -j REDIRECT --to-ports "§port" 2>/dev/null || true
              else
                while "§t" -t nat -D PREROUTING -p udp --dport 53 -j REDIRECT --to-ports "§port" 2>/dev/null; do :; done
              fi
            done
            exit 0
            HELPER

            step 2
            fetch_bin dnstt-server-linux-$a ${ServerBinaries.sha("dnstt-server", a)} /usr/local/bin/maximus-dnstt-server
            fetch_bin maximus-socks-linux-$a ${ServerBinaries.sha("maximus-socks", a)} /usr/local/bin/maximus-socks

            step 3
            if [ ${if (newKey) 1 else 0} = 1 ] || ! §SUDO test -s "§DIR/server.key"; then
              §SUDO rm -f "§DIR/server.key" "§DIR/server.pub"
              §SUDO /usr/local/bin/maximus-dnstt-server -gen-key -privkey-file "§DIR/server.key" -pubkey-file "§DIR/server.pub" >/dev/null 2>&1
            fi
            §SUDO chown root:maximus "§DIR/server.key" "§DIR/server.pub"
            §SUDO chmod 640 "§DIR/server.key"; §SUDO chmod 644 "§DIR/server.pub"

            step 4
            write_file /etc/systemd/system/maximus-socks.service 0644 <<'UNIT'
            @@SOCKS_UNIT@@
            UNIT
            write_file /etc/systemd/system/maximus-dnstt.service 0644 <<'UNIT'
            @@DNSTT_UNIT@@
            UNIT
            §SUDO systemctl daemon-reload

            step 5
            fw_allow 53 udp
            fw_allow $dnsPort udp

            step 6
            §SUDO systemctl enable maximus-socks maximus-dnstt >/dev/null 2>&1
            §SUDO systemctl restart maximus-socks maximus-dnstt
            wait_active maximus-socks maximus-dnstt
            result PUBKEY "§(§SUDO cat "§DIR/server.pub" | tr -d ' \n')"
            result DNS_PORT $dnsPort
            result SOCKS_PORT $socksPort
            """,
            DNSTT_STEPS,
            mapOf("SOCKS_UNIT" to socksUnit, "DNSTT_UNIT" to dnsttUnit)
        )
    }

    // ---------------------------------------------------------------- Hysteria2

    val HYSTERIA_STEPS = listOf("Prepare the server", "Download the verified server", "Create certificate and secrets",
        "Add the service", "Open the UDP port", "Start and verify")

    /**
     * Installs or reconfigures Hysteria2 on UDP [port] with password [auth] and Salamander key
     * [obfs]. The certificate is self-signed for [host] and pinned by the app; [newCert] replaces it.
     * [oldPort] is closed in the firewall after a port change.
     */
    fun hysteriaInstall(arch: String, host: String, port: Int, auth: String, obfs: String, newCert: Boolean, oldPort: Int? = null): ToolScript {
        val a = arch(arch)
        port(port, "Hysteria2")
        oldPort?.let { port(it, "old") }
        require(HOST.matches(host)) { "Invalid server address" }
        require(SECRET.matches(auth) && SECRET.matches(obfs)) { "Invalid Hysteria2 secret" }
        val isIp = host.matches(Regex("[0-9.]+")) || host.contains(':')
        val san = if (isIp) "IP.1 = $host" else "DNS.1 = $host"
        val cn = if (isIp) "maximus" else host
        val hyUnit = unit(
            "Maximus Hysteria2 server",
            listOf(
                "Environment=HYSTERIA_DISABLE_UPDATE_CHECK=1",
                "ExecStart=/usr/local/bin/maximus-hysteria server -c $HYSTERIA_DIR/config.yaml --disable-update-check"
            ),
            extra = listOf("AmbientCapabilities=CAP_NET_BIND_SERVICE", "CapabilityBoundingSet=CAP_NET_BIND_SERVICE")
        )
        val closeOld = if (oldPort != null && oldPort != port) "fw_close $oldPort udp" else ":"
        return script(
            """
            DIR=$HYSTERIA_DIR
            FRESH=1
            if §SUDO test -f /etc/systemd/system/maximus-hysteria.service; then FRESH=0; fi
            rollback() {
              §SUDO systemctl disable --now maximus-hysteria >/dev/null 2>&1 || true
              §SUDO rm -f /etc/systemd/system/maximus-hysteria.service /usr/local/bin/maximus-hysteria /etc/sysctl.d/60-maximus-hysteria.conf
              §SUDO rm -rf "§DIR"
              §SUDO systemctl daemon-reload >/dev/null 2>&1 || true
            }
            trap 'rc=§?; if [ §rc -ne 0 ] && [ "§FRESH" = 1 ]; then echo "Install failed, undoing its changes" >&2; rollback; fi' EXIT

            step 1
            have systemctl || { echo 'systemd is required' >&2; exit 21; }
            ensure_user
            have openssl || pkg_install openssl
            §SUDO mkdir -p "§DIR"; §SUDO chown root:maximus "§DIR"; §SUDO chmod 750 "§DIR"

            step 2
            fetch_bin hysteria-linux-$a ${ServerBinaries.sha("hysteria", a)} /usr/local/bin/maximus-hysteria

            step 3
            if [ ${if (newCert) 1 else 0} = 1 ] || ! §SUDO test -s "§DIR/cert.pem"; then
              cnf=§(mktemp)
              printf '[req]\ndistinguished_name = dn\nx509_extensions = v3\nprompt = no\n[dn]\nCN = $cn\n[v3]\nsubjectAltName = @alt\n[alt]\n$san\n' > "§cnf"
              §SUDO openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 3650 \
                -keyout "§DIR/key.pem" -out "§DIR/cert.pem" -config "§cnf" >/dev/null 2>&1
              rm -f "§cnf"
            fi
            §SUDO chown root:maximus "§DIR/key.pem" "§DIR/cert.pem"; §SUDO chmod 640 "§DIR/key.pem"
            write_file "§DIR/config.yaml" 0640 <<'CONF'
            listen: :$port
            tls:
              cert: $HYSTERIA_DIR/cert.pem
              key: $HYSTERIA_DIR/key.pem
            auth:
              type: password
              password: $auth
            obfs:
              type: salamander
              salamander:
                password: $obfs
            acl:
              inline:
                - reject(127.0.0.0/8)
                - reject(10.0.0.0/8)
                - reject(172.16.0.0/12)
                - reject(192.168.0.0/16)
                - reject(169.254.0.0/16)
                - reject(100.64.0.0/10)
                - reject(::1/128)
                - reject(fc00::/7)
                - reject(fe80::/10)
                - direct(all)
            CONF
            §SUDO chown root:maximus "§DIR/config.yaml"
            printf 'net.core.rmem_max=16777216\nnet.core.wmem_max=16777216\n' | write_file /etc/sysctl.d/60-maximus-hysteria.conf 0644
            §SUDO sysctl -q -p /etc/sysctl.d/60-maximus-hysteria.conf >/dev/null 2>&1 || true

            step 4
            write_file /etc/systemd/system/maximus-hysteria.service 0644 <<'UNIT'
            @@HY_UNIT@@
            UNIT
            §SUDO systemctl daemon-reload

            step 5
            fw_allow $port udp
            $closeOld

            step 6
            §SUDO systemctl enable maximus-hysteria >/dev/null 2>&1
            §SUDO systemctl restart maximus-hysteria
            wait_active maximus-hysteria
            result PORT $port
            result CERT_SHA256 "§(§SUDO openssl x509 -noout -fingerprint -sha256 -in "§DIR/cert.pem" | sed 's/.*=//; s/://g' | tr 'A-F' 'a-f')"
            """,
            HYSTERIA_STEPS,
            mapOf("HY_UNIT" to hyUnit)
        )
    }

    // ---------------------------------------------------------------- SSH key login

    val KEY_STEPS = listOf("Add this phone's key")

    /** Adds [publicKey] to the signed-in account's authorized_keys (no sudo; it is that account's file). */
    fun keyLoginAdd(publicKey: String): ToolScript {
        require(PUBLIC_KEY.matches(publicKey)) { "Invalid public key" }
        return ToolScript(
            """
            set -eu
            umask 077
            echo '${STEP}1'
            mkdir -p "§HOME/.ssh"
            chmod 700 "§HOME/.ssh"
            touch "§HOME/.ssh/authorized_keys"
            chmod 600 "§HOME/.ssh/authorized_keys"
            if ! grep -qxF '$publicKey' "§HOME/.ssh/authorized_keys"; then
              printf '%s\n' '$publicKey' >> "§HOME/.ssh/authorized_keys"
            fi
            if command -v restorecon >/dev/null 2>&1; then restorecon -R "§HOME/.ssh" >/dev/null 2>&1 || true; fi
            echo '${RESULT}KEY=added'
            """.trimIndent().replace('§', '$'),
            KEY_STEPS
        )
    }

    val PASSWORD_STEPS = listOf("Check the SSH settings", "Apply and reload SSH")

    /**
     * Turns SSH password login off (or back on) with a drop-in that sorts before cloud-init's. The
     * app runs it only after a key login succeeded; sshd's own check must pass before the reload.
     */
    fun passwordLogin(off: Boolean): ToolScript = script(
        """
        CONF=/etc/ssh/sshd_config.d/01-maximus-keys.conf
        step 1
        if ! grep -qsE '^[[:space:]]*Include[[:space:]]+/etc/ssh/sshd_config\.d/' /etc/ssh/sshd_config; then
          echo 'This SSH server does not read /etc/ssh/sshd_config.d; change PasswordAuthentication by hand' >&2; exit 50
        fi
        step 2
        if [ ${if (off) 1 else 0} = 1 ]; then
          printf '# Added by Maximus VPN after key login was verified.\nPasswordAuthentication no\nKbdInteractiveAuthentication no\n' | write_file "§CONF" 0644
        else
          §SUDO rm -f "§CONF"
        fi
        if ! §SUDO sshd -t; then §SUDO rm -f "§CONF"; echo 'sshd rejected the change, nothing was applied' >&2; exit 51; fi
        §SUDO systemctl reload ssh 2>/dev/null || §SUDO systemctl reload sshd
        result PASSWORD_LOGIN "§(§SUDO sshd -T 2>/dev/null | awk '/^passwordauthentication /{print §2}')"
        """,
        PASSWORD_STEPS
    )

    // ---------------------------------------------------------------- Security and speed

    val FIREWALL_STEPS = listOf("Install UFW", "Allow SSH and the ports in use", "Turn the firewall on")

    /** Allows [sshPort], every port a service listens on now and [extraTcp]/[extraUdp], then enables UFW. */
    fun firewall(sshPort: Int, extraTcp: Set<Int>, extraUdp: Set<Int>): ToolScript {
        port(sshPort, "SSH")
        (extraTcp + extraUdp).forEach { port(it, "extra") }
        return script(
            """
            step 1
            have ufw || pkg_install ufw
            step 2
            §SUDO ufw allow $sshPort/tcp >/dev/null
            for p in §(ss -Hlnt | awk '{print §4}' | grep -vE '^(127[.]|[[]::1[]]|::1)' | sed 's/.*://' | sort -un) ${extraTcp.sorted().joinToString(" ")}; do
              §SUDO ufw allow "§p/tcp" >/dev/null
            done
            for p in §(ss -Hlnu | awk '{print §4}' | grep -vE '^(127[.]|[[]::1[]]|::1)' | sed 's/.*://' | sort -un) ${extraUdp.sorted().joinToString(" ")}; do
              §SUDO ufw allow "§p/udp" >/dev/null
            done
            step 3
            §SUDO ufw default deny incoming >/dev/null
            §SUDO ufw default allow outgoing >/dev/null
            §SUDO ufw --force enable >/dev/null
            result FIREWALL "§(§SUDO ufw status | head -n1 | sed 's/Status: //')"
            """,
            FIREWALL_STEPS
        )
    }

    val FAIL2BAN_STEPS = listOf("Install fail2ban", "Add the SSH jail", "Start and verify")

    fun fail2ban(sshPort: Int): ToolScript {
        port(sshPort, "SSH")
        return script(
            """
            step 1
            pkg_install fail2ban python3-systemd
            step 2
            write_file /etc/fail2ban/jail.d/maximus-sshd.local 0644 <<'JAIL'
            [sshd]
            enabled = true
            port = $sshPort
            backend = systemd
            maxretry = 5
            findtime = 10m
            bantime = 1h
            JAIL
            step 3
            §SUDO systemctl enable fail2ban >/dev/null 2>&1
            §SUDO systemctl restart fail2ban
            wait_active fail2ban
            sleep 1
            §SUDO fail2ban-client status sshd >/dev/null
            result FAIL2BAN active
            """,
            FAIL2BAN_STEPS
        )
    }

    val UPDATES_STEPS = listOf("Install unattended-upgrades", "Turn on daily security updates")

    fun autoUpdates(): ToolScript = script(
        """
        step 1
        pkg_install unattended-upgrades
        step 2
        printf 'APT::Periodic::Update-Package-Lists "1";\nAPT::Periodic::Unattended-Upgrade "1";\n' | write_file /etc/apt/apt.conf.d/20auto-upgrades 0644
        result AUTO_UPDATES on
        """,
        UPDATES_STEPS
    )

    val BBR_STEPS = listOf("Load BBR", "Make it permanent")

    fun bbr(): ToolScript = script(
        """
        step 1
        §SUDO modprobe tcp_bbr 2>/dev/null || true
        if ! grep -qw bbr /proc/sys/net/ipv4/tcp_available_congestion_control; then
          echo 'This kernel has no BBR (a container or a very old kernel)' >&2; exit 60
        fi
        step 2
        printf 'net.core.default_qdisc=fq\nnet.ipv4.tcp_congestion_control=bbr\n' | write_file /etc/sysctl.d/60-maximus-bbr.conf 0644
        echo tcp_bbr | write_file /etc/modules-load.d/maximus-bbr.conf 0644
        §SUDO sysctl -q -p /etc/sysctl.d/60-maximus-bbr.conf >/dev/null
        result CC "§(cat /proc/sys/net/ipv4/tcp_congestion_control)"
        """,
        BBR_STEPS
    )

    val SWAP_STEPS = listOf("Create the swap file", "Turn it on")

    fun swap(sizeMb: Int): ToolScript {
        require(sizeMb in 256..8192) { "Invalid swap size" }
        return script(
            """
            FRESH=1
            if §SUDO test -e /swapfile; then FRESH=0; fi
            trap 'rc=§?; if [ §rc -ne 0 ] && [ "§FRESH" = 1 ]; then §SUDO swapoff /swapfile 2>/dev/null || true; §SUDO rm -f /swapfile; fi' EXIT
            step 1
            if §SUDO swapon --show=NAME --noheadings 2>/dev/null | grep -q .; then
              echo 'Swap is already on'
            else
              if [ "§FRESH" = 1 ]; then
                §SUDO fallocate -l ${sizeMb}M /swapfile 2>/dev/null || §SUDO dd if=/dev/zero of=/swapfile bs=1M count=$sizeMb status=none
              fi
              §SUDO chmod 600 /swapfile
              §SUDO mkswap /swapfile >/dev/null
              step 2
              §SUDO swapon /swapfile
            fi
            if ! grep -qs '^/swapfile ' /etc/fstab; then echo '/swapfile none swap sw 0 0' | §SUDO tee -a /etc/fstab >/dev/null; fi
            echo 'vm.swappiness=10' | write_file /etc/sysctl.d/60-maximus-swap.conf 0644
            §SUDO sysctl -q -p /etc/sysctl.d/60-maximus-swap.conf >/dev/null 2>&1 || true
            result SWAP_MB "§(free -m | awk '/^Swap:/{print §2}')"
            """,
            SWAP_STEPS
        )
    }

    // ---------------------------------------------------------------- Manage

    val UNINSTALL_STEPS = listOf("Stop and remove")

    /** Removes what the install of [toolId] added. [port] closes the tool's firewall port. */
    fun uninstall(toolId: String, port: Int? = null): ToolScript {
        port?.let { port(it, "tool") }
        val body = when (toolId) {
            ServerToolCatalog.DNSTT -> """
                §SUDO systemctl disable --now maximus-dnstt maximus-socks >/dev/null 2>&1 || true
                §SUDO rm -f /etc/systemd/system/maximus-dnstt.service /etc/systemd/system/maximus-socks.service \
                  /usr/local/bin/maximus-dnstt-server /usr/local/bin/maximus-socks /usr/local/lib/maximus/dnstt-redirect
                §SUDO rm -rf $DNSTT_DIR
                §SUDO systemctl daemon-reload
                fw_close 53 udp
                ${port?.let { "fw_close $it udp" } ?: ":"}
            """
            ServerToolCatalog.HYSTERIA2 -> """
                §SUDO systemctl disable --now maximus-hysteria >/dev/null 2>&1 || true
                §SUDO rm -f /etc/systemd/system/maximus-hysteria.service /usr/local/bin/maximus-hysteria /etc/sysctl.d/60-maximus-hysteria.conf
                §SUDO rm -rf $HYSTERIA_DIR
                §SUDO systemctl daemon-reload
                ${port?.let { "fw_close $it udp" } ?: ":"}
            """
            ServerToolCatalog.XUI -> """
                §SUDO systemctl disable --now x-ui >/dev/null 2>&1 || true
                §SUDO rm -rf /usr/local/x-ui /etc/x-ui /etc/systemd/system/x-ui.service /usr/bin/x-ui
                §SUDO systemctl daemon-reload
            """
            ServerToolCatalog.FIREWALL -> "§SUDO ufw --force disable >/dev/null"
            ServerToolCatalog.FAIL2BAN -> """
                §SUDO rm -f /etc/fail2ban/jail.d/maximus-sshd.local
                §SUDO systemctl disable --now fail2ban >/dev/null 2>&1 || true
            """
            ServerToolCatalog.AUTO_UPDATES ->
                "printf 'APT::Periodic::Update-Package-Lists \"0\";\\nAPT::Periodic::Unattended-Upgrade \"0\";\\n' | write_file /etc/apt/apt.conf.d/20auto-upgrades 0644"
            ServerToolCatalog.BBR -> """
                §SUDO rm -f /etc/sysctl.d/60-maximus-bbr.conf /etc/modules-load.d/maximus-bbr.conf
                §SUDO sysctl -q -w net.ipv4.tcp_congestion_control=cubic >/dev/null 2>&1 || true
            """
            ServerToolCatalog.SWAP -> """
                §SUDO swapoff /swapfile 2>/dev/null || true
                §SUDO sed -i '\|^/swapfile |d' /etc/fstab
                §SUDO rm -f /swapfile /etc/sysctl.d/60-maximus-swap.conf
            """
            else -> throw IllegalArgumentException("Nothing to remove for $toolId")
        }
        return script("step 1\n" + body.trimIndent() + "\nresult REMOVED $toolId", UNINSTALL_STEPS)
    }

    /** Restarts the units of [toolId] and waits for them. */
    fun restart(toolId: String): ToolScript {
        val units = requireNotNull(UNITS[toolId]) { "Nothing to restart for $toolId" }.joinToString(" ")
        return script("step 1\n§SUDO systemctl restart $units\nwait_active $units\nresult RESTARTED $toolId", listOf("Restart"))
    }

    /** The last [lines] journal lines of [toolId]'s units. */
    fun logs(toolId: String, lines: Int = 40): ToolScript {
        val units = requireNotNull(UNITS[toolId]) { "No logs for $toolId" }
        require(lines in 1..200)
        return script(
            "step 1\n§SUDO journalctl ${units.joinToString(" ") { "-u $it" }} -n $lines --no-pager -o short-iso 2>&1 | tail -n $lines",
            listOf("Read logs")
        )
    }
}
