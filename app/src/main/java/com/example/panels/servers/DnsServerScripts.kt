package com.example.panels.servers

/** Fixed-version stand-alone DNS tunnel servers; no remote installer scripts are executed. */
object DnsServerScripts {
    const val SOAR_VERSION = "v0.2.4"
    const val COTTEN_VERSION = "v2026.10.24.183015-7db7b73"
    private val COTTEN_SHA = mapOf(
        "amd64" to "276412ca9aeb1d56b7e7306947ca7abaf9c668901245fb2b256163d477ad13cb",
        "arm64" to "19d7dad466835b1dff6f73ed44c2588b7fba137b7d5d8c731e7e0c05fbb96c80"
    )
    val SOAR_STEPS = listOf("Check toolchain", "Build pinned Soar", "Create Ed25519 key", "Install systemd", "Open UDP 53", "Verify server")
    val COTTEN_STEPS = listOf("Check system", "Verify CottenDNS SHA-256", "Configure server", "Install systemd", "Open DNS ports", "Verify server")

    fun soar(domain: String): ToolScript {
        require(ToolScripts.DOMAIN.matches(domain))
        val dir = "/etc/maximus/soar"
        val unit = ToolScripts.unit("MAXIMUS Soar DNS tunnel",
            listOf("ExecStart=/usr/local/bin/maximus-soar-server -zone $domain -key-file $dir/key -listen :53 -allow-ports 80,443"),
            extra = listOf("AmbientCapabilities=CAP_NET_BIND_SERVICE", "CapabilityBoundingSet=CAP_NET_BIND_SERVICE", "ReadOnlyPaths=$dir"))
        return ToolScripts.script("""
            step 1
            have systemctl || exit 21
            have go || pkg_install golang-go
            gov=§(go version | sed -n 's/.*go\([0-9][0-9]*\)\.\([0-9][0-9]*\).*/\1 \2/p')
            [ -n "§gov" ] || exit 21
            set -- §gov
            if [ "§1" -lt 1 ] || { [ "§1" -eq 1 ] && [ "§2" -lt 21 ]; }; then
              echo 'Go 1.21+ required to securely fetch Go 1.25 toolchain' >&2; exit 21
            fi
            ensure_user
            [ ! -f /etc/systemd/system/maximus-soar.service ] || { echo 'Already installed; do not replace a running server' >&2; exit 22; }
            [ ! -e $dir ] || { echo 'Previous Soar data found; refusing to overwrite it' >&2; exit 22; }
            stage=§(mktemp -d)
            mutated=0
            rollback() {
              rc=§?
              if [ "§rc" -ne 0 ] && [ "§mutated" = 1 ]; then
                §SUDO systemctl disable --now maximus-soar >/dev/null 2>&1 || true
                §SUDO rm -f /etc/systemd/system/maximus-soar.service /usr/local/bin/maximus-soar-server
                §SUDO rm -rf $dir
                §SUDO systemctl daemon-reload >/dev/null 2>&1 || true
              fi
              rm -rf "§stage"
            }
            trap rollback EXIT
            step 2
            GOBIN="§stage" GOTOOLCHAIN=auto GOPROXY=https://proxy.golang.org GOSUMDB=sum.golang.org \
              go install github.com/getlantern/soar/cmd/soar-server@v0.2.4 || exit 31
            [ -x "§stage/soar-server" ] || exit 31
            step 3
            "§stage/soar-server" keygen | awk '§1=="private"{print §2}' > "§stage/key"
            [ "§(wc -c < "§stage/key")" -ge 40 ] || exit 40
            step 4
            mutated=1
            §SUDO install -d -m 0750 -o root -g maximus $dir
            §SUDO install -m 0640 -o root -g maximus "§stage/key" $dir/key
            §SUDO install -m 0755 "§stage/soar-server" /usr/local/bin/maximus-soar-server
            write_file /etc/systemd/system/maximus-soar.service 0644 <<'UNIT'
            @@UNIT@@
            UNIT
            §SUDO systemctl daemon-reload
            step 5
            fw_allow 53 udp
            step 6
            §SUDO systemctl enable maximus-soar >/dev/null
            §SUDO systemctl restart maximus-soar
            wait_active maximus-soar
            pub=§(§SUDO /usr/local/bin/maximus-soar-server pubkey $dir/key)
            [ -n "§pub" ] || exit 40
            result PUBKEY "§pub"
        """, SOAR_STEPS, mapOf("UNIT" to unit))
    }

