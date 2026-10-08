# Maximus VPN: Iran Connectivity Upgrade V2, progress

Task tracking for the Iran Connectivity Upgrade V2 (Stages 0 to 12). Statuses: NOT_STARTED, IN_PROGRESS,
BLOCKED, IMPLEMENTED, TESTING, COMPLETED, FAILED. A task is COMPLETED only after its tests passed in CI.

Real-device and in-Iran validation (Irancell, MCI, Wi-Fi, IPv6, time periods) cannot be done from the build
environment, which reaches neither Iranian networks nor workers.dev. Every such item stays open until the
product owner runs it on a phone.

## Working state

| Field | Value |
|---|---|
| Branch | `feature/iran-connectivity-v2` |
| Base | `main` at `eddcb16` (Merge PR #27) |
| Version | versionName 1.0.0, versionCode 27 (unchanged; no automatic bump) |

---

## MAXIMUS IRAN CONNECTIVITY — PHASE 0 AUDIT

Audit of `main` at `eddcb162499f7558a7070cf5b1190dbaf8ed171f` (2026-10-08). Every claim names the file it was
read from. Paths are under `app/src/main/java/com/example/` unless they start with `tools/` or `.github/`.

### 1. Current branch
Audit taken on `main`. Work continues on `feature/iran-connectivity-v2`.

### 2. HEAD commit
`eddcb16` "Merge pull request #27 from drfxai/feature/maximus-upgrade-7ooxpw" (Gemini key paste fix).
101 commits on main. One open PR: #20 (draft, 2026-10-05) "Cap free configurations at 30 and require recent
Iranian network verification". Its cap and LKG ideas already landed through later PRs; it is left untouched.

### 3. Current Android version
`app/build.gradle.kts`: versionName `1.0.0`, versionCode `27`, compileSdk 36.1. Release tag in
`release-version.txt` is V1.0.0 (rebuilt in place by `.github/workflows/release.yml`).

### 4. Current VPN engine(s)
- Xray: libXray v26.9.9 (Xray-core v26.9.9), in-process, pinned by SHA-256 in
  `scripts/fetch-libxray-android.sh` (`xray/XrayEngine.kt`, `xray/XrayConfigBuilder.kt`). Runs VLESS (REALITY,
  TLS, WS, gRPC, XHTTP, HTTPUpgrade, Encryption), VMess, Trojan, Shadowsocks, Hysteria2, WireGuard.
- Kotlin packet tunnel for plain VLESS and SOCKS (`vpn/TunnelManager.kt`, `vpn/tunnel/*`).
- Sidecar processes: Mihomo, Tor (Snowflake), dnstt, Psiphon (off until a config secret exists)
  (`vpn/sidecar/*`). WARP registration (`vpn/warp/*`).
- Selection: `vpn/engine/EngineSelectionPolicy.kt`; refusal of what no engine runs:
  `vpn/engine/RuntimeCapabilities.kt`; per-engine circuit breaker: `vpn/engine/registry/EngineCircuitBreaker.kt`.

### 5. Existing probe implementation
Stages exist but are spread over several classes; there is no single multi-probe engine or `ProbeResult` type.
- TCP / TLS / WebSocket upgrade per server: `vpn/ServerTester.kt` (handshake only).
- Real request through a config before connecting (libXray `pingBatch`, max 5 per call):
  `xray/RealDelayProbe.kt`. This is the probe the app trusts.
- Real HTTPS request through the running tunnel, bound to the VPN network: `vpn/diagnostics/LiveTunnelProbe.kt`.
- DNS path check on the phone: `vpn/diagnostics/DnsPathTest.kt` (`dnsLeakDetected` is always null: it never
  claims a leak verdict).
- Network capability probes outside the VPN (DNS, TCP, TLS, UDP, IPv4/IPv6, Cloudflare, HTTP/2):
  `vpn/smart/NetworkCapabilityDetector.kt`. QUIC, HTTP/3 and ECH are not probed (fields stay null).
- Failure stage from an exception or engine text: `vpn/diagnostics/FailureStage.kt`.
- ICMP is never used to decide health. `vpn/tunnel/IcmpHandler.kt` only answers ICMP inside the tunnel.

### 6. Existing Top-30 implementation
- Builder: `tools/aggregator/scripts/pipeline.py` `MAX_PUBLISHED = 30`, `select_diverse()`; workflow
  `.github/workflows/free-configs.yml` every 6 h, publishes to the `free-configs` branch.
- App: `vpn/hub/FreeConfigList.kt` `MAX_CONFIGS = 30` in `prepare()`, plus `surplus()` for older installs.
- Diversity (builder): caps per failure domain (3), per protocol/transport/security kind (10), per source (12),
  CDN or direct share (70 %), one IPv4 and one IPv6 seeded first; caps relaxed in steps when the list is short.
  Failure domain = CDN name, IPv4 /16, IPv6 /32 or registrable domain.
- One public list only. No per-operator or per-protocol lists exist.

### 7. Existing scoring system
Two scorers, unrelated to each other:
- `vpn/scoring/ScoringEngine.kt`: benchmark score for the Benchmark screen; BALANCED is 45 % speed and 30 %
  latency, so latency/speed dominate there.
- `vpn/hub/FreeConfigEvidence.kt` `FreeConfigScore`: mirrors the builder's weights (reliability 30, stability
  15, http 10, security 15, latency 10, iran 15, history 5). Latency counts little; a stable 250 ms server
  beats an unstable 80 ms one. Security is a weighted part, not an override; insecure configs are kept out
  earlier by `FreeConfigList.usable()` / `ConfigValidationPipeline.securityProblem()`.
