package com.example.panels.servers.tunnel

import com.example.panels.servers.ServerBinaries
import com.example.panels.servers.ToolScript
import com.example.panels.servers.ToolScripts

/**
 * Shell scripts for Maximus Tunnel, in the same style and under the same rules as [ToolScripts]:
 * pinned programs only, values checked before they reach a script, a failed first install undoes
 * itself, services run as the locked `maximus` user. REALITY private keys and the Hysteria2
 * certificate key are created on the servers and never leave them; the app only learns the public
 * halves.
 */
object TunnelScripts {
    const val DIR = "/etc/maximus/tunnel"
    const val BIN = "/usr/local/lib/maximus/tunnel"
    const val ROTATE = "/usr/local/lib/maximus/tunnel-rotate"
    const val KEY_PLACEHOLDER = "@PRIVATE_KEY@"

    val UNITS = listOf("maximus-tunnel", "maximus-tunnel-hy")

    /** Sites the REALITY handshake can imitate, tried in order from each server (TLS 1.3 and HTTP/2 needed). */
    val ABROAD_SNI = listOf("www.microsoft.com", "www.apple.com", "dl.google.com", "www.cloudflare.com", "www.samsung.com", "www.amazon.com")
    val IRAN_SNI = listOf("www.digikala.com", "www.aparat.com", "www.divar.ir", "snapp.ir", "www.speedtest.net", "www.microsoft.com", "www.apple.com")

    private val SNI = Regex("^(?=.{4,200}$)([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}$")
    private val HEX = Regex("^[0-9a-f]{8,64}$")
    private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val KEY = Regex("^[A-Za-z0-9_-]{43}$")
    private val PATH = Regex("^/[A-Za-z0-9]{4,32}$")
    private val CERT = Regex("^-----BEGIN CERTIFICATE-----\\n[A-Za-z0-9+/=\\n]{100,4000}\\n-----END CERTIFICATE-----$")

    /** Checks every value of [spec] that ends up in a script or a config file. */
    fun validate(spec: TunnelSpec) {
        require(spec.transports.isNotEmpty()) { "Pick at least one way to reach the server abroad" }
        require(spec.rotationHours in TunnelSpec.ROTATION_CHOICES) { "Invalid rotation period" }
        require(HEX.matches(spec.seed) && spec.seed.length >= 32) { "Invalid rotation seed" }
        require(UUID.matches(spec.entryUuid) && UUID.matches(spec.linkUuid)) { "Invalid tunnel id" }
        require(SNI.matches(spec.entrySni) && SNI.matches(spec.exitSni)) { "Invalid camouflage site" }
        require(HEX.matches(spec.entryShortId) && spec.entryShortId.length <= 16 && HEX.matches(spec.exitShortId) && spec.exitShortId.length <= 16) { "Invalid short id" }
        require(PATH.matches(spec.xhttpPath)) { "Invalid XHTTP path" }
        require(ToolScripts.SECRET.matches(spec.hyAuth) && ToolScripts.SECRET.matches(spec.hyObfs)) { "Invalid Hysteria2 secret" }
        require(spec.exitPublicKey.isEmpty() || KEY.matches(spec.exitPublicKey)) { "Invalid key from the server abroad" }
        require(spec.entryPublicKey.isEmpty() || KEY.matches(spec.entryPublicKey)) { "Invalid key from the Iran server" }
        listOf(spec.entryPort, spec.hyPort, spec.hyLocalPort).forEach { ToolScripts.port(it, "tunnel") }
        spec.fixedPorts.values.forEach { ToolScripts.port(it, "tunnel") }
        spec.avoidPorts.forEach { ToolScripts.port(it, "avoided") }
        if (TunnelTransport.REVERSE in spec.transports) require(UUID.matches(spec.reverseUuid) && spec.reverseUuid != spec.entryUuid) { "Invalid reverse id" }
        if (spec.rotationHours == 0) require(TunnelTransport.ROTATING.filter { it in spec.transports }.all { it in spec.fixedPorts }) { "Missing a fixed port" }
    }

    // ---------------------------------------------------------------- check (read-only)

    val CHECK_STEPS = listOf("Read the clock", "Look for camouflage sites", "Check GitHub", "Try the other server")