    fun cotten(domain: String, arch: String): ToolScript {
        require(ToolScripts.DOMAIN.matches(domain))
        val a = ToolScripts.arch(arch)
        val sha = COTTEN_SHA.getValue(a)
        val prefix = "CottenDns_Server_Linux_" + a.uppercase()
        val dir = "/etc/maximus/cotten"
        val ns = "ns." + domain.substringAfter('.')
        val unit = ToolScripts.unit("MAXIMUS CottenDNS Normal and Max",
            listOf("WorkingDirectory=$dir", "ExecStart=/usr/local/bin/maximus-cotten-server -config $dir/server_config.toml -metrics-address 127.0.0.1:9092"),
            extra = listOf("AmbientCapabilities=CAP_NET_BIND_SERVICE", "CapabilityBoundingSet=CAP_NET_BIND_SERVICE", "ReadWritePaths=$dir"))
        return ToolScripts.script("""
            step 1
            have systemctl || exit 21
            ensure_user
            have curl || pkg_install curl ca-certificates
            have unzip || pkg_install unzip
            have sha256sum || exit 21
            [ ! -f /etc/systemd/system/maximus-cotten.service ] || { echo 'Already installed; do not replace a running server' >&2; exit 22; }
            [ ! -e $dir ] || { echo 'Previous Cotten data found; refusing to overwrite it' >&2; exit 22; }
            stage=§(mktemp -d)
            mutated=0
            rollback() {
              rc=§?
              if [ "§rc" -ne 0 ] && [ "§mutated" = 1 ]; then
                §SUDO systemctl disable --now maximus-cotten >/dev/null 2>&1 || true
                §SUDO rm -f /etc/systemd/system/maximus-cotten.service /usr/local/bin/maximus-cotten-server
                §SUDO rm -rf $dir
                §SUDO systemctl daemon-reload >/dev/null 2>&1 || true
              fi
              rm -rf "§stage"
            }
            trap rollback EXIT
            step 2
            url=https://github.com/WhiteDNS/CottenDNS/releases/download/v2026.10.24.183015-7db7b73/$prefix.zip
            curl -fLsS --proto '=https' --proto-redir '=https' --retry 3 --connect-timeout 20 --max-time 180 -o "§stage/server.zip" "§url" || exit 31
            printf '%s  %s\n' '$sha' "§stage/server.zip" | sha256sum -c - >/dev/null || { echo 'Archive checksum mismatch' >&2; exit 30; }
            files=§(unzip -Z1 "§stage/server.zip") || exit 30
            bin=§(printf '%s\n' "§files" | grep -E '^__PREFIX__§' | head -n 1)
            [ -n "§bin" ] || exit 30
            printf '%s\n' "§files" | grep -qx server_config.toml || exit 30
            unzip -p "§stage/server.zip" "§bin" > "§stage/server"
            unzip -p "§stage/server.zip" server_config.toml > "§stage/config"
            chmod 0700 "§stage/server"
            "§stage/server" --capabilities | grep -q cottenmax || exit 30
            step 3
            sed -i 's/^DOMAIN[[:space:]]*=.*/DOMAIN = ["$domain"]/' "§stage/config"
            sed -i 's/^ZONE_NS[[:space:]]*=.*/ZONE_NS = ["$ns"]/' "§stage/config"
            sed -i 's/^TCP_IPV6_ENABLED[[:space:]]*=.*/TCP_IPV6_ENABLED = false/' "§stage/config"
            sed -i 's/^DATA_ENCRYPTION_METHOD[[:space:]]*=.*/DATA_ENCRYPTION_METHOD = 5/' "§stage/config"
            "§stage/server" --config "§stage/config" --check-config || exit 30
            step 4
            mutated=1
            §SUDO install -d -m 0770 -o root -g maximus $dir
            §SUDO install -m 0755 "§stage/server" /usr/local/bin/maximus-cotten-server
            §SUDO install -m 0640 -o root -g maximus "§stage/config" $dir/server_config.toml
            write_file /etc/systemd/system/maximus-cotten.service 0644 <<'UNIT'
            @@UNIT@@
            UNIT
            §SUDO systemctl daemon-reload
            step 5
            fw_allow 53 udp
            fw_allow 53 tcp
            step 6
            §SUDO systemctl enable maximus-cotten >/dev/null
            §SUDO systemctl restart maximus-cotten
            wait_active maximus-cotten
            for i in 1 2 3 4 5 6 7 8 9 10; do
              §SUDO test -s $dir/encrypt_key.txt && break
              sleep 1
            done
            key=§(§SUDO cat $dir/encrypt_key.txt 2>/dev/null | tr -d ' \r\n')
            printf '%s' "§key" | grep -Eq '^[a-fA-F0-9]{64}§' || exit 40
            result COTTEN_KEY "§key"
        """.replace("__PREFIX__", prefix + "_v[A-Za-z0-9._-]+"), COTTEN_STEPS, mapOf("UNIT" to unit))
    }
}
