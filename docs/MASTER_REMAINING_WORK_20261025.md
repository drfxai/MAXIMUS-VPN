# MAXIMUS VPN remaining engineering work — execution ledger

Authoritative baseline read on 2026-10-25: main `fa9a75b3a4e28685cf91dcedc5cd025b93fb8f45`, tree `5b3127aa0d3bd5b5ac42a4b04913f45bd4345492`; versionName `1.0.2`, versionCode `37`. Baseline Checks `40196788688`, transport `40196788536`, signed release `40196788574`: success. PR128 and PR129 were open/draft/unmerged; PR20 is an older unrelated change and is not automatically included. User's final directive authorizes reviewed PR merges and a signed release, not live VPS/Cloudflare production mutations.

## Source and engine inventory

Existing single ConnectivityBrain, LAB, EngineLedger, RouteMemory/RouteBook, ResolverIntelligence, CapabilityRegistry and Android VpnService remain the integration points. No second brain or TUN owner is introduced.

Registry baseline: libXray 26.9.9; Mihomo 1.19.32; tor-android 0.4.9.5.1 / lyrebird 75ef9b2c1f18; Psiphon 4d6eb0e8a7e9; ByeDPI ba532298de7b; DNSTT v1.20260501.0; Slipstream 397850b1; MASQUE/usque 1.5.0; MatrixTransport 1.6.0 secure-v1; CottenDNS e50a43ef75ad10ae2ffbfa106d13c50de3e77624; SOAR a8af533d82284f5e6ab13b706552596c108bacf2. Standalone amneziawg-go and NaiveProxy are not bundled; AWG support uses Mihomo. OpenVPN is not implemented by this patch. Declared bundle state is not field verification.

Hiddify Manager pinned source: v14.0.5 / `331c1114d4bb0bcf62e9ac719932ae904e88cf9c`; embedded panel submodule `a3a08d66b88cef7b0d4181638db97d9f92befe2c`. Manager `scripts/hiddify` calls `check_hiddify_panel` for `admin`; `scripts/common/utils.sh` shows this reloads config and may install qrencode. `print_panel_links` reads `data/current.json.panel_links`. Recovery now reads that bounded JSON directly, without mutation. API source reviewed includes panel CLI, v2 initialization and admin users resource. This is not enough to claim installed-version compatibility or enable remote user writes without further contract/auth tests.

## Current changes and proof boundaries

- PR128: preserve reviewed standalone live sidecar across mode-routing rebuild; refuse reuse across changed network/profile or unverified/dead engine; second-start SOAR/CottenDNS config repair; Ghost Rider private bridge DNS; chain-hop cleanup and upstream credentials/refusal; LAB and subscription stale-boundary guards. Short protected TUN interruption remains. See MODE_ENGINE_AUDIT_20261025.md.
- PR129: visible official-panel fallback, read-only Hiddify URL recovery, stricter HTTPS URL validation, capability-URL log/error redaction, synthetic shell/result-channel tests; clearer installation-versus-request evidence and stale Wizard-result guard. UI links do not implement direct users/CDN/Telegram administration.
- Local integration evidence: extracted production Hiddify link-reader Shell/Python ran against synthetic current.json and selected the HTTPS fixture. No real Hiddify host was accessed.
- CI must be read at final PR SHA. No physical-device, Iranian field, DNS-leak, full TUN, frame-time or before/after TTFVIT measurement is invented.

## Executable tasks and acceptance gates

Status vocabulary: DONE = implemented with stated evidence; IMPLEMENTED_NOT_FIELD_TESTED; REMAINING = code work remains; BLOCKED = named external prerequisite unavailable. CI pending is not success.

