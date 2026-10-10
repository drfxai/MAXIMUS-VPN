package com.example.panels.servers

/**
 * What a read-only check found on a server: system, resources, open ports and the state of the
 * tools the Install Center manages. [SCRIPT] prints KEY=VALUE lines; [parse] reads them.
 */
data class ServerFacts(
    val osName: String = "",
    val osId: String = "",
    val osVersion: String = "",
    val arch: String = "",
    val kernel: String = "",
    val cpus: Int = 0,
    val ramMb: Long = 0,
    val ramAvailableMb: Long = 0,
    val swapMb: Long = 0,
    val diskMb: Long = 0,
    val diskFreeMb: Long = 0,
    val uptimeSec: Long = 0,
    val load1: Double = 0.0,
    val root: Boolean = false,
    val sudo: Boolean = false,
    val systemd: Boolean = false,
    val packageManager: String = "",
    val tcpPorts: Set<Int> = emptySet(),
    val udpPorts: Set<Int> = emptySet(),
    val congestionControl: String = "",
    val firewall: String = "",
    val fail2ban: Boolean = false,
    val autoUpdates: Boolean = false,
    /** null when the SSH server's setting could not be read. */
    val sshPasswordLogin: Boolean? = null,
    val sshdDropIns: Boolean = false,
    /** systemd unit -> "active", "inactive", "failed"... for the units Maximus manages. */
    val services: Map<String, String> = emptyMap()
) {
    /** The Go architecture name of the programs this server needs, or null when none is built for it. */
    val goArch: String? get() = when (arch.lowercase()) {
        "x86_64", "amd64" -> "amd64"
        "aarch64", "arm64" -> "arm64"
        else -> null
    }
    val canAdmin: Boolean get() = root || sudo
    val usesApt: Boolean get() = packageManager == "apt-get"
    val ramUsedPercent: Int get() = if (ramMb <= 0) 0 else (((ramMb - ramAvailableMb) * 100) / ramMb).toInt().coerceIn(0, 100)
    val diskUsedPercent: Int get() = if (diskMb <= 0) 0 else (((diskMb - diskFreeMb) * 100) / diskMb).toInt().coerceIn(0, 100)
    fun active(unit: String): Boolean = services[unit] == "active"

    /** Why the Install Center cannot manage this server, or null when it can. */
    fun blocker(): String? = when {
        !canAdmin -> "This account needs root or passwordless sudo."
        !systemd -> "This server does not run systemd."
        goArch == null -> "This server's processor (${arch.ifBlank { "unknown" }}) is not supported; amd64 and arm64 are."
        else -> null
    }

    companion object {
        const val BEGIN = "---MX-FACTS---"

        /** Read-only: changes nothing on the server. */
        val SCRIPT: String = """
            SUDO=""
            if [ "§(id -u)" -ne 0 ] && command -v sudo >/dev/null 2>&1 && sudo -n true 2>/dev/null; then SUDO="sudo -n"; fi
            echo '$BEGIN'
            if [ -r /etc/os-release ]; then . /etc/os-release; fi
            echo "OS_ID=§{ID:-}"; echo "OS_VERSION=§{VERSION_ID:-}"; echo "OS_NAME=§{PRETTY_NAME:-}"
            echo "ARCH=§(uname -m)"; echo "KERNEL=§(uname -r)"
            echo "CPUS=§(nproc 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null || echo 0)"
            awk '/^MemTotal:/{print "RAM_KB="§2} /^MemAvailable:/{print "RAM_AVAIL_KB="§2} /^SwapTotal:/{print "SWAP_KB="§2}' /proc/meminfo 2>/dev/null
            df -Pk / 2>/dev/null | awk 'NR==2{print "DISK_KB="§2; print "DISK_FREE_KB="§4}'
            awk '{print "UPTIME=" int(§1)}' /proc/uptime 2>/dev/null
            awk '{print "LOAD1=" §1}' /proc/loadavg 2>/dev/null
            echo "UID=§(id -u)"
            if [ -n "§SUDO" ]; then echo SUDO=1; else echo SUDO=0; fi
            if [ -d /run/systemd/system ]; then echo SYSTEMD=1; else echo SYSTEMD=0; fi
            for p in apt-get dnf yum apk; do if command -v §p >/dev/null 2>&1; then echo "PKG=§p"; break; fi; done
            if command -v ss >/dev/null 2>&1; then
              echo "TCP=§(ss -Hlnt 2>/dev/null | awk '{print §4}' | grep -vE '^(127[.]|[[]::1[]]|::1)' | sed 's/.*://' | sort -un | tr '\n' ',')"
              echo "UDP=§(ss -Hlnu 2>/dev/null | awk '{print §4}' | grep -vE '^(127[.]|[[]::1[]]|::1)' | sed 's/.*://' | sort -un | tr '\n' ',')"
            fi
            echo "CC=§(cat /proc/sys/net/ipv4/tcp_congestion_control 2>/dev/null)"
            if command -v ufw >/dev/null 2>&1; then
              if §SUDO ufw status 2>/dev/null | grep -q 'Status: active'; then echo FIREWALL=ufw-active; else echo FIREWALL=ufw-inactive; fi
            elif command -v firewall-cmd >/dev/null 2>&1 && §SUDO firewall-cmd --state >/dev/null 2>&1; then echo FIREWALL=firewalld-active
            else echo FIREWALL=none; fi
            if command -v fail2ban-client >/dev/null 2>&1; then echo F2B=1; else echo F2B=0; fi
            if grep -qs 'Unattended-Upgrade "1"' /etc/apt/apt.conf.d/20auto-upgrades; then echo AUTOUPD=1; else echo AUTOUPD=0; fi
            if command -v sshd >/dev/null 2>&1; then
              §SUDO sshd -T 2>/dev/null | awk '/^passwordauthentication /{print "SSH_PASSWORD=" §2}'
            fi
            if grep -qsE '^[[:space:]]*Include[[:space:]]+/etc/ssh/sshd_config\.d/' /etc/ssh/sshd_config; then echo SSHD_DROPINS=1; else echo SSHD_DROPINS=0; fi
            for u in x-ui maximus-dnstt maximus-socks maximus-hysteria fail2ban; do
              echo "SVC_§u=§(systemctl is-active §u 2>/dev/null || true)"
            done
        """.trimIndent().replace('§', '$')

        fun parse(output: String): ServerFacts {
            val v = output.substringAfter(BEGIN, output).lineSequence().mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }.toMap()
            fun long(k: String) = v[k]?.toLongOrNull() ?: 0L
            fun ports(k: String) = v[k].orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..65535 }.toSet()
            val uid = v["UID"]?.toIntOrNull()
            return ServerFacts(
                osName = v["OS_NAME"].orEmpty().trim('"'),
                osId = v["OS_ID"].orEmpty().trim('"'),
                osVersion = v["OS_VERSION"].orEmpty().trim('"'),
                arch = v["ARCH"].orEmpty(),
                kernel = v["KERNEL"].orEmpty(),
                cpus = v["CPUS"]?.toIntOrNull() ?: 0,
                ramMb = long("RAM_KB") / 1024,
                ramAvailableMb = long("RAM_AVAIL_KB") / 1024,
                swapMb = long("SWAP_KB") / 1024,
                diskMb = long("DISK_KB") / 1024,
                diskFreeMb = long("DISK_FREE_KB") / 1024,
                uptimeSec = long("UPTIME"),
                load1 = v["LOAD1"]?.toDoubleOrNull() ?: 0.0,
                root = uid == 0,
                sudo = v["SUDO"] == "1",
                systemd = v["SYSTEMD"] == "1",
                packageManager = v["PKG"].orEmpty(),
                tcpPorts = ports("TCP"),
                udpPorts = ports("UDP"),
                congestionControl = v["CC"].orEmpty(),
                firewall = v["FIREWALL"].orEmpty(),
                fail2ban = v["F2B"] == "1",
                autoUpdates = v["AUTOUPD"] == "1",
                sshPasswordLogin = when (v["SSH_PASSWORD"]?.lowercase()) { "yes" -> true; "no" -> false; else -> null },
                sshdDropIns = v["SSHD_DROPINS"] == "1",
                services = v.filterKeys { it.startsWith("SVC_") }
                    .mapKeys { it.key.removePrefix("SVC_") }
                    .mapValues { it.value.ifBlank { "inactive" } }
            )
        }
    }
}
