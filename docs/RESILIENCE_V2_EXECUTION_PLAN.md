# MAXIMUS VPN execution plan
Updated 2026-10-25. Source: user-supplied Unified Next-Generation Connectivity Engineering Directive, sections 0–16. This file is the durable continuation checkpoint; completion requires evidence, not merely code.

## Baseline and rules
Repository drfxai/MAXIMUS-VPN. Main baseline f7a77a563c45ff56c6e5a20b38c062849f95e4f5. Version 1.0.2/code37/build91. Development branch codex/resilience-v2-p0, PR125. Preserve version, signing and app identity. Do not merge/release/deploy without explicit authorization. One ConnectivityBrain and Android TUN owner. Reuse existing engine contracts and route memory.
Primary metric: elapsed user action to first verified international traffic through intended protected path, including failed attempts. Secondary: secure stable verified path. Under-60s cached reconnect is a target, not a measured result.

Statuses: TODO, IN_PROGRESS, IMPLEMENTED_PENDING_TEST, TESTED(layer), VERIFIED(layer), BLOCKED(reason), RESEARCH_ONLY. Never collapse source/unit/emulator/device/field/real-traffic evidence into one "done".

## Phase 0 — baseline audit (IN_PROGRESS)
- P0.1 Record exact main/development SHA, dependency/native versions, CI and rollback commits.
- P0.2 Inventory ConnectivityBrain, CurrentNetworkSession, NetworkEvidence/Assessment, RecoveryDecision, EngineLedger, RouteMemory, catalog/capabilities, LAB, Sidecars and TUN ownership.
- P0.3 Audit Tor/WebTunnel/Snowflake, MatrixTransport/Aether/MASQUE/WARP, Psiphon, CottenDNS/DNSTT/Slipstream, Mihomo/AWG, per-app routing, Room and subscriptions.
- P0.4 Classify every requested feature as absent/partial/broken/present-unverified/tested/verified/dependency-blocked.
Acceptance: source paths plus test evidence for every classification; independent engine version matrix. Current partial findings: RESILIENCE_V2_BASELINE.md.

## Phase 1 — existing connection P0 repairs (IN_PROGRESS)
- P1.1 Revalidate network-specific LKG; budget small races; deduplicate failure domains; preserve current verified path. Extend existing RouteBook/EngineRouteHints with measured setup/RTT/TTFB/uptime/freshness and migration.
- P1.2 TorBridgeHealthManager: verified bridge identity, refused/reset/timeout/EOF/DNS taxonomy, bounded cooldown/backoff/jitter, supported authenticated control events CIRC/ORCONN/STREAM, active-circuit protection. Audit control support before runtime suppression.
- P1.3 Bound logs without discarding later recovery events. Consecutive redacted aggregation implemented in PR125; not runtime retry suppression.
- P1.4 Propagate requestId/sessionId/networkGenerationId/start time across every async probe; reject ABA/stale completions and cancel superseded work.
- P1.5 Idempotent lifecycle and explicit failure reasons; safe DAILY/GOD policy diff; rebind underlay on handover; restart only affected components.
- P1.6 Distinguish engine/SOCKS readiness, TUN establishment, actual protected application egress and stability. Record interface, target, route/hops, exit IP if observed, RTT/TTFB, DNS/IPv6 policy, total wait and median/p95.
Acceptance: stale callback and ABA tests; healthy-circuit plus failed-bridge tests; mode/network transitions; cancellation/crash cleanup; TUN/per-app/lockdown/leak checks. Physical acceptance still outstanding.

## Phase 2 — resolver and DNS family P0 (IN_PROGRESS)
- P2.1 A/AAAA/TXT/NXDOMAIN; UDP/TCP/DoH/DoT; optional audited DoQ/DoH3; EDNS, QNAME/answer limits, RTT/loss/rate tolerance and per-network freshness.
- P2.2 Manual mode only supplied resolvers; validated IPv4/IPv6/port(default53); bounded discovery/progress; stop when adequate evidence exists.
- P2.3 States NO_RESOLVER/SCANNING/CANDIDATE/RESOLVER_VERIFIED/ENGINE_START/READY/TRAFFIC_VERIFIED; DNS response never proves egress.
- P2.4 SOAR source/license/protocol/crypto/build/server audit then independent real SoarSidecar: domain/key/resolvers/transport/discovery/QNAME/answer/hold/inflight/window/import/cancel/diagnostics.
- P2.5 MasterDNS remains audit-gated; no opaque binaries.
- P2.6 One adaptive DnsRecoveryFamily using eligible SOAR/CottenDNS/DNSTT/Slipstream/audited MasterDNS and real bidirectional evidence.
- P2.7 Conditional MTU experiments1280/1360/1400/1500, separate TUN/upstream and respect IPv6 minimum.
Implemented pending final CI in PR125: mixed poisoned answer rejection, cached ranking, physical-network scan result guard, shared12s WebTunnel DNS preparation deadline. This guard is not complete generation protection.
Acceptance: manual-only/no-resolver/truncation/TCP fallback/poisoning/expiry/cancellation and actual DNS tunnel egress tests against controlled servers.

