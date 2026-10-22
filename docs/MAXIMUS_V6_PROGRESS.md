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
| Rotation rolls back to the previous ports when Xray does not start | yes | script syntax only | no | no | no | no | see "Rotation without a restart" below |
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

## PR C: one server panel interface, 3x-ui and Hiddify adapters (backend only)

`ServerPanelProvider` (panels/servers/panel) gives every server panel the same operations:
install blocker, status, install, update, backup, restart, start, stop, logs, uninstall. Status is
`PanelState`: NOT_INSTALLED, INSTALLING, RUNNING, DEGRADED, STOPPED, UNKNOWN (an unreachable
server is UNKNOWN, never "not installed").

Hiddify Manager (GPL-3.0) is never bundled: the app runs Hiddify's official installer on the
user's server, pinned to tag `v14.0.5` (commit `331c1114`). The two installer scripts are checked
against SHA-256 values recorded from that tag before they run; what they download afterwards
(Python packages, the panel) is pinned by tag only, as Hiddify's own installer does it.

DrFX's decision (2026-10-22): Hiddify is installed only on a server of its own. The app refuses a
server with 3x-ui, Maximus Tunnel or anything on ports 80/443, a non-Ubuntu system, under 500 MB of
memory (under 1 GB it warns to add swap first) or under 5 GB free disk; the script repeats the 80/443 and existing-install checks on the
server. An existing Hiddify is detected and imported without any change on the server.

| Feature | Implemented | Unit | Simulator | Emulator | Device | Field | Limitation |
|---|---|---|---|---|---|---|---|
| Provider interface + registry (3x-ui, Hiddify) | yes | yes | no | no | no | no | no screens yet; previews come first |
| Hiddify install with pinned, hash-checked official installer | yes | script syntax + pins | no | no | no | no | never run against a real server; packages it downloads are tag-pinned only |
| Hiddify dedicated-server blocker | yes | yes | no | no | no | no | reads ServerFacts; the script re-checks 80/443 |
| Hiddify detect/import, status per hiddify-* unit | yes | yes (fake shell) | no | no | no | no | |
| Hiddify update (no downgrade), backup, restart/start/stop, logs, uninstall (keeps data) | yes | script syntax | no | no | no | no | admin URL is masked in logs and stored only in the encrypted server store |
| 3x-ui adapter (install/uninstall/restart via existing flows; new status, start/stop, backup) | yes | yes | no | no | no | no | update not offered: the installer leaves a working panel alone |
| Server check finds 3x-ui, Maximus Tunnel and Hiddify from their files (not from unit states) | yes | yes | no | no | no | no | |

### Hiddify screens (previews approved by DrFX 2026-10-22, /mnt/project-files/previews/hiddify-panel)

| Feature | Implemented | Unit | Simulator | Emulator | Device | Field | Limitation |
|---|---|---|---|---|---|---|---|
| Install tab: Hiddify under Panels, "needs a server of its own" | yes | catalog test | no | no | no | no | |
| Wizard: servers with 3X-UI or Maximus Tunnel greyed out with the reason; check list blocks a shared server | yes | yes | no | no | no | no | |
| Server card: version, services n of m, last backup, amber when some services are down | yes | no | no | no | no | no | status comes from the server check (refresh), not live |
| Actions: Open panel (admin link from the install), Back up, Restart, Update (backs up first), Logs, Start, Stop, Remove | yes | no | no | no | no | no | an imported Hiddify has no admin link in the app |
| "Hiddify found on this server" card: add without changes | yes | yes (import over a fake shell) | no | no | no | no | "Not now" lasts until the app restarts |
| 3X-UI: Back up, Start, Stop in its ••• menu | yes | no | no | no | no | no | |

## Rotation without a restart

A rotation used to restart Xray on both servers, which dropped every open session. Now each
port file and its tag are named by the port (`20-t-reality-31001.json`, tag `t-reality-31001`),
so a port that stays open across a rotation keeps its file and its connections. When the
running Xray has its loopback API (`05-api.json`, HandlerService on a random 127.0.0.1 port),
the rotate script adds the new ports first (`xray api adi` abroad, `ado` in Iran), then removes
the expired ones (`rmi`/`rmo`). Any API failure, a kept file whose content changed, or a tunnel
still on the old `-now`/`-prev` files takes the old path: restart, and roll back if Xray does not
start. The Iran balancer's fallback tag follows this period's port of the fallback way.

| Item | Implemented | Unit-tested | Emulator | Phone | Live server | Iran network | Notes |
|---|---|---|---|---|---|---|---|
| Port-named files and tags, API inbound on loopback | yes | yes | no | no | no | no | tunnels set up before this restart once per rotation until "Update tunnel" |
| Add new ports, then remove expired ones, without a restart | yes | yes (script run against a fake xray/systemctl) | no | no | no | no | `xray api` command syntax and dynamic balancer pick-up not tried on a real server |
| Fall back to restart and rollback on any API failure | yes | yes (fake xray) | no | no | no | no | |

## Smart Connect recovery reads the engine ledger

The recovery stage of Smart Connect (LabController.fastRecovery) now asks the engine ledger about
each recovery engine on the current network: an engine whose last start or request here failed
within 10 minutes, and that has not carried a request here in that time, is not restarted (same
rule as ConnectivityBrain's RECENTLY_FAILED for paths); one that carried a request here within 10
minutes is ranked as "worked here recently". Chains are judged by their paths, not by one hop.

| Feature | Implemented | Unit | Simulator | Emulator | Device | Field | Limitation |
|---|---|---|---|---|---|---|---|
| Skip an engine that just failed on this network | yes | yes (ledger) | no | no | no | no | the ledger is in memory: an app restart forgets it |
| Rank an engine that just worked here first | yes | yes (ledger) | no | no | no | no | a nudge in the planner's order, not a guarantee |

## Remaining P0 work (not done yet)

No P0 item above is left unbuilt. None of them has been tried on a phone, a live server or an
Iran network yet.

## Earlier exclusions: superseded status

The earlier statement that all listed transport-recovery items were absent is historical.
See [DNS_TRANSPORT_RECOVERY.md](DNS_TRANSPORT_RECOVERY.md) for the newer implementation:
fronted VLESS/WS/TLS profiles, bounded TCP endpoint checks, native DNS pacing jitter,
repeat-sampled clean-IP statistics, and opt-in first-TLS-write splitting for WARP registration.
These do not implement raw L3 classification, forged carrier identities, a universal spoof-tunnel
protocol or IP fragmentation. Source support does not imply device/carrier verification.

See [DNS_RECOVERY_REVIEW_2026-10-22.fa.md](DNS_RECOVERY_REVIEW_2026-10-22.fa.md) for the
uploaded-source audit, DNS hardening changes and validation limits.
