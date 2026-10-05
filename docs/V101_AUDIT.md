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

## Step 3 progress: MaximusVpnSupervisor

`vpn/safety/MaximusVpnSupervisor` now owns the "traffic must stay in the VPN" flag. Every connect,
reconnect, failover and Always-on start requests protection; it is released only for a named reason
(user disconnect, revoked by Android, service destroyed, or an unusable profile the user picked in DAILY
mode), and each release is logged.

At each connect it reads whether Android's "Block connections without VPN" is on (Android 10+). GOD MODE
logs a request to turn it on when it is not; the diagnostics report shows the state. This is the only
protection against gap 2 (process death) while libXray runs inside the app process; moving the engine to
its own process is a later, larger change.

Gap 4 is not a leak: while reconnecting, the tunnel interface stays up, so a stopped engine leaves traffic
captured and dropped, not direct. Gap 3: the in-app kill-switch flag has no effect because the blocking
interface is always used; the setting is left as is until the mode screens are redesigned (UI changes
need a preview first).

## Step 4 progress: OperatingModePolicy

`vpn/safety/OperatingModePolicy` is now what the two modes change at runtime:

| | DAILY | GOD MODE |
|---|---|---|
| Routing | the user's choice (LAN bypass, custom list) | everything through the proxy |
| Direct rules in imported Xray configs | kept | dropped |
| IPv6 | the user's setting | blocked |
| Smart Connect | when the user asks | every connect |
| Failover and reconnect | the user's setting | always on |
| Unusable profile picked by the user | releases the block | keeps it |
| Android lockdown off | logged | asked for |

Switching mode while connected reconnects the same server under the new mode. The FakeDNS pool
(198.18.0.0/15) is no longer in the direct lists. The mode subtitles, the landing tour and the home
screen's failover line no longer name Psiphon or a P2P mesh, which do not run.

## Step 5 progress: engine registry

`vpn/engine/registry`: `EngineRegistry` lists each engine with its pinned version, license and whether it
is bundled (Xray and the Kotlin tunnel are; Mihomo is listed, GPL-3.0, not bundled). `CapabilityState`
(NONE, PARSE_SUPPORTED, ENGINE_SUPPORTED, VERIFIED_WORKING) and `EngineRegistry.CAPABILITIES` hold the
protocol matrix above in code; `EngineRegistryTest` checks it against what the engines accept and keeps
"working" to the five protocols the simulator verified. `EngineCircuitBreaker` counts start failures per
engine (three in a row: skipped for five minutes); with one engine per protocol today it only reports.

## Step 6 progress: network environment detector

`vpn/smart/NetworkEnvironment` rates the network outside the VPN at every connect as open, filtered,
heavy or blackout, from Android's own check (validated, metered, roaming), whether the server's name met
a blocked DNS answer, and which kinds of connection recently failed or worked on that carrier or Wi-Fi
(NetworkMemory). It is logged with each connect and shown in the diagnostics report; in DAILY mode a
heavy or blackout network logs a suggestion to use GOD MODE.

## Step 7 progress: Xray

libXray stays pinned at v26.9.9 (archive SHA-256 checked before extracting). `scripts/fetch-libxray-android.sh`
no longer trusts an AAR that is already in `app/libs`: it is reused only when a stamp written at
extraction names the same version and archive hash and the AAR still matches the hash recorded then;
otherwise it is fetched and checked again. The engine registry records the version and licenses.

## Step 8: Mihomo

Mihomo is GPL-3.0. Bundling it (and Psiphon, step 19) is a license decision put to the owner; until then
Clash / Mihomo YAML keeps importing its servers onto Xray, and `EngineRegistry.MIHOMO` stays unbundled.

## Step 9 progress: DNS

GOD MODE looks up server names only over DNS-over-HTTPS at resolver IPs (`EndpointResolver.resolve`
with `private = true`), so the name never reaches the ISP's DNS in plaintext; if every resolver fails the
connect fails closed. DAILY keeps the faster system-first lookup. Together with step 2 (named DoH presets
run by address, blocked answers refused for plaintext profiles) and the existing in-tunnel rules (FakeDNS
plus one private resolver with fallback disabled; port 53 always through the proxy), this is the DNS
policy for both modes.

## Steps 10–11: Smart Connect and failover

Reused from war plan Phase 4 (ServerRace, NetworkMemory, WatchPolicy, FailoverManager), now driven by
the mode: GOD MODE races the saved servers on every connect and keeps failover on (step 4).

## Step 12 progress: Free Config Hub

There was no hub before; `vpn/hub` is its backend, with no UI yet (a screen needs a preview first).
- `FreeConfigProvider` / `ProviderRegistry`: provider definitions outside UI code. The official source
  appears once `OfficialSubscriptions.URLS` has the bot's address; no public lists are built in until
  the signed aggregator (step 14) exists.
- `ConfigValidationPipeline`: size limit, parsing, sanitizing (names shortened, ids per provider),
  security validation, de-duplication by canonical fingerprint (the credential only as a hash) and
  quarantine. Configs without encryption, without certificate checks, pointing at private or reserved
  addresses, or that no engine runs are quarantined with a reason.
- Node states: `SecurityState`, `HealthState`, `PerformanceState`; `ConfigHealthScorer`.
- `ConfigSyncManager`: fetch, pipeline, then a real request through each accepted node; only nodes that
  carried traffic are approved.

## Step 13 progress: Clean IP

`CleanIpOptimizer.findBestCleanIpSync` now measures: three TCP pings per address, a real median, jitter
and loss, and an address must answer at least twice (it used to report an invented jitter of 8 ms and
three successes from a single ping). It had no callers; it is now the last resort in the connect path:
when a Cloudflare-fronted server (WebSocket, XHTTP, gRPC or HTTP/2 with TLS or REALITY) carries no
traffic on any path, the connect scans for a clean Cloudflare address, for at most 12 seconds, and uses
the same server through it when a real request succeeds. The existing staged scan (TCP, TLS, WebSocket,
download) and its scoring stay as they are, in the panel manager's Clean IP tab.
