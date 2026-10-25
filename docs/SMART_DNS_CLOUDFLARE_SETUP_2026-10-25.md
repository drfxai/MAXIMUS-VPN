# MAXIMUS VPN: simple DNS tunnel and Cloudflare setup
Checkpoint: 2026-10-25. Scope: MAXIMUS VPN + MAXIMUS Tunnel only. Matrix Server is out of scope.

## User journey
1. Add own Linux server through pinned SSH host-key confirmation (Iran or abroad); show preflight results and free ports.
2. Optional: open Cloudflare's **official token template**, approve the least-privilege DNS permissions on Cloudflare's own website, paste token back. No account credentials or globally privileged API key.
3. Choose an existing Cloudflare zone, or keep manual DNS provider mode.
4. Pick a server engine: DNSTT (existing installer), Soar (future pinned installer), or CottenDNS Normal/Max (future pinned installer).
5. Present a preview: exact A/AAAA/NS records, transport ports, firewall changes, conflicts and safe rollback.
6. With explicit confirmation, create only missing DNS records in the owned zone and install the server through verified SSH; no automatic overwrite.
7. Wait for delegation, authenticated server handshake, actual TCP request through the VPN, and report separate results. Export/import the compatible link.
8. Use one existing ConnectivityBrain and Android TUN owner; manual profiles and last-known-good connections remain unchanged.

## Current implementation status
- **Exists:** `soar://` and `cottendns://` v1 imports on main, editable manual resolver list, separate pinned Android engines.
- **Exists:** bounded LAB DNS resolver tests, network-specific freshness and some poisoned-answer checks.
- **Exists:** DNSTT server wizard with pinned binaries, DNS record previews and phone-side egress test.
- **Exists:** Smart domains token template, Keystore-protected DNS token, A/AAAA DNS-only/CDN records and Cloudflare API client.
- **PR #126:** opt-in Cloudflare A/AAAA + NS provisioning from DNSTT wizard; preflight and compensating rollback. DNS tunnel resolver evidence ranking.
- **NOT IMPLEMENTED by PR #126:** Soar server provisioning, CottenDNS server provisioning, automatic user-controlled resolver discovery for imported profiles, universal domain allocator, verified end-to-end Iranian cellular tests.

## Next workstreams (ordered)
1. **DNSTT hardening:** detect occupied public 53/UDP and conflicting DNS services before installation; rollback/reinstall should preserve existing key, service and rules; test both zone delegation and real tunneled TCP, IPv4/IPv6, restart, reverse proxy limitations and provider firewall. Never promise zero bugs.
2. **Soar installer:** audit pinned server revision/API/license; build verified amd64/arm64 artifacts, generate Ed25519 key on server, restrict egress ports and public destinations, manage systemd, isolate key and account policies, export `soar://` v1 compatible with MAXIMUS client.
3. **CottenDNS installer:** audit pinned release, MIT/Apache notices, legacy/Max compatibility and encryption methods; signed or checksum-pinned binaries; service conflicts with Soar/DNSTT on UDP/TCP 53 require explicit mode or reviewed DNS multiplexer.
4. **Resolver intelligence:** bounded and opt-in discovery of current-network DNS plus user-provided resolvers; validated results against controlled authoritative DNS and real tunnel egress; UDP/TCP/TXT/AAAA/EDNS/timeouts, hijack/rate-limit/loss/jitter ranking; strict no-scanning when user selected manual-only.
5. **Domain manager:** one audited component to allocate domain names for compatible products; separate protocol-aware DNS-only vs Cloudflare-proxied logic, conflict-free names, ownership inventory and per-zone least privilege. Never proxy UDP/53/SSH/REALITY/Hysteria2 through ordinary Cloudflare CDN.
6. **Offline survival:** preserve DNS configurations, recent verified resolver data and last-known-good server links; optional offline binary staging and user-provided alternate zones without requiring reachable Cloudflare APIs during a blackout.
7. **Measured performance:** track touch-to-first VERIFIED international traffic, p50/p95 setup time, verified uptime, TCP TTFB, actual bytes and packet loss by network key; no success claim from local SOCKS startup or ICMP alone.

## Security and functional gates
- Cloudflare token creation is approved on Cloudflare. The app must not silently mint high-privilege user tokens or log token/SSH secrets.
- A DNS resolver's successful reply never proves a functional DNS tunnel or working international egress.
- A newly configured user domain must be DNS delegated correctly; Cloudflare outgoing delegated subdomains are DNS-only and not CDN accelerated.
- Do not mutate existing DNS records with a different type/address; audit and user approval before destructive updates.
- No fail-open direct bypass for required protected traffic, preserve Android lockdown and TUN ownership.
- Always record evidence separately: unit, CI, emulator, physical device, network operator, protected egress and real speed.