    /**
     * Changes nothing: the server's clock, which [sites] answer with TLS 1.3 + HTTP/2, whether GitHub
     * is reachable, and whether a TCP connection to the other server ([peerHost]:[peerPort], its SSH
     * port) opens. REACH is ok, no or unknown (no tool to test with).
     */
    fun check(sites: List<String>, peerHost: String, peerPort: Int): ToolScript {
        require(sites.all { SNI.matches(it) })
        require(ToolScripts.HOST.matches(peerHost)) { "Invalid address of the other server" }
        ToolScripts.port(peerPort, "SSH")
        return ToolScripts.script(
            """
            step 1
            result TIME "§(date +%s)"
            have curl || pkg_install curl ca-certificates
            have openssl || pkg_install openssl
            step 2
            ok=""
            for h in ${sites.joinToString(" ")}; do
              v=§(curl -sS -o /dev/null --max-time 6 --tlsv1.3 --http2 -w '%{http_version}' "https://§h/" 2>/dev/null || true)
              if [ "§v" = 2 ]; then ok="§ok,§h"; fi
            done
            result SNI "§{ok#,}"
            step 3
            if curl -fsSIL -o /dev/null --max-time 15 "§MX_BASE/SHA256SUMS" 2>/dev/null; then result GITHUB ok; else result GITHUB no; fi
            step 4
            reach=unknown
            if have bash && have timeout; then
              if timeout 8 bash -c 'exec 3<>"/dev/tcp/§1/§2"' _ "$peerHost" $peerPort 2>/dev/null; then reach=ok; else reach=no; fi
            elif have nc; then
              if nc -z -w 8 "$peerHost" $peerPort 2>/dev/null; then reach=ok; else reach=no; fi
            fi
            result REACH "§reach"
            """,
            CHECK_STEPS
        )
    }

    // ---------------------------------------------------------------- shared parts

    /**
     * Writes the rotating Xray files for this period and the previous one from the templates and
     * restarts Xray when they changed. Abroad it also opens the new ports and closes the old ones.
     * Ports come from the shared seed and the clock (see [TunnelPorts]).
     */
    private val ROTATE_SCRIPT = """
        #!/bin/sh
        # Maximus Tunnel: writes this period's Xray port files (see TunnelPorts in the app).
        set -eu
        D=$DIR
        . "§D/rotate.env"
        umask 027
        port_for() {
          n=0
          while :; do
            if [ "§n" -eq 0 ]; then m="§1:§2"; else m="§1:§2:§n"; fi
            h=§(printf '%s' "§m" | openssl dgst -sha256 -hmac "§SEED" | awk '{print §NF}' | cut -c1-8)
            p=§((20000 + 0x§h % 40000))
            case " §AVOID " in *" §p "*) n=§((n + 1)) ;; *) echo "§p"; return 0 ;; esac
          done
        }
        fw() {
          if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q 'Status: active'; then
            if [ "§1" = allow ]; then ufw allow "§2/tcp" >/dev/null; else ufw delete allow "§2/tcp" >/dev/null 2>&1 || true; fi
          fi
          if command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1; then
            if [ "§1" = allow ]; then firewall-cmd --permanent --add-port="§2/tcp" >/dev/null
            else firewall-cmd --permanent --remove-port="§2/tcp" >/dev/null 2>&1 || true; fi
            firewall-cmd --reload >/dev/null 2>&1 || true
          fi
        }
        new=§(mktemp -d)
        trap 'rm -rf "§new"' EXIT
        ports=""
        now=§(date +%s)
        for t in §NAMES; do
          if [ "§HOURS" -gt 0 ]; then
            e=§((now / (HOURS * 3600)))
            pn=§(port_for "§t" "§e"); pp=§(port_for "§t" §((e - 1)))
          else
            eval "pn=\§FIXED_§t"; pp="§pn"
          fi
          sed -e "s/@PORT@/§pn/" -e "s/@TAG@/t-§t-now/" "§D/tpl/§t.json" > "§new/20-t-§t-now.json"
          ports="§ports §pn"
          if [ "§pp" != "§pn" ]; then
            sed -e "s/@PORT@/§pp/" -e "s/@TAG@/t-§t-prev/" "§D/tpl/§t.json" > "§new/21-t-§t-prev.json"
            ports="§ports §pp"
          fi
        done
        old=§(cat "§D/ports" 2>/dev/null || true)
        if [ "§ROLE" = abroad ]; then
          for p in §ports; do fw allow "§p"; done
          for p in §old; do case " §ports " in *" §p "*) ;; *) fw delete "§p" ;; esac; done
        fi
        changed=0
        for f in "§new"/*.json; do cmp -s "§f" "§D/conf.d/§(basename "§f")" || changed=1; done
        for f in "§D"/conf.d/2*-t-*.json; do [ -e "§f" ] || continue; [ -e "§new/§(basename "§f")" ] || changed=1; done
        if [ "§changed" = 1 ]; then
          rm -f "§D"/conf.d/2*-t-*.json
          for f in "§new"/*.json; do install -m 0640 -g maximus "§f" "§D/conf.d/§(basename "§f")"; done
          echo "§ports" > "§D/ports"
          if [ "§{1:-}" != --no-restart ]; then systemctl restart maximus-tunnel; fi
        fi
        echo "PORTS=§ports"
    """.trimIndent()

