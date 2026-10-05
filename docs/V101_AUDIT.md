# V1.0.1 dual-mode plan — Step 1: current-state audit

State of `feature/war-plan` at 43281c4 (2026-10-05), read from the code, before any V1.0.1 change.
Every claim names the file it was read from. "Verified working" means real traffic passed through it in an
automated test (the censorship simulator or a socket test); nothing has been verified on a physical phone yet.

## Capability states

| Protocol / feature | PARSE_SUPPORTED | ENGINE_SUPPORTED | VERIFIED_WORKING |
|---|---|---|---|
| VLESS + REALITY + Vision | yes (VlessParser, MihomoParser) | Xray (XrayConfigBuilder) | yes, censorsim |
| VLESS + WebSocket + TLS (CDN) | yes | Xray | yes, censorsim |
| VLESS Encryption | yes | Xray | yes, censorsim |
| Hysteria2 | yes (ProtocolLinks, MihomoParser) | Xray | yes, censorsim |
| WireGuard | yes (links, .conf, Clash) | Xray userspace WireGuard | yes, censorsim |
| VLESS plain TCP, no security | yes | Kotlin tunnel (TunnelManager) | loopback socket test only |
| VMess, Trojan, Shadowsocks | yes | Xray | no |
| XHTTP / SplitHTTP, gRPC, HTTPUpgrade | yes | Xray | no |
| AmneziaWG | yes (WireGuardConf) | partial: junk packets become Xray noise; S1–S4 / H1–H4 refused | no AmneziaWG server tested |
| SOCKS5 | yes | Kotlin tunnel, only without credentials | handshake unit test only |
| HTTP proxy | yes | in practice never: links with credentials are refused (RuntimeCapabilities:48) | no |
| Xray JSON | yes (also sing-box outbounds) | Xray, sanitized | no |
| Mihomo / Clash YAML | nodes extracted, run on Xray | whole-YAML profile refused; MihomoEngine is never used | no |
| TUIC | refused at import | no | — |
| Psiphon | no | no (PsiphonConduitBridge has an empty bridge list and disables itself) | — |
| Tor, WARP, NaiveProxy, DNS tunnels, Slipstream | no | no | — |

Runtimes: native libXray v26.9.9 in-process (`XrayEngineImpl`, pinned by SHA-256 in
`scripts/fetch-libxray-android.sh`) and the Kotlin packet tunnel (`TunnelManager`). Selection is in
`EngineSelectionPolicy`; `RuntimeCapabilities.unsupportedReason` refuses what neither runs.

**UI claims to correct:** Psiphon appears on the home screen and in the landing tour (HomeScreen.kt:313,
LandingTourDialog.kt:196) but does nothing. GOD MODE tiers 3–4 (bridges, mesh) are labels only.

## Operating modes today

`OperationalMode` (DAILY / GOD_MODE, AppModels.kt:247) is saved, changes the notification text, and calls
bridge and mesh switches that do nothing. It does not change routing, engine choice, DNS or failover.
`godModeMeshEnabled` and `godModePsiphonEnabled` are saved but never read.

## Kill switch and leak protection

What holds:
- A blocking interface (routes `0.0.0.0/0` and `::/0`, nothing reads it) is raised before every connect, on
  Always-on start, and before engines stop during reconnect and failover (RayVpnService.kt:172-178, 338, 896-903).
- When the engine dies while connected, traffic stays blocked (RayVpnService.kt:818-826).
- Probe and outbound sockets are protected; Xray's dialer goes through `VpnService.protect`.
- No local listeners: Xray gets only a `tun` inbound, there is no ServerSocket.
- IPv6: blackholed in Xray unless enabled; dropped by the Kotlin tunnel.

**Fail-open gaps (release blockers):**
1. **Resolve or validation failure releases the block.** RayVpnService.kt:387-400 sets
   `protectionRequested = false` when the server name cannot be resolved. This also runs on failover,
   network-restore reconnect and Always-on restart, which are exactly the moments the network is hostile.
   Traffic then goes direct.
2. **Process death.** libXray runs in the app process; a native crash leaves traffic unprotected until the
   service restarts unless Android's "Block connections without VPN" is on. The app does not detect or
   prompt for that setting.
3. **The in-app kill switch setting is dead code.** `RoutingEngine` receives `isTunnelConnected = (profile != null)`,
   which is always true (TcpVlessTunnel.kt:99, UdpRelay.kt:157).
4. **Watcher stops in RECONNECTING.** Engine-death detection only runs while CONNECTED (RayVpnService.kt:815).
5. **Raw Xray JSON** keeps the user's own routing, so a `freedom` outbound or direct rules bypass the tunnel.
6. **Bypass rules are keyword matches.** An entry like `com` or `*:443` sends most traffic direct.
7. **FakeDNS pools are in the LAN bypass list** (198.18.0.0/15, fc00::/7 in XrayConfigBuilder.kt:100-117);
   a stale fake address after a core restart could go direct. Inferred, not reproduced.
8. **CGNAT 100.64.0.0/10 goes direct even in Global mode** (RoutingEngine.kt:50-58).
9. **No per-app split tunneling** exists (no addAllowed/DisallowedApplication).

## DNS

- Inside the tunnel, DNS is never sent to the ISP: Xray uses FakeDNS plus one private resolver with
  fallback disabled; the Kotlin tunnel sends port 53 through the proxy (RoutingEngine.kt:32-36).
- Server-name bootstrap asks the system resolver first (plaintext, 1.5 s grace), then races six DoH
  endpoints by IP (EndpointResolver.kt:23-93). Gaps:
  - the server's host name always reaches the ISP's DNS in plaintext;
  - when every DoH endpoint fails, a blocked answer is still used (EndpointResolver.kt:85), so a plain
    VLESS/SOCKS/HTTP profile can send its credentials to the censor's address;
  - `isBlockedAnswer` misses 100.64/10, 198.18/15 and 240/4.
