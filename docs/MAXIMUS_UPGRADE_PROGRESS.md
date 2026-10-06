# Maximus upgrade: implementation progress

Read this file before doing anything else when resuming the upgrade. It is updated after every
milestone. Branch: `feature/maximus-upgrade-7ooxpw` (from `main` 2bdf976).

## Current phase

Phases 0 to 5 implemented. Next: Phases 6 and 7 (Free configs UI; previews for DrFX first).

## Exact next action

Phases 6 and 7: draw previews (Delete Free button next to "ALL NODES · n / Ping All" with the
confirmation dialog; per-config delete; refresh progress with pool/candidates/validated/retained/
removed/added/last update; "Refresh failed — existing verified configurations retained."; YT/TG/X
relabelled as a global check; lifecycle label) into `/mnt/project-files/previews/maximus-upgrade/`
and ask DrFX to approve. While waiting, do Phase 8 (quality hardening). The logic for 6/7 already
exists: `SubscriptionManager.deleteAllFree / deleteFree` and the `SyncResult` counts.

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
| CI checks.yml | run 37466933586 on dd58a8e (Phases 1-3): green |

### Results after Phase 5

| Check | Result |
|---|---|
| JVM rig (adds DiagnosticReportTest, 5 tests) | 41 passed |
| Hygiene scan | 0 problems |
| CI checks.yml | run 37468322849 on 1fa29eb failed: build script `java.time` reference and the secret scan on fake test tokens; both fixed in the next commit |

### Results after Phase 4

| Check | Result |
|---|---|
| JVM rig (adds DiagnosticEventsTest, 12 tests) | 36 passed |
| Hygiene scan | 335 files, 0 problems |
| CI checks.yml | see the run for this commit (adds RuntimeHealthTest, CI only) |

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

- Phase 3: `ConnectionStatus` gains ENGINE_STARTED, VERIFYING, TUNNEL_STARTED_CONNECTIVITY_UNVERIFIED
  and DEGRADED; `ConnectionState` carries sessionId, attemptId, selectedProfileId,
  attemptedProfileId, verifiedAt, failureStage, probeFailures, networkGeneration and `passKey`.
  `ConnectionVerification.afterCheck` (pure) decides every transition and ignores results of other
  attempts. `RayVpnService` verifies real traffic (`LiveTunnelProbe`, 3 tries) before CONNECTED,
  re-checks every 4 s while unverified and every 10 s when connected (2 failures → DEGRADED), bumps
  the network generation on network changes, records verified free sessions in the evidence store
  and last-known-good pool, and releases retained free configs after disconnect. Diagnostics drop a
  PASS when the pass key changes. UI words for the new states (button, badge, tile, home pill).

