# Panels V2: My Servers and the Install Center

The Panels screen has five tabs: **Servers**, **Install**, **Cloudflare**, **Clean IP** and
**Tunnel**.

## My Servers

- Servers are grouped by where they are. Servers in Iran carry the Iranian flag, servers abroad
  the German flag. The flags are drawn by the app, so they look the same on every phone.
- Each card shows whether the SSH port answers from this phone (with the connect time), the
  system, and the tools installed on it.
- **Add server** asks for the location, address, SSH port, user and password. The app first reads
  the server's SSH key and shows its fingerprint; it is pinned once you confirm it, and any later
  key change is refused.
- Sign-in details are kept on the phone only, encrypted with the Android keystore
  (`managed_servers_v1`). They are never sent to the AI or written to logs.
- 3X-UI servers added before V2 are moved into My Servers once, as servers abroad that need a
  sign-in (passwords were never stored for them).

## Install Center

| Tool | Where | What it does |
| --- | --- | --- |
| 3X-UI | Iran and abroad | The existing panel installer (pinned HTTPS, firewall, API check) |
| DNS tunnel (dnstt) | Abroad, needs a domain | DNS tunnel server; the wizard shows the two DNS records and checks them over DoH |
| Hysteria2 | Abroad | UDP proxy with Salamander obfuscation and a pinned self-signed certificate |
| Key login | Both | Creates an ECDSA key on the phone, adds it, proves a key-only sign-in |
| Password login off | Both | Only after key login is proved; validated with `sshd -t` |
| Firewall, Fail2ban, Security updates | Both (apt) | UFW, SSH jail, unattended security upgrades |
| TCP BBR, Swap | Both | Speed and stability for small servers |
| Maximus Tunnel | Iran and abroad | Shown as "Later"; it comes after this release |

The wizard runs: pick server, checks (sign-in, admin rights, systemd, CPU, free disk, ports),
settings, summary, live steps, result. A first install that fails is rolled back on the server.
Each installed tool can be tested from the phone with a real request, restarted, read (logs),
moved to a new port or given new keys where that makes sense, and removed.

## Server programs

dnstt-server, Hysteria2 and a small loopback-only SOCKS5 server (`tools/server-tools/socks`) are
built reproducibly by `scripts/server-tools/build.sh` (Go 1.26.8, `-trimpath`, empty build id)
and published by `.github/workflows/server-tools.yml` to the prerelease `server-tools-v1`.
The app pins the SHA-256 of every file (`tools/server-tools/SHA256SUMS`), and the install
scripts refuse a download whose hash differs. Nothing is piped into a shell.

Services run as the unprivileged user `maximus` with systemd hardening. dnstt listens on a random
internal UDP port and port 53 is redirected to it with an iptables rule that the service adds
and removes. The SOCKS server refuses private, loopback, link-local, CGNAT and metadata
addresses.

## Not yet tested

None of this has been run against a real server from a phone yet. The scripts were run on a
Linux test machine with the real binaries, and a dnstt tunnel carried traffic end to end there.
