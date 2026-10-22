# Maximus V6 connectivity program: progress

This file tracks the V6.0 connectivity directive (2026-10-22) together with the earlier
Maximus Tunnel / Hiddify engineering prompt (2026-10-20). Where they differ, V6 wins. Every row
states its evidence level; nothing here is DEVICE_TESTED or FIELD_TESTED unless it says so.

## Baseline (before any V6 change)

| Item | Value |
|---|---|
| main HEAD | `b9bb535d74d38ebebe94372b8a7a1c08196d22f9` (merge of #104, 2026-10-19) |
| versionName / versionCode | `1.0.2` / `31` (app/build.gradle.kts; unchanged by this work) |
| Source size | 498 Kotlin files, 133 unit test files |
| Xray (app, libXray) | v26.9.9 (scripts/fetch-libxray-android.sh) |
| Xray (servers, server-tools) | pinned by SHA-256 in ToolScripts / tools/server-tools/SHA256SUMS |
| Mihomo | v1.19.32 |
| Psiphon | 0.0.14-beta pseudo-version, commit 4d6eb0e8a7e9 (2026-09-28) |
| dnstt | v1.20260501.0 (commit 0c5c52a5) + query-rate and multipath patches |
| Slipstream | commit 397850b1 (2026-04-11) |
| lyrebird | commit 75ef9b2c1f18 (2026-09-21) |
| usque (MASQUE) | v1.5.0 |
| MatrixTransport | in-repo fork (scripts/engines/matrix-transport.sh) |
| SOAR | not bundled, no source in the repo |

Already present before V6 (not rebuilt): build provenance fields `GIT_COMMIT` and `BUILD_TIME`
(BuildConfig); release APK engine gate `scripts/check-apk.py` (fails a release missing mihomo,
psiphon, dnstt, slipstream, lyrebird, tor, usque or matrixtransport); ConnectivityBrain, PathRef,
RECENTLY_FAILED eligibility and multi-target probes (Smart LAB V5.3, PR #50); per-network route
memory (#86); engine handoff from recovery to the connection (#101/#102).

## Confirmed defects found in the audit

1. **Tunnel setup reported success without proof (P0).** `TunnelManager.install()` ran the path
   check inside `runCatching { … }.getOrNull()`. If the check failed, timed out or found no
   working path, the setup still ended on a green "Tunnel is up".
2. **A path counted as working on one HTTP 204 alone.** Nothing showed that the request left
   through the server abroad rather than the Iran server.
3. **Reverse way "working" meant an open TCP connection.** Its status was the kernel round trip
   of the connection the server abroad holds open; no request ever crossed it.
4. **Port rotation had no rollback after the restart.** New port files were checked with
   `xray run -test`, but if Xray then failed to start, the server stayed down on the new files.
5. **The pre-setup reachability check only tested the peer's SSH port** and the setup screen
   called that "direct ways can be used".

## PR A: truthful Maximus Tunnel verification (#105, merged)

| Feature | Implemented | Unit | Simulator | Emulator | Device | Field | Limitation |
|---|---|---|---|---|---|---|---|
| Install ends in a verified/not-verified state (`TunnelHealth`) | yes | yes | no | no | no | no | UI wording only; no preview images yet |
| Path verified only with two targets + foreign exit IP (`PathState`) | yes | yes | no | no | no | no | probe shell function run once against a fake curl; exit compared with the Iran server's own addresses, not with the abroad server's IP |
| Reverse way: real request through a loopback test port | yes | yes (config + script syntax) | no | no | no | no | tunnels set up before this need one "Update tunnel" to get the test port; until then they show "Linked, no traffic" |
| Check repeated once after setup when nothing verified | yes | no | no | no | no | no | fixed 8 s wait |
| Rotation rolls back to the previous ports when Xray does not start | yes | script syntax only | no | no | no | no | a rotation still restarts Xray, so open sessions on the Iran side drop (see below) |
| Pre-check wording says "SSH port answers" | yes | n/a | no | no | no | no | ports of the tunnel itself cannot be tested before they exist |

States now used by the tunnel: `ROUTE_VERIFIED`, `DEGRADED`, `NO_EXTERNAL_ROUTE_OBSERVED`,
`FAILED`, `UNKNOWN` for the tunnel; `ROUTE_VERIFIED`, `NO_EXIT_PROOF`, `LINK_ONLY`, `FAILED` per
path. Only `ROUTE_VERIFIED` paths count as working, rank in "best" or show a latency.

The reverse test port listens on 127.0.0.1 only and routes only into the reverse link; any
local user on the Iran server could use it to send traffic out through the server abroad.

## PR B: one engine state for the LAB, diagnostics and the release check

`EngineRegistry` (what an engine is) now has `EngineLedger` (what it did on this phone) and
`EngineRegistry.statuses(network)`, which combines the build, this install's settings and fresh
evidence into one `EngineState`: UNSUPPORTED, NOT_BUNDLED, NOT_CONFIGURED, AVAILABLE, STARTED,
TRAFFIC_VERIFIED, DEGRADED, FAILED, UNKNOWN.

| Feature | Implemented | Unit | Simulator | Emulator | Device | Field | Limitation |
|---|---|---|---|---|---|---|---|
| Engine states from build + settings + evidence | yes | yes | no | no | no | no | evidence is in memory only and expires after 10 minutes |
| Evidence is per network; another network or old evidence never shows TRAFFIC_VERIFIED | yes | yes | no | no | no | no | network key is NetworkIdentity (same as route memory) |
| LAB engine tests and the connection write the ledger | yes | no | no | no | no | no | a failing chain or engine behind Xray is not blamed on one hop |
| LAB readiness (Psiphon, Tor, MASQUE, Matrix) reads the registry | yes | no | no | no | no | no | |
| Diagnostic report lists every engine's state | yes | no | no | no | no | no | |
| Release gate list equals the registry's declared programs | yes | yes | n/a | n/a | n/a | n/a | test compares scripts/check-apk.py with the registry |

DEVICE_VERIFIED and FIELD_VERIFIED are evidence levels from a person's report, not states the app
can reach by itself, so they are recorded here per feature rather than computed.

## Remaining P0 work (not done yet)

- Zero-downtime rotation: a rotation still restarts Xray. Real make-before-break needs Xray's
  API (add/remove inbound and outbound at runtime) instead of a restart.
- Smart Connect ordering does not read the engine ledger yet (it uses NetworkMemory and ConnectivityBrain).
- `ServerPanelProvider` with 3x-ui and Hiddify adapters (items 78, 79); Hiddify's licence and
  official installer provenance must be checked first.

## Excluded by earlier safety stops

These were stopped by safety checks in earlier work and are not built or researched in any form:
SNI spoofing (item 39), Spoof Tunnel, L3 allowlist scanner/classifier and spoofed carriers
(items 50-53 and Stage 9), DNS timing jitter and similar traffic camouflage (item 11), clean-IP
scanner upgrades, fragment-based WARP registration, and anything framed as making traffic
undetectable to censors.