- Phase 4: `vpn/diagnostics/events/`: `DiagEvent` (OTel-shaped record: time, severity, name,
  session/attempt/test ids, hashed profile ref, engine, network bucket, attributes, duration,
  result, failure stage, exception, OBSERVATION/ASSESSMENT); `EventLog` (1000 in memory, JSON lines
  on disk rotated at 512 KB × 2, written by one background thread, restored at start, `truncated`
  flag, flushed on crash); `ErrorAggregator` (count, first/last, profiles, networks, sessions);
  `TestRegistry` (QUEUED/RUNNING/COMPLETED/FAILED/CANCELLED, cancel reasons, disconnect or a new
  session cancels the old session's tests and refuses their late results); `ConnectionEvents`
  (session/attempt start and end, every state change as an assessment, selected/attempted/active
  profiles apart, network changes); `RuntimeHealth` (ApplicationExitInfo with the ANR main-thread
  trace on Android 11+, main-thread stall watchdog at 2 s, memory pressure, sidecar and Xray
  engine termination). Verifiable PASS events from the tunnel verification and re-checks. Error and
  fatal log lines become events. Network signals (observation) and the filtering level
  (assessment, citing its signals) are separate events. Free Configs tests go through the registry.

- Phase 5: `vpn/diagnostics/report/DiagnosticReportBuilder.kt` (pure): a short summary (status,
  verified time, session/attempt/profile, network, earlier crash/ANR exits, top 5 failure groups,
  background test counts, last verified PASS, truncation note) and a JSON report (schema 1: app
  version/code/build commit/build time, Android, device model, engine and version, session,
  network profile and observations, error groups, tests, attempts, runtime exits, events, truncated
  flag; at most 400 events and 100 tests). `ReportRedaction.secondPass` runs on every string at
  export (bot tokens, Google API keys, e-mail, phone numbers, long tokens, IPv4 masked to a.b.x.x,
  IPv6). `ReportExporter` writes both on the IO dispatcher to `files/diagnostics/reports/` (newest 5
  kept) and opens the share sheet (FileProvider limited to that folder). BuildConfig `GIT_COMMIT` and
  `BUILD_TIME` come from CI (`GITHUB_SHA`), "unknown" locally. The existing Export Report button still
  copies the readable report and now also shares the two files; that report shows a hashed profile
  reference instead of the server address and gets the second redaction pass.

- Phase 8 (in progress): manager scopes (`core/AppScopes.kt` `managerScope`) with a SupervisorJob and a
  logging exception handler for FailoverManager, PsiphonConduitBridge, MaximusMeshManager and
  SecretChatManager (one failed task no longer silently kills the scope); the Quick Settings tile
  cancels its scope in `onDestroy` and stops any tunnel that is up (it used to try to connect again
  when the tunnel was up but unverified); server switch, node edit, panel message, AI diagnostics and
  the real-delay probe guard use "tunnel up" instead of "verified"; a mutex serializes free list
  refresh, Delete Free, single delete and the post-disconnect release; deleting one free config from
  the Servers list goes through `deleteFree` (leaves the last-known-good pool and its test
  metadata); the stall watchdog runs only while a screen is visible (no background wake-ups); when
  Android destroys the VPN service without a disconnect the state goes to DISCONNECTED instead of
  staying "up" (stale screens, tile and session tests). Reviewed and already bounded: Ping All
  (sequential), clean-IP scans (batches of 8/16), UDP DNS (32 slots), logs (500 lines), events (1000).
- CI fix: `app/build.gradle.kts` imports `java.time` (inside `android {}` `java` is the Gradle
  extension); the report test assembles its fake bot token and API key at run time so the secret
  scan passes.

- Phase 6 (DrFX marked the place on a screenshot, 13:11Z): "Delete Free" text button next to Ping
  All on the Servers screen (red, same style; shown only while free configs are listed, disabled
  during Ping All), the confirmation dialog with DrFX's exact wording and Cancel / Delete All,
  `ServerViewModel.deleteAllFree` → `SubscriptionManager.deleteAllFree` (free only; the running
  session keeps its in-memory copy; LKG pool, retained ids and test evidence cleared; event history
  kept). Single delete from a node's ⋮ menu uses `deleteFree` for free configs.

- Phase 7 (previews approved by DrFX 13:15Z): `SubscriptionManager.freeProgress` (DOWNLOADING →
  SWAPPING with candidate and valid counts; null when done) shown in the summary card with a
  "Refreshing" pill, step line, bar and "N still in use"; the card shows the lifecycle counts
  (verified here / checked outside Iran / on trial / failing) and the last refresh's added / kept /
  retained / removed; a failed refresh shows "Refresh failed — existing verified configurations
  retained." with the reason and "Last try failed …" and never touches the list. Rows show the
  lifecycle label, the YT·TG·X tag as "global", the site filter says "(global)", and a ⋮ menu has
  Test / Connect / Delete (`deleteFree`).

## Pending tasks

- Phases 6 to 11.

## Files changed

- `docs/MAXIMUS_UPGRADE_PROGRESS.md` (this file)
- Aggregator: `tools/aggregator/scripts/{aggregate,verify,pipeline}.py`, `tests/test_pipeline.py`,
  `sources/sources.json`, `README.md`; `.github/workflows/free-configs.yml`
- App: `vpn/diagnostics/FailureStage.kt`, `vpn/smart/NetworkCapability{Profile,Detector}.kt`,
  `vpn/hub/FreeConfigEvidence.kt`, `vpn/hub/LastKnownGood.kt`, `vpn/subscription/SubscriptionManager.kt`,
  `data/database/ServerProfileDao.kt`, `data/repository/ServerRepository.kt`, `RayApplication.kt`,
  `ui/freeconfigs/FreeConfigs{State,ViewModel}.kt`
- Phase 4: `vpn/diagnostics/events/{DiagEvent,EventLog,ErrorAggregator,TestRegistry,ConnectionEvents,RuntimeHealth}.kt`,
  `RayApplication.kt` (`startDiagnostics`), `RayVpnService.kt`, `xray/XrayLogManager.kt`,
  `vpn/sidecar/SidecarProcess.kt`, `ui/freeconfigs/FreeConfigsViewModel.kt`
- Phase 5: `vpn/diagnostics/report/{DiagnosticReportBuilder,ReportExporter}.kt`,
  `res/xml/diagnostic_paths.xml`, `AndroidManifest.xml` (FileProvider), `app/build.gradle.kts`
  (GIT_COMMIT, BUILD_TIME), `ui/viewmodel/DiagnosticsViewModel.kt`, `ui/diagnostics/DiagnosticsScreen.kt`
- Tests: `DiagnosticReportTest`, `DiagnosticEventsTest`, `RuntimeHealthTest`, `ConnectionVerificationTest`, `FreeConfigEvidenceTest`, `LastKnownGoodTest`, `FreeListSwapIntegrationTest` (Robolectric,
  scenarios A to D)

## Phase 10 findings (read-only so far)

Gemini key storage in `tools/telegram-bot/worker.js`, causes found:

1. Bare `/setkey` from the bot's command menu (Telegram sends menu commands at once, with no
   argument) is refused as "not a key"; the admin then pastes the key alone, which is not a command,
   so it goes to the AI chat path, is never stored, and stays visible in the chat. Most likely the
   reported "key does not save".