    private fun rotateEnv(spec: TunnelSpec, role: String): String {
        val names = TunnelTransport.ROTATING.filter { it in spec.transports }
        return (listOf(
            "ROLE=$role", "SEED=${spec.seed}", "HOURS=${spec.rotationHours}",
            "NAMES=\"${names.joinToString(" ") { it.id }}\"",
            "AVOID=\"${spec.avoidPorts.sorted().joinToString(" ")}\""
        ) + names.mapNotNull { t -> spec.fixedPorts[t]?.let { "FIXED_${t.id}=$it" } }).joinToString("\n")
    }

    private fun mainUnit(role: String) = ToolScripts.unit(
        if (role == TunnelSpec.ROLE_IRAN) "Maximus Tunnel (Iran side)" else "Maximus Tunnel (abroad side)",
        listOf("Environment=XRAY_LOCATION_ASSET=$DIR", "ExecStart=$BIN/xray run -confdir $DIR/conf.d"),
        extra = listOf("LimitNOFILE=65535", "AmbientCapabilities=CAP_NET_BIND_SERVICE", "CapabilityBoundingSet=CAP_NET_BIND_SERVICE")
    )

    private fun hyUnit(role: String) = ToolScripts.unit(
        if (role == TunnelSpec.ROLE_IRAN) "Maximus Tunnel Hysteria2 client" else "Maximus Tunnel Hysteria2 server",
        listOf(
            "Environment=HYSTERIA_DISABLE_UPDATE_CHECK=1",
            if (role == TunnelSpec.ROLE_IRAN) "ExecStart=$BIN/hysteria client -c $DIR/hy/client.yaml --disable-update-check"
            else "ExecStart=$BIN/hysteria server -c $DIR/hy/server.yaml --disable-update-check"
        ),
        extra = listOf("AmbientCapabilities=CAP_NET_BIND_SERVICE", "CapabilityBoundingSet=CAP_NET_BIND_SERVICE")
    )

    private val ROTATE_UNIT = listOf(
        "[Unit]", "Description=Maximus Tunnel port rotation", "", "[Service]", "Type=oneshot", "ExecStart=$ROTATE"
    ).joinToString("\n")

    private fun timer(hours: Int): String = listOf(
        "[Unit]", "Description=Maximus Tunnel port rotation every $hours hours", "", "[Timer]",
        "OnCalendar=*-*-* 00/$hours:00:05 UTC", "OnBootSec=30s", "AccuracySec=1s", "Persistent=true",
        "", "[Install]", "WantedBy=timers.target"
    ).joinToString("\n")

    /** Creates (or keeps) this server's REALITY key pair in [file] and puts the private key into [targets]. */
    private fun keyBlock(file: String, renew: Boolean, targets: String) = """
        if [ ${if (renew) 1 else 0} = 1 ] || ! §SUDO test -s "$file"; then
          §SUDO $BIN/xray x25519 | §SUDO tee "$file" >/dev/null
          §SUDO chmod 600 "$file"
        fi
        PRIV=§(§SUDO awk -F': *' '/^PrivateKey/{print §2}' "$file")
        PUB=§(§SUDO awk -F': *' '/PublicKey|^Password/{print §2}' "$file" | head -n1)
        [ -n "§PRIV" ] && [ -n "§PUB" ] || { echo 'Could not create the REALITY key' >&2; exit 70; }
        for f in $targets; do §SUDO sed -i "s|$KEY_PLACEHOLDER|§PRIV|" "§f"; done
    """.trimIndent()

