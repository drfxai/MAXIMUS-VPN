package com.example.panels.servers

/**
 * Offline script generator for a private Psiphon server. It does not fetch or trust an executable:
 * a caller must first verify an independently pinned psiphond binary's SHA-256.
 *
 * The caller must pin and verify a reviewed upstream artifact before approving a remote
 * install. Service activation alone does not prove end-to-end Android tunnel traffic.
 */
object PrivatePsiphonServerScripts {
    const val TOOL_ID = "private-psiphon"
    const val UNIT = "maximus-psiphon"
    const val INSTALL_DIR = "/opt/maximus-private-psiphon"
    private const val CONTROL_DIR = "/etc/maximus-private-psiphon"
    private const val SYSTEM_USER = "maximus-psiphon"

    data class Request(
        val publicIp: String,
        val port: Int,
        val stagedBinary: String,
        val sha256: String
    ) {
        init {
            require(validIpv4(publicIp)) { "A literal public IPv4 address is required for private Psiphon." }
            require(port in 1024..65535 && port != 22 && port != 53) { "Use a dedicated unprivileged TCP port." }
            require(stagedBinary.matches(Regex("^/tmp/maximus-psiphond-[a-f0-9]{32}[.]bin$")))
            require(sha256.matches(Regex("^[a-f0-9]{64}$"))) { "Pinned SHA-256 digest required." }
        }
    }

    fun validIpv4(address: String): Boolean {
        if (address.length !in 7..15) return false
        val octets = address.split('.')
        if (octets.size != 4 || !octets.all { s ->
                s.isNotBlank() && s.length <= 3 && s.all { it in '0'..'9' } &&
                    (s.length == 1 || s[0] != '0') && (s.toIntOrNull() ?: -1) in 0..255
            }) return false
        val (a, b, c, _) = octets.map(String::toInt)
        // Public endpoints may be behind cloud NAT and need not belong to an interface.
        return when {
            a == 0 || a == 10 || a == 127 || a >= 224 -> false
            a == 100 && b in 64..127 -> false
            a == 169 && b == 254 -> false
            a == 172 && b in 16..31 -> false
            a == 192 && (b == 168 || (b == 0 && c == 0) || (b == 0 && c == 2) ||
                (b == 88 && c == 99)) -> false
            a == 198 && (b in 18..19 || (b == 51 && c == 100)) -> false
            a == 203 && b == 0 && c == 113 -> false
            else -> true
        }
    }

