package com.example.panels.servers

/**
 * Offline script generator for a private Psiphon server. It does not fetch or trust an executable:
 * a caller must first verify an independently pinned psiphond binary's SHA-256.
 *
 * This script is intentionally NOT reachable from the UI until an artifact trust manifest and
 * an Android end-to-end Psiphon test are integrated. The user must explicitly approve each VPS.
 */
object PrivatePsiphonServerScripts {
    const val TOOL_ID = "private-psiphon"
    const val UNIT = "maximus-psiphon"
    const val INSTALL_DIR = "/opt/maximus-private-psiphon"

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
        return octets.size == 4 && octets.all { s ->
            s.isNotBlank() && s.length <= 3 && s.all(Char::isDigit) &&
                (s.length == 1 || s[0] != '0') && (s.toIntOrNull() ?: -1) in 0..255
        }
    }

    /** Root-only; fails without changing an existing installation or stealing occupied ports. */
    fun install(request: Request): ToolScript {
        val r = request
        val script = """
            set -eu
            umask 077
            if [ "§(id -u)" -ne 0 ]; then echo 'Private Psiphon install requires root SSH' >&2; exit 1; fi
            for exe in sha256sum ss systemctl; do
              command -v "§exe" >/dev/null 2>&1 || { echo "Missing mandatory §exe" >&2; exit 1; }
            done
            if [ -e '$INSTALL_DIR' ] || [ -e /etc/systemd/system/$UNIT.service ]; then
              echo 'Existing Psiphon installation: no overwrite permitted' >&2; exit 1
            fi
            [ -f '${r.stagedBinary}' ] || { echo 'Staged psiphond file missing' >&2; exit 1; }
            expected='${r.sha256}'
            actual=§(sha256sum '${r.stagedBinary}' | awk '{print §1}')
            [ "§actual" = "§expected" ] || { echo 'Pinned executable SHA-256 mismatch' >&2; exit 1; }
            # Check again while holding the Maximus cooperative installer lock.
            if ss -Hlnt | awk '{print §4}' | grep -Eq '[:.]${r.port}§'; then
              echo 'Chosen TCP port has a listener; no install started' >&2; exit 1
            fi
            mkdir -m 700 '$INSTALL_DIR'
            mv '${r.stagedBinary}' '$INSTALL_DIR/psiphond'
            chmod 500 '$INSTALL_DIR/psiphond'
            cd '$INSTALL_DIR'
            './psiphond' -ipaddress '${r.publicIp}' -protocol 'OSSH:${r.port}' generate >/dev/null
            [ -s server-entry.dat ] && [ -s psiphond.config ] || {
              echo 'Psiphon did not generate the required server config' >&2; exit 1
            }
            chmod 600 ./*.config server-entry.dat
            # No remote shell output includes the private server entry; retrieved through the
            # authenticated SSH download interface and encrypted in Android Keystore.
            cat > '/etc/systemd/system/$UNIT.service' <<'MXUNIT'
            [Unit]
            Description=MAXIMUS Private Psiphon
            After=network-online.target
            Wants=network-online.target
            [Service]
            Type=simple
            User=root
            WorkingDirectory=$INSTALL_DIR
            ExecStart=$INSTALL_DIR/psiphond run
            Restart=on-failure
            RestartSec=5
            NoNewPrivileges=true
            ProtectSystem=strict
            ReadWritePaths=$INSTALL_DIR
            PrivateTmp=true
            [Install]
            WantedBy=multi-user.target
            MXUNIT
            chmod 644 '/etc/systemd/system/$UNIT.service'
            systemctl daemon-reload
            systemctl enable --now '$UNIT.service' >/dev/null
            systemctl is-active --quiet '$UNIT.service' || { echo 'psiphond did not stay running' >&2; exit 1; }
            echo '##RESULT PSIPHON_UNIT_ACTIVE=1'
        """.trimIndent().replace('§', '$')
        return ToolScript(script, listOf("Verify staged executable", "Inspect listener", "Generate private server keys",
            "Register systemd service", "Verify psiphond process"))
    }

    /** No broad glob, firewall deletion, external package changes or unrelated directories. */
    fun uninstall(): ToolScript = ToolScript("""
        set -eu
        if [ "§(id -u)" -ne 0 ]; then exit 1; fi
        [ -f '/etc/systemd/system/$UNIT.service' ] || { echo 'Not a Maximus-owned unit' >&2; exit 1; }
        systemctl disable --now '$UNIT.service'
        rm -f '/etc/systemd/system/$UNIT.service'
        systemctl daemon-reload
        # Important: retain server entry and keys until user explicitly approves secure data removal.
        echo '##RESULT PRIVATE_PSIPHON_DISABLED=1'
    """.trimIndent().replace('§', '$'), listOf("Stop managed service", "Disable automatic restart", "Keep private keys for review"))
}