| Task | Status | Concrete next change / acceptance |
|---|---|---|
| A1 Hiddify link recovery | IMPLEMENTED_NOT_FIELD_TESTED | Run fresh/import/missing/stale-domain fixtures and real pinned-version host; secret path absent from all logs |
| A2 Hiddify API auth/version contract | REMAINING | Pin installed version; test documented auth, 401/403/404/expired session before writes |
| A3 Hiddify users/plans/quota/expiry | REMAINING | Build typed existing-panel adapter, explicit destructive-action confirmation, deduplicated config import and QR |
| A4 Hiddify dashboard/domain/TLS/backup/update | PARTIAL | Existing SSH lifecycle and web fallback; implement audited API pages and separate service/domain/egress evidence |
| B1 Cloudflare permissions and inventory | PARTIAL | Review existing token/zone discovery, permission errors and encrypted storage; no one-click OAuth claim |
| B2 DNS transactional changes | REMAINING | Plan/conflict detection/dry run, snapshot, idempotent apply and rollback tests; production write needs explicit target approval |
| B3 Domain health | REMAINING | Collect NS/delegation/TLS/SNI/HTTPS/origin observations independently; distinguish timeout from propagation |
| C1 Telegram proxy management | REMAINING | Audit installed Hiddify telemt/API; separate MTProto/SOCKS5 links from CDN transports; device route test |
| D1 SSH diagnosis | PARTIAL | Existing pinned-host-key/auth/timeout distinction; add staged TCP/handshake/auth evidence, full sanitized expandable error |
| D2 Install verification workflow | PARTIAL | Label installed/unverified separately; add mandatory distinct verify stage with upload/download and explicit waiting/failed evidence |
| D3 DNS listener conflicts | REMAINING | Preflight listeners plus NAT redirects; refuse incompatible UDP/TCP53 ownership without real multiplexing |
| D4 Installer cancellation/rollback/resources | PARTIAL | Audit each installer against existing service preservation, RAM/swap, pinning and cancellation; real Linux scenarios |
| E1 SOAR/CottenDNS/DNSTT root cause | BLOCKED for real host | Need authorized VPS access/profile and phone network; independently test authoritative DNS, resolver, handshake and server egress |
| E2 SOAR/CottenDNS client lifecycle | IMPLEMENTED_NOT_FIELD_TESTED | Import/edit/manual resolvers exist; restart regression repaired in PR128; verify matching public server separately |
| E3 Resolver capability evidence | PARTIAL | Extend existing intelligence to typed A/AAAA/TXT/NXDOMAIN and transport/EDNS/size/loss/freshness, with generation guards |
| E4 Adaptive DNS family | REMAINING | Rank existing SOAR/Cotten/DNSTT/Slipstream on fresh resolver and failure-domain evidence; bounded manual-only discovery |
| E5 MasterDNS audit | REMAINING | Source/license/crypto/provenance/build review before any binary or capability declaration |
| F1 LKG and total waiting clock | PARTIAL | Existing per-network hints reverified; measure from original user action across all attempts and cancellation |
| F2 Tor bridge health | REMAINING | Circuit-aware CIRC/STREAM/ORCONN observation; app-controlled retry cooldown/backoff; preserve healthy circuits; aggregation alone is insufficient |
| F3 Atomic generations and continuity | PARTIAL | PR128 mode reuse/boundary guards; full network/request/session generation coverage and all-engine transition matrix remain |
| F4 Real Android TUN verification | REMAINING | Protected-app IPv4/IPv6/DNS/exit test with path provenance; local SOCKS success must stay separate |
| F5 Failover/watchdogs | PARTIAL | One evidence-based decision, multi-target health, no unverified-ready standby, bounded restart/crash tests |
| F6 OnRevoke/Always-on | REMAINING | Classify system revocation without bypassing Android ownership; kill-switch tests with lockdown on/off |
| F7 MTU/DoH/health counters | REMAINING | Evidence-driven MTU trials; runtime resolver proof; successful/failed/skipped health counts with targets/timestamps |
| P1A OpenVPN | REMAINING | License/build audit; .ovpn credentials/certs/routes, one TUN owner, TCP/UDP and fail-closed device tests |
| P1A AWG/chains/per-app | PARTIAL | Reuse Mihomo and GenericChainPlanner; preserve TCP/UDP/DNS hops and explicit per-app choice; no skipped carrier |
| P1B WARP/MASQUE | REMAINING | Singleflight, separate nested identities, verified reuse, multi-target health and bounded startup supervisor |
| P1B Xray upgrade | REMAINING | Verify authentic stable target and regression compatibility before replacing 26.9.9 |
| P1B Research engines | RESEARCH_ONLY | Bepass/SIMORGH/SNI claims require source/license/security evidence; no speculative bundle |
| P1C Subscriptions | PARTIAL | Existing LKG/503 separation/background work; audit atomic refresh/backoff/quota and Worker error provenance |
| P1C Worker investigation | BLOCKED for production diagnosis | Need correct authorized Worker/log target; code branch work independent, no deployment implied |
| P1D UI performance | REMAINING measurement | Existing flow dispatcher repairs are not FPS proof; run 50/100/300/500 profile cold-start/scroll/batch/ANR/battery matrix |
| P2 Endurance/security acceptance | REMAINING | Device 30-minute continuity, leak/kill-switch, server rollback and cloud conflict tests; record exact artifacts and timestamps |

## Verification matrix (before final CI/release)

Y means implementation exists, not a universal connectivity guarantee. Pending and unmeasured must remain explicit.

| FEATURE | IMPLEMENTED | BUNDLED | UNIT | INTEGRATION | EMULATOR | DEVICE | FIELD | REAL-TRAFFIC | LIMITATION | NEXT ACTION |
|---|---|---|---|---|---|---|---|---|---|---|
| Sidecar mode continuity | Y, reviewed standalone engines | Existing engines | New tests, CI pending | Not measured | Not run for this patch | No | No | No new field proof | Protected TUN rebuild; other engines/chains restart | Transition/device matrix |
| SOAR/Cotten restart | Y | Baseline release92; new build pending | New regression tests | Existing loopback/upstream CI only | Pending release check | No | No | Local CI is not Iran field | Server compatibility unmeasured | Matching server + phone test |
| Hiddify admin recovery | Y | App adapter only; Hiddify not bundled | New tests, CI pending | Synthetic shell reader passed | No | No | No | No | Existing generated current.json needed | Fresh/import server tests |
| Chain carrier boundary | Y | Existing adapters | New tests | No new multi-hop egress test | No | No | No | No | Unsupported carriers rejected | Real chains with full hop proof |
| Wizard evidence/stale result | Y | App | CI pending | No device workflow | No | No | No | No | Single HTTP request is not full TUN/upload proof | Mandatory complete verify stage |
| Full Hiddify API/CDN/Telegram | No | No new server module | No | No | No | No | No | No | Web fallback only | A2/A3/B/C tasks |
| Full TUN/leak/500-profile KPI | No completion claim | Existing app | Partial existing tests | No full matrix | No new benchmark | No | User historical reports only | No new verified result | Requires device/network evidence | F4/P1D/P2 |

## External prerequisites and rollout

No live VPS credentials, explicit production Cloudflare record-change target, or attached Android debugging device were available to this execution. Those are external test blockers, not a reason to label unfinished code complete. No secret supplied in public profile examples is copied into this ledger or the repository.

Merge only final reviewed heads with successful CI; inspect combined main before release. Signed release must pass packaging, permanent signer, Android15 install/launch and updater-metadata checks. A successful launch is not a censorship or TUN leak test. Preserve version/signing. Rollback uses prior reviewed release/source; never delete healthy server data to make a test pass.
