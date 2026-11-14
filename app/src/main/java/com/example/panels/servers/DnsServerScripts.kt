package com.example.panels.servers

/** Fixed-version stand-alone DNS tunnel servers; no remote installer scripts are executed. */
object DnsServerScripts {
    const val SOAR_VERSION = "v0.2.4"
    const val COTTEN_VERSION = "v2026.10.24.183015-7db7b73"
    private val COTTEN_SHA = mapOf(
        "amd64" to "276412ca9aeb1d56b7e7306947ca7abaf9c668901245fb2b256163d477ad13cb",
        "arm64" to "19d7dad466835b1dff6f73ed44c2588b7fba137b7d5d8c731e7e0c05fbb96c80"
    )
    /** MasterDnsVPN by Amin Mahmoudi (masterking32), MIT. Release archives pinned by SHA-256. */
    const val MASTERDNS_VERSION = "v2026.10.26.100415-5f83ee9"
    internal val MASTERDNS_SHA = mapOf(
        "amd64" to "3f5c1ae974dcecb1b0536c0e60ac2711da42eb066d3b8470c641e498825aa0d8",
        "arm64" to "d6dbfe018a447f15a81f6cd4911488bc39cf0002b2314b0b12b368464ce50b07"
    )
    const val MASTERDNS_DIR = "/etc/maximus/masterdns"
    /** MasterDNS AES-256-GCM; the phone's MasterDNS client accepts methods 3 to 5. */
    const val MASTERDNS_METHOD = 5
    val MASTERDNS_STEPS = listOf("Check system", "Verify MasterDNS SHA-256", "Create key and config", "Install systemd", "Open UDP 53", "Verify server")
    val SOAR_STEPS = listOf("Check toolchain", "Build pinned Soar", "Create Ed25519 key", "Install systemd", "Open UDP 53", "Verify server")
    val COTTEN_STEPS = listOf("Check system", "Verify CottenDNS SHA-256", "Configure server", "Install systemd", "Open DNS ports", "Verify server")

    /** Select an assigned IPv4 address; never disable the system resolver. */
    internal val LISTENER_PREFLIGHT = """
        select_dns_bind() {
          command -v ip >/dev/null 2>&1 || { echo 'iproute2 is required to select the DNS listener address' >&2; exit 23; }
          addresses=§(ip -o -4 addr show scope global | awk '{split(§4,a,"/"); print a[1]}')
          ssh_local=§(printf '%s\n' "§{SSH_CONNECTION:-}" | awk '{print §3}')
          DNS_BIND=§(printf '%s\n' "§addresses" | grep -Fx "§ssh_local" || true)
          if [ -z "§DNS_BIND" ]; then
            [ "§(printf '%s\n' "§addresses" | awk 'NF {n++} END {print n+0}')" = 1 ] || {
              echo 'Cannot choose one assigned IPv4 address; reconnect SSH using the intended server address.' >&2; exit 23;
            }
            DNS_BIND=§addresses
          fi
          printf '%s\n' "§DNS_BIND" | awk -F. 'NF!=4 {exit 1} {for(i=1;i<=4;i++) if(§i !~ /^[0-9]+§/ || §i>255) exit 1; if(§1==127 || §1==0) exit 1}' || exit 23
          echo "DNS listener address: §DNS_BIND (system DNS unchanged)"
        }
        require_free_listener() {
          proto=§1
          port=§2
          bind=§{3:-§DNS_BIND}
          command -v ss >/dev/null 2>&1 || { echo 'Cannot inspect listeners: install iproute2 before retrying' >&2; exit 23; }
          if [ "§proto" = udp ]; then flags=-Hlnu; else flags=-Hlnt; fi
          listeners=§(ss "§flags" 2>/dev/null) || { echo 'Cannot inspect listeners; no installation changes made' >&2; exit 23; }
          occupied=§(printf '%s\n' "§listeners" | awk -v port="§port" -v bind="§bind" '{
            endpoint=§4; n=split(endpoint,a,":"); if(a[n]!=port) next;
            sub(/:[^:]*§/, "", endpoint); sub(/%[^%]*§/, "", endpoint);
            if(bind=="*" || endpoint==bind || endpoint=="0.0.0.0" || endpoint=="*" || endpoint=="[::]" || endpoint=="::" || endpoint=="[::ffff:" bind "]") print §4
          }')
          if [ -n "§occupied" ]; then
            echo "Cannot bind §proto §bind:§port: conflicting listener(s): §occupied. Remove or move that tool first; system DNS is not stopped." >&2
            exit 23
          fi
        }
        require_no_dns_redirect() {
          for command in iptables nft; do
            command -v "§command" >/dev/null 2>&1 || continue
            if [ "§command" = iptables ]; then
              rules=§(§SUDO iptables -t nat -S) || { echo 'Cannot inspect DNS NAT rules' >&2; exit 23; }
              conflict=§(printf '%s\n' "§rules" | grep -E -- '--dport(s)? (53([ ,:]|§)|[0-9]+:53([ ,]|§))' | grep -E -- '-j (REDIRECT|DNAT)' || true)
            else
              rules=§(§SUDO nft list ruleset) || { echo 'Cannot inspect nftables rules' >&2; exit 23; }
              conflict=§(printf '%s\n' "§rules" | grep -E 'dport (53|\{[^}]*53[^}]*\})' | grep -E 'redirect|dnat' || true)
            fi
            [ -z "§conflict" ] || { echo 'Existing DNS port forwarding detected. Remove the previous DNS tunnel before installing another engine.' >&2; exit 23; }
          done
        }
    """.trimIndent()