    private fun rollbackBlock() = """
        FRESH=1
        if §SUDO test -f /etc/systemd/system/maximus-tunnel.service; then FRESH=0; fi
        rollback() {
          §SUDO systemctl disable --now maximus-tunnel maximus-tunnel-hy maximus-tunnel-rotate.timer >/dev/null 2>&1 || true
          §SUDO rm -f /etc/systemd/system/maximus-tunnel.service /etc/systemd/system/maximus-tunnel-hy.service \
            /etc/systemd/system/maximus-tunnel-rotate.service /etc/systemd/system/maximus-tunnel-rotate.timer $ROTATE
          §SUDO rm -rf "$DIR" "$BIN"
          §SUDO systemctl daemon-reload >/dev/null 2>&1 || true
        }
        trap 'rc=§?; if [ §rc -ne 0 ] && [ "§FRESH" = 1 ]; then echo "Setup failed, undoing its changes" >&2; rollback; fi' EXIT
    """.trimIndent()

    private fun startBlock(spec: TunnelSpec) = """
        §SUDO systemctl daemon-reload
        units=maximus-tunnel
        if [ ${if (TunnelTransport.HYSTERIA2 in spec.transports) 1 else 0} = 1 ]; then units="§units maximus-tunnel-hy"
        else §SUDO systemctl disable --now maximus-tunnel-hy >/dev/null 2>&1 || true; fi
        §SUDO systemctl enable §units >/dev/null 2>&1
        §SUDO systemctl restart §units
        if [ ${spec.rotationHours} -gt 0 ]; then
          §SUDO systemctl enable --now maximus-tunnel-rotate.timer >/dev/null 2>&1
        else
          §SUDO systemctl disable --now maximus-tunnel-rotate.timer >/dev/null 2>&1 || true
        fi
        wait_active §units
    """.trimIndent()

    private fun prepareBlock(spec: TunnelSpec, a: String) = """
        step 1
        have systemctl || { echo 'systemd is required' >&2; exit 21; }
        ensure_user
        have openssl || pkg_install openssl
        have curl || pkg_install curl ca-certificates
        §SUDO mkdir -p "$DIR/conf.d" "$DIR/tpl" "$DIR/hy" "$BIN"
        §SUDO chown root:maximus "$DIR" "$DIR/conf.d" "$DIR/hy"; §SUDO chmod 750 "$DIR" "$DIR/conf.d" "$DIR/hy"
        §SUDO chmod 700 "$DIR/tpl"

        step 2
        fetch_bin xray-linux-$a ${ServerBinaries.sha("xray", a)} $BIN/xray
        if [ ${if (TunnelTransport.HYSTERIA2 in spec.transports) 1 else 0} = 1 ]; then
          fetch_bin hysteria-linux-$a ${ServerBinaries.sha("hysteria", a)} $BIN/hysteria
        fi
    """.trimIndent()

    private fun commonFiles(spec: TunnelSpec, role: String): Map<String, String> = mapOf(
        "ROTATE_SCRIPT" to ROTATE_SCRIPT,
        "ROTATE_ENV" to rotateEnv(spec, role),
        "MAIN_UNIT" to mainUnit(role),
        "HY_UNIT" to hyUnit(role),
        "ROTATE_UNIT" to ROTATE_UNIT,
        "TIMER" to timer(spec.rotationHours.coerceAtLeast(1))
    )

    private val WRITE_COMMON = """
        write_file $ROTATE 0755 <<'EOF_ROTATE'
        @@ROTATE_SCRIPT@@
        EOF_ROTATE
        write_file $DIR/rotate.env 0600 <<'EOF_ENV'
        @@ROTATE_ENV@@
        EOF_ENV
        write_file /etc/systemd/system/maximus-tunnel.service 0644 <<'EOF_UNIT'
        @@MAIN_UNIT@@
        EOF_UNIT
        write_file /etc/systemd/system/maximus-tunnel-hy.service 0644 <<'EOF_UNIT'
        @@HY_UNIT@@
        EOF_UNIT
        write_file /etc/systemd/system/maximus-tunnel-rotate.service 0644 <<'EOF_UNIT'
        @@ROTATE_UNIT@@
        EOF_UNIT
        write_file /etc/systemd/system/maximus-tunnel-rotate.timer 0644 <<'EOF_UNIT'
        @@TIMER@@
        EOF_UNIT
    """.trimIndent()

    // ---------------------------------------------------------------- server abroad

