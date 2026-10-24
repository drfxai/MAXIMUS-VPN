# Resilience V2 baseline — 2026-10-25

This is a partial source audit, not completion of the engineering directive.

## Reproducible baseline
- Main: f7a77a563c45ff56c6e5a20b38c062849f95e4f5
- Android versionName 1.0.2, versionCode 37, published build 91.
- Baseline Checks 40109959272 and transport recovery 40109959223 succeeded.
- Signed release 40109959280 succeeded.
- Development branch: codex/resilience-v2-p0; PR #125.
- Functional head before this documentation: 47ad57ee89fb8c446afd2d837932f518b3475ed2.
- Transport recovery 40133275184 passed; Android Checks 40133275094 were running when checked.
- Version, signing and production configuration unchanged.

## Source findings
| Area | Evidence | Status / implication |
|---|---|---|
| Resolver poisoning | ResolverIntelligence.judge used all blocked addresses | PR125 changes to any blocked address; mixed answers covered by regression tests |
| Cached resolver selection | LabController.measureResolvers returned cached rows directly | PR125 ranks cached rows using same policy as fresh rows |
| Network handover during DNS scan | Completed scans persisted without checking captured Network | PR125 discards results if physical Network differs; not full generation/ABA protection |
| Tor retry ownership | TorSidecar starts PluggableTransports and supplies its SOCKS ports through torrcWithPorts | Tor/transport internal attempts are not an app-level connect loop |
| Tor process lifecycle | TorInApp.start stops previous Tor process; generation guards late stop | Restarting to remove one failed bridge would disrupt healthy circuits |
| Bridge diagnostics | PluggableTransports logs only first 500 output lines | Later bridge evidence disappears; bounded aggregation with counters is required |
| Readiness | TorSidecar.carriesTraffic checks SOCKS CONNECT response | Readiness alone is not authenticated application egress or Android TUN proof |
| Route memory | RouteBook and EngineRouteHints already exist; PR121/124 add revalidation ordering | Extend these; do not create an independent connectivity brain |
| UI load | PR122/123 offload sorting/filtering and profile-flow mapping | No measured 50/100/300/500 profile frame-time baseline yet |

## Tor implementation gate
Identify bridge attempts using validated endpoint/bridge identity before redaction, without storing credentials. Do not infer bridge identity from truncated diagnostic IPs. Separate refused/reset/timeout/EOF/DNS categories and retain aggregate counters. Circuit health must come from current protected traffic and supported Tor control events, not a generic transport error. No global Tor restart or failure from a single auxiliary bridge error.

Before enforcing per-bridge cooldown on a running instance, audit the available authenticated Tor control channel and whether removing a candidate preserves existing circuits. If unsupported, expose limited observability and apply candidate selection on future starts only; do not claim runtime retry suppression. Network-generation scoping and cancellation tests are required.

## Remaining acceptance work
- Full CurrentNetworkSession/request/session/generation propagation, including switch-away-and-back (ABA).
- Actual Android TUN traffic verification, per-app behavior, DNS/IPv6 leak and lockdown tests.
- Bridge health integration with healthy-circuit preservation and bounded log aggregation.
- Resolver manual-only selection, transport-specific evidence, freshness and DNS-family ranking.
- SOAR/MasterDNS/OpenVPN source, license, crypto, build and server prerequisites; not implemented by this PR.
- AWG/Psiphon chain and underlay/final-hop verification.
- WARP/MASQUE registration singleflight, identity reuse and bounded startup recovery.
- Worker HTTP503 root cause requires deployment/origin evidence; no production inspection or changes performed.
- Performance acceptance with 50/100/300/500 profiles, frame time, input latency, CPU/RAM/ANRs.

## Evidence limits
No new device, emulator, field, DNS leak, protected international traffic or before/after speed measurement has been performed for this PR. User-reported timings are observations, not reproduced benchmarks. No merge, release or deployment is part of this development branch.