    fun soar(domain: String): ToolScript {
        require(ToolScripts.DOMAIN.matches(domain))
        val dir = "/etc/maximus/soar"
        val unit = ToolScripts.unit("MAXIMUS Soar DNS tunnel",
            listOf("ExecStart=/usr/local/bin/maximus-soar-server -zone $domain -key-file $dir/key -listen DNS_BIND_ADDRESS:53 -allow-ports 80,443"),
            extra = listOf("AmbientCapabilities=CAP_NET_BIND_SERVICE", "CapabilityBoundingSet=CAP_NET_BIND_SERVICE", "ReadOnlyPaths=$dir"))
        return ToolScripts.script("""
            @@PREFLIGHT@@
            step 1
            select_dns_bind
            require_no_dns_redirect
            require_free_listener udp 53
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
            require_no_dns_redirect
            require_free_listener udp 53
            mutated=1
            §SUDO install -d -m 0750 -o root -g maximus $dir
            §SUDO install -m 0640 -o root -g maximus "§stage/key" $dir/key
            §SUDO install -m 0755 "§stage/soar-server" /usr/local/bin/maximus-soar-server
            write_file /etc/systemd/system/maximus-soar.service 0644 <<'UNIT'
            @@UNIT@@
            UNIT
            §SUDO sed -i "s/DNS_BIND_ADDRESS/§DNS_BIND/g" /etc/systemd/system/maximus-soar.service
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
        """, SOAR_STEPS, mapOf("UNIT" to unit, "PREFLIGHT" to LISTENER_PREFLIGHT))
    }

    /** [ns] is the name server host the tunnel is delegated to; it must match the NS record. */
    fun cotten(domain: String, arch: String, ns: String = "ns." + domain.substringAfter('.')): ToolScript {
        require(ToolScripts.DOMAIN.matches(domain))
        require(ToolScripts.DOMAIN.matches(ns)) { "Invalid name server" }
        val a = ToolScripts.arch(arch)
        val sha = COTTEN_SHA.getValue(a)
        val prefix = "CottenDns_Server_Linux_" + a.uppercase()
        val dir = "/etc/maximus/cotten"
        val unit = ToolScripts.unit("MAXIMUS CottenDNS Normal and Max",
            listOf("WorkingDirectory=$dir", "ExecStart=/usr/local/bin/maximus-cotten-server -config $dir/server_config.toml -metrics-address 127.0.0.1:9092"),
            extra = listOf("AmbientCapabilities=CAP_NET_BIND_SERVICE", "CapabilityBoundingSet=CAP_NET_BIND_SERVICE", "ReadWritePaths=$dir"))
        return ToolScripts.script("""
            @@PREFLIGHT@@
            step 1
            select_dns_bind
            require_no_dns_redirect
            require_free_listener udp 53
            require_free_listener tcp 53
            require_free_listener tcp 9092 127.0.0.1
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
            grep -q '^UDP_HOST[[:space:]]*=' "§stage/config" || { echo 'Pinned config has no UDP_HOST' >&2; exit 30; }
            sed -i "s/^UDP_HOST[[:space:]]*=.*/UDP_HOST = \"§DNS_BIND\"/" "§stage/config"
            sed -i 's/^DOMAIN[[:space:]]*=.*/DOMAIN = ["$domain"]/' "§stage/config"
            sed -i 's/^ZONE_NS[[:space:]]*=.*/ZONE_NS = ["$ns"]/' "§stage/config"
            sed -i 's/^TCP_IPV6_ENABLED[[:space:]]*=.*/TCP_IPV6_ENABLED = false/' "§stage/config"
            sed -i '/^ENCRYPTION_AUTO_DETECT[[:space:]]*=/d' "§stage/config"
            printf '\nENCRYPTION_AUTO_DETECT = false\n' >> "§stage/config"
            sed -i 's/^DATA_ENCRYPTION_METHOD[[:space:]]*=.*/DATA_ENCRYPTION_METHOD = 5/' "§stage/config"
            "§stage/server" --config "§stage/config" --check-config || exit 30
            step 4
            require_no_dns_redirect
            require_free_listener udp 53
            require_free_listener tcp 53
            require_free_listener tcp 9092 127.0.0.1
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
            printf '%s' "§key" | grep -Eq '^[a-fA-F0-9]{32}§' || { echo 'CottenDNS did not produce the pinned method-5 key (32 hexadecimal characters)' >&2; exit 40; }
            result COTTEN_KEY "§key"
        """.replace("__PREFIX__", prefix + "_v[A-Za-z0-9._-]+"), COTTEN_STEPS, mapOf("UNIT" to unit, "PREFLIGHT" to LISTENER_PREFLIGHT))
    }