- The DoH presets `dns.google` and `dns.quad9.net` (SettingsScreen.kt:395-396) are refused by
  `applyPrivateDns`, which needs an IP: picking them breaks every Xray connect (fails closed).
  `setDnsServer` does not validate.

## Downloads and secrets

- Subscriptions: HTTPS only, private and metadata addresses refused, answers checked after DNS and the
  checked address is the one connected to, redirects re-checked (at most 5), 5 MB limit, 30 s deadline.
  Gaps: a system HTTP proxy skips the DNS check (no `Proxy.NO_PROXY`); `isRestrictedIp` misses 240/4,
  192.0.0/24, 64:ff9b::/96 and 2002::/16; the redirect check has no test.
- TLS: no trust-all code; `allowInsecure` requires a pinned certificate; cleartext traffic is disabled.
- Storage: profile secrets and subscription URLs are AES-256-GCM with an Android Keystore key. Gaps:
  encryption failure returns an empty string and the secret is lost silently (SecureStorage.kt:162-165);
  a failed AI-key decrypt uses the ciphertext as the key.
- Logs go through `SecretRedactor`. Gaps: base64 `vmess://` and `ss://` links, `socks5://user:pass@`,
  JSON keys `auth` / `secretKey` / `preSharedKey`, and tokens in URL paths are not redacted.
- BPB provisioning downloads `worker.js` from GitHub with no hash pin and deploys it with the user's
  Cloudflare token (PanelProvisioner.kt:443-460).
- A Gemini key in `.env` at build time is compiled into the APK as a fallback (app/build.gradle.kts:106-119).

## Failover and Smart Connect

War plan Phases 2 and 4: StealthPathFinder (disguised alternates), ServerRace (five real requests per
round, kinds spread, kind-ranked winner), NetworkMemory (per carrier; failed kinds kept 30 minutes,
which is a per-kind circuit breaker), WatchPolicy (dead connection given up in about 18 s) and
FailoverManager (25 s cooldown). Simulator numbers are in [WAR_PLAN.md](WAR_PLAN.md#phase-4--sub-10-second-auto-connect-this-branch).
GOD MODE's cascade only re-ranks the same servers.

## Discovery, Free Config Hub, Clean IP

- No Free Config Hub exists. `OfficialSubscriptions.URLS` is empty until the Telegram bot's Worker is
  deployed; subscriptions fall back to GitHub CDN mirrors (SubscriptionSources.kt).
- Clean IP: `panels/CleanIpOptimizer.kt` scans hard-coded Cloudflare /24 prefixes with TCP, TLS, WebSocket
  and download tests, used by the panel manager's Clean IP tab. `findBestCleanIpSync` is unused and
  reports invented jitter.

## Build, release and tests

- versionCode 26, versionName 1.0.0, minSdk 24, targetSdk 36. One workflow, `release.yml`, run by hand:
  unit tests, lint, universal and arm64 APKs, `check-apk.py`, apksigner, SHA256SUMS, emulator smoke test.
  No tests run on pushes or pull requests. APK names hard-code "V1.0.0".
- 39 unit test classes; none sends traffic through Xray (the simulator does).
- `THIRD_PARTY_NOTICES.md` omits JSch, SnakeYAML, OkHttp/Retrofit/Moshi, Coil and Room.
- `.github/patches` and `.github/v102-fixes` are unused.

## What Step 2 (security regression baseline) takes from this

Tests first, then fixes, for: fail-open on resolve failure (1), the dead kill-switch flag (3), the
watcher in RECONNECTING (4), raw-JSON direct outbounds (5), the blocked bootstrap answer, hostname DoH
presets, the SSRF proxy bypass and missing ranges, redactor gaps, silent secret loss and the AI-key
fallback. Process death (2) needs the Supervisor (Step 3) and an Always-on lockdown prompt.

## Step 2 progress: security baseline

Fixed, with `SecurityBaselineTest` guarding each:
- Gap 1: a server name that cannot be looked up no longer releases the block, in any mode
  (`vpn/safety/FailClosedPolicy`). Only a profile that can never run, picked by the user in DAILY mode,
  releases it. The status says "Traffic blocked … Disconnect to use the network without the VPN".
- A filtering-range DNS answer (now also 100.64/10, 198.18/15, 240/4) is refused for profiles without
  TLS or REALITY, so their login is never sent to the filter's address.
- Named DoH presets (`dns.google`, `dns.quad9.net`, `cloudflare-dns.com`) run by address
  (`vpn/safety/DnsResolvers`); the presets now store the address; a resolver that needs a lookup is refused
  in Settings.
- Raw Xray JSON: a first outbound that is not a proxy (`freedom`, `blackhole`) and WireGuard peers named
  by host are refused.
- Subscriptions ignore a system HTTP proxy and refuse 192.0.0/24, 240/4, and NAT64 / 6to4 addresses that
  wrap a private one.
- Logs hide base64 `vmess://` / `ss://` links, `socks5://` logins and `auth` / `secretKey` /
  `preSharedKey` / `uuid` JSON values.
- A stored AI key that fails to decrypt is no longer used as the key.

Left for the Supervisor (step 3): process death and the Always-on lockdown prompt (2), the dead in-app
kill-switch flag (3), engine-death detection while reconnecting (4). Left for OperatingModePolicy
(step 4): user direct rules in raw JSON and broad bypass entries (5, 6), CGNAT direct in Global (8).
Still open: silent secret loss when encryption fails, the unpinned BPB `worker.js`, the build-time Gemini
key fallback.