- Builder score: `tools/aggregator/scripts/pipeline.py` `score()`, with reasons per part.

### 8. Existing Last-Known-Good behavior
`vpn/hub/LastKnownGood.kt` `LastKnownGoodPool`: up to 3 free configs that carried verified traffic on this
phone; the active one never leaves; others leave only when a newer verified one replaces them or local evidence
says they are dead. Persisted as JSON (fingerprint, id, time, count, RTT). Tests: `LastKnownGoodTest`.

### 9. Existing atomic refresh behavior
`vpn/hub/LastKnownGood.kt` `FreeListSwap.plan()` computes delete/insert/retained/kept; applied in one database
transaction by `vpn/subscription/SubscriptionManager.kt`. Configs dropped by the new list but in use, protected
or favourite are retained (`RetainedFreeConfigs`) and released after disconnect. A failed refresh keeps the old
list and shows a notice (`vpn/subscription/FreeRefreshProgress.kt`). Tests: `FreeListSwapIntegrationTest`,
`FreeConfigListTest`, `SubscriptionManagerResilienceTest`.

### 10. Existing signed publication
`vpn/hub/HubManifest.kt`: ECDSA P-256 signature over `manifest.json`, which names each file's SHA-256. Public
key comes from the HUB_SIGNING_KEY secret at build time; a build without it refuses every list. Every mirror
(raw GitHub, jsDelivr, Statically, Githack) is checked the same way (`FreeConfigList.download()`).
Tests: `HubManifestTest`, `FreeConfigHubTest`, `tools/aggregator/tests/test_aggregate.py`.

### 11. Existing DNS implementation
- Xray: FakeDNS pools (198.18.0.0/15, fc00::/18 when IPv6 is on), `queryStrategy UseIPv4` when IPv6 is off
  (`xray/XrayConfigBuilder.kt`).
- Resolvers must be IP literals or known DoH names rewritten to IPs: `vpn/safety/DnsResolvers.kt`.
- Server name resolution falls back to DoH (1.1.1.1 / 8.8.8.8) when the ISP's DNS returns a block-page or
  private answer: `vpn/EndpointResolver.kt`.
- GOD MODE: server names only over DoH.
- Missing: resolver ranking by real queries, timeout/NXDOMAIN-anomaly measurement, DNS-through-tunnel check as a
  probe stage, a DNS resilience profile.

### 12. Existing IPv6 handling
- TUN always routes `::/0` (`vpn/RayVpnService.kt` ~line 632), so IPv6 cannot bypass the VPN; when IPv6 is off
  Xray sends it to a blackhole outbound (`XrayConfigBuilder`, `private-ipv6-block`). The holding (fail-closed)
  TUN also captures `::/0` (~line 987).
- Builder classifies each candidate's address family and seeds one of each.
- Missing: independent IPv6 measurement and scoring on the phone; explicit IPv6 leak regression test.

### 13. Existing kill switch
Fail-closed holding TUN (`RayVpnService` ~line 985: blocking TUN that captures IPv4, IPv6 and DNS) and
`vpn/safety/FailClosedPolicy.kt` (only an invalid profile started by the user in DAILY mode releases the block).
Always-on / lockdown detection and advice: `vpn/safety/MaximusVpnSupervisor.kt`. Tests: `VpnSupervisorTest`,
`VpnReliabilityLifecycleTest`, `SecurityAndNetworkingRemediationTest`.