## Phase 3 — OpenVPN/AWG/chains P1 (TODO; audit gates)
- P3.1 Audit OpenVPN3 license/native ABI/build and integrate suitable native engine, not Xray alias.
- P3.2 .ovpn TCP/UDP, CA/client certs and inline/imported certs, username/password, IPv4/6/routes/DNS; reject unsupported security-critical directives; Keystore-backed secret storage, no private export/logs.
- P3.3 Complete existing Mihomo AWG import/edit/obfuscation/multi-peer/routes/DNS/IPv6/per-app/delay/exit verification.
- P3.4 Reviewed Psiphon over OpenVPN/SOAR/AWG/MasterDNS only when each hop routable; no skipped hops/direct fallback.
- P3.5 Engine-neutral PROXY/BYPASS and measured split TCP/UDP/DNS policy; optional reversible LAB QUIC experiment.
Acceptance: TCP/UDP native engine and credentials tests; full-chain protected egress; required-hop failure stays closed; per-app policy preserved.

## Phase 4 — WARP/MASQUE/startup/Xray P1 (TODO)
- P4.1 Multi-target provisional/verified/degraded/failed health; singleflight scan/key/connect; superseded callbacks rejected.
- P4.2 Independent outer/inner WARP identities; valid permitted registration reuse; verified bootstrap router; bounded registration retries.
- P4.3 EngineStartupSupervisor bounded timeout/cleanup/restart and isolation where feasible.
- P4.4 Verify authentic suitable Xray release before considering26.10.17; JNI/config/REALITY/XHTTP/ECH/TUN/DNS/IPv6/fragment regression.
Acceptance: stale cancellation/key independence/multi-target tests; startup crash cleanup; native provenance and ABI checks; no TLS weakening.

## Phase 5 — performance/subscriptions/UI (PARTIAL)
- P5.1 Existing PR122/123 offload list processing; measure50/100/300/500 profiles, cold start/open/scroll/search/sort/batch/input/RAM/CPU/battery/ANR.
- P5.2 Optimize lazy groups, selected metadata, Room indexes/atomic updates, StateFlow emissions/recomposition and background parse.
- P5.3 Diagnose subscription503 with authorized origin/Worker evidence; retain LKG; bounded retry/backoff; no production changes implied.
- P5.4 Minimal AMOLED Home with concise truthful status, LAB diagnostics and reduced motion. Distinguish local traffic from server quota.
Acceptance: reproducible before/after measurements; HTTP503 cannot erase good profiles; no fabricated performance numbers.

## Phase 6 — research gates (RESEARCH_ONLY)
Bepass incremental failure-domain value/license/security/Android cost; SIMORGH MSP, SNI Spoof Plus and announced engines need source/spec/release/license evidence. Recipes stay unverified until certificate/auth/real traffic pass. No universal hardcoded SNI success.

## Phase 7 — acceptance and review (TODO)
Record exact final SHA, CI IDs, built APK artifacts if available, engine matrix, failed tests, unresolved P0s and blockers. Layered test matrix: source, bundled, config, unit, simulator, emulator, device, field, real traffic. Security gates: no TLS/hostname bypass, leaked DNS/IPv6, plaintext credentials, skipped hop, accidental direct route or false fail-closed claim with lockdown off.
Prepare reviewable PRs; keep rollback commits. Release is a separate authorized operation after acceptance.

## Continuation procedure
1. Read this plan and baseline document; fetch latest main and PR125 head/CI.
2. Fix failed tests before broadening changes. Avoid recreating shipped PR121–124.
3. Complete Phase1/2 existing-path work first; perform external audits before introducing engines.
4. For each task record changed files, commit, tests and remaining limits here.
5. Do not claim whole directive completed while audit/server/device gates remain open.

## Current checkpoint
PR125 contains partial DNS/Tor preparation/logging repairs. Latest functional commit before this plan:86aec807d4e29df50b5e9c96dee1c1a7eeb805f8. No new APK or field measurements. Next: inspect Android CI, validate cancellation/deadline tests, then implement generation-aware evidence and supported bridge-control integration.

### Phase1/2 checkpoint — 4a8b7beccc4a095f1904b0311c6ce76e68ae6a2a
LabController resolver scan now checks coroutine cancellation and existing tracker session before work and before persistence. Resolver burst requires matching network key, uses runInterruptible, and discards output if physical network or tracker session changed. Analysis rechecks session before proceeding to classification. Status IMPLEMENTED_PENDING_TEST; no full generation/ABA guarantee, no atomic session/persistence claim, and no physical handover evidence yet. CI on previous plan head was running; transport checks passed.
