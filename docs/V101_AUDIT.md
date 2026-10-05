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

## Step 14 progress: signed aggregator

`tools/aggregator` builds the free list outside the app: it reads `sources/sources.json` (empty until
sources are chosen), fetches each over HTTPS with a size limit, refuses entries that are unencrypted,
disable certificate checks, point at private or reserved addresses or use an unknown scheme, drops
duplicates by a canonical fingerprint (the credential only as a hash), and writes `free.txt`,
`free-base64.txt` and a manifest with each file's SHA-256. With `HUB_SIGNING_KEY` from the repository's
secrets (ECDSA P-256) the manifest is signed; without it the manifest is written unsigned, for dry runs.
Eight tests in `tools/aggregator/tests`.

In the app, `HubManifest` checks the signature against a public key built in and then each file's hash,
so an unsigned or changed list is refused whatever address served it; the key is empty until the signing
key is created, so nothing is accepted yet. `HubSnapshots` keeps three lists (current, previous, and the
last whose nodes carried traffic), so a bad update never leaves the app without a list.

## Steps 15–26: the remaining transports

Already working on the bundled Xray core, verified with real traffic in the simulator: WireGuard
(step 15) and Hysteria2 (step 17).

AmneziaWG (step 16) is partly there: junk packets (Jc/Jmin/Jmax) are sent, but a server that changes
WireGuard's packet format (S1–S4, H1–H4) is refused with a message saying what to ask for. Supporting it
needs amneziawg-go as a second native engine.

Steps 18–26 (WARP, Psiphon, Aether, the DNS-rescue transports, Slipstream, NaiveProxy, Tor) each need a
native library the app does not ship. Every one is listed in `EngineRegistry` with its license, mapping
to no runtime, so none can be selected or shown as working. Before any of them is bundled it needs:

1. a license decision (Psiphon and Mihomo are GPL-3.0; amneziawg-go, NaiveProxy, Tor and dnstt are
   permissive) — asked of the owner;
2. a pinned download with a checked hash, as `scripts/fetch-libxray-android.sh` does;
3. real traffic through it in the simulator before it may be called working.

The APK also grows with each native engine; adding them all would multiply its size, so they are worth
adding one at a time, strongest first.

## Step 27 progress: GOD MODE orchestration

The cascade is now the ladder of things that exist, in order, each step tried only when the one before
found nothing:

1. the user's server on the path this network remembers;
2. the other saved servers, raced with real requests, disguised forms included;
3. the same server through a clean Cloudflare address;
4. nodes from the signed free list that carried traffic (once a list is published).

When nothing works the connection is reported as blocked and traffic stays blocked, while the watchdog
keeps retrying the user's own server, so a filter that lifts is picked up on its own. The old tiers 3 and
4 (Psiphon bridges, P2P mesh) were labels over code that disabled itself; the service no longer calls
them. The home screen still shows a ladder with bridge and mesh counts, which now read zero: that is a
layout change, so it waits for a preview.

## Step 31 progress: failure lab

`tools/censorsim` has a lab pass (`LAB=1 ./run.sh …`, results in
`results/2026-10-05-lab.jsonl`, a table in `report.py`). It connects each saved server under the current
filtering and then runs two cases where nothing can work: the only saved server is down, and every saved
server is down. There the app must report no path rather than start a connection that carries nothing,
since that is what keeps traffic blocked.

Results, real traffic through a real Xray core:

| Filtering | each saved server connects | time (median / p90) | nothing can work: no path claimed | time to give up |
|---|---|---|---|---|
| nothing filtered | 100% | 0.0 s / 0.1 s | 100% | 29.3 s / 44.5 s |
| server names + fingerprint + UDP inspection | 100% | 9.1 s / 9.2 s | 100% | 29.3 s / 44.6 s |

Under all three filters every profile still connected, each landing on REALITY, in about 9 seconds. No
case ever claimed a working path that carried nothing. Giving up takes 14 seconds with one dead server
and 45 with five, because every server, disguise and alternate is tried before the app says nothing
works; traffic stays blocked throughout, so this is slow rather than unsafe.

## Steps 30 and 33: checks on every push

Tests used to run only when a release was built by hand, so a change could sit on a branch untested.
`.github/workflows/checks.yml` now runs on every push and pull request:

- the Android unit tests and release lint, with the reports kept as artifacts;
- the aggregator's tests;
- `scripts/check-repo-hygiene.py`, which refuses a tree carrying a private key, a Telegram bot token, a
  Cloudflare or GitHub token, a Google API key or an AWS key, or an assistant's name.

