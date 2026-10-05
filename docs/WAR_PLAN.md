# MAXIMUS Anti-Censorship War Plan

**Goal:** connect users under the harshest filtering (Iran-style), and beat every competing client.
**Branch:** `feature/war-plan`. **Order:** 0 → 1+2 → 3 → 4 → first shippable milestone → 5 → 6 → 7.

A phase is done only when it moves the KPIs below in the censorship simulator, with numbers in this file.

---

## Phase 0 — Battlefield and KPIs

### Competitor matrix

"Death point" is the situation under severe disruption where the app leaves its user offline.

| App | Core / engines | What it does well | Death point under severe disruption |
|---|---|---|---|
| **WhiteVPN** (WhiteDNS) | Mihomo through FlClash's Android JNI path. GPL-3.0. | AmneziaWG, WireGuard, Hysteria2, connection chaining, a built-in encrypted subscription, Persian/RTL UI. | The built-in subscription lives on a single `*.workers.dev` endpoint. Once that endpoint and the public subscriptions are blocked, there is no other way to get configs, and the user is offline. |
| **WhiteAesther** (WhiteDNS) | Aether engine: MASQUE over HTTP/3 and HTTP/2 to Cloudflare WARP endpoints. AGPL-3.0. | It needs no server of its own. It scans endpoints and only accepts a route after real traffic returns. It falls back from QUIC to HTTP/2 when UDP is blocked, and it has padding profiles. | Everything rides on Cloudflare WARP. If the WARP endpoint ranges, MASQUE or WARP registration are blocked, every route dies together. The plan also lists Psiphon and Tor, but the README does not mention them. |
| **v2rayNG** | Xray (and v2fly). GPL-3.0. | The widest protocol support on Xray, plus a manual fragment setting. | One engine, and the user has to find, paste and pick working configs by hand. There is no automatic stealth fallback. Once the user's links are blocked, nothing else is tried. |
| **Hiddify** | sing-box. | VLESS/VMess/REALITY/TUIC/Hysteria/WireGuard/SSH, delay-based auto-select, automatic subscription updates and TLS tricks (fragment, padding). | It depends on subscriptions. When the subscription domains die, it has no discovery. Its TLS tricks are switches the user must find and turn on. |
| **MAXIMUS (main before this branch)** | Xray, a Kotlin tunnel, and a "Mihomo" adapter with **no native core**. | It sets up the user's own 3X-UI and BPB servers, has REALITY Quick Config, and runs Hysteria2 and WireGuard on Xray. | Clash/Mihomo YAML proxies and the Settings "Mihomo" engine sent traffic to the adapter, which carries nothing. VLESS Encryption links (what Quick Config creates for no-TLS inbounds) were refused at connect. A blocked handshake was only noticed after at least 36 s, and then the app switched to another node, never to another disguise. |