### 14. Existing connection state machine
`data/model/ConnectionState.kt`: DISCONNECTED, PREPARING, CONNECTING, VPN_INTERFACE_ESTABLISHED, ENGINE_STARTED,
PROXY_CONNECTING, VERIFYING, TUNNEL_STARTED_CONNECTIVITY_UNVERIFIED, CONNECTED, DEGRADED, RECONNECTING,
DISCONNECTING, FAILED. CONNECTED only after a real request through the tunnel (`ConnectionVerification`), with
`sessionId`, `attemptId`, `networkGeneration` and `passKey` so stale results are ignored. Missing compared with
the spec: SWITCHING and NETWORK_CHANGED as explicit states (network change exists as a generation counter).
Tests: `ConnectionVerificationTest`.

### 15. Existing crash/ANR diagnostics
`vpn/diagnostics/events/RuntimeHealth.kt`: reads `ApplicationExitInfo` on launch (crash, native crash, ANR, low
memory, signalled...), main-thread stall watchdog (2 s), memory pressure, native engine exits; events in
`EventLog`, redaction by `core/SecretRedactor.kt` and the report exporter. Tests: `RuntimeHealthTest`,
`DiagnosticReportTest`, `SecretRedactorTest`.

### 16. Existing relevant tests
61 JVM test files (`app/src/test`), 1 instrumented. Relevant: ConnectionVerificationTest, LastKnownGoodTest,
FreeListSwapIntegrationTest, FreeConfigListTest, FreeConfigEvidenceTest, HubManifestTest, FreeConfigHubTest,
RealDelayProbeTest, DnsPathProbeTest, NetworkEnvironmentTest, SmartConnectRaceTest, BpbFixTest,
EdgeOptimizerTest, WarPlanEnginesAndStealthTest, VpnSupervisorTest, VpnReliabilityLifecycleTest,
PrivateDnsAndSecurityTest, SecurityBaselineTest, RuntimeHealthTest, SecretRedactorTest. Builder: 3 Python test
files. Bot: `tools/telegram-bot/test.mjs`. Censorship simulator: `tools/censorsim`.

### 17. Current CI state
`.github/workflows/checks.yml` runs on every push and PR: unit tests, lint (release), aggregator tests, bot
tests, secret scan. Latest run on main (`eddcb16`, run 66): green. Free config list run 10 was in progress
(started 12:16 UTC by the owner); runs 6 to 9 green.

### 18. Discrepancies between the spec and the repository
1. Lifecycle names: the code uses `IRAN_PROBATION` / `IRAN_VERIFIED` (`vpn/hub/FreeConfigEvidence.kt`). The spec
   asks for `LOCAL_PROBATION` / `LOCAL_NETWORK_VERIFIED`, and forbids implying Iran-wide validity. The screen
   labels already say "on your network". Missing states: `GLOBAL_FAILED` (exists only as `GlobalStatus`),
   `RECOVERY`, `SECURITY_REJECTED` (folded into `QUARANTINED` together with "unsupported").
2. Failure taxonomy: no `DNS_RESPONSE_INVALID`, `CERTIFICATE_VALIDATION_FAILED` (merged into TLS),
   `DNS_TUNNEL_FAILED`, `SECURITY_REJECTED`; `AUTH_FAILED` is `PROXY_AUTH_FAILED`, `HTTP_CONNECTIVITY_FAILED` is
   `HTTP_REQUEST_FAILED`, `PROTOCOL_HANDSHAKE_FAILED` is `PROXY_HANDSHAKE_FAILED`.
3. `fingerprint: unsafe`: checked in Xray-core v26.9.9 source (`infra/conf/transport_security.go`,
   `transport/internet/tls/tls.go`, `config.go`). "unsafe" is not a uTLS preset; it makes Xray use Go's own
   `crypto/tls` so custom cipher suites apply. Certificate chain and hostname verification are unchanged
   (`RootCAs`, `verifyPeerCert`); `allowInsecure` was removed from Xray and is refused. REALITY refuses "unsafe"
   (the builder maps it to chrome). Conclusion: it does not weaken authenticity, so it is not a security
   rejection; its cost is a Go-TLS ClientHello that is easier to identify. FIX BPB uses it today.