    /**
     * Joins [body] with the shared blocks. Inserts are applied in order after the body is
     * de-indented: first the blocks (which hold further `@@NAME@@` lines), then the files.
     */
    private fun assemble(body: String, steps: List<String>, spec: TunnelSpec, role: String, arch: String,
                         keyFile: String, keyTargets: String, renew: Boolean, files: Map<String, String>): ToolScript {
        val inserts = linkedMapOf(
            "ROLLBACK" to rollbackBlock(),
            "PREPARE" to prepareBlock(spec, arch),
            "WRITE_COMMON" to WRITE_COMMON,
            "KEYS" to keyBlock(keyFile, renew, keyTargets),
            "START" to startBlock(spec)
        )
        inserts.putAll(files)
        inserts.putAll(commonFiles(spec, role))
        return ToolScripts.script(body, steps, inserts)
    }

    private fun writeTemplates(rotating: List<TunnelTransport>) = rotating.joinToString("\n") { t ->
        "write_file $DIR/tpl/${t.id}.json 0600 <<'EOF_TPL'\n@@TPL_${t.id}@@\nEOF_TPL"
    }.ifBlank { ":" }

    val ABROAD_STEPS = listOf("Prepare", "Download verified programs", "Write the configs", "Create keys", "Open the ports", "Start and verify")

    /**
     * Sets up (or updates) the exit on [abroadHost]. [renewKeys] replaces its REALITY key and
     * Hysteria2 certificate. Results: PUBKEY (REALITY public key), CERT (Hysteria2 certificate as
     * base64 PEM), PORTS.
     */
    fun abroadInstall(spec: TunnelSpec, arch: String, abroadHost: String, renewKeys: Boolean): ToolScript {
        validate(spec)
        require(ToolScripts.HOST.matches(abroadHost)) { "Invalid address of the server abroad" }
        val a = ToolScripts.arch(arch)
        val rotating = TunnelTransport.ROTATING.filter { it in spec.transports }
        val hy = if (TunnelTransport.HYSTERIA2 in spec.transports) 1 else 0
        val isIp = abroadHost.matches(Regex("[0-9.]+")) || abroadHost.contains(':')
        val san = if (isIp) "IP:$abroadHost" else "DNS:$abroadHost"
        val files = linkedMapOf("WRITE_TPL" to writeTemplates(rotating))
        rotating.forEach { files["TPL_${it.id}"] = TunnelConfigs.abroadTemplate(spec, it) }
        files["BASE"] = TunnelConfigs.abroadBase()
        files["HY_SERVER"] = TunnelConfigs.abroadHysteria(spec, "$DIR/hy").trimEnd()
        return assemble(
            """
            @@ROLLBACK@@

            @@PREPARE@@

            step 3
            @@WRITE_COMMON@@
            write_file $DIR/conf.d/00-base.json 0640 <<'EOF_BASE'
            @@BASE@@
            EOF_BASE
            §SUDO chgrp maximus $DIR/conf.d/00-base.json
            if [ ${if (TunnelTransport.REVERSE in spec.transports) 1 else 0} = 0 ]; then §SUDO rm -f $DIR/conf.d/${TunnelConfigs.REVERSE_FILE}; fi
            @@WRITE_TPL@@
            OLD_HY=§(§SUDO awk '/^listen:/{sub(/.*:/, ""); print}' $DIR/hy/server.yaml 2>/dev/null || true)
            if [ $hy = 1 ]; then
              write_file $DIR/hy/server.yaml 0640 <<'EOF_HY'
            @@HY_SERVER@@
            EOF_HY
              §SUDO chgrp maximus $DIR/hy/server.yaml
            fi

            step 4
            @@KEYS@@
            result PUBKEY "§PUB"
            if [ $hy = 1 ]; then
              if [ ${if (renewKeys) 1 else 0} = 1 ] || ! §SUDO test -s "$DIR/hy/cert.pem"; then
                §SUDO openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 3650 \
                  -keyout "$DIR/hy/key.pem" -out "$DIR/hy/cert.pem" -subj "/CN=maximus-tunnel" \
                  -addext "subjectAltName=$san" >/dev/null 2>&1
              fi
              §SUDO chown root:maximus "$DIR/hy/key.pem" "$DIR/hy/cert.pem"; §SUDO chmod 640 "$DIR/hy/key.pem"
              result CERT "§(§SUDO base64 "$DIR/hy/cert.pem" | tr -d '\n')"
            fi

            step 5
            OUT=§(§SUDO $ROTATE --no-restart)
            result PORTS "§(echo "§OUT" | sed -n 's/^PORTS= *//p')"
            if [ $hy = 1 ]; then
              fw_allow ${spec.hyPort} udp
              if [ -n "§OLD_HY" ] && [ "§OLD_HY" != ${spec.hyPort} ]; then fw_close "§OLD_HY" udp; fi
            elif [ -n "§OLD_HY" ]; then fw_close "§OLD_HY" udp; fi

            step 6
            @@START@@
            """,
            ABROAD_STEPS, spec, TunnelSpec.ROLE_ABROAD, a,
            "$DIR/reality.key", rotating.joinToString(" ") { "$DIR/tpl/${it.id}.json" }, renewKeys, files
        )
    }

