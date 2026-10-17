# Smart Connect and configuration revival — audit (2026-10-17)

**Baseline:** `main` at the merge of PR #84 (V1.0.2, versionCode 29).
**Method:** source read of the connect path and the components below, plus the PR #84 research register
and ADR. Nothing here was run on a phone or on an affected network. "Present" means the code exists on
`main`; it is not a field result.

## How a connect works today

`RayVpnService.connectLocked` runs one sequential ladder (about 750 lines):

1. Pre-flight the selected profile with one real request through a test core (`RealDelayProbe`).
2. On failure, race the other saved servers (`ServerRace`, ordered by `NetworkMemory` kinds,
   `TransportCapabilityEngine` and `ConnectivityBrain` eligibility), then a disguised retry.
3. A recorded recovery copy for this profile on this network (`BpbRecoveryEngine`).
4. Stealth variants of the profile (`StealthPathFinder`: fragment, fingerprint, ECH, junk).
5. Clean Cloudflare address, then, in GOD MODE only, WARP, MASQUE, Psiphon and Tor.
6. `LabController.fastRecovery` (90 s) over other families and built-in engines.
7. `ConfigRevival` (60 s) over dead Cloudflare-fronted configs.
8. `PathGate`: no verified path, no tunnel (fail-closed unless the user chose "Connect anyway").
9. Start the engine, then `verifyTraffic` (one HTTPS request) decides CONNECTED.

The order is fixed; evidence changes the order inside a stage but not which stage runs first.

## Deficiencies that block a reliable one-tap Smart Connect

| # | Deficiency | Evidence | Effect |
|---|---|---|---|
| D1 | Verification is one request for an empty `generate_204`. No upload and no bulk download is ever checked. | `RayVpnService.verifyTraffic`, `LiveTunnelProbe.latency` | A tunnel that passes tiny requests but stalls on real data (MTU black hole, throttling) shows CONNECTED. The "connects, then cut after 15–20 s" field reports fit this. |
| D2 | Sessions on a separate engine program (Psiphon, Tor, MASQUE, DNS tunnel, Slipstream, Mihomo, chains) leave the app out of its own tunnel (`addDisallowedApplication`), but every check binds to the VPN network. Android's netd refuses a VPN network to an app the VPN does not apply to (EPERM). | `RayVpnService` builder, `LiveTunnelProbe.vpnNetwork`; netd `checkUserNetworkAccess` | These sessions most likely never reach CONNECTED and their health checks always fail, which drives failover. Needs one phone run to confirm. |
| D3 | No "last verified route first". Smart Connect always starts with the selected profile; memory holds connection kinds, stealth variant keys and a latency average, not the exact route that worked. | `NetworkMemory`, `connectLocked` step 1 | Every connect on a known network pays the full search again. |
| D4 | Every Wi-Fi is one network (`"wifi"`); there is no network epoch for remembered routes. | `NetworkKey.current` | A route learned on one Wi-Fi is tried as if proven on another. Probe results already carry `networkGeneration`, remembered routes do not. |
| D5 | Two network classifiers. The connect path uses the coarse `NetworkEnvironment` (from past failures); the measured `NetworkStateClassifier` (UDP, QUIC, IPv4/IPv6, CDN, DNS egress) lives in the LAB and does not steer connect. | `connectLocked` step 3a, `lab/NetworkStateClassifier` | UDP-impaired or TLS-impaired networks are learned only by failing on them. |
| D6 | Emergency engines (WARP, MASQUE, Psiphon, Tor) are reached directly only in GOD MODE; DAILY reaches them only through the 90 s fast recovery. | `connectLocked` step 5 | Slow escalation on a fully filtered network in the default mode. |
| D7 | Revival tries recipes without first classifying why the config died, and covers Cloudflare-fronted TLS configs only. | `ConfigRevival` | Recipes that cannot help (fragment when the address is null-routed) spend the 60 s budget. |
| D8 | MTU is fixed per settings for the TUN; only WireGuard has a measured MTU ladder. Path MTU is not measured. | `MtuIntelligence`, builder `setMtu` | A black hole at ~1350 bytes is not detected or tested. |
| D9 | No stage counts or progress model for the screen (tested / verified / failed). | `RecoveryMachine` exposes a stage title only | The UI cannot show real progress. |

## Implementation matrix