    fun masterDnsArchive(arch: String): String = "MasterDnsVPN_Server_Linux_${ToolScripts.arch(arch).uppercase()}.zip"

    fun masterDnsUrl(arch: String): String =
        "https://github.com/masterking32/MasterDnsVPN/releases/download/$MASTERDNS_VERSION/${masterDnsArchive(arch)}"

    /**
     * MasterDnsVPN server for [domain] from the pinned release. The key is made on the server with
     * /dev/urandom before the first start (the server would otherwise create one itself and print
     * it to the journal), logging stays at WARN so the key never reaches the journal, and the
     * listener binds the server's own IPv4 address so a local resolver on 127.0.0.53 keeps running.
     */
    fun masterDns(domain: String, arch: String): ToolScript {
        require(ToolScripts.DOMAIN.matches(domain)) { "Invalid tunnel domain" }
        val a = ToolScripts.arch(arch)
        val sha = MASTERDNS_SHA.getValue(a)
        val prefix = "MasterDnsVPN_Server_Linux_" + a.uppercase()
        val dir = MASTERDNS_DIR
        val unit = ToolScripts.unit("MAXIMUS MasterDnsVPN server",
            listOf("WorkingDirectory=$dir", "ExecStart=/usr/local/bin/maximus-masterdns-server -nowait -config $dir/server_config.toml"),
            extra = listOf("AmbientCapabilities=CAP_NET_BIND_SERVICE", "CapabilityBoundingSet=CAP_NET_BIND_SERVICE", "ReadOnlyPaths=$dir",
                "StandardInput=null",
                // Tunnel streams may only leave for the internet, never the server's own or private services.
                "IPAddressDeny=localhost link-local multicast 10.0.0.0/8 172.16.0.0/12 192.168.0.0/16 100.64.0.0/10 fc00::/7"))
        return ToolScripts.script("""
            @@PREFLIGHT@@
            step 1
            select_dns_bind
            require_no_dns_redirect
            require_free_listener udp 53
            have systemctl || exit 21
            ensure_user
            have curl || pkg_install curl ca-certificates
            have unzip || pkg_install unzip
            have sha256sum || exit 21
            have od || exit 21
            have timeout || exit 21
            [ ! -f /etc/systemd/system/maximus-masterdns.service ] || { echo 'Already installed; do not replace a running server' >&2; exit 22; }
            [ ! -e $dir ] || { echo 'Previous MasterDNS data found; refusing to overwrite it' >&2; exit 22; }
            stage=§(mktemp -d)
            mutated=0
            rollback() {
              rc=§?
              if [ "§rc" -ne 0 ] && [ "§mutated" = 1 ]; then
                §SUDO systemctl disable --now maximus-masterdns >/dev/null 2>&1 || true
                §SUDO rm -f /etc/systemd/system/maximus-masterdns.service /usr/local/bin/maximus-masterdns-server
                §SUDO rm -rf $dir
                §SUDO systemctl daemon-reload >/dev/null 2>&1 || true
              fi
              rm -rf "§stage"
            }
            trap rollback EXIT
            step 2
            url=${masterDnsUrl(a)}
            curl -fLsS --proto '=https' --proto-redir '=https' --retry 3 --connect-timeout 20 --max-time 180 -o "§stage/server.zip" "§url" || exit 31
            printf '%s  %s\n' '$sha' "§stage/server.zip" | sha256sum -c - >/dev/null || { echo 'Archive checksum mismatch' >&2; exit 30; }
            files=§(unzip -Z1 "§stage/server.zip") || exit 30
            bin=§(printf '%s\n' "§files" | grep -Ex '${prefix}_v[A-Za-z0-9._-]+' | head -n 1)
            [ -n "§bin" ] || exit 30
            printf '%s\n' "§files" | grep -qx server_config.toml || exit 30
            unzip -p "§stage/server.zip" "§bin" > "§stage/server"
            unzip -p "§stage/server.zip" server_config.toml > "§stage/config"
            chmod 0700 "§stage/server"
            "§stage/server" -version 2>/dev/null | grep -qF '$MASTERDNS_VERSION' || { echo 'Unexpected MasterDNS server version' >&2; exit 30; }
            step 3
            for k in DOMAIN UDP_HOST UDP_PORT DATA_ENCRYPTION_METHOD ENCRYPTION_KEY_FILE LOG_LEVEL PROTOCOL_TYPE; do
              grep -q "^§k[[:space:]]*=" "§stage/config" || { echo "Pinned config has no §k" >&2; exit 30; }
            done
            sed -i 's/^DOMAIN[[:space:]]*=.*/DOMAIN = ["$domain"]/' "§stage/config"
            sed -i "s/^UDP_HOST[[:space:]]*=.*/UDP_HOST = \"§DNS_BIND\"/" "§stage/config"
            sed -i 's/^UDP_PORT[[:space:]]*=.*/UDP_PORT = 53/' "§stage/config"
            sed -i 's/^PROTOCOL_TYPE[[:space:]]*=.*/PROTOCOL_TYPE = "SOCKS5"/' "§stage/config"
            sed -i 's/^DATA_ENCRYPTION_METHOD[[:space:]]*=.*/DATA_ENCRYPTION_METHOD = $MASTERDNS_METHOD/' "§stage/config"
            sed -i 's|^ENCRYPTION_KEY_FILE[[:space:]]*=.*|ENCRYPTION_KEY_FILE = "$dir/encrypt_key.txt"|' "§stage/config"
            sed -i 's/^LOG_LEVEL[[:space:]]*=.*/LOG_LEVEL = "WARN"/' "§stage/config"
            key=§(od -An -tx1 -N16 /dev/urandom | tr -d ' \n')
            printf '%s' "§key" | grep -Eq '^[a-f0-9]{32}§' || { echo 'Could not create the MasterDNS key' >&2; exit 40; }
            ( umask 077; printf '%s' "§key" > "§stage/encrypt_key.txt" )
            # Dry start on a loopback port with a copy of the config: proves the config and key load
            # without touching port 53. A server still running after 3 seconds started cleanly.
            sed "s|^ENCRYPTION_KEY_FILE[[:space:]]*=.*|ENCRYPTION_KEY_FILE = \"§stage/encrypt_key.txt\"|" "§stage/config" > "§stage/probe.toml"
            probed=0
            for p in 45353 46353 47353; do
              rc=0
              timeout 3 "§stage/server" -nowait -config "§stage/probe.toml" -udp-host 127.0.0.1 -udp-port "§p" </dev/null >/dev/null 2>&1 || rc=§?
              if [ "§rc" = 124 ]; then probed=1; break; fi
            done
            [ "§probed" = 1 ] || { echo 'MasterDNS rejected the generated configuration' >&2; exit 30; }
            step 4
            require_no_dns_redirect
            require_free_listener udp 53
            mutated=1
            §SUDO install -d -m 0750 -o root -g maximus $dir
            §SUDO install -m 0755 "§stage/server" /usr/local/bin/maximus-masterdns-server
            §SUDO install -m 0640 -o root -g maximus "§stage/config" $dir/server_config.toml
            §SUDO install -m 0640 -o root -g maximus "§stage/encrypt_key.txt" $dir/encrypt_key.txt
            write_file /etc/systemd/system/maximus-masterdns.service 0644 <<'UNIT'
            @@UNIT@@
            UNIT
            §SUDO systemctl daemon-reload
            step 5
            fw_allow 53 udp
            step 6
            §SUDO systemctl enable maximus-masterdns >/dev/null
            §SUDO systemctl restart maximus-masterdns
            wait_active maximus-masterdns
            stored=§(§SUDO cat $dir/encrypt_key.txt 2>/dev/null | tr -d ' \r\n')
            [ "§stored" = "§key" ] || { echo 'MasterDNS key on the server changed during start' >&2; exit 40; }
            result MASTERDNS_KEY "§key"
        """, MASTERDNS_STEPS, mapOf("UNIT" to unit, "PREFLIGHT" to LISTENER_PREFLIGHT))
    }
}