    // ---------------------------------------------------------------- Iran server

    val IRAN_STEPS = listOf("Prepare", "Download verified programs", "Write the configs", "Create keys", "Open the phone's port", "Start and verify")

    /** Sets up (or updates) the Iran server. Results: PUBKEY (the phone's REALITY public key), PORTS. */
    fun iranInstall(spec: TunnelSpec, arch: String, abroadHost: String, renewKeys: Boolean, oldEntryPort: Int?): ToolScript {
        validate(spec)
        require(ToolScripts.HOST.matches(abroadHost)) { "Invalid address of the server abroad" }
        require(spec.exitPublicKey.isNotEmpty()) { "Set up the server abroad first" }
        val a = ToolScripts.arch(arch)
        val rotating = TunnelTransport.ROTATING.filter { it in spec.transports }
        val hy = if (TunnelTransport.HYSTERIA2 in spec.transports) 1 else 0
        if (hy == 1) require(CERT.matches(spec.hyCertPem.trim())) { "Missing the Hysteria2 certificate of the server abroad" }
        oldEntryPort?.let { ToolScripts.port(it, "old") }
        val files = linkedMapOf("WRITE_TPL" to writeTemplates(rotating))
        rotating.forEach { files["TPL_${it.id}"] = TunnelConfigs.iranTemplate(spec, abroadHost, it) }
        files["BASE"] = TunnelConfigs.iranBase(spec)
        files["BLOCK"] = TunnelConfigs.iranDefaultBlock()
        files["HY_OUT"] = TunnelConfigs.iranHysteriaOutbound(spec)
        files["HY_CLIENT"] = TunnelConfigs.iranHysteriaClient(spec, abroadHost, "$DIR/hy")
        files["HY_CERT"] = spec.hyCertPem.trim()
        val closeOld = if (oldEntryPort != null && oldEntryPort != spec.entryPort) "fw_close $oldEntryPort tcp" else ":"
        return assemble(
            """
            @@ROLLBACK@@

            @@PREPARE@@

            step 3
            @@WRITE_COMMON@@
            write_file $DIR/conf.d/00-base.json 0640 <<'EOF_BASE'
            @@BASE@@
            EOF_BASE
            §SUDO chgrp maximus $DIR/conf.d/00-base.json
            write_file $DIR/conf.d/${TunnelConfigs.IRAN_BLOCK_FILE} 0640 <<'EOF_BLOCK'
            @@BLOCK@@
            EOF_BLOCK
            §SUDO chgrp maximus $DIR/conf.d/${TunnelConfigs.IRAN_BLOCK_FILE}
            @@WRITE_TPL@@
            if [ $hy = 1 ]; then
              write_file $DIR/conf.d/30-t-hy2.json 0640 <<'EOF_HYOUT'
            @@HY_OUT@@
            EOF_HYOUT
              write_file $DIR/hy/client.yaml 0640 <<'EOF_HY'
            @@HY_CLIENT@@
            EOF_HY
              write_file $DIR/hy/abroad-cert.pem 0644 <<'EOF_CERT'
            @@HY_CERT@@
            EOF_CERT
              §SUDO chgrp maximus $DIR/conf.d/30-t-hy2.json $DIR/hy/client.yaml
            else
              §SUDO rm -f $DIR/conf.d/30-t-hy2.json
            fi
            if [ ${if (TunnelTransport.REVERSE in spec.transports) 1 else 0} = 1 ]; then
              write_file $DIR/reverse.env 0600 <<'EOF_RV'
            RV_FROM=$abroadHost
            RV_PORT=${spec.entryPort}
            EOF_RV
            else
              §SUDO rm -f $DIR/reverse.env
            fi

            step 4
            @@KEYS@@
            result PUBKEY "§PUB"

            step 5
            OUT=§(§SUDO $ROTATE --no-restart)
            result PORTS "§(echo "§OUT" | sed -n 's/^PORTS= *//p')"
            fw_allow ${spec.entryPort} tcp
            $closeOld

            step 6
            @@START@@
            """,
            IRAN_STEPS, spec, TunnelSpec.ROLE_IRAN, a,
            "$DIR/entry.key", "$DIR/conf.d/00-base.json", renewKeys, files
        )
    }

