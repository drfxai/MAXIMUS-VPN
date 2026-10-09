# MAXIMUS Autonomous LAB V5: adaptive reasoning, live recovery

Version stays **V1.0.1** (versionName 1.0.1, versionCode 28). This work publishes no APK and changes no release
metadata. The Iran Server / Tunnel feature (Panels Tunnel) is untouched: its UI is preserved and its provisioning
logic is not edited.

Result labels used everywhere below: **unit** (JVM tests in CI), **simulator** (tools/censorsim), **emulator**,
**device** (target: Samsung SM-F711B, Android 15, API 35), **field** (a real restricted network). Only **unit**
results exist for this work. Nothing was run on an emulator, a phone or in Iran.

## 1. Audit (start of this work)

| Item | State |
|---|---|
| main HEAD | 627dee9 (PR #37, Autonomous LAB, merged 2026-10-09 18:25Z) |
| Open PRs | #20 (Iran-verified free-list gate), kept open on purpose for the future Iran server |
| Release | V1.0.1 build 53 (run 37973755154), SHA256SUMS matched the published digests |
| Version | versionName 1.0.1, versionCode 28 |
| CI on main | green at the merge of #37 |
| LAB before V5 | 12-phase Full Analysis; FULL_ISOLATION stopped the run; one batch of Xray tests, then engines; winners rechecked once (Xray only); "wifi" was one network for every Wi-Fi |

## 2. The adaptive loop

`AdaptivePlanner` (pure, clock-injected) drives the run one test at a time:

OBSERVE (two measurements, resolvers, UDP burst) → CLASSIFY (`NetworkStateClassifier`) → DIAGNOSE (restrictions)
→ HYPOTHESES (`ExperimentPlanner` mode, family order, optional AI checkpoint, fresh memory) → choose the
candidate with the highest **probability × information gain ÷ cost** → EXECUTE (one real request, or an engine
run) → RECORD → RECLASSIFY/REPLAN (rules below) → VERIFY (rechecks) → RANK → REPORT → LEARN (evidence records).

Replanning rules, each unit-tested in `AdaptiveLabTest`:

| Evidence in this run | Change |
|---|---|
| A UDP method passed, or QUIC answered | UDP families raised (even if direct UDP DNS failed) |
| International IPv4 fails while IPv6 TLS works | IPv6-literal endpoints raised; IPv6 endpoints lowered where IPv6 is broken |
| DNS tunnel passed in emergency mode | Ordinary config copies stop; other recovery families are still compared |
| Two failures in one failure domain and no pass | That domain is deprioritized (×0.6 per failure); family repeats ×0.7 |
| A family already works | New tests prefer families with no working path (diversity) |
| Fresh/aging memory, AI hint | Small nudges only (±0.15, +0.1) |

Costs: Xray test 1, engine 2.5, Tor 3.5; engines ×1.5 on metered data or under 20% battery.

## 3. Isolation semantics

| State | Meaning |
|---|---|
| PARTIAL_INTERNATIONAL_CONNECTIVITY ("Partial egress") | Some international references answer |
| DOMESTIC_ONLY_NO_VERIFIED_EGRESS | Domestic answers, nothing abroad, no verified way out |
| NIN_WITH_DNS_EGRESS | Domestic only, recursive DNS egress verified (needs the zone in section 10) |
| SEVERE_FILTERING | Nothing abroad, but DNS, UDP, DoH, DoT or QUIC still answered |
| NO_VERIFIED_EGRESS | Nothing answered; **not** isolation |
| TRUE_PHYSICAL_ISOLATION | Only from `EmergencyRecovery.conclude` after a run: every network check negative, every family with a saved config really tested (not skipped) and failed, at least one recovery engine among them |

Unknown stays UNKNOWN. The classifier can never produce TRUE_PHYSICAL_ISOLATION; when the conditions are not met
the report says why not (for example "Not every recovery family was tested (Tor Snowflake)").

## 4. Emergency recovery planner

Mode EMERGENCY_RECOVERY for the four no-egress states. Order (`ExperimentPlanner.emergencyOrder`):

1. DNS tunnel first when DNS or UDP answers (or recursive egress is verified); otherwise after Tor.
2. Psiphon.
3. Tor WebTunnel, Tor obfs4, Tor Snowflake, Tor (meek).
4. Mihomo transports.
5. AmneziaWG, WireGuard, Hysteria2, TUIC, only when UDP is not dead (direct UDP DNS failed **and** QUIC silent).
6. Ordinary saved configs last, low prior (some networks allowlist a few endpoints). No copies, no brute force.

A family with no saved config is reported as NOT_TESTED with what to add ("add a Tor config with obfs4 bridges").
Families come from config fields only: Tor by its first bridge transport, Mihomo by its proxy type
(`wireguard` + `amnezia-wg-option` = AmneziaWG).

## 5. Two phases and the winner

- **Phase A, Fast Recovery:** gain is 1.0 for everything until the first real request passes. That path is shown
  at once ("First working path", USE RECOMMENDED is available) and the run continues.
- **Phase B, Deep Optimization:** rechecks of winners after the stability window, then families with no working
  path yet. A saved config that already works never ends optimization.
- The report keeps FIRST WORKING PATH (`firstWorking`) apart from BEST VERIFIED PATH (rank 1).

## 6. Verification

`FullAnalysis.verdict`: pass + pass at least 3 s apart = **VERIFIED**; pass then a failed recheck = **DEGRADED**;
one pass (or two too close) = **CANDIDATE**; only failures = FAILED. Xray winners get a separate recheck after the
window. Engine winners are re-verified inside the same engine run (`EngineProbe.test(requests = 2)`, 3 s gap), so
Tor/Psiphon/dnstt do not start twice. DEGRADED ranks below CANDIDATE; VERIFIED ranks first.

## 7. Live path updates

`LabSnapshot.live` (`LiveAnalysis`) is republished before and after every test: phase, hypothesis, current test,
first working, best so far, tests done/budget, replanning events, and path rows. Family rows move
QUEUED → TESTING → VERIFIED / CANDIDATE / DEGRADED / FAILED, and at the end leftover rows become NOT_TESTED (with the
stop reason) or NOT_REQUIRED (three verified paths made them unnecessary). Each row carries status, stage, reason,
latency, confidence, attempts, last update and the network session id.

## 8. Network identity V2

`NetworkIdentity` (LAB only): Wi-Fi and Ethernet get `wifi:<12 hex>` from SHA-256 over a per-install random salt
plus default gateway, DNS servers, search domains, IPv4 network, IPv6 presence and MTU. No SSID or BSSID is read
(no location permission), nothing leaves the phone, and `LabBrief` sends only "Wi-Fi" to the AI. The label shows
"Wi-Fi · a1b2" so two networks can be told apart. Mobile data keeps `cell:<MCC+MNC>`; the carrier is metadata and
never chooses a protocol. The VPN side (recovery ledger, endpoint scores, Network Memory, Panels) keeps the old
coarse key; `NetworkContext.vpnKey` maps LAB keys back for those stores.

## 9. Network Memory V2 and freshness

`LabEvidence`: network, family, profile fingerprint, success, stage, latency, passes/attempts (stability), plain
DNS result, security result, time, expiry (7 days), app build. Freshness: FRESH < 6 h, AGING < 24 h, STALE,
EXPIRED; a different app build makes a record STALE at most. Only FRESH (weight 1) and AGING (0.5) records move
priors, and only by ±0.15. History never marks a path as working; every status shown comes from this run.
No addresses, UUIDs, passwords, keys or config text are stored.

## 10. True DNS egress (design; not built)

Needed: a foreign authoritative zone we control (e.g. `probe.<our domain>` with NS at a foreign host) that logs
queries. The phone asks the network's own resolver for `<random nonce>.probe.<domain>` (TXT); egress is verified
only when our authoritative server saw that nonce and answers with a signed token the phone checks. Until that
zone exists `recursiveDnsEgress` stays NOT_TESTED. The fields stay separate: DIRECT_FOREIGN_DNS (udpAvailable),
DOH, DOT, RECURSIVE_FOREIGN_DNS_EGRESS (the nonce), DNS_TUNNEL_VERIFIED (real traffic through a dnstt config).

## 11. Resolver Intelligence V2

Per resolver (≤7): A, AAAA, TXT (EDNS 1232), random NXDOMAIN, EDNS A, TCP. Fields: udp, tcp, rtt, block page,
NXDOMAIN hijack, EDNS size, truncation, AAAA, TXT. Status: DNS_FAILED / DNS_MANIPULATED / DNS_HEALTHY /
DNS_TUNNEL_CANDIDATE (TXT plus large answers or TCP). RECURSION_VERIFIED and DNS_TUNNEL_VERIFIED exist but are never
produced by probes. The best honest resolver also gets a 10-query burst: loss %, median, jitter, rate limiting
(early answers then silence), shown as the "Packet loss / jitter (UDP)" row.

## 12. Multi-vantage quorum (design)

Today: three international TLS references on different operators; partial answers are PARTIAL, never isolation.
Next step (not built): a signed probe manifest (`probes.json`, Ed25519, same key handling as the Free Config Hub
signature) listing references grouped by ASN/CDN/operator, with TTL and version; fetched over the existing
jsDelivr/Statically/Githack fallbacks, cached on the phone, and a built-in fallback list in the APK. A state needs
agreement from references in at least two independent groups.

## 13. QUIC honesty

AVAILABLE / DEGRADED / UNRESPONSIVE / BLOCKED_SUSPECTED / NOT_TESTED. The UI shows BLOCKED_SUSPECTED as
"Blocked (suspected)" in amber, never as BLOCKED. A single failed DoH/DoT check is also "Blocked (suspected)".

## 14. Capability Registry V2 (`CapabilityMatrix`)

Layers: PARSER, MODEL, BUILDER, RUNTIME_PRESENT, RUNTIME_CONFIG_ACCEPTED, RUNTIME_TRAFFIC_VERIFIED, UNIT, SIMULATOR,
EMULATOR, DEVICE, FIELD; values YES / NO / PARTIAL / UNKNOWN.

| Row | Present | Config accepted | Traffic verified | Device / field |
|---|---|---|---|---|
| Xray-core v26.9.9 | YES | PARTIAL | UNKNOWN | UNKNOWN |
| Mihomo v1.19.32 | YES | PARTIAL (Linux build, same tag, `-t`) | UNKNOWN | UNKNOWN |
| AmneziaWG on Mihomo | YES | PARTIAL (`-t` accepted AWG, rejected a bad jc) | NO | UNKNOWN |
| Psiphon | PARTIAL (needs PSIPHON_CONFIG at release) | UNKNOWN | UNKNOWN | UNKNOWN |
| Tor + lyrebird | YES | UNKNOWN | UNKNOWN | UNKNOWN |
| dnstt | YES | UNKNOWN | UNKNOWN | UNKNOWN |
| ECH | YES | UNKNOWN | UNKNOWN (no handshake measured) | UNKNOWN |
| WARP (WireGuard) | YES | UNKNOWN | UNKNOWN; MASQUE unsupported | UNKNOWN |
| TUIC | PARTIAL (Mihomo only; Xray refuses) | UNKNOWN | UNKNOWN | UNKNOWN |
| HTTP/3 | YES | UNKNOWN | UNKNOWN (QUIC reachability only) | UNKNOWN |
| Fragment / finalMask | YES | UNKNOWN | UNKNOWN | UNKNOWN |

Nothing in the matrix counts as proven working (`provenWorking` needs traffic verified on a device or in the field).

## 15. AWG / WARP audit

Mihomo v1.19.32, built from its source tag, has `AmneziaWGOption` (jc, jmin, jmax, s1–s4, h1–h4, i1–i5…). The
Linux build of the same tag accepted a plain WireGuard and an AWG config with `-t` and rejected an invalid jc. The
Android binary was not exercised here. EngineRegistry keeps the standalone AmneziaWG engine as not bundled;
AWG is only reachable as a Mihomo proxy from a Clash file. WARP is WireGuard registration through Xray; WARP over
MASQUE is not supported and is never offered.

## 16. ECH, fragment, MTU, loss, IPv4/IPv6, optimizer

- ECH: ECH_NOT_TESTED in every run. The LAB does not look up configs' server names on the censored network
  (that would reveal them in plain DNS). No silent fallback: an ECH config that fails is reported as failed.
- Fragment / finalMask: only the curated profiles already in `FragmentProfileEngine`, through the mutation policy
  and security gate; a copy is kept only when it passes and beats its parent. No new optimizer in this PR.
- MTU, throughput, upload/download asymmetry: not measured (listed under "Not tested").
- Loss / jitter: measured with the UDP DNS burst (section 11).
- IPv4 and IPv6: measured independently; planner and loop treat them separately; no IPv6 route change is made.
- Config optimizer: unchanged from V4 (allowlisted fields, originals immutable, transactions PROPOSED / REFUSED /
  STAGED / COMMITTED / ROLLED_BACK / EXPIRED). Emergency mode makes no copies.

## 17. AI inside the loop (optional)

Checkpoints: after classification, and after the first wave (3 tests) when nothing passed or the evidence
conflicts (UDP measured dead but a UDP method passed). Not used for ties. Pipeline: AI proposal (`LabAgent.rankFamilies`,
JSON) → schema (known family names) → capability (`AiCheckpoint.validate`: offered, saved, not skipped, at most
three) → mutation policy / security gate (unchanged, candidates already passed them) → deterministic experiment.
The AI sees state, restrictions and per-family pass counts only; never names, addresses, the network fingerprint,
credentials or keys. A failure or 10 s timeout changes nothing. The AI cannot connect, change configs or
credentials, touch security settings, or mark a path verified.

## 18. Stop criteria

- Three independent families VERIFIED and the best is ≤ 70% of the second's latency → stop (others NOT_REQUIRED).
- Budget spent (15 user-started; 10 metered; 4 on low battery).
- Nothing left with expected value ≥ 0.03, or every candidate tested.
- The network changed → stop; results stay with the old session.
- One weak path is never a reason to stop.

## 19. Engine temp cleanup

Each engine test uses `noBackupFilesDir/lab-engines/<engine>-<random>`; it is deleted after the engine stops,
and every LAB engine folder is deleted when the LAB starts (crash leftovers). Only sanitized results are kept.

## 20. UX

LAB home and Live page: BEST CONNECTION NOW (name, status, family, latency, reason) with **Use recommended**,
which only selects the saved config (nothing connects, nothing changes); live summary (network, state, progress,
phase, hypothesis, testing now, first working path, recent plan changes); simple view (family rows) and technical
view (all rows with stage, confidence, attempts, session). The report adds "How the plan changed".

## 21. Censor simulator and testing ladder

tools/censorsim was **not** extended in this PR. Replanning is covered by unit-level scenarios in
`AdaptiveLabTest` (domain deprioritization, UDP raise, IPv6 first, DNS-tunnel pass, three-verified stop, budget
stop, isolation conclusion, live rows). Those are unit results, not simulator results.

## 22. Field matrix (to run on SM-F711B, Android 15)

| Network | Expect to record | Pass condition |
|---|---|---|
| Irancell (cell:43235) | state, first working path, best verified path, families tried | new session, fresh rows |
| MCI (cell:43211) | same | new session; nothing inherited from Irancell |
| Home Wi-Fi A | same, label "Wi-Fi · xxxx" | own fingerprint |
| Other Wi-Fi B | same | different fingerprint; no history from A |
| Back to Wi-Fi A | priors from A's fresh evidence only | statuses still re-measured |
| VPN on | every family NOT_TESTED with the reason | no tunnel disturbed |

No carrier → protocol mapping is hard-coded; results are per network and per run.

## 23. Security invariants (checked)

Nothing in V5 sets allowInsecure, disables certificate checks, weakens server authentication, changes
UUID/password/keys/REALITY keys, touches the kill switch, routing, DNS protection or IPv6 handling, runs downloaded
binaries or Telegram scripts, sends VPN credentials or AI keys to the AI, or edits a saved config. Configs with
allowInsecure are listed as SECURITY_REJECTED and never tested or recommended. fingerprint="unsafe" is not
treated as allowInsecure. Control plane (AI, memory, manifests) only orders; the data plane (real requests) decides.

## 24. Priority status

| Priority | Item | Status |
|---|---|---|
| P0 | Isolation semantics, emergency diversity | Done (unit) |
| P0 | Network identity V2 | Done (unit) |
| P0 | Adaptive loop, fast/deep phases, stop criteria | Done (unit) |
| P0 | Live streaming of path states | Done (UI untested on a phone) |
| P0 | Engine winner re-verification, temp cleanup | Done (unit for the verdict; engines untested on a phone) |
| P1 | AI checkpoint | Done (unit for validation; untested against a provider) |
| P1 | DNS egress, multi-vantage | Design only |
| P1 | Resolver V2, Capability Registry V2, AWG/WARP audit | Done |
| P2 | Loss/jitter | Done (UDP DNS burst) |
| P2 | ECH handshake, MTU, throughput, fragment optimizer V2, config optimizer V2 | Not done |
| P2 | Memory unification, temp cleanup | Done |
| P3 | Censorsim V2, emulator, device, field | Not done (field matrix prepared) |
