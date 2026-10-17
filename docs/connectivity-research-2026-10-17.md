# Connectivity resilience research register — 2026-10-17

**Status:** Research record / engineering backlog, **not** an implementation or release claim.  
**Repository baseline reviewed:** `main`, public version `V1.0.2`, Android `versionCode 29` (at research capture).  
**Priorities:** (1) restore real connectivity; (2) stability and throughput; (3) optional privacy features. Authenticated transport, certificate verification, DNS/IPv6 containment, and fail-closed routing are mandatory at every priority.

This document retains lessons from upstream releases and supplied field reports while separating reproducible findings from hypotheses. It does not record subscription credentials, tunnel passwords, private keys, user-specific endpoints, or network identifiers.

## Evidence levels and acceptance language

- **Existing in MAXIMUS:** code or documented behavior is present on the reviewed `main` branch. This does **not** establish physical-device effectiveness.
- **Upstream release:** upstream authors describe an implementation; behavior and licensing must be independently checked before MAXIMUS adopts it.
- **User-reported field sample:** observed by the reporting user, but not independently reproduced under controlled conditions.
- **Research proposal:** not coded, not tested, and not an accepted architectural change.
- **Verified connection:** authenticated session **and** real application traffic through the intended VPN/TUN route; a socket, TLS, WebSocket, MASQUE or DNS handshake alone does not qualify. For stronger resilience acceptance, independently exercise both upload and download.

For claims about existing MAXIMUS behavior see [CONNECTIVITY.md](CONNECTIVITY.md), [MAXIMUS_MASQUE.md](MAXIMUS_MASQUE.md), [MAXIMUS_NETWORK_STATE.md](MAXIMUS_NETWORK_STATE.md), and the referenced Kotlin modules. Distinguish tests run in CI, simulations, desktop upstream tests, and physical Android tests on affected networks.

## Reviewed external findings