    val REVERSE_STEPS = listOf("Link back to the Iran server", "Start and verify")

    /**
     * On the server abroad, after the Iran server is set up: writes the reverse link to [iranHost]
     * (it needs the Iran server's public key) and restarts Xray. The server abroad only connects
     * out, so no port is opened for it.
     */
    fun abroadReverse(spec: TunnelSpec, iranHost: String): ToolScript {
        validate(spec)
        require(TunnelTransport.REVERSE in spec.transports)
        require(ToolScripts.HOST.matches(iranHost)) { "Invalid address of the Iran server" }
        return ToolScripts.script(
            """
            step 1
            write_file $DIR/conf.d/${TunnelConfigs.REVERSE_FILE} 0640 <<'EOF_RV'
            @@RV@@
            EOF_RV
            §SUDO chgrp maximus $DIR/conf.d/${TunnelConfigs.REVERSE_FILE}
            step 2
            §SUDO systemctl restart maximus-tunnel
            wait_active maximus-tunnel
            result REVERSE linked
            """,
            REVERSE_STEPS,
            mapOf("RV" to TunnelConfigs.abroadReverse(spec, iranHost))
        )
    }

    // ---------------------------------------------------------------- manage

    val STATUS_STEPS = listOf("Read the tunnel", "Test each path")

    /**
     * On the Iran server: the current ports and a real request over each path to the server abroad,
     * each through a short-lived Xray that uses only that path. Results: PATH_<tag>=ok:<ms> or
     * fail, PORTS, SVC_<unit>, TIME.
     */
    fun iranStatus(): ToolScript = ToolScripts.script(
        """
        step 1
        result TIME "§(date +%s)"
        result PORTS "§(cat $DIR/ports 2>/dev/null | tr -s ' ' | sed 's/^ //')"
        for u in ${UNITS.joinToString(" ")}; do result "SVC_§u" "§(systemctl is-active §u 2>/dev/null || true)"; done
        step 2
        work=§(mktemp -d)
        pid=""
        trap 'if [ -n "§pid" ]; then kill "§pid" 2>/dev/null || true; fi; rm -rf "§work"' EXIT
        i=0
        for f in §(§SUDO sh -c 'ls $DIR/conf.d/2*-t-*.json $DIR/conf.d/30-t-hy2.json 2>/dev/null' || true); do
          tag=§(basename "§f" .json | sed 's/^[0-9]*-//')
          i=§((i + 1))
          port=§((39000 + (§§ % 500) * 2 + i))
          rm -rf "§work/c"; mkdir -p "§work/c"
          printf '{"inbounds":[{"listen":"127.0.0.1","port":%s,"protocol":"socks","settings":{"udp":false}}]}\n' "§port" > "§work/c/00-in.json"
          §SUDO cat "§f" > "§work/c/10-out.json"
          $BIN/xray run -confdir "§work/c" >/dev/null 2>&1 &
          pid=§!
          n=0
          if have ss; then
            while [ §n -lt 25 ] && ! ss -Hlnt "sport = :§port" 2>/dev/null | grep -q .; do sleep 0.2; n=§((n + 1)); done
          else sleep 1; fi
          r=§(curl -s -o /dev/null -w '%{http_code} %{time_total}' --max-time 12 -x "socks5h://127.0.0.1:§port" ${TunnelConfigs.PROBE_URL} 2>/dev/null || true)
          kill "§pid" 2>/dev/null || true; wait "§pid" 2>/dev/null || true; pid=""
          code=§(echo "§r" | awk '{print §1}')
          if [ "§code" = 204 ]; then result "PATH_§tag" "ok:§(echo "§r" | awk '{printf "%d", §2 * 1000}')"
          else result "PATH_§tag" "fail"; fi
        done
        rm -rf "§work"
        # Reverse: the server abroad holds connections open to the phone port; report the round trip
        # of the best one (from the kernel), or fail when none is open.
        if §SUDO test -f $DIR/reverse.env; then
          RV_FROM=§(§SUDO sed -n 's/^RV_FROM=//p' $DIR/reverse.env)
          RV_PORT=§(§SUDO sed -n 's/^RV_PORT=//p' $DIR/reverse.env)
          ips=§(getent ahosts "§RV_FROM" 2>/dev/null | awk '{print §1}' | sort -u | tr '\n' ' ')
          [ -n "§ips" ] || ips="§RV_FROM"
          rtt=""
          if have ss; then
            rtt=§(ss -Htni state established "( sport = :§RV_PORT )" 2>/dev/null | awk -v ips=" §ips " '
              /^[ \t]/ { if (m && match(§0, /rtt:[0-9.]+/)) { v = substr(§0, RSTART + 4, RLENGTH - 4) + 0; if (best == "" || v < best) best = v }; next }
              { p = §4; sub(/:[0-9]+§/, "", p); gsub(/[][]/, "", p); sub(/^::ffff:/, "", p); m = index(ips, " " p " ") > 0 }
              END { if (best != "") printf "%d", (best < 1 ? 1 : best) }')
          fi
          if [ -n "§rtt" ]; then result "PATH_${TunnelTransport.REVERSE.tagPrefix}" "ok:§rtt"; else result "PATH_${TunnelTransport.REVERSE.tagPrefix}" "fail"; fi
        fi
        """,
        STATUS_STEPS
    )

