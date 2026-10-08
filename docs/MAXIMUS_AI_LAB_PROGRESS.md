# Maximus VPN: AI Gateway + Autonomous Network LAB, progress

Task tracking for "AI Gateway + Autonomous Network Lab, Final V3" (Phases 0 to 8). Statuses: NOT_STARTED,
IN_PROGRESS, BLOCKED, IMPLEMENTED, TESTING, COMPLETED, FAILED. A task is COMPLETED only after its tests passed
in CI (`.github/workflows/checks.yml`: unit tests, lint, secret and name scan).

Nothing in this work was measured on a real Iranian network or a real phone: the build environment reaches
neither. LAB only claims what the phone running it measured; everything below that depends on a real network is
listed under Limitations until the product owner runs it on a phone.

## Working state

| Field | Value |
|---|---|
| Branch | `feature/ai-gateway-lab-uvhw6m` |
| Base | `main` at `8640eb8` (Merge PR #30) |
| Version | 1.0.0 / 27 during the work; V1.0.1 at the end, as the product owner asked (overrides section 60's "do not auto-increment") |

---

## MAXIMUS AI + LAB — PHASE 0 AUDIT

Audit of `main` at `8640eb858c9724c810000775e6fde2845e67fd38` (2026-10-08). Paths are under
`app/src/main/java/com/example/` unless they say otherwise.

| Item | Finding |
|---|---|
| Repository | `drfxai/MAXIMUS-VPN` |
| Branch / HEAD | `main`, `8640eb8` "Merge pull request #30" (community + update pop-ups) |
| Open PRs | #20 (draft, kept open on purpose for a future Iran test server; not touched) |
| CI | Checks run 83 on `8640eb8`: success. Release run 46 (V1.0.0 build 46): success |
| Android version | versionName 1.0.0, versionCode 27, compileSdk 36.1, minSdk 24 (`app/build.gradle.kts`) |
| VPN core | libXray / Xray-core v26.9.9, SHA-256 pinned (`scripts/fetch-libxray-android.sh`); sidecars Mihomo, Tor, dnstt, Psiphon (`vpn/sidecar/*`); WARP registration (`vpn/warp/*`) |
| AI architecture | One consumer, `ai/AiAgentManager.kt`, calling Gemini directly through Retrofit (`ai/GeminiApiService.kt`); model list hard-coded in `ai/GeminiModels.kt`; one read-only tool (`ai/AiAgentTools.kt`) that returns counts only |
| AI providers | Gemini only. No provider abstraction, no discovery, no failover beyond a fixed model list, no circuit breaker |
| Credential storage | `data/security/SecureStorage.kt`: AES-256-GCM with an Android Keystore key; the Gemini key is stored encrypted in `maximus_ai_agent_prefs` (`ai/AiAgentPreferences.kt`). The decrypted key is held in `AiAgentConfig.apiKey` inside a StateFlow the UI reads, i.e. the AI layer holds the raw key |
| AI boundary | `AiBoundaryTest` scans `com.example.ai` for references to connection state, kill switch, recovery, LKG, intelligence, process execution. Privacy filter `core/AiPrivacyFilter.kt` redacts URLs, IPs, hosts and keys |
| Probe engines | `vpn/connectivity/MultiProbeHealthEngine.kt` (DNS, TCP, TLS, protocol, engine, TUN, HTTP, DNS-in-tunnel, stability; observation vs assessment in `ProbeResult.kt`); `xray/RealDelayProbe.kt` (real request through a config, max 5 per call); `vpn/smart/NetworkCapabilityDetector.kt` (outside the VPN) |
| BPB recovery | `vpn/connectivity/BpbRecoveryEngine.kt` + `RecoveryProfile.kt` (10 reviewed, expiring built-in profiles) + `RecoveryLedger` (per config and network, rollback after failures). Used by FIX BPB and by `RayVpnService` at connect |
| Security gate | `vpn/connectivity/RecoverySecurityGate.kt`: derived copy may differ only in `finalMask`, `fingerprint`, `alpn`, `cipherSuites`, `echConfigList`, `address`; TLS kept, certificate checks on, identity fields frozen, bounded masks, ECH only via IP-literal DoH, new endpoint only as public IP |
| Fragment | Xray `finalmask` JSON (`VlessProfile.finalMask`); trials vs plain config with automatic revert (`FragmentProfileEngine.kt`) |
| ECH | Parser reads `ech=` (`vless/VlessParser.kt`), model `echConfigList`/`echSockopt`, builder writes it (`xray/XrayConfigBuilder.kt`), runtime Xray v26.9.9. Not probed on the network (`echCapable` stays null) |
| Transport capability | `vpn/connectivity/TransportCapabilityEngine.kt` (UDP/QUIC/H3 vs TCP, IPv4/IPv6 measured separately, H3→H2 fallback through the gate) |
| IPv6 | Measured per network; `vpn/safety/VpnRoutePolicy.kt` makes every TUN capture IPv6 so nothing bypasses it |
| DNS protection | `vpn/safety/DnsResolvers.kt`, `vpn/connectivity/DnsResilienceEngine.kt`, DNS-in-tunnel probe step |
| Kill switch | Fail-closed connect (`vpn/safety/FailClosedPolicy.kt`, `MaximusVpnSupervisor.kt`); settings flag in `SettingsRepository` |
| Free configs / Top-30 / LKG | `vpn/hub/*`: signed list, one capped Top-30 with diversity, atomic swap, `LastKnownGoodPool` |
| Existing LAB | `vpn/lab/ProtocolLab.kt` + `ui/protocols/*`: tests one config per protocol family; no experiments, promotion or memory |
| Network memory | `vpn/smart/NetworkMemory.kt` (per carrier/Wi-Fi: working kinds, latency, recent failures); `vpn/smart/NetworkKey.kt` (MCC+MNC, no identifiers) |
| Persistence | Room v8 for servers/subscriptions/benchmarks; every connectivity store is JSON in SharedPreferences behind `load`/`save` lambdas (testable). The new stores follow the second pattern, so no Room migration is needed |
| Navigation/UI | Jetpack Compose + Navigation Compose (`ui/navigation/RayNavHost.kt`), six bottom tabs (Tunnel, Nodes, Top 10, AI Agent, Panels, Settings) |
| Crash/ANR | `vpn/diagnostics/events/RuntimeHealth` (crash, ANR, stall, memory evidence) |
| Tests | ~80 JVM/Robolectric test classes under `app/src/test`, Roborazzi available; CI runs them on every push |

### PattNG / Maximus capability gap

PattNG (GPL-3.0, ideas only; see `/mnt/project-files/research/zedsecure-pattng-ideas.md`) vs Maximus, split by layer.
"Link parser" means share links; Maximus also imports Xray JSON, where every Xray field passes through.

| Capability | PattNG | Maximus link parser | Maximus model | Maximus config builder | Maximus runtime |
|---|---|---|---|---|---|
| ECH | yes | yes (`ech`) | yes | yes | yes (Xray v26.9.9) |
| Fragment / finalmask | yes | no (Hysteria2 links only) | yes | yes | yes |
| Cipher-suite override | yes | no | yes | yes (Go TLS stack only) | yes |
| TLS fingerprints | yes | yes (`fp`) | yes | yes | yes |
| XHTTP | yes | yes | yes | yes | yes |
| H2 transport | yes | yes | yes | yes | yes |
| H3 / QUIC (XHTTP over h3) | yes | partial (alpn) | yes | yes | yes, needs UDP |
| MASQUE | yes (Aether) | no | no | no | no |
| WARP | yes (Aether) | no | partial (WireGuard profile) | yes (WireGuard) | yes (WireGuard WARP, not MASQUE) |
| Chains | yes | no | partial (sidecar chain only) | partial | partial (Xray → sidecar SOCKS) |
| IPv6 endpoints | yes | yes | yes | yes | yes |

These rows are now code: `vpn/lab/CoreCapabilityRegistry.kt`, so the UI and LAB cannot show a parse-only feature
as working.

### Reusable components
MultiProbeHealthEngine, ProbeResult/HealthReport (observation vs assessment), FailureStage, RecoveryProfile(s),
RecoverySecurityGate, BpbRecoveryEngine + RecoveryLedger (also the path AUTO APPLY uses, with its rollback),
TransportCapabilityEngine, ProbeBudget, ConnectionScore, NetworkCapabilityProfile + Detector, NetworkKey,
RealDelayProbe, SecureStorage, AiPrivacyFilter, AiBoundaryTest.

### Missing components
Provider abstraction, credential vault, model discovery, routing modes, failover, circuit breaker, health; LAB
failure classifier taxonomy, mutation allowlist, experiment engine, promotion policy, verified profiles, LAB
network memory, network-session tracking; research candidates; core capability registry; LAB and AI provider UI.

### Architectural conflicts and risks
1. The AI layer holds the raw Gemini key in UI state. Fixed in Phase 2: the key lives in `AiCredentialVault`;
   only provider adapters read it, at request time.
2. `AiAgentManager` implements the Gemini API itself; spec forbids per-module provider code. Moved behind the gateway.
3. Seven bottom tabs would not fit; LAB placement is a UI decision for the product owner (preview first).
4. `vpn/smart/NetworkCapabilityProfile` already exists; it is reused, not duplicated. `vpn/smart/NetworkMemory`
   exists with another job (stealth alternates); LAB's verified-profile memory is `LabNetworkMemory`.
5. 9Router is self-hosted (default `http://localhost:20128/v1`); the user must give its address. Its combos are
   listed by its `/v1/models` and selected as the model name, which is its documented native routing.
6. Real network results (Irancell/MCI/TCI), real provider keys and on-phone UI are untested from here.
7. Telemetry (section 49) is not built: the product owner rejected any reporting on 2026-10-08. Remote probes
   (section 37) are an interface only.

---

## Task table

| Task | Phase | Status | Files | Tests | Result / limitation |
|---|---|---|---|---|---|
| MX-AI-P00-T01 | 0 Audit | COMPLETED | this file | n/a | Audit above |
