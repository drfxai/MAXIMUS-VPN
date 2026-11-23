# DNS-first connectivity and automatic upkeep

Iran's filtering can tighten until only DNS still works: foreign TCP/UDP is cut, CDN edges are blocked,
and only domestic resolvers on UDP/TCP 53 still get through. This page covers what MAXIMUS does for that
case, and what runs on its own in the background so a connection is ready when the user needs it.

## What already existed (survey, 2026-11-24)

| Piece | Where | What it does |
|---|---|---|
| Resolver scanner | `vpn/dns/ResolverScanner.kt`, `assets/resolvers/part_*.txt` | Asks each candidate resolver plain questions over UDP/53, TCP/53 and DoT. Flags hijacking (10.10.34.x or bogon answers) and interception, gives each a score from 0 to 100, and keeps the best ones per network in `VerifiedResolverPool`. |
| Resolver order at connect | `DnsTunnelResolverOrder` | Puts the verified resolvers first for dnstt, Slipstream and CottenDNS. Profiles that use the default resolver get verified ones added. |
| DNS tunnel engines | `DnsttSidecar` (multiple resolvers, `-qps`, `-qps-per-resolver`, backup domains), `SlipstreamSidecar` (QUIC over DNS, multiple resolvers), `CottenDnsSidecar` (CottenDNS / CottenMax / MasterDnsVPN server, udp/tcp/dot/doh transports), `SoarSidecar` | Each engine already spreads its queries over every resolver it is given. |
| Isolated engine test | `vpn/lab/EngineProbe.kt` | Starts an engine on its own port while the VPN is off and sends a real HTTPS request through it. Only a request that comes back counts. |
| Unlock DNS mode | `vpn/unlock/*` (#275) | A DNS-only VPN session using anti-sanction resolvers. It can also apply CircumVolve query shaping (TC/Z, count claim, pointer chain), tested per resolver. |
| DNS transport recovery, DoH/DoT | `DnsResilienceEngine`, `ProxyDnsTransport`, `docs/DNS_TRANSPORT_RECOVERY.md` | Changes how the app resolves names *inside* a working tunnel. |
| Clean CDN edges | `vpn/cleanip/*`, `CleanIpOptimizer` | Handshake scans per CDN, cached per network. Detects networks that proxy Cloudflare traffic by SNI. |
| Config revival | `vpn/lab/ConfigRevival.kt` | Recipes for ECH, fragment, fingerprint and clean IP, verified with a real request. The winners are remembered per network. Runs from the LAB, or as the last step of Smart Connect. |
| GeoFiles | `update/GeoFilesUpdater.kt` | Chocolate4U/Iran-v2ray-rules (GPL-3.0). Every file is checked against the release's `.sha256sum` and the GitHub asset digest. Can download through the tunnel. Was manual only. |

## Added in this change

### A. DNS-only internet

**Tunnel-aware resolver probe** (`vpn/dnsfirst/TunnelResolverProbe.kt`, `DnsWire.kt`)

The resolver scanner answers "does this resolver tell the truth about a public name?". A tunnel needs a different question answered: "does this resolver carry *tunnel-shaped* queries to *my* name server?". The probe sends ordinary queries for random names under each saved tunnel domain and checks each of the following:

- whether the resolver recurses for random labels at all (NOERROR or NXDOMAIN; REFUSED, SERVFAIL and silence count as no);
- near-maximum names (about 200 bytes, 63-byte labels), sent as TXT questions with EDNS(0) padded to 128-byte blocks (RFC 7830/8467);
- TXT, NULL and CNAME questions. Some networks drop the query types tunnels rely on;
- the advertised EDNS UDP payload size;
- TCP/53;
- a burst of 10 queries sent together. How many come back sets a per-resolver rate: 4, 2 or 1 queries a second (dnstt's own limit is 4);
- whether the resolver accepts a query with the reserved Z bit set (CircumVolve Table 2 row 10). This is recorded only; see the deferred items below.

Transports:

- **UDP/TCP 53** from the app's own sockets.
- **DoH (RFC 8484 POST)**. This includes two domestic endpoints published by their operators: `https://free.shecan.ir/dns-query` and `https://dns.403.online/dns-query`. They are only *candidates*. Whether they recurse for a tunnel domain is measured, never assumed.

A reply from an address other than the one asked is treated as interception and ignored.

**DNS tunnel ladder** (`DnsTunnelLadder.kt`, `DnsLadderMemory.kt`, `DnsTunnelSpecs.kt`)

For every saved dnstt, Slipstream and CottenDNS/MasterDNS profile, the ladder plans these resolver variants, in this order:

1. The variant that worked on this network before. It is remembered for 3 days and only while the profile is unchanged.
2. **stripe-udp**: the best 3 suitable resolvers at once, so the engine spreads its load over them. For dnstt this sets `-qps-per-resolver` to the slowest member's measured rate.
3. **single-udp**: only the best resolver.
4. **tcp**: CottenDNS only, through resolvers that answered over TCP/53.
5. **doh**: dnstt only, through a DoH endpoint that recursed for the tunnel domain (domestic ones included).
6. **as-configured**: the profile as saved.

Rules the ladder follows:

- "Suitable" depends on the engine. dnstt and Slipstream need TXT and long names to work. CottenDNS needs long names, and TCP for its TCP variant.
- A variant that failed twice on a network is skipped there for 12 hours.
- Tunnels are interleaved round-robin, so one dead server cannot use up the whole run (at most 10 variants, 4 minutes).
- Each variant is tested with `EngineProbe` and a real request, with the VPN off.
- The first variant that passes for a tunnel is stored per network (`DnsLadderRunner`).

**At connect.** `DnsTunnelResolverOrder.apply` asks `DnsLadderHook` first. If the ladder verified a variant for this profile on this network, the engine starts with it. Otherwise the earlier verified-resolver ordering applies unchanged. The saved profile is never modified, and a failing lookup never blocks a connect.

### B. Automatic upkeep

`vpn/automation/` has five tasks. `AutomationPolicy` decides which ones run for each trigger:

| Task | Runs on | Skips when | Result kept |
|---|---|---|---|
| Resolver scan | network change, VPN stopped, daily, failed connect, manual | any VPN is up; scanned on this network in the last 6 h (30 min after a failed connect) | `VerifiedResolverPool` per network |
| DNS tunnel ladder | same | VPN up; no DNS tunnel profiles; ran here in the last 24 h (1 h after a failed connect) | `DnsLadderMemory` per network |
| Clean CDN IP scan | same | VPN up; no CDN-fronted profiles; scanned here in the last 12 h (1 h) | `CleanIpCache` per network (used by revival and connect) |
| Config revival | same | VPN up; nothing revivable; ran here in the last 24 h (2 h) | revival winners per network; measured latency written back to working configs; dead-strike book |
| Iran GeoFiles | daily, after a connect, manual | "GeoFiles via proxy" is on and the tunnel is down; on mobile data without the tunnel; updated in the last 3 days | verified GeoFiles (SHA-256 checked, atomic swap) |

**Triggers** (`AutomationScheduler`, `AutomationStartup`):

- WorkManager runs a daily periodic job, with battery-not-low required.
- A network change (default-network callback, compared by `NetworkIdentity`) starts a run after 20 s. A newer change replaces a pending one.
- When the VPN stops, a run starts after 30 s.
- A failed connect (`ConnectionStatus.FAILED`) starts a run after 15 s. If the blocking interface is still up, it retries up to 3 times.
- When the VPN connects, the GeoFiles task runs through the tunnel after 60 s.
- Manual: "Run all now", or tap one row to run only that task.

**No network-type constraint.** WorkManager's "connected" constraint requires Android to *validate* the network, and a DNS-only network never validates. That is exactly when this upkeep matters most. The tasks handle having no network themselves.

**Process.** WorkManager starts on demand in the main process only, from an `androidx.startup` initializer. Its default initializer is removed, so the `:tor` process and Robolectric never start it.

**Status** (`AutomationStatusBook`, `ui/automation/AutomationViewModel.kt`):

- Each task records its last run, outcome, found count, skip reason, and when it last succeeded on each network.
- The ViewModel exposes this as `AutomationUiState`.
- `ui/settings/AutomationStatusSection.kt` is a deliberately basic card under Settings → App updates. The new design can render the same state differently.

**Dropping dead configs.** This is off by default, through a switch on the card. When on, it removes only configs that:

- came from a subscription;
- are not favourites;
- are not the selected profile;
- failed revival in 3 runs in a row spread over at least 48 hours (`ConfigHealthBook`).

A config that answers once is cleared from the list.

**Iran geo data.** Chocolate4U/Iran-v2ray-rules (GPL-3.0) is already bundled and verified. Its `geosite` `ir` category already includes bootmortis/iran-hosted-domains (MIT), so no second source is downloaded. Chocolate4U publishes SHA-256 sums but no signatures. The updater checks both the `.sha256sum` file and the GitHub API digest, and rejects any mismatch, so nothing is installed unverified.

## Deferred, and why

- **CircumVolve shaping on tunnel queries.** The tunnel engines are separate native programs that send their own queries. Shaping those queries would need a local UDP relay between the engine and the resolvers, with its lifecycle tied to every sidecar start and stop. That means touching the engine start path, which another branch is changing. The relay also could not be validated against the Iranian censor in CI. The probe already records which resolvers accept the Z bit, so a relay can later be limited to those resolvers.
- **A single "DNS tunnel" Smart Connect mode that cycles through ladder rungs live during a connect.** The ladder runs in the background and hands its winner to the connect. Changing Smart Connect's attempt loop belongs to the Smart Connect work (#276), so it is not done here.
- **XDNS (Xray finalmask) as a ladder engine.** There is no first-class XDNS profile type yet (`docs` survey, 2026-11-23). Once there is, it plugs in as a fourth `EngineKind`.
- **QPS/rate-limit detection over time.** The burst gives a first rate. Changing the rate while a tunnel runs needs engine feedback that dnstt/Slipstream/CottenDNS don't expose.
- **A DNS-reachable mirror for GeoFiles.** In a DNS-only shutdown, nothing but DNS reaches outside. The only "mirror" then is the DNS tunnel itself, so the task downloads through the tunnel once one is up. jsDelivr mirrors of the `release` branch carry the same files and `.sha256sum`s but no release tag to compare. They are left out so the updater keeps a single version source.
- **DoT/DoH variants for Slipstream and CottenDNS.** Slipstream takes only UDP resolvers. CottenDNS DoT/DoH needs per-resolver TLS names that the probe does not learn.

## Tests

- `DnsWireTest`: query encoding, padding, reply matching, EDNS parsing, tunnel-like names, the Z bit.
- `TunnelResolverProbeTest`: honest, refusing, TCP-only, TXT-dropping, long-name-dropping and rate-limited resolvers; DoH; ranking; a real localhost UDP socket.
- `DnsTunnelLadderTest`: rung order per engine, per-resolver rate, skipping failed rungs, remembered winners, interleaving, the runner's budget and memory, persistence and expiry.
- `DnsTunnelSpecsTest`: building variants for dnstt, Slipstream and CottenDNS; the connect hook (per network, ignored after a profile edit, a lookup that throws).
- `AutomationRunnerTest`: the policy matrix (VPN, metered, tunnel, freshness per network, triggers), the runner's records and retry signal, the dead-config book.
- `AutomationSchedulerTest` (Robolectric): unique work KEEP/REPLACE, no network constraint, the daily job, input round-trip, UI state, startup staying off under Robolectric.
- `AutomationStringsTest`: Russian covers every upkeep string, with matching placeholders.
