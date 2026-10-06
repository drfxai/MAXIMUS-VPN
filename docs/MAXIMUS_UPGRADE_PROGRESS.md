# Maximus upgrade: implementation progress

Read this file before doing anything else when resuming the upgrade. It is updated after every
milestone. Branch: `feature/maximus-upgrade-7ooxpw` (from `main` 2bdf976).

## Current phase

Phases 0, 1 and 2 implemented; CI (checks.yml) is the build of record for them. Next: Phase 3.

## Exact next action

Phase 3: add the intermediate connection states (ENGINE_STARTED, VERIFYING,
TUNNEL_STARTED_CONNECTIVITY_UNVERIFIED, DEGRADED) to `data/model/ConnectionState.kt`, verify real
traffic through the tunnel in `RayVpnService.connectLocked` before CONNECTED, and from there record
verified free sessions into the last-known-good pool and evidence store, and call
`SubscriptionManager.releaseRetained()` after disconnect.

## Phase 0: inspection (done)

| Area | Where |
|---|---|
| Android app module | `app/` (namespace `com.example`, applicationId `com.drfxai.maximusvpn`) |
| VPN service / tunnel | `vpn/RayVpnService.kt` (connect state machine, `connectLocked`), `vpn/VpnController.kt`, `vpn/TunnelManager.kt`, `vpn/engine/*`, `xray/XrayEngine.kt`, sidecars in `vpn/sidecar/*` |
| Fail-closed / modes | `vpn/safety/{FailClosedPolicy,MaximusVpnSupervisor,OperatingModePolicy}.kt` |
| Smart connect / failover | `vpn/smart/{ServerRace,FailoverManager,NetworkMemory,NetworkKey,NetworkEnvironment}.kt`, `vpn/stealth/*` |
| Free config storage | Room `server_profiles` (DB v8, `data/database/ServerProfileEntity.kt`); free servers carry `sourceSubscription` = `FreeConfigList.URL`. Logic: `vpn/hub/FreeConfigList.kt`, sync in `vpn/subscription/SubscriptionManager.kt` (`syncSubscription`, `pruneFreeList`, `setFreeList`) |
| Free configs UI | `ui/freeconfigs/*`; "ALL NODES · n / Ping All" header in `ui/servers/ServersScreen.kt` |
| Free config generation | `.github/workflows/free-configs.yml` every 6 h → `tools/aggregator/scripts/aggregate.py` + `verify.py` (pinned Xray, real requests) → signed list on branch `free-configs` |
| Telegram bot / backend | `tools/telegram-bot/worker.js` (Cloudflare Worker, KV `STORE`, Gemini admin assistant from PR #22, merged) |
| Gemini integration | App: `ai/AiAgentManager.kt`, `ai/GeminiModels.kt`, key stored on device by the AI Agent setup. Bot: `/setkey` stores the key in KV |
| Version | `app/build.gradle.kts`: versionName `1.0.0`, versionCode `26`; `release-version.txt` = `V1.0.0` |
| Diagnostics / logging | `xray/XrayLogManager.kt` (500-line in-memory ring, redacted), `core/SecretRedactor.kt`, `ui/viewmodel/DiagnosticsViewModel.kt`, `ui/diagnostics/DiagnosticsScreen.kt`, `vpn/diagnostics/*` |
| Config models | `data/model/VlessProfile.kt`, `ConnectionState.kt`, `CanonicalFingerprint.kt`, `AppModels.kt` |

### Build environment

The cloud sandbox cannot run the Android Gradle build (Google Maven is blocked). Authoritative builds:

- `.github/workflows/checks.yml` runs on every push: `:app:testDebugUnitTest`, `:app:lintRelease`,
  aggregator tests, and the secret/assistant-name hygiene scan.
- `.github/workflows/release.yml`, dispatched with a dry-run tag (for example `dry-run-check`),
  builds the debug and release APKs and runs the emulator test without publishing.
- Locally: aggregator tests (`python3 -m unittest discover -s tools/aggregator/tests`), bot tests
  (`node tools/telegram-bot/test.mjs`), hygiene (`python3 scripts/check-repo-hygiene.py`), and a
  scratch Kotlin/JVM project for pure-Kotlin classes.

### Baseline (before any change)

| Check | Result |
|---|---|
| Checks run 37451055287 on main 2bdf976 (unit tests, lint, aggregator, hygiene) | green |
| Release run 37451065332 on main 2bdf976 (APKs) | green |
| Aggregator tests (local) | 26 passed, 2 skipped |
| Telegram bot tests (local) | all passed |
| Hygiene scan (local) | 323 files, 0 problems |

### Results after Phases 1-2

| Check | Result |
|---|---|
| Aggregator tests (local, with the pinned Xray core) | 45 passed |
| Pure Kotlin tests on the JVM rig (FreeConfigEvidenceTest, LastKnownGoodTest) | 18 passed |
| Hygiene scan | 0 problems |
| CI checks.yml | see the run for this commit |

### Findings that shape the plan

- `connectLocked` sets `CONNECTED` right after the TUN loop starts; no traffic is verified (Phase 3).
- The free list refresh already inserts before pruning and keeps the selected server, but it
  deletes and inserts in separate statements and has no last-known-good pool (Phase 2).
- The aggregator already caps the list at 30 and verifies with real requests, but ranks by
  YouTube/Telegram/X reach and latency, and the app shows those tags as if they were proof of use (Phase 1).
- Logs are free text only, lost on restart, with no correlation ids (Phase 4).

## Completed tasks

- Phase 0 inspection and baseline.
- Phase 1 (aggregator): source metadata (`source_meta` in the manifest report, ids in
  `sources.json`); normalized `Candidate` (`scripts/pipeline.py`); extra security checks (VMess
  credentials and encryption, unsupported transports, `fp=unsafe`, `verify=0`, non-AEAD Shadowsocks,
  obfuscated or private IPv6/IPv4 addresses); quarantine records with reasons and no credentials;
  DNS and TCP stage measurement; three real-request rounds per server (`verify.py`); statuses
  GLOBAL_VERIFIED / GLOBAL_FAILED and UNKNOWN_IRAN_STATUS; deterministic score with reasons;
  diverse selection of at most 30 (failure domain, kind, source, CDN share, address family);
  `configs.json` and `history.json` published and signed with the list; `quarantine.json` kept as a
  workflow artifact.
- Phase 1 (app): `FailureStage`; `NetworkCapabilityProfile` + `NetworkCapabilityDetector`
  (measured on the physical network, unmeasured fields stay null); `FreeConfigLifecycle`
  (NEW → GLOBAL_VERIFIED → IRAN_PROBATION → IRAN_VERIFIED / DEGRADED / DEAD / QUARANTINED),
  `FreeConfigLifecycleRules`, `FreeConfigEvidenceStore` (per fingerprint, bounded 300, persisted),
  `FreeConfigScore`. Free Configs tests now file each result as evidence.
- Phase 2: `LastKnownGoodPool` (3 entries: active + two best recent; eviction only by a newer
  verified config or dead evidence), `FreeListSwap.plan` + `ServerProfileDao.replace` (one Room
  transaction), `RetainedFreeConfigs`, `SubscriptionManager.swapFreeList / deleteAllFree / deleteFree /
  releaseRetained`. The refresh never deletes protected or in-use configs and never empties the list.

## Pending tasks

- Phase 1 UI wording: the Free Configs screen must call the YT/TG/X badges a global check (outside
  Iran) and show the lifecycle label; goes with the Phase 6/7 previews.
- Phases 3 to 11.

## Files changed

- `docs/MAXIMUS_UPGRADE_PROGRESS.md` (this file)
- Aggregator: `tools/aggregator/scripts/{aggregate,verify,pipeline}.py`, `tests/test_pipeline.py`,
  `sources/sources.json`, `README.md`; `.github/workflows/free-configs.yml`
- App: `vpn/diagnostics/FailureStage.kt`, `vpn/smart/NetworkCapability{Profile,Detector}.kt`,
  `vpn/hub/FreeConfigEvidence.kt`, `vpn/hub/LastKnownGood.kt`, `vpn/subscription/SubscriptionManager.kt`,
  `data/database/ServerProfileDao.kt`, `data/repository/ServerRepository.kt`, `RayApplication.kt`,
  `ui/freeconfigs/FreeConfigs{State,ViewModel}.kt`
- Tests: `FreeConfigEvidenceTest`, `LastKnownGoodTest`, `FreeListSwapIntegrationTest` (Robolectric,
  scenarios A to D)

## Known issues

- No on-device test is possible from the cloud sandbox; device scenarios are covered by JVM tests of
  the pure logic and must be confirmed on a phone.

## Migration notes

- No database schema change (still version 8). New SharedPreferences files:
  `free_config_evidence`, `free_last_known_good`, `free_retained`.
- The free-configs branch gains `configs.json` and `history.json` (listed and hashed in the signed
  manifest). Older app builds ignore them.
- A server must now pass 2 of 3 rounds instead of 2 of 2.