4. FIX BPB (`panels/BpbFix.kt`, applied from `ui/panels/PanelManagerViewModel.kt`) overwrites the saved config
   after a real-request test. The spec requires the original to stay immutable with derived candidates.
5. ECH: Xray v26.9.9 does support client ECH (`echConfigList`, `echSockopt`), and `vpn/stealth/StealthVariants.kt`
   already offers an ECH variant fetched over DoH. The phone never measures ECH capability.
6. Stealth variants (`StealthVariants`, `StealthPathFinder`) already derive fragment / fingerprint / ECH / noise
   alternates without changing the saved profile and pick by real request, remembered per network
   (`vpn/smart/NetworkMemory.kt`). They have no version, expiry, success/failure statistics or rollback record.
7. Two scoring systems (benchmark vs free list); neither has a hard security override nor address-family /
   transport diversity on the phone.
8. Failover (`vpn/smart/FailoverManager.kt`, `WatchPolicy`): 3 failed real checks, then switch with a 20 to 25 s
   cooldown, family-aware. No exponential backoff and no explicit Primary / Backup A / Backup B model.
9. Probe budget: limits exist per site (libXray batch 5, "Test all" one at a time, 512 sessions per relay), but
   no central, configurable budget and no metered/battery rule.
10. Iran intelligence: the builder takes per-fingerprint phone evidence (`aggregate.py` state "iran"), with no
    TTL, confidence or rule model. No Telegram/channel intelligence pipeline exists, which is safe.
11. The in-app AI agent (`ai/AiAgentTools.kt`) exists already; Stage 12 must check what its tools may change.

### 19. Components that can be reused
ConnectionState + ConnectionVerification (Stage 2), FailureStage (Stage 1, extend), RealDelayProbe and
LiveTunnelProbe (Stage 1 stages), NetworkCapabilityDetector/Profile (Stages 6 and 10), FreeConfigEvidence and
FreeConfigScore (Stage 3), StealthVariants + StealthPathFinder + NetworkMemory (Stages 4 and 5), CleanIpOptimizer
(endpoint hints, Stage 10), select_diverse (Stage 7), LastKnownGoodPool + FreeListSwap + RetainedFreeConfigs
(Stage 8), FailoverManager + WatchPolicy + ServerRace (Stage 9), DnsResolvers + EndpointResolver (Stage 10),
HubManifest (Stage 11 must go through it), RuntimeHealth + EventLog + SecretRedactor (observability).

### 20. Components that need implementation
- `MultiProbeHealthEngine` with a `ProbeResult` record per stage, combining the existing probes (Stage 1).
- Missing failure codes and lifecycle names (Stages 1 and 3), done as additions with old stored names still read.
- SWITCHING / NETWORK_CHANGED connection states and live-PASS invalidation rules (Stage 2).
- `ConnectionScore` with security override, freshness and diversity inputs (Stage 3).
- `RecoveryProfile` model and `BpbRecoveryEngine` producing immutable `DerivedRecoveryCandidate`s (Stage 4).
- `TlsResilienceEngine` (security gate for TLS options, ECH only where Xray supports it) and
  `FragmentProfileEngine` (versioned, bounded, auto-revert) (Stage 5).
- `TransportCapabilityEngine` deciding UDP/H3 vs TCP/H2 and IPv4/IPv6 candidates (Stage 6).
- Phone-side diversity check inside the one Top-30 list (Stage 7).
- LKG replacement only after a validated replacement; explicit "retained" refresh message (Stage 8, mostly done).
- `SmartFailoverManager` policy: hysteresis, exponential backoff, Primary/Backup A/B metadata (Stage 9).
- `DnsResilienceEngine` / `DnsResilienceProfile`, `EndpointScoringEngine` (Stage 10).
- Iran intelligence rules with TTL, confidence and bounded adjustment (Stage 11).
- `ProbeBudget` (Stage 1 / P1.9).
- AI layer guard (Stage 12), only after the rest passes.

---

## Tasks