2. `aiKey()` prefers the `GEMINI_API_KEY` Worker secret over the stored key, so a stale secret
   silently overrides a newly saved key while `/setkey` still says "Key saved".
3. The key is stored in KV in plain text (`gemini_key`).
4. KV reads are cached at the edge, so a status check right after saving can still read the old value.

Plan: encrypt with AES-GCM under a Worker secret (`KEY_ENCRYPTION_SECRET`) before writing KV; never
return it to the browser (only Configured / Not configured / ••••ABCD); handle bare `/setkey` by
waiting for the next message and deleting it; stored key wins over the env secret, or the status says
which one is used; Save, Replace, Delete, Validate (a models.get call); model stored separately and
checked against the API's model list, with no silent substitution.

## Known issues

- Connection events come from a StateFlow collector, so two state changes within one frame can
  merge into one event (the final state is always recorded).

- No on-device test is possible from the cloud sandbox; device scenarios are covered by JVM tests of
  the pure logic and must be confirmed on a phone.

## Migration notes

- No database schema change (still version 8). New SharedPreferences files:
  `free_config_evidence`, `free_last_known_good`, `free_retained`, `runtime_health`.
- New files: `files/diagnostics/events/events.jsonl` and `events.1.jsonl` (at most about 1 MB);
  `files/diagnostics/reports/` (newest 5 exports).
- The free-configs branch gains `configs.json` and `history.json` (listed and hashed in the signed
  manifest). Older app builds ignore them.
- A server must now pass 2 of 3 rounds instead of 2 of 2.