    /** Root-only; fails without changing an existing installation or stealing occupied ports. */
    fun install(request: Request): ToolScript {
        val r = request
        val script = """
            set -eu
            umask 077
            if [ "§(id -u)" -ne 0 ]; then echo 'Private Psiphon install requires root SSH' >&2; exit 1; fi
            for exe in sha256sum ss systemctl id useradd chown; do
              command -v "§exe" >/dev/null 2>&1 || { echo "Missing mandatory §exe" >&2; exit 1; }
            done
            if [ -e '$INSTALL_DIR' ] || [ -e /etc/systemd/system/$UNIT.service ] || [ -e '$CONTROL_DIR' ] || [ -L '$CONTROL_DIR' ]; then
              echo 'Existing Psiphon installation: no overwrite permitted' >&2; exit 1
            fi
            [ -f '${r.stagedBinary}' ] || { echo 'Staged psiphond file missing' >&2; exit 1; }
            expected='${r.sha256}'
            listeners=§(ss -Hlnt) || { echo 'TCP socket inventory failed; no changes started' >&2; exit 78; }
            if printf '%s\n' "§listeners" | awk '{print §4}' | grep -Eq '[:.]${r.port}§'; then
              echo 'Chosen TCP port has a listener; no install started' >&2; exit 78
            fi
            mkdir -m 700 '$INSTALL_DIR'
            if ! mkdir -m 700 '$CONTROL_DIR'; then
              rmdir '$INSTALL_DIR'
              exit 1
            fi
            # Only the freshly created Maximus directory may be removed by this trap.
            cleanup_install() {
              code=§?
              trap - 0
              if [ "§code" -ne 0 ]; then
                if [ -f '$CONTROL_DIR/ufw-owned' ]; then
                  ufw --force delete allow ${r.port}/tcp comment 'maximus-private-psiphon' >/dev/null 2>&1 || :
                fi
                systemctl disable --now '$UNIT.service' >/dev/null 2>&1 || :
                rm -f '/etc/systemd/system/$UNIT.service'
                systemctl daemon-reload >/dev/null 2>&1 || :
                rm -rf -- '$INSTALL_DIR' '$CONTROL_DIR'
              fi
              exit "§code"
            }
            trap cleanup_install 0
            # A dedicated locked account owns only this MAXIMUS installation. The server
            # uses an unprivileged port and needs no ambient Linux capabilities.
            if ! id -u '$SYSTEM_USER' >/dev/null 2>&1; then
              useradd --system --user-group --no-create-home --home-dir '$INSTALL_DIR' --shell /usr/sbin/nologin '$SYSTEM_USER'
            fi
            mv '${r.stagedBinary}' '$INSTALL_DIR/psiphond'
            actual=§(sha256sum '$INSTALL_DIR/psiphond' | awk '{print §1}')
            [ "§actual" = "§expected" ] || { echo 'Pinned executable SHA-256 mismatch' >&2; exit 1; }
            chmod 500 '$INSTALL_DIR/psiphond'
            cd '$INSTALL_DIR'
            './psiphond' -ipaddress '${r.publicIp}' -protocol 'OSSH:${r.port}' generate >/dev/null
            [ -s server-entry.dat ] && [ -s psiphond.config ] || {
              echo 'Psiphon did not generate the required server config' >&2; exit 1
            }
            chmod 600 ./*.config server-entry.dat
            chown -R '$SYSTEM_USER:$SYSTEM_USER' '$INSTALL_DIR'
            chmod 700 '$INSTALL_DIR'
            # No remote shell output includes the private server entry; retrieved through the
            # authenticated SSH download interface and encrypted in Android Keystore.
            cat > '/etc/systemd/system/$UNIT.service' <<'MXUNIT'
            [Unit]
            Description=MAXIMUS Private Psiphon
            After=network-online.target
            Wants=network-online.target
            [Service]
            Type=simple
            User=$SYSTEM_USER
            Group=$SYSTEM_USER
            WorkingDirectory=$INSTALL_DIR
            ExecStart=$INSTALL_DIR/psiphond run
            Restart=on-failure
            RestartSec=5
            NoNewPrivileges=true
            ProtectSystem=strict
            ReadWritePaths=$INSTALL_DIR
            PrivateTmp=true
            ProtectHome=true
            ProtectKernelTunables=true
            ProtectKernelModules=true
            CapabilityBoundingSet=
            AmbientCapabilities=
            LockPersonality=true
            RestrictSUIDSGID=true
            [Install]
            WantedBy=multi-user.target
            MXUNIT
            chmod 644 '/etc/systemd/system/$UNIT.service'
            systemctl daemon-reload
            if command -v ufw >/dev/null 2>&1 && ufw status | grep -q '^Status: active'; then
              if ! ufw status | grep -Eq '^${r.port}/tcp[[:space:]]+ALLOW'; then
                ufw allow ${r.port}/tcp comment 'maximus-private-psiphon' >/dev/null
                touch '$CONTROL_DIR/ufw-owned'
              fi
            fi
            echo '${r.port}' > '$CONTROL_DIR/listen-port'
            systemctl enable --now '$UNIT.service' >/dev/null
            ready=0
            for attempt in 1 2 3 4 5 6 7 8 9 10; do
              pid=§(systemctl show '$UNIT.service' -p MainPID --value)
              if [ "§pid" != 0 ] && ss -Hlntp 'sport = :${r.port}' | grep -Fq "pid=§pid,"; then ready=1; break; fi
              sleep 1
            done
            [ "§ready" = 1 ] && systemctl is-active --quiet '$UNIT.service' || { echo 'psiphond did not own the selected TCP listener' >&2; exit 1; }
            trap - 0
            echo '##RESULT PSIPHON_UNIT_ACTIVE=1'
        """.trimIndent().replace('§', '$')
        return ToolScripts.guarded(ToolScript(script, listOf("Verify staged executable", "Inspect listener", "Generate private server keys",
            "Register systemd service", "Verify psiphond process")))
    }

    /**
     * Default removal stops the service but keeps private keys. Explicit purge can also
     * clean up a previously interrupted installation that has no systemd unit.
     */
    fun uninstall(purge: Boolean = false): ToolScript {
        val removal = if (purge) """
            [ ! -L "§dir" ] || { echo 'Unsafe Psiphon path' >&2; exit 1; }
            if [ -d "§dir" ]; then rm -rf -- "§dir"; fi
            echo '##RESULT PRIVATE_PSIPHON_PURGED=1'
        """.trimIndent() else "echo '##RESULT PRIVATE_PSIPHON_DISABLED=1'"
        return ToolScripts.guarded(ToolScript("""
            set -eu
            if [ "§(id -u)" -ne 0 ]; then echo 'Root SSH is required' >&2; exit 1; fi
            unit='/etc/systemd/system/$UNIT.service'
            dir='$INSTALL_DIR'
            control='$CONTROL_DIR'
            if [ ! -f "§unit" ] && [ ! -d "§dir" ] && [ ! -d "§control" ]; then
              echo 'No Maximus Psiphon unit or files were found' >&2; exit 78
            fi
            [ ! -L "§control" ] || { echo 'Unsafe firewall metadata path' >&2; exit 1; }
            if [ -f "§control/ufw-owned" ] && [ -f "§control/listen-port" ]; then
              port=§(cat "§control/listen-port")
              case "§port" in ''|*[!0-9]*) echo 'Invalid managed firewall metadata' >&2; exit 1;; esac
              ufw --force delete allow "§port/tcp" comment 'maximus-private-psiphon'
              rm -f "§control/ufw-owned"
            fi
            if [ -f "§unit" ]; then
              systemctl disable --now '$UNIT.service'
              rm -f "§unit"
              systemctl daemon-reload
            fi
            rm -rf -- "§control"
            ${removal}
        """.trimIndent().replace('§', '$'),
            listOf("Stop managed service", if (purge) "Purge Maximus-owned data" else "Preserve private keys")))
    }
}