| ID | Task | Status |
|---|---|---|
| MX-IR-P00-T01 | Stage 0 audit | COMPLETED |
| MX-IR-P01-T01 | Multi-probe engine and probe results | TESTING |
| MX-IR-P02-T01 | Connection state correctness | TESTING |
| MX-IR-P03-T01 | Connection score and candidate states | TESTING |
| MX-IR-P04-T01 | BPB recovery engine | TESTING |
| MX-IR-P05-T01 | TLS / ECH / fragmentation profiles | NOT_STARTED |
| MX-IR-P06-T01 | Transport capability engine | NOT_STARTED |
| MX-IR-P07-T01 | Top-30 diversity on the phone | NOT_STARTED |
| MX-IR-P08-T01 | Last-Known-Good and atomic refresh | NOT_STARTED |
| MX-IR-P09-T01 | Smart failover | NOT_STARTED |
| MX-IR-P10-T01 | DNS resilience and endpoint scoring | NOT_STARTED |
| MX-IR-P11-T01 | Iran intelligence | NOT_STARTED |
| MX-IR-P12-T01 | AI layer | NOT_STARTED |
| MX-IR-P13-T01 | Real-device validation (owner, on phones in Iran) | BLOCKED |

### MX-IR-P00-T01 Stage 0 audit
- Status: COMPLETED
- Files changed: this file only
- Architecture impact: none
- Tests run: none needed (read-only); CI on main green (Checks run 66)
- Known limitations: audit read from code; nothing verified on a phone
- Security impact: none
- Next action: Stage 1, MultiProbeHealthEngine