| Capability | State on `main` | Plan |
|---|---|---|
| Real request through the running tunnel | Implemented (TUN path only) | Engine-port path added (D2) — this PR |
| Upload and download verification | Missing | Added: byte-counted download, echoed upload, stall detection — this PR |
| Strict verification states | Partial (`ConnectionStatus`) | `RouteVerification` derived from state + evidence — this PR |
| Saved-server race, stealth variants, recovery copy | Implemented, untested on a phone | Keep; feed from the orchestrator |
| Exact route recipe + replay per network | Missing (D3, D4) | Done in PR #86: `RouteRecipe`/`RouteBook`, per-network, expiry, clear in `NetworkMemory` (button with the UI work) |
| Unified orchestrator with stage counts | Partial (inline ladder, D9) | `SmartConnectPlan` + `SearchTracker`: stages A–G with tested/verified/failed counts in `ConnectionState.search`; order and budgets unchanged |
| Measured network state steering connect | Partial (D5) | The plan skips WARP and MASQUE when UDP or QUIC was measured blocked on this network in the last 10 minutes; nothing is skipped on a guess |
| Failure-classified revival | Partial (D7) | `RevivalTriage` reads the plain failure first: a name that does not resolve or an address that refuses gets address recipes only; a cut handshake moves fragment, ECH and fingerprint up; a refused certificate moves other addresses up (the check stays on). Ambiguous errors limit nothing. Still Cloudflare-fronted configs only |
| MTU stall diagnosis | Missing (D8) | `TunMtuTrial`: after a bulk stall through the tunnel, the next connect on that network uses MTU 1280; its bulk check keeps it (3 days) or restores the configured MTU and does not retest. The running tunnel is never rebuilt |
| MASQUE HTTP/3 | Sidecar present, never run against Cloudflare | Needs a phone run |
| MASQUE HTTP/2 | Missing | Not built: see "Not built" |
| Slipstream | Sidecar present; CI host self-test carries traffic both ways | Needs the user's own server and a phone run |
| DNS tunnel (dnstt) | Sidecar present | Needs the user's own server and a phone run |
| Psiphon | Present, off | Needs the `PSIPHON_CONFIG` secret |
| Tor (Snowflake via lyrebird) | Present, untested on a phone | Phone run |
| AmneziaWG, CottenDNS, TUIC | Not bundled | Blocked: no compatible peer, no pinned build |

## Search stages (Phase 3)

| Stage | What runs | Counted from |
|---|---|---|
| A | Route that worked on this network before (up to 2) | each replay request |
| B | Selected server (its remembered disguise first) | the single pre-flight request |
| C | Recovered copy, disguises and other kinds on the same server | each request in the finder after B |
| D | Other saved servers, raced in bounded rounds | each request in the race |
| E | Clean Cloudflare address; in GOD MODE also WARP, MASQUE, Psiphon, Tor | requests; an engine chosen unmeasured is shown as "chosen", never verified |
| F | LAB fast recovery (90 s) | the LAB's own tested count |
| G | Config revival (60 s) | recipes tried per config |

Finding while wiring this: in GOD MODE, when MASQUE, Psiphon and Tor are all available, each later step
replaces the earlier choice, so Tor is the one started. Behaviour is unchanged here; it needs a decision
and a phone run before changing which engine starts.

## Not built

- **MASQUE over HTTP/2.** It is WARP carried over TCP, which a safety check stopped on 2026-10-16. It is
  left out of this work.
- **Clean-IP scanner changes, security ordering or labels in Smart Connect, Direct mode.** Stopped
  earlier; not rebuilt in another form.

## Architecture choice

Extend, do not duplicate. The verification model sits beside `ConnectionStatus` and is derived from it,
so no existing screen or test changes meaning. Route memory extends `NetworkMemory`. The orchestrator will
be a pure planner that `connectLocked` asks for the next stage, so the existing single-flight lifecycle,
fail-closed rules and `PathGate` stay where they are.

## Blockers needing a person

- One phone run on any network with Tor or MASQUE selected, to confirm D2 and its fix.
- Phone runs on Irancell, MCI and Wi-Fi with the bulk check enabled, to see whether stalls are common.
- A server of the user's own for Slipstream or the DNS tunnel; the `PSIPHON_CONFIG` secret for Psiphon.
