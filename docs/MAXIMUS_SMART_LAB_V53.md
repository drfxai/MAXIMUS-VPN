# Autonomous Smart LAB V5.3: unified international egress recovery

Version stays **1.0.1 / 28**. No release or APK was built or replaced by this work.

## What changed

| Area | Change | Where |
|---|---|---|
| Shared evidence | One evidence book per network session (`CurrentNetworkSession`, `NetworkEvidence`, `NetworkAssessment`, `RecoveryDecision`). Every real test in the app (LAB, Smart Connect, failover race, server tests) reaches it through `RealDelayProbe.observer`. A network change starts a new session. | `vpn/connectivity/ConnectivityBrain.kt` |
| Path identity | `PathRef` = the saved config's identity + its variant fields (fragment, fingerprint, ECH, MTU, ALPN). A failed ECH or MTU copy never marks its saved config as failed. Resolved-address copies keep the same identity. | `ConnectivityBrain.kt`, `RayVpnService.resolveEndpoint`, `RayApplication` |
| Fresh-evidence eligibility | FRESH_VERIFIED → FRESH_CANDIDATE → UNTESTED → STALE_VERIFIED. RECENTLY_FAILED (a real request failed on this network in the last 10 minutes) is never started automatically: failover, the Smart Connect race and the LAB all skip it. | `EvidenceBook.eligibility`, `FailoverManager`, `RayVpnService.raceServers` |
| No blind connect | A server that was not tested gets one real request before the tunnel starts; a selected server that failed here moments ago is not retried (the user can still choose "Connect anyway"). | `RayVpnService.connectLocked` |
| Smart Connect → LAB fast recovery | When the selected server and the race find nothing, the connect escalates to the LAB's fast recovery (CONNECT goal): every other saved family plus the built-in Psiphon and Tor engines this build carries, stopping at the first path that carries a real request (90 s limit). Engine tests run in an "engine window" in which the blocking interface leaves only the app's own sockets out. | `LabController.fastRecovery`, `RayVpnService.engineWindow` |
| Recovery stages | INITIAL → ORDINARY_PREFLIGHT → SAVED_PROFILE_RACE → SAFE_VARIANTS → RECOVERY_ENGINES → FIRST_VERIFIED_PATH → … → CONNECTED_VERIFIED, else NO_VERIFIED_EGRESS_AFTER_RECOVERY. Only forward moves; only the user can go to "Connect anyway". The stage is in `ConnectionState.recoveryStage`. | `RecoveryStage`, `RecoveryMachine` |
| Multi-target verification | Four independent HTTPS targets (Google, Cloudflare, Apple, Mozilla). Fast mode: a failure is retried once through a second provider unless the first target is known to be up. Full mode (`measureTargets`): 1 of 3 = verified but degraded; all failed = this candidate failed only. | `xray/ProbeTargets.kt`, `xray/RealDelayProbe.kt` |
| Signed probe manifest | `probes.json` is published by the aggregator next to the free list and listed (with its SHA-256) in the ECDSA-signed hub manifest. The app keeps the last valid one, refuses HTTP, a single failure domain or a TTL over 30 days, and falls back to the built-in targets. A missing manifest never blocks a connection. | `vpn/connectivity/ProbeManifest.kt`, `tools/aggregator` |
| Honest TLS/SNI labels | Ordinary TLS or REALITY failures are a **TLS path failure**. "SNI interference suspected" only comes from the controlled comparison (the same address with a filtered and a neutral name). `NetworkEnvironment` uses TLS_PATH_FAILURE and NO_VERIFIED_EGRESS (was SNI_FILTERED / BLACKOUT). | `NetworkStateClassifier`, `NetworkEnvironment`, `FailureClassifier` |
| Single flight | Every Connect, Disconnect or reconnect opens a new intent; a failover or auto-reconnect started under an older intent is dropped. One failover switch at a time, through `connectJob`. A second Disconnect joins the first. | `vpn/ConnectionLifecycle.kt`, `RayVpnService` |
| ECH | Measured, not assumed, and only when a TLS hypothesis exists. A derived copy fetches its ECHConfig over DoH only. With ECH configured, Xray/Go never fall back silently, so a pass proves ECH; a recheck after 3 s makes it verified. A failed ECH copy next to a working ordinary config is reported as "ECH failed; the ordinary path is used". | `vpn/lab/EchMeasurement.kt`, LAB "ECH" row |
| Adaptive MTU | Only on evidence (a WireGuard config failed while UDP answers). A bounded search (≤4 tests, floor first). A value is committed only after a second pass, for one network + engine + transport + address family, for 24 h; a connect that then fails with it forgets it. The saved config is never changed. | `vpn/lab/MtuIntelligence.kt`, LAB "MTU (WireGuard)" row, `RayVpnService` |
| DNS kept apart | System DNS, direct foreign DNS, DoH and DoT before connecting, and recursive DNS egress are separate facts. Recursive egress stays INFRASTRUCTURE_REQUIRED (it needs a controlled foreign zone). | `DnsEvidenceKind` |
| Resources | Each LAB report ends with a line: candidate tests, real requests, engine starts, data (app uid bytes) and elapsed time, counted separately. | `LabController.resourceLine` |
| Wording | "Every … failed. No software path can work here" became "No verified international egress after recovery … other methods or servers may still work." | `LabController` |
| Dependabot | `.github/dependabot.yml` was removed from main on request (commit b1ee458). | |

