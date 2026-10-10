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
| Maximus Tunnel | Iran and abroad | Opens the tunnel setup (see below) |

The wizard runs: pick server, checks (sign-in, admin rights, systemd, CPU, free disk, ports),
settings, summary, live steps, result. A first install that fails is rolled back on the server.
Each installed tool can be tested from the phone with a real request, restarted, read (logs),
moved to a new port or given new keys where that makes sense, and removed.

## Server programs

dnstt-server, Hysteria2, Xray and a small loopback-only SOCKS5 server (`tools/server-tools/socks`) are
built reproducibly by `scripts/server-tools/build.sh` (Go 1.26.8; Xray v26.9.9 with Go 1.27.2, `-trimpath`, empty build id)
and published by `.github/workflows/server-tools.yml` to the prerelease `server-tools-v1`.
The app pins the SHA-256 of every file (`tools/server-tools/SHA256SUMS`), and the install
scripts refuse a download whose hash differs. Nothing is piped into a shell.

Services run as the unprivileged user `maximus` with systemd hardening. dnstt listens on a random
internal UDP port and port 53 is redirected to it with an iptables rule that the service adds
and removes. The SOCKS server refuses private, loopback, link-local, CGNAT and metadata
addresses.

## Maximus Tunnel

The Tunnel tab links one server in Iran to one server abroad:
phone → Iran server → server abroad → internet. It needs both servers in My Servers, signed in.

- The phone connects to the Iran server with VLESS + REALITY + Vision on one TCP port.
- The Iran server reaches the server abroad three ways at once: REALITY, XHTTP over REALITY and
  Hysteria2. Xray probes each one every 30 seconds and uses the fastest that answers
  (`leastPing`), so a blocked path is skipped without the phone noticing.
- REALITY and XHTTP ports move on a schedule (off, 6, 12 or 24 hours). Both servers work out the
  next port themselves from a shared seed with a systemd timer, so the phone is not needed; the
  previous period's port keeps working for one more period. "Move now" picks a new seed.
- REALITY private keys and the Hysteria2 certificate are made on the servers; only public keys
  reach the app. The Iran side checks the Hysteria2 certificate with its own copy, never with
  insecure mode.
- The server abroad refuses private and local addresses; the Iran server only forwards abroad and
  never sends traffic to the internet directly.
- If the Iran server can't reach GitHub, the app downloads the pinned program on the phone (or
  copies it from the server abroad), checks its SHA-256 and uploads it over SSH.
- If the server abroad has a cloud firewall (Hetzner, AWS, ...), allow TCP 20000–59999 and the
  Hysteria2 UDP port there; the app opens them in the server's own firewall.

Code: `panels/servers/tunnel/` (configs, scripts, rotating ports, setup) and
`ui/panels/servers/TunnelScreens.kt`.

## Not yet tested

None of this has been run against a real server from a phone yet. The scripts were run on a
Linux test machine with the real binaries, and a dnstt tunnel carried traffic end to end there.

The Maximus Tunnel has not been tested on real servers or a phone in Iran yet. Both installs,
port rotation, path switching, the status check and removal were run on a Linux test machine with
the real Xray and Hysteria2 programs, and traffic passed phone → Iran → abroad there.