| Source | Useful change or result | MAXIMUS implication | Evidence status |
| --- | --- | --- | --- |
| [WhiteAestherMobile v1.13.0](https://github.com/WhiteDNS/WhiteAestherMobile/releases/tag/v1.13.0) | Discovery and recovery across exact endpoints, H2/H3 MASQUE, IPv4/IPv6, ECH, TLS variations, network-scoped route caching, cancellation on network change, enrollment state and complete 429 Retry-After handling. Positive selection requires tunneled traffic, not just handshake. | Record/replay complete successful routes; separately measure ECH/TLS/traffic stages; research MASQUE H2 alongside current H3-only implementation. | Upstream release; published test counts and desktop validation do not prove MAXIMUS works on affected carriers. |
| [WhiteVPN v1.7.1](https://github.com/WhiteDNS/WhiteVPN/releases/tag/v1.7.1) | Fragment suggestion activation, persistence across UI recreation and migration of legacy Mihomo `maxSplit=0` to `256`. | Add migration and persistence tests for *each engine's own syntax*. **Do not apply Mihomo semantics blindly to Xray.** | Upstream release. |
| [WhiteDNS-Android 1.6.0](https://github.com/WhiteDNS/WhiteDNS-Android/releases/tag/1.6.0) | CottenDNS/CottonDNS client integration, importer/exporter, multiple domains with per-profile settings, Android 15 `dataSync` foreground-service timeout handling, TalkBack/accessibility role and localized text fixes. | Research independent DNS emergency transport; keep its domains and resolvers per profile. Add Android 15 service lifecycle and accessibility checks to release tests. | Upstream release, not proof of end-to-end MAXIMUS tunnel. |
| [WhiteDNS Clean IP Finder v1.4.7](https://github.com/WhiteDNS/WhiteDNS-cleanip-finder/releases/tag/v1.4.7) | Limited-network mode avoids overaggressive one-shot rejection, tests at most three domains concurrently, retries timeout candidates, and does not lower the proof required to call an IP clean. | Bounded, loss-tolerant discovery mode with retry and concurrency control. Do not classify an IP as working after only a port check. | Upstream release. |
| [PingNG v2.3.11-Pi45](https://github.com/rezakhosh78/PingNG/releases/tag/v2.3.11-Pi45) | Bepass for selected TCP+TLS/REALITY VMess/VLESS/Trojan/Shadowsocks; avoids ClientHello fragmentation for `workers.dev` to prevent EOF; introduces WARP WireGuard/MASQUE-H2/AWG/Plus templates; routing/subscription isolation and APK-update safety improvements. | Keep fragmentation conditional, not always-on; benchmark unfragmented `workers.dev` first. Preserve route rule order, per-app restart semantics and APK ABI/package checks. | Upstream release. |
| [Slipstream](https://github.com/EndPositive/slipstream) and [SlipNet](https://github.com/anonvector/SlipNet) | Multipath QUIC carried over recursive DNS; multiple resolvers, congestion handling and Android integration references. | Existing MAXIMUS Slipstream Sidecar requires authenticated, real Android/TUN and resolver-path acceptance tests; do not re-implement the already bundled adapter. | Upstream capability; current MAXIMUS binary presence is not carrier success. |
| [CottenDNS](https://github.com/WhiteDNS/CottenDNS) | DNS carrier when regular VPN connectivity is unavailable but the recursive DNS path survives. | Experimental emergency engine with owned tunnel zone, strict authentication, explicit trade-offs in throughput. | Research proposal for MAXIMUS. |
| [AmneziaWG](https://docs.amnezia.org/documentation/amnezia-wg/) | Updated 3.1 packet-shape obfuscation around the WireGuard cryptographic core. | Evaluate compatible client/server infrastructure and actual transport support before enabling selection. | Upstream specification; current MAXIMUS separate AWG engine descriptor is not bundled. |
| [Codebase Memory MCP v0.12.0](https://github.com/DeusData/codebase-memory-mcp/releases/tag/v0.12.0) / [Plannotator](https://github.com/backnotprop/plannotator) | Repository-linked code/document indexing and reviewed engineering plans/diffs. | Use documents and ADRs as versioned handoff; these are tooling references, not VPN engines. | Tooling options, not installed by these docs. |

Additional experimental *bidirectional* research: [Minecruft-PT](https://github.com/doudoulong/Minecruft-PT), [Protozoa](https://github.com/dmbb/Protozoa), [Huma](https://cs.uwaterloo.ca/~s4kamali/publications/huma/), [Conjure](https://github.com/refraction-networking/conjure), [SWEET](https://arxiv.org/abs/1211.3191), [Skyhook research](https://www.petsymposium.org/foci/2024/foci-2024-0011.php), and [FreeWave research](https://arxiv.org/abs/1207.2683). They are **not** deployable MAXIMUS routes without a verified compatible server, functioning return channel, integration and throughput testing. Conjure additionally needs cooperating refraction infrastructure. One-way broadcast designs such as [CovertCast](https://petsymposium.org/popets/2016/popets-2016-0024.php) are *not* candidates for a general two-way VPN data plane.

## MAXIMUS baseline and actual gaps (2026-10-17 review)

| Component | Present | Gap / limitation to verify |
| --- | --- | --- |
| `vpn/sidecar/SlipstreamSidecar.kt` + `EngineRegistry.SLIPSTREAM` | Pinned separate native sidecar registered as bundled; parses domain, multiple resolvers, congestion policy and optional pinned server certificate. | Native start/loopback port is not end-to-end verification. **Automatic mode must not choose an unauthenticated profile merely because it has a reachable resolver.** Validate certificate-pin enforcement and physical Android data path. |
| `vpn/sidecar/MasqueSidecar.kt` and [MASQUE docs](MAXIMUS_MASQUE.md) | `usque` MASQUE/HTTP3 sidecar and Cloudflare registration model. | **No H2 fallback**; physical Cloudflare enrollment and tunnel data path explicitly not verified in checked docs. Need restart-safe identity/retry semantics and pins audit before claiming operation. |
| `vpn/smart/NetworkMemory.kt` | Stores successful stealth-variant key, transport-kind order and average real-request latency. | Does not persist an immutable *full endpoint + SNI + ECH + Fragment + MTU* winner. |
| `vpn/smart/NetworkKey.kt` | Differentiates cellular carrier using MCC/MNC; generic Wi-Fi/Ethernet classes without location permissions. | Every Wi-Fi is presently the same key. Avoid treating a route learned on one Wi-Fi as verified on another; use permission-safe network-epoch validation. |
| `vpn/lab/MtuIntelligence.kt` | Bounded MTU ladder, staged/verified/rollback transactions and one-day network+engine+transport+address-family memory. | Current source describes `PATH_MTU_ESTIMATE` as **not measured**. Field MTU black-hole diagnosis is not equivalent to a directly measured physical PMTU. |
| `vpn/connectivity/FragmentProfileEngine.kt` | Keeps fragmentation trials and can revert a worse profile. | Verify selected-settings persistence after process restart, compatibility exceptions and engine-specific input validation. |
| `vpn/diagnostics/LiveTunnelProbe.kt` + [CONNECTIVITY.md](CONNECTIVITY.md) | Real request through active VPN path; distinguishes handshake from traffic success. | Add reproducible *both-direction* upload/download receipts and explicit "not tested" where physical path or upload test is unavailable. |
| `vpn/shadowlink/ShadowLink.kt`, `vpn/smart/ServerRace.kt` | Prioritisation of candidate strategies and real-request ranking. | Determine whether the exact tested configuration is replayed, isolated by current network epoch, and confirmed before promoting a winner. |

### Important field observations — not upstream guarantees

**Known working Trojan/WebSocket sample (reported by user).** One authenticated `Trojan + WebSocket + TLS` route was reported to connect under selective blocking. Technical features worth measuring separately: endpoint IP distinct from HTTP Host/TLS SNI, `http/1.1` ALPN, Cloudflare Worker hostname, Chrome-like fingerprint setting, and an ECH source hint. A configured ECH hint is **not** proof that the server accepted ECH; capture the TLS library's negotiated outcome. **No credential, literal endpoint address, or private WS path is stored here.**

**Mobile-operator MTU claim (reported via Telegram 2026-10-17).** A community message attributes connected-but-stalled applications to dropping packets around 1350 bytes and suggests VPN MTU 1280 plus Cloudflare DoH. This is a *hypothesis*, not verified operator-wide packet-capture evidence. Run controlled A/B at fixed DNS, endpoint, client and transport with small and large uploads/downloads. Use `MtuIntelligence` and preserve no-improvement outcomes. Do **not** force MTU 1280 or change every user's DoH globally.

**Limited-network mode.** Retry bounded timeouts and limit simultaneous probes (e.g. three), but do not enlarge the acceptance criterion or retry disallowed/unauthorised destinations. Cache only two-way-verified candidates per network and configuration; invalidate old-epoch probes.

## Priority-ranked follow-up

**P0 — correctness and evidence, before adding another engine**

1. Make `authenticated + real tunneled traffic` the explicit state contract. Add a two-direction verifier (independent upload and download plus TX/RX evidence) and test failure/timeout states.
2. Define an immutable **route recipe** carrying transport, endpoint IP/port, independent Host/SNI, requested and negotiated ALPN, ECH source+actual state, Fragment policy, MTU, DNS resolvers, security pins and engine-specific settings. Persist only after verification and scope it to a valid network epoch. See [adaptive-route-validation ADR](adr/adaptive-route-validation.md).
3. Validate existing Slipstream and MASQUE H3 binaries on actual supported Android ABIs, with owned endpoints/authorized resolvers, correct TUN routing, authentication and no leaks. Existing registry/sidecar entries must not be described as passed on physical Iranian networks.
4. Make connected-but-stalled traffic diagnosable: MTU A/B, IPv4/IPv6, DNS, HTTP upload, HTTP download, TLS and ECH failures recorded separately.
5. Enforce kill switch/fail-closed transition, verified TLS/cert pins, DNS and IPv6 containment; redact diagnostics and keep signing/connection secrets out of Git and APK.

**P1 — resilience improvements**

6. Design and validate an independent MASQUE H2 adapter/fallback; verify actual ECH negotiation, persistent enrollment/identity, `429 Retry-After`, and network-switch cancellation. Do not assume an HTTP/3 `usque` binary can speak HTTP/2.
7. Implement limited-network scan policy: bounded retries, maximum three concurrent domain probes initially, explicit per-candidate deadlines and correct cancellation; retain strict proof.
8. Maintain a per-engine Fragment compatibility database: baseline unfragmented ClientHello for Worker hosts; only try other policies as authorised alternatives; test save/restart/migration for Mihomo versus Xray.
9. Prototype CottenDNS independently, only after controlled delegated domains, compatible server, authenticated tunnel and end-to-end validation. Research AWG 3.1 with a matching peer.
10. Include Android 15 `Service.onTimeout` foreground-service regression checks, localised Connect strings, accessibility roles, subscription group isolation and safe APK upgrade selection.

**P2 — controlled research only**

11. Evaluate cover transports (WebRTC, web, Minecraft) on owned endpoints with real bidirectional receipts, traffic/CPU/battery budgets and explicit consent. Keep one-way dissemination channels outside the VPN route registry.
12. Use revisioned docs plus structured knowledge notes for reproducible handoff; any upstream source-code reuse needs an explicit license-compatibility review (some reference applications are GPL/AGPL).

## Validation artefacts

Every accepted network claim should record: test date; app, core and engine commit/hash; Android model/build and network type without subscriber identifiers; route recipe with **all secrets removed**; requested and negotiated ALPN; actual ECH state when exposed by the TLS implementation; certificate/pin result; MTU/fragment candidate matrix; bounded probe timings; current network epoch; request-response data for upload **and** download through TUN; exit-IP test where applicable; sanitized failure-stage logs and TUN byte counters. Mark unavailable external leak tests as **NOT TESTED**, never "pass".

### Completion gate

These notes introduce **no product features**. Completion of each P0/P1 item requires a reviewed implementation, automated tests and authenticated physical Android tunnel verification on the relevant network; **connection possible on a developer workstation is not carrier acceptance**. No blanket claim of surviving a total international routing cutoff is supportable without a genuinely available independent bearer.