Telemetry (step 30) is not built: it needs a collection endpoint and a privacy policy, neither of which
exists, and nothing may be sent off the phone without the owner's decision. What a user can already give
is the diagnostics report (now naming Android's lockdown state and how hostile the network looked), which
is redacted and shared only when they choose to.

## Step 32: tests that need a real phone

Everything above was proved against a real Xray core on a machine, which cannot show how Android itself
behaves: the kill switch, Always-on, lockdown, doze and a real carrier network are the phone's own
behaviour. These are the checks that decide whether V1.0.1 can be published, and only the owner of a
device can run them. Each one names what to look for.

1. **Kill switch under failure.** Connect, then make the server unreachable (airplane mode on the server
   side, or a wrong port). The app must keep reporting blocked traffic and no app may reach the network
   until Disconnect is pressed. Check with a browser and a second app, not only the app's own screen.
2. **Always-on and Block connections without VPN.** Turn both on in Android settings, reboot, and before
   touching the app open a browser: nothing may load. The app's screen must name the lockdown state.
3. **An invalid profile.** Import a broken config and press Connect: the error must appear and the
   network must work again afterwards in Daily mode, while GOD MODE keeps traffic blocked.
4. **A hostile network.** On a network that filters (mobile data in a filtered country, or a hotspot with
   DNS interception), Connect in GOD MODE and confirm it lands on a working path, and that the path it
   names matches what carried traffic.
5. **DNS leaks.** While connected, check a DNS leak page and confirm no resolver from the carrier appears,
   and that queries do not leave the tunnel.
6. **Doze.** Leave the phone connected and idle for an hour, then use it: the connection must either still
   carry traffic or have reconnected on its own, with traffic blocked in between.
7. **Battery and speed.** A download and a speed test in Daily mode, and the same in GOD MODE, so the cost
   of survival mode is known rather than guessed.

Until 1, 2, 3 and 5 pass on a device, no transport may be shown to users as working, and the V1.0.1 tag
stays unpublished: that is the Phase 73 blocker this project cannot clear by itself.

## Step 34: performance after the safety changes

The V1.0.1 changes add checks on every connect (fail-closed decisions, the engine breaker, private DNS
lookups in GOD MODE), so the 8-minute soak from the war plan was run again on this code: a request every
second while the censor changes every 90 seconds (none, server names, server names with UDP inspection,
all three filters, UDP blocked, none).

| Failover logic | requests through | outages | time without traffic | longest outage |
|---|---|---|---|---|
| before Phase 4 | 90% | 1 | 46 s | 45 s |
| V1.0.1 code | 95% | 1 | 23 s | 23 s |

These match the Phase 4 numbers exactly (95%, 23 s per run): the safety work cost nothing in survival.
The one outage is the forced cut when server names start being filtered. First-connect times are in the
step 31 table above (about 9 s under three filters). Battery and real throughput can only be measured on a
phone; they are item 7 of step 32. Raw results: `tools/censorsim/results/2026-10-05-v101-soak.jsonl`.

## Engines 15–26, built (2026-10-05)

The owner asked for Psiphon and Mihomo to be included and the other engines added by strength. GPL
engines cannot be linked into an app under a non-commercial license, so every new engine except Tor
runs as its own program (`lib<name>.so` in the APK, built in CI from a pinned Go module whose hash is
checked against the Go checksum database and the script). Xray still owns the TUN, DNS and the kill
switch; its one proxy becomes the engine's SOCKS port on 127.0.0.1, with a random login per connection
where the engine can check one. While an engine program runs, the app's own traffic is kept out of the
VPN so the program's sockets do not loop back into it; every other app still goes through the tunnel.

| Engine | Carries | How a user gets it | State |
|---|---|---|---|
| Mihomo v1.19.32 (GPL-3.0) | TUIC links, AmneziaWG servers with changed headers, Clash-only proxies (AnyTLS, Mieru, Snell, SSH, Hysteria v1) | imported links and files | TUIC carried TCP and UDP through Xray → Mihomo → TUIC server in the lab: **verified** |
| Cloudflare WARP (WireGuard on Xray) | free Cloudflare egress | GOD MODE, after the clean-address step | registration untested (Cloudflare's API is unreachable from the lab) |
| Psiphon tunnel-core (GPL-3.0) | Psiphon's own network | GOD MODE, after WARP | built; needs the network settings Psiphon Inc. issues to the app (the `PSIPHON_CONFIG` secret) |
| Tor 0.4.9 + lyrebird | Snowflake by default; pasted obfs4, WebTunnel or meek bridge lines | GOD MODE, last step; pasted bridge lines | built; Snowflake needs WebRTC, which the lab cannot reach |
| dnstt v1.20260501.0 (CC0) | a DNS tunnel to the user's own dnstt server | `dnstt://` links | built; needs a server |
| NaiveProxy | – | – | not started: it is a Chromium build with no Go source to pin |

Psiphon and Tor are only taken when nothing else carried traffic, and if they cannot connect the
traffic stays blocked. None of these is shown as working beyond what the lab verified.
