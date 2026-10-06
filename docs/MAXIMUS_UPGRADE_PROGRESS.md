# Maximus upgrade: implementation progress

Read this file before doing anything else when resuming the upgrade. It is updated after every
milestone. Branch: `feature/maximus-upgrade-7ooxpw` (from `main` 2bdf976).

## Current phase

Phase 0 complete. Next: Phase 1 (Iran-optimized free config pipeline).

## Exact next action

Start Phase 1.1: add source metadata (source_id, last_fetch_time, fetch_status, candidate_count,
valid_count) and the normalized candidate format to `tools/aggregator/scripts/aggregate.py`.

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

### Findings that shape the plan

- `connectLocked` sets `CONNECTED` right after the TUN loop starts; no traffic is verified (Phase 3).
- The free list refresh already inserts before pruning and keeps the selected server, but it
  deletes and inserts in separate statements and has no last-known-good pool (Phase 2).
- The aggregator already caps the list at 30 and verifies with real requests, but ranks by
  YouTube/Telegram/X reach and latency, and the app shows those tags as if they were proof of use (Phase 1).
- Logs are free text only, lost on restart, with no correlation ids (Phase 4).

## Completed tasks

- Phase 0 inspection and baseline.

## Pending tasks

Phases 1 to 11 as written in the plan (thread "Maximus upgrade").

## Files changed

- `docs/MAXIMUS_UPGRADE_PROGRESS.md` (this file)

## Known issues

- No on-device test is possible from the cloud sandbox; device scenarios are covered by JVM tests of
  the pure logic and must be confirmed on a phone.

## Migration notes

None yet.