Sources: [WhiteVPN](https://github.com/WhiteDNS/WhiteVPN), [WhiteAestherMobile](https://github.com/WhiteDNS/WhiteAestherMobile), [v2rayNG](https://github.com/2dust/v2rayNG), [Hiddify](https://github.com/hiddify/hiddify-app), plus this repository's code. The Hiddify fragment/padding claims and the v2rayNG fragment claim come from general knowledge of those apps and were not re-checked here. License note: none of these apps' code may be copied into MAXIMUS (it has no LICENSE file). Ideas only.

### KPIs

| # | KPI | Definition | Target |
|---|---|---|---|
| 1 | **Connection success under disruption** | Share of connect attempts that end with a real HTTP request through the tunnel, per censorship scenario | 100% for every scenario that has *any* working path |
| 2 | **Time to connect** | From tapping connect to the first successful request through the chosen path (median / p90) | < 10 s, including under disruption |
| 3 | **Connection survival** | Outages (2+ failed requests in a row) and time without traffic on a held connection while the censor changes (`soak.sh`) | Fewer outages and less time without traffic every phase |

### How it is measured: the censorship simulator (`tools/censorsim`)

The simulator runs a real Xray 26.9.9 server (the same core as the app's libXray v26.9.9) with REALITY+Vision, VLESS+WS+TLS (CDN-style), VLESS Encryption, Hysteria2 and WireGuard on one host. A censor proxy sits in front of it. The app's own import, config builder and path-finder code runs on a JVM, with a real Xray client standing in for libXray's `pingBatch`. Scenarios:

| Scenario | What the censor does |
|---|---|
| `none` | Nothing (baseline) |
| `sni` | Resets any TCP connection whose first packet carries a blocked TLS server name. Like most national DPI, it inspects packets and does not reassemble the stream. |
| `fe` | Resets "fully encrypted" first packets: high entropy with no TLS/HTTP look, using the GFW's 2023 exemption rules but blocking every time instead of 26% of the time. |
| `udp-block` | Drops all UDP |
| `udp-dpi` | Blocks any UDP flow whose first datagram is a QUIC long header or a WireGuard handshake initiation |
| `throttle` | Delays every packet by 150 ms and drops 5% of UDP |
| `sni,fe,udp-dpi` | All filters at once ("Iran-like") |

**DNS kill** (system DNS dropped or answering with a block-page address) is covered by unit tests. With the network's DNS silent, `EndpointResolverTest`/`WarPlanEnginesAndStealthTest` show the server name resolving over DoH within the 1.5 s grace period. `main` waits for the OS resolver to give up first, then asks DoH resolvers one by one with 5 s each.

Run it: `tools/censorsim/run.sh <xray binary> <work dir> <non-loopback host ip> [trials]`. It needs Python 3, OpenSSL, JDK 21 and Gradle. Build Xray from `github.com/xtls/xray-core` at the commit libXray v26.9.9 uses.

**Limits:** a simulator is not Iran. Real DPI can reassemble, sample, throttle selectively and block by IP. ECH can't be exercised locally (it needs a Cloudflare site with ECH and reachable 1.1.1.1), and every result needs confirmation on a real phone on a real filtered network.

---

## Phase 1 — Multi-engine (this branch)

What the plan assumed vs. what the code did:

| Claim | Reality before this branch | Now |
|---|---|---|
| "Hysteria2 parsed but not executed" | Already ran on Xray since PR #11 (SECURITY.md was stale) | Unchanged; SECURITY.md corrected |
| "Mihomo parsed but not executed" | True. YAML proxies were tagged `MIHOMO` and routed to an adapter with no native core, so they carried no traffic. The Settings "Mihomo" engine did the same to *every* profile. | All YAML proxies run on Xray. Engine choice is `EngineSelectionPolicy.select` (Xray or Kotlin tunnel only). The bundle profile that could never run is gone. |
| (not in plan) | VLESS Encryption links, including Quick Config's own no-TLS configs, were refused at connect ("requires a native Xray core") | Run on Xray |
| "TUIC parsed but not executed" | True | Refused **at import** with the reason (Xray has no TUIC client), instead of a dead entry in the list |
| WireGuard / AmneziaWG | WireGuard links only. No `.conf` files, and YAML `wireguard` proxies were dropped. | `.conf` files and YAML proxies import. AmneziaWG junk packets (Jc/Jmin/Jmax) become an Xray UDP noise mask. AmneziaWG servers that change the packet format (S1/S2, H1–H4) are refused at import with the reason. |
| (not in plan) | Add Server paste only understood VLESS/SS/Trojan/HY2/WG single links | Every link type, and several lines at once |

**Not done, and why:** a true second native engine. Mihomo and sing-box are GPL-3.0, so bundling either would make the whole app GPL. The licence-compatible route to full AmneziaWG (custom headers) is amneziawg-go (MIT) as a second native library, built in CI with the Android NDK. That needs a decision (APK size, build time), so it is listed under next steps.

## Phase 2 — Adaptive stealth (this branch)

- **Stealth alternates per profile** (`vpn/stealth/StealthVariants.kt`). Every profile that runs on Xray gets at least two alternates. They only change what the client sends, so the user's server needs no change:
  - TLS and REALITY: the ClientHello split into small TCP pieces, a Firefox/Chrome fingerprint swap, both together, and ECH fetched over DoH (TLS with a domain name only).
  - Other TCP (VLESS Encryption, SS, VMess): the first packets split into random pieces.
  - Hysteria2 and WireGuard: one junk datagram, or a burst of junk datagrams, before the handshake.
- **Path finder before connect** (`vpn/stealth/StealthPathFinder.kt`), with real requests every time:
  1. The saved profile, or the alternate that won last time. A working path costs one request.
  2. Otherwise, in one parallel round: up to 3 alternates plus other kinds of connection to the same server (REALITY → CDN → TLS → QUIC → WireGuard). The user's own profile wins over a switch when both work. Two WireGuard paths with the same key are never probed together, because the server follows a key's newest address, so parallel probes knock each other out. The simulator caught this.
  3. If nothing works, or the probe can't run, the app connects as before and the existing failover takes over.
- **Resilient DNS:** once the network's DNS answers with a block page, fails, or stays silent for 1.5 s, six DoH resolvers from four operators (Cloudflare, Google, Quad9, AdGuard, all addressed by IP) are asked in parallel, and the first public answer wins.

### Results (simulator, 5 trials per profile per scenario, 5 profiles)

Measured 2026-10-05 against `main` at 965e23e. "main" connects with the saved profile only. Main's failover watchdog would switch to *another node* after at least 36 s (three failed 12 s checks); that time is not counted here. "branch" is this branch's path finder.

**Summary across the six disrupted scenarios (150 connect attempts each way):**

| KPI | main | this branch |
|---|---|---|
| 1. Connected | **45%** (68/150) | **100%** (150/150) |
| 2. Connected in under 10 s | 45% | **97%** (146/150) |
| 3. Drops per hour | not measured | measured from Phase 4 on (see below) |

Per scenario (time is wall time to the first successful request, including Xray process start-up in the simulator):

| Scenario | main: connected | branch: connected | branch: time to connect (median / p90) | under 10 s (branch) |
|---|---|---|---|---|
| none | 80% | 100% | 0.0 s / 0.1 s | 100% |
| sni | 36% | 100% | 0.1 s / 8.2 s | 100% |
| fe | 80% | 100% | 0.0 s / 0.2 s | 100% |
| udp-block | 40% | 100% | 0.1 s / 9.1 s | 100% |
| udp-dpi | 40% | 100% | 0.1 s / 9.1 s | 100% |
| throttle | 76% | 100% | 0.9 s / 1.7 s | 100% |
| sni,fe,udp-dpi | 0% | 100% | 9.1 s / 14.3 s | 84% |

Per profile, main → branch, and the path that won most often:

| Scenario | CDN (WS+TLS) | Hysteria2 | REALITY | VLESS Encryption | WireGuard |
|---|---|---|---|---|---|
| none | 100% → 100% (as saved) | 100% → 100% (as saved) | 100% → 100% (as saved) | 0% → 100% (as saved) | 100% → 100% (as saved) |
| sni | 0% → 100% (TLS handshake split into small pieces) | 100% → 100% (as saved) | 0% → 100% (TLS handshake split into small pieces) | 0% → 100% (as saved) | 80% → 100% (as saved) |
| fe | 100% → 100% (as saved) | 100% → 100% (as saved) | 100% → 100% (as saved) | 0% → 100% (REALITY on the same server (REALITY)) | 100% → 100% (as saved) |
| udp-block | 100% → 100% (as saved) | 0% → 100% (REALITY on the same server (REALITY)) | 100% → 100% (as saved) | 0% → 100% (as saved) | 0% → 100% (REALITY on the same server (REALITY)) |
| udp-dpi | 100% → 100% (as saved) | 0% → 100% (Junk packet before the handshake) | 100% → 100% (as saved) | 0% → 100% (as saved) | 0% → 100% (Junk packet before the handshake) |
| throttle | 100% → 100% (as saved) | 100% → 100% (as saved) | 100% → 100% (as saved) | 0% → 100% (as saved) | 80% → 100% (as saved) |
| sni,fe,udp-dpi | 0% → 100% (TLS handshake split into small pieces) | 0% → 100% (Junk packet before the handshake) | 0% → 100% (TLS handshake split into small pieces) | 0% → 100% (QUIC on the same server (Hysteria2), junk packet before the handshake) | 0% → 100% (Junk packet before the handshake) |

What the numbers say:
- **SNI filtering** kills REALITY and CDN configs on main. The split handshake gets them through, which is the same trick as Hiddify's fragment, but here it is found automatically.
- **UDP blocked:** Hysteria2 and WireGuard die on main. The branch moves to REALITY on the same server within one round, in about 9 s.
- **First-packet UDP DPI:** a junk datagram before the handshake gets QUIC and WireGuard through. This is the AmneziaWG idea, and it works against any server.
- **"Fully encrypted" DPI** catches VLESS Encryption. Splitting the first packet does not fool an entropy test, so the branch switches to REALITY on the same server.
- **All filters at once:** every profile still connects. VLESS Encryption needs the third round (another kind in disguise) and takes about 14 s in 4 of 5 attempts. These are the only attempts over the 10 s target.
- WireGuard on main shows 80% in two scenarios that don't filter it. A WireGuard handshake sometimes took longer than the 4 s first probe there; the branch's next round caught those.
- Rescued connections take about 9 s because a probe round waits for its slowest member (4 s first try + 5 s round). Phase 4 should return on the first success in a round, which would bring these close to 4–5 s.
- The simulator also caught a bug before it shipped: two WireGuard probes with the same key, run in parallel, knock each other out. The path finder now never does that.

Raw results: `tools/censorsim/results/2026-10-05.jsonl`. The `sni,fe,udp-dpi` rows come from a re-run after the third round was added; the code paths of the other scenarios never reached that round.

Tests: `WarPlanEnginesAndStealthTest` (16 tests) plus the existing `AntiCensorshipProtocolsTest`, `EndpointResolverTest` and `XrayConfigBuilderTest` pass on a JVM. The full Android `testDebugUnitTest`, `lintRelease`, both APKs and the emulator install check run in CI (release workflow dry run on this branch).

---

## Phase 3 — Discovery & distribution (where WhiteVPN dies)

Before this phase a subscription was one address fetched once, by hand, through the network's own DNS. The `autoRefresh` flag existed but nothing ever ran it. A poisoned DNS answer even made a valid address fail validation before any request. A filter's block page (HTML, status 200) counted as a successful sync with no servers.

What changed:
- **Never one domain.** A subscription has mirrors: paste several addresses at once and the first is the subscription, the rest are mirrors. A file on GitHub is also fetched through the CDNs that serve GitHub content: jsDelivr on four networks (Cloudflare, Fastly, Gcore, its own), Statically and Githack. (`SubscriptionSources`)
- **Fetched like the path finder connects.** Sources start 2.5 s apart, and a failed source starts the next one at once, so a working address costs one request. A source only counts when it holds configurations. A block page or an emptied file moves on to the next source. (`SubscriptionFetcher`)
- **DNS can't stop it.** Lookups use the network's DNS, then six DoH resolvers when it fails, stays silent or returns a block-page address. Every answer is still checked against private ranges (SSRF).
- **Offline copy.** The last payload that held configurations is kept encrypted on the device. When every source fails, its servers stay, and they are restored if they were deleted. The subscription shows "Offline copy from <day> in use". (`SubscriptionSnapshots`)
- **Refresh through the tunnel.** Once a connection is up, every due subscription (older than its interval, or failed last time) refreshes through it. One working server is enough to get a fresh list even when the subscription's address is blocked outside the tunnel.
- **Telegram distribution.** `tools/telegram-bot` is a Cloudflare Worker bot with `/configs`, `/sub` and `/app` in English and Persian. It fetches from the same mirrors, checks Telegram's secret header and limits each chat to 6 requests a minute. The admin (`ADMIN_ID`) changes the lists from Telegram with `/addsub`, `/delsub`, `/addconfig` and `/list`. It runs on Cloudflare's free tier; the README has dashboard install steps.
- **Phone to phone, no internet.** Any server's menu has "Share offline (QR)". "Share working servers" shows the servers that worked in the last day as codes that change every 1.5 s. On the other phone, Add Server → "Scan from another phone" reads them with the camera and adds each one, saying how many of the set have arrived. The code (`mx1:`, `ShareCode`) carries every protocol's settings, including WireGuard keys and Hysteria2 masks, which no share link format does. The scanner also reads other apps' QR links, and pasting a code works like a link. The design was approved from previews.

| Scenario (checked by tests) | main | this branch |
|---|---|---|
| Subscription domain SNI-blocked or reset (GitHub file) | no servers | CDN mirror, one stagger later |
| Block page with HTTP 200 instead of the file | "success", 0 servers | next source |
| Address silent (dropped packets) | waits 15 s or more, then fails | next source after 2.5 s |
| DNS poisoned to a block-page address | rejected as invalid | DoH answer used |
| Every source blocked, servers deleted | nothing | offline copy restores them |
| Address blocked outside the tunnel, one server works | stays stale | refreshed through the tunnel after connect |

Tests: `ShareCodeTest` (4, JVM) covers the QR code for VLESS REALITY, AmneziaWG and Hysteria2. `SubscriptionResilienceTest` (6, JVM) and `SubscriptionManagerResilienceTest` (2, Robolectric with Room) cover the rows above. The DNS row is covered by `EndpointResolverTest`, and its wiring into the subscription client is reviewed, not tested. The bot has `node tools/telegram-bot/test.mjs`.

Not done yet:
- **Built-in sources** (official plus 2–3 public): these need addresses you trust.
- **QR sharing on real phones:** the codec is tested and the build compiles; the camera path still needs a test between two phones.

## Phase 4 — Sub-10-second auto-connect (this branch)

What changed in the app:

- **Server race** (`vpn/smart/ServerRace.kt`). When the chosen server carries no traffic, the other saved servers are tested with real requests, five at a time, with the kinds of connection spread over each round so one filter cannot empty it. The best TLS-looking servers are also tried in disguise in the same round. Used at connect (after the path finder found nothing), by Smart Connect and by failover.
- **The race picks the path least likely to be cut next, not the fastest.** Kinds that look like ordinary HTTPS (REALITY, CDN, TLS) come before QUIC, WireGuard and unencrypted ones, and latency decides within a kind. Kinds that failed on this network in the last 30 minutes go last, and after a failover the cut server is tried again in disguise first.
- **Per-network memory** (`NetworkMemory`, `NetworkKey`). Each carrier (by MCC+MNC: Irancell, Hamrah-e Aval, Rightel...) and Wi-Fi keeps its own stealth winners, working kinds, recent failures and average latency. The latency sets the probe time limits: 3 s and 4 s on a fast network, up to 6 s and 7 s on a slow one, 4 s and 5 s before anything is known. These are the "per-carrier thresholds": learned on the phone, not hard-coded.
- **Faster watchdog** (`WatchPolicy`). A healthy connection is still checked every 12 s; after a failed check it is re-checked every 3 s, so a dead connection is given up after about 18 s instead of 36 s, still after three failures in a row.
- **Smart Connect** tests the recommended server alone first (one request when it works), then races the others.

Not possible: returning the moment the first server in a round answers. libXray's `pingBatch` holds one lock for the whole batch and returns only when every member has finished, so a round with a blocked member always takes its time limit.

### Results (simulator)

KPI 1 and 2, first connect on a new network ("branch", the Phase 2/3 code) against a network the phone has used before (Phase 4), 2 trials × 5 profiles per scenario:

| Scenario | branch: time (median / p90) | branch under 10 s | Phase 4: time (median / p90) | Phase 4 under 10 s |
|---|---|---|---|---|
| none | 0.0 s / 0.0 s | 100% | 0.0 s / 0.1 s | 100% |
| sni | 0.1 s / 8.2 s | 100% | 0.1 s / 8.2 s | 100% |
| fe | 0.0 s / 0.2 s | 100% | 0.1 s / 0.3 s | 100% |
| udp-block | 0.1 s / 9.1 s | 100% | 0.1 s / 7.1 s | 100% |
| udp-dpi | 0.1 s / 9.1 s | 100% | 0.1 s / 7.1 s | 100% |
| throttle | 0.9 s / 1.1 s | 100% | 0.9 s / 2.9 s | 100% |
| sni,fe,udp-dpi | 9.1 s / 14.3 s | 80% | 8.1 s / 12.3 s | 80% |

Every attempt connected in both. Under all filters at once the slowest case (VLESS Encryption, rescued by another kind in disguise on round three) is still over 10 s; a faster round there needs the probe to return early, which libXray does not allow.

**Saved server down** (its address accepts nothing, like a blocked IP), time to a working connection:

| Scenario | before Phase 4 | Phase 4 |
|---|---|---|
| none | 45.1 s | 9.4 s |
| sni | 53.2 s | 13.2 s |
| fe | 45.1 s | 9.3 s |
| udp-block | 45.1 s | 14.2 s |
| udp-dpi | 45.1 s | 14.2 s |
| throttle | 46.2 s | 10.7 s |
| sni,fe,udp-dpi | 54.2 s | 14.2 s |

Before Phase 4 the dead path was started and the watchdog switched after three 12 s checks (36 s added to the measured probing).

**KPI 3, soak** (`soak.sh`): a connection held for 8 minutes, a request every second, while the censor goes none → sni → sni+udp-dpi → sni+fe+udp-dpi → udp-block → none, changing every 90 s and cutting open flows it now blocks. Two runs each:

| Failover logic | requests through | outages | time without traffic | longest outage |
|---|---|---|---|---|
| before Phase 4 | 90% | 2 | 93 s | 45 s |
| Phase 4 | 95% | 2 | 46 s | 23 s |

The same number of outages (each run has one forced cut, when server names start being filtered), half the time without traffic. The soak also caught two mistakes before they shipped: taking the fastest working path landed on WireGuard or VLESS Encryption, which the next filter killed (4 outages, 149 s without traffic), and leaving out the server that was just cut removed its disguised form, the one path that survived (2 outages, 57 s).

Raw results: `tools/censorsim/results/2026-10-05-phase4.jsonl`, `2026-10-05-soak.jsonl`.

## Phases 5–7: superseded

The V1.0.1 dual-mode plan (2026-10-05) replaces Phases 5–7. Its step 1 audit is in [V101_AUDIT.md](V101_AUDIT.md).
The table below is kept for reference.

| Phase | First concrete step | Needs from you |
|---|---|---|
| 5 Emergency tiers | Wire the existing tiers into one "route N of 6" ladder | Psiphon needs official sponsor/propagation channel IDs from Psiphon Inc.; its library is GPL-3.0 (license decision) |
| 6 Anti-censorship loop | Opt-in "protocol X failed on carrier Y" reports | A collection endpoint and a privacy policy |
| 7 Combat verification | Every scenario above plus total blackout and sub-domain block, then `testDebugUnitTest`, `lintRelease`, both APKs and `check-apk.py` | — |

Optional Phase 1 follow-up: amneziawg-go as a second native engine for AmneziaWG servers with custom headers.