    /** A new rotation seed: every rotating port moves now, without waiting for the next period. */
    fun reseed(seed: String): ToolScript {
        require(HEX.matches(seed) && seed.length >= 32) { "Invalid seed" }
        return ToolScripts.script(
            """
            step 1
            §SUDO sed -i 's/^SEED=.*/SEED=$seed/' $DIR/rotate.env
            OUT=§(§SUDO $ROTATE)
            result PORTS "§(echo "§OUT" | sed -n 's/^PORTS= *//p')"
            """,
            listOf("Move the ports")
        )
    }

    /** Removes the tunnel from one server; [entryPort] closes the phone's port on the Iran server. */
    fun uninstall(role: String, entryPort: Int?, hyPort: Int?): ToolScript {
        entryPort?.let { ToolScripts.port(it, "entry") }
        hyPort?.let { ToolScripts.port(it, "Hysteria2") }
        val close = buildList {
            if (role == TunnelSpec.ROLE_ABROAD) add("for p in §(cat $DIR/ports 2>/dev/null || true); do fw_close \"§p\" tcp; done")
            if (role == TunnelSpec.ROLE_ABROAD && hyPort != null) add("fw_close $hyPort udp")
            if (role == TunnelSpec.ROLE_IRAN && entryPort != null) add("fw_close $entryPort tcp")
        }.joinToString("\n")
        return ToolScripts.script(
            """
            step 1
            §SUDO systemctl disable --now maximus-tunnel maximus-tunnel-hy maximus-tunnel-rotate.timer >/dev/null 2>&1 || true
            @@CLOSE@@
            §SUDO rm -f /etc/systemd/system/maximus-tunnel.service /etc/systemd/system/maximus-tunnel-hy.service \
              /etc/systemd/system/maximus-tunnel-rotate.service /etc/systemd/system/maximus-tunnel-rotate.timer $ROTATE
            §SUDO rm -rf "$DIR" "$BIN"
            §SUDO systemctl daemon-reload
            result REMOVED tunnel
            """,
            listOf("Stop and remove"),
            mapOf("CLOSE" to close.ifBlank { ":" })
        )
    }

    fun restart(): ToolScript = ToolScripts.script(
        """
        step 1
        units=maximus-tunnel
        if systemctl is-enabled --quiet maximus-tunnel-hy 2>/dev/null; then units="§units maximus-tunnel-hy"; fi
        §SUDO systemctl restart §units
        wait_active §units
        result RESTARTED tunnel
        """,
        listOf("Restart")
    )

    fun logs(lines: Int = 40): ToolScript = ToolScripts.script(
        "step 1\n§SUDO journalctl -u maximus-tunnel -u maximus-tunnel-hy -u maximus-tunnel-rotate -n $lines --no-pager -o short-iso 2>&1 | tail -n $lines",
        listOf("Read logs")
    )
}