Security: no automatic change ever sets allowInsecure, disables certificate or hostname checks, edits UUIDs, passwords, keys or REALITY material, disables the kill switch or downgrades protected DNS. ECH and MTU copies keep every security field of their parent. AI remains advisory only (unchanged in this work).

## Section 34 report

**IMPLEMENTED**
- Evidence book, assessment, decisions, recovery stages, PathRef, fresh-evidence eligibility (P0)
- No blind connect: always a pre-flight, RECENTLY_FAILED skipped (P0)
- Smart Connect escalation to LAB fast recovery with built-in Psiphon/Tor and the engine window (P0/P1)
- Multi-target real-traffic verification (fast and full modes) (P0)
- Honest TLS/SNI classification across the LAB, NetworkEnvironment and FailureClassifier (P0)
- Single-flight connect/disconnect/failover/auto-reconnect (P0)
- ECH measurement states and the LAB experiment (P1)
- Adaptive MTU engine with transactions and per-key cache, used by the connect for WireGuard (P1)
- Shared network memory across LAB, connect and failover (P1); replanning skips recent failures (P1)
- Signed probe manifest (P2); DNS evidence model (P2)

**UNIT TESTED** (`SmartLabV53Test`, `NetworkEnvironmentTest`, updated `NetworkStateClassifierTest`, `AdaptiveLabTest`)
- Eligibility order and aging, network-session isolation, variant isolation
- TLS vs SNI assessment, UDP degradation, exhaustion only after recovery, no automatic Connect anyway
- Recovery ladder, single-flight lifecycle and teardown joining
- Multi-target verdicts, target outage fallback, known-up target saving a request, observer reporting
- ECH judge, copy security and DoH-only source, priority
- MTU search, transactions, per-key cache and expiry
- Probe manifest validation, expiry fallback, version rules
- Planner CONNECT goal and recently-failed candidates

**SIMULATOR TESTED** (`ScenarioSimulationTest`, a fake libXray answering per server and per target): DNS poisoning, SNI filtering with ECH, TLS cutoff, UDP block, QUIC blackhole, partial international access, IPv4/IPv6 asymmetry, recursive DNS only, MTU blackhole, loss, probe-target outage, network switch.

**EMULATOR TESTED**: none.

**DEVICE TESTED**: none. The fast recovery, engine window, ECH and MTU experiments have never run on a phone.

**FIELD TESTED**: none. Field matrix still owed (Samsung SM-F711B, Android 15, API 35):

| Network | Ordinary connect | Fast recovery | ECH | MTU | Psiphon/Tor |
|---|---|---|---|---|---|
| Irancell | not tested | not tested | not tested | not tested | not tested |
| MCI | not tested | not tested | not tested | not tested | not tested |
| Wi-Fi A | not tested | not tested | not tested | not tested | not tested |
| Wi-Fi B | not tested | not tested | not tested | not tested | not tested |

**DESIGN ONLY**
- Loss and jitter measurement beyond pass/fail patterns
- Remote egress observation through `locationUrl`
- Home screen display of the recovery stage (the state carries it; no UI yet)

**INFRASTRUCTURE REQUIRED**
- Recursive DNS egress (a controlled foreign authoritative zone)
- PATH_MTU_ESTIMATE (needs a measuring endpoint; never invented)
- Psiphon needs the PSIPHON_CONFIG secret from Psiphon Inc.; without it Psiphon is reported "not configured", never "failed"
- The Iran server / Tunnel provisioning feature (not implemented, by instruction)

**NOT IMPLEMENTED**
- CensorSim expansion inside `tools/censorsim` (the scenario test is a JVM unit-level simulation)
- Emulator tests
- DNS tunnel intelligence beyond the existing dnstt engine

## Limitations
- During an engine test in a connect, the blocking interface leaves the app's own sockets out for that test only; other apps stay blocked.
- Fast recovery is limited to 90 s and stops at the first path that carries a real request; deeper comparison stays in the LAB's Full Analysis.
- A probe target can be blocked on purpose by a network; with four providers in four failure domains the app still needs one to answer.