### MX-IR-P01-T01 Multi-probe engine and probe results
- Status: TESTING (local JVM tests pass; full Android CI pending)
- Files changed: `vpn/connectivity/ProbeResult.kt`, `vpn/connectivity/ProbeBudget.kt`,
  `vpn/connectivity/MultiProbeHealthEngine.kt`, `vpn/connectivity/ProbeEvents.kt`,
  `vpn/diagnostics/FailureStage.kt` (new codes DNS_RESPONSE_INVALID, CERTIFICATE_VALIDATION_FAILED,
  DNS_TUNNEL_FAILED, SECURITY_REJECTED and `taxonomyName` for the spec's names), `vpn/RayVpnService.kt`
  (post-connect verification runs through the engine).
- Architecture impact: one engine runs ordered steps (DNS, DNS query, TCP, TLS, protocol, engine, TUN,
  HTTP through tunnel, DNS through tunnel, stability) supplied by the caller; returns `ProbeResult` per step
  and a `HealthReport` (verdict, failed step, stage, OBSERVATION text, ASSESSMENT text). ICMP is not a step.
  Per-step and per-config time limits, bounded retries with exponential backoff, no retry of security,
  certificate or login failures. A run overtaken by a new attempt or network change is `stale` and never a
  pass. `ProbeBudget` holds the concurrency, timeout, retry, metered and battery limits (P1.9).
  The post-connect check keeps its old behaviour (3 tries, 1.5 s apart) and now logs each step.
- Tests run: `MultiProbeHealthEngineTest` (16): ICMP-free health, TCP ok/TLS fail, refused certificate not
  retried, TLS ok/protocol fail, tunnel up/HTTP fail, HTTP ok/DNS-tunnel fail, network change mid-probe,
  stale rejection, step timeout inside the config budget, flaky retry, security refusal before any network
  step, stability, result privacy, backoff, metered/battery budget, certificate texts.
- Test result: 16/16 pass in a JVM harness (pure sources); full `testDebugUnitTest` and lint run in CI.
- Known limitations: pre-connect tests still use `RealDelayProbe` directly (they already measure a real
  request); their stages will be expressed as engine steps where Stage 6/10 need per-step data.
- Security impact: none weakened; adds a security gate hook that stops a run before any network step.

### MX-IR-P02-T01 Connection state correctness
- Status: TESTING (local JVM tests pass; full Android CI pending)
- Files changed: `data/model/ConnectionState.kt` (SWITCHING, NETWORK_CHANGED, `onNetworkChanged`, `onSwitching`),
  `vpn/RayVpnService.kt` (network callbacks and failover use them), `ui/components/CyberComponents.kt`,
  `vpn/tile/VpnTileService.kt` (labels for the new states).
- Architecture impact: the existing chain DISCONNECTED → PREPARING/CONNECTING → VPN_INTERFACE_ESTABLISHED →
  ENGINE_STARTED → VERIFYING → CONNECTED stays. New: when the phone's network changes under a verified tunnel
  the status becomes NETWORK_CHANGED (tunnel still up, traffic still inside it, old PASS cleared) until a
  request through the tunnel passes again (rechecked every 4 s). When failover starts, the status becomes
  SWITCHING and late checks of the old tunnel are ignored. Old results stay in the event log as history.
- Tests run: `ConnectionStateCorrectnessTest` (8) and the existing `ConnectionVerificationTest`.
- Test result: 8/8 pass in the JVM harness; full suite in CI.
- Known limitations: behaviour on a real handover (Wi-Fi to cellular) is untested on a phone.
- Security impact: none; the kill switch and routes are unchanged (the tunnel is not torn down on a network change).

### MX-IR-P03-T01 Connection score and candidate states
- Status: TESTING (local JVM tests pass; full Android CI pending)
- Files changed: `vpn/connectivity/ConnectionScore.kt` (new), `vpn/hub/FreeConfigEvidence.kt` (states renamed
  and extended), `ui/freeconfigs/FreeConfigsContent.kt`, `ui/freeconfigs/FreeConfigsState.kt`, tests using the names.
- Architecture impact: candidate states are now NEW, GLOBAL_VERIFIED, GLOBAL_FAILED, LOCAL_PROBATION,
  LOCAL_NETWORK_VERIFIED, DEGRADED, RECOVERY, DEAD, QUARANTINED, SECURITY_REJECTED. Stored IRAN_PROBATION /
  IRAN_VERIFIED still load (mapped to the LOCAL_ names). SECURITY_REJECTED and QUARANTINED are sticky.
  `ConnectionScore` ranks with explained parts (stages 20, recent 20, history 10, stability 15, latency 8,
  DNS-in-tunnel 5, network match 7, local sessions 10, security mode 5), ages local evidence (6 h half-life),
  and refuses security-rejected, quarantined, dead and globally failed candidates outright. A bounded
  adjustment slot (±5) is reserved for Stage 11. `FreeConfigScore` (the mirror of the builder's score) is unchanged.
- Tests run: `ConnectionScoreTest` (11), existing `FreeConfigEvidenceTest`, `ConnectionVerificationTest`.
- Test result: all pass in the JVM harness; full suite in CI.
- Known limitations: the score is used by the selector and failover from Stages 7 and 9 on.
- Security impact: security refusal now overrides any positive score by construction.

### MX-IR-P04-T01 BPB recovery engine
- Status: TESTING (local JVM tests pass; full Android CI pending)
- Files changed: `vpn/connectivity/RecoveryProfile.kt` (model + 10 reviewed built-in profiles, expiring
  every 120 days), `vpn/connectivity/RecoverySecurityGate.kt`, `vpn/connectivity/BpbRecoveryEngine.kt`
  (`DerivedRecoveryCandidate`, `FieldChange`, `BpbRecoveryEngine`, `RecoveryLedger`), `RayApplication.kt`,
  `vpn/RayVpnService.kt` (step 3a), `ui/panels/PanelManagerViewModel.kt` (FIX BPB).
- Architecture impact: original → failure classification → strategies → derived copies (one recovery profile
  each, only finalMask / fingerprint / ALPN / cipher list / ECH / endpoint address may change) → security
  gate → real-request test → ledger by original config, profile, endpoint and network. Not attempted after a
  refused certificate, failed login, proxy-protocol error or security refusal. Connect tries a candidate that
  worked on this network first (one request), then the existing stealth/clean-IP search.
  FIX BPB no longer overwrites saved configs: it tests derived copies, records the winner, and offers it to the
  worker's other TLS configs. Rollback: 2 failures in a row after working withdraw a candidate; a profile that
  failed 3 times on a network without working is not offered there again (until expiry). Configs changed by
  earlier FIX BPB versions keep their saved settings.
- Tests run: `BpbRecoveryEngineTest` (11): unrecoverable failures, bounded safe candidates with the original
  untouched, blocked endpoint with validated IPv4/IPv6 alternatives, gate refusals (private endpoint,
  allowInsecure, downgrade, SNI change, pin removal, unknown fingerprint, weak cipher, non-IP ECH resolver,
  unbounded or unknown mask), refused candidates never tested, ECH candidate, success then rollback,
  never-working profile retired, expiry, ledger persistence without credentials, identity fields locked.
- Test result: 11/11 pass in the JVM harness; full suite in CI.
- Known limitations: endpoint alternatives need locally validated addresses (Stage 10 supplies them; until
  then the existing clean-IP scan in the connect path covers that case). Not tested against a real BPB worker
  from Iran.
- Security impact: tightens FIX BPB (no untested settings applied, no saved config rewritten). "unsafe"
  fingerprint verified to keep certificate checks in Xray v26.9.9.
