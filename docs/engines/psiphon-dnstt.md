# Psiphon and DNS tunnel engines

Both run as separate programs next to the app (see `app/src/main/java/com/example/vpn/sidecar/`).
Each is shipped in the APK as `lib<binary>.so`, listens on a loopback port the app chooses, and
Xray's only proxy becomes that port. Xray keeps the TUN, DNS handling and kill switch.

| Engine | Kotlin | Binary | Upstream | License |
|---|---|---|---|---|
| Psiphon | `PsiphonSidecar.kt` (id `psiphon`) | `libpsiphon.so` | psiphon-tunnel-core `ConsoleClient` | GPL-3.0 |
| DNS tunnel | `DnsttSidecar.kt` (id `dns-tunnel`) | `libdnstt.so` | dnstt `dnstt-client` | Public domain (CC0 1.0) |

## Psiphon

Psiphon finds and connects to Psiphon's own servers (many protocols, server lists fetched and
signature-checked at run time). The app writes a config file and starts
`libpsiphon.so -config <file> -dataRootDirectory <dir> -formatNotices`.

**Network settings.** Psiphon needs a PropagationChannelId, SponsorId, remote server list URLs and
signing key (and usually more) issued by Psiphon Inc. They are not in this repository. CI writes
them as the asset `psiphon/config.json` from a secret; builds without it report "Psiphon needs this
build's Psiphon network settings, which are not included" for Psiphon profiles. Do not copy these
values from another app.

The app takes that JSON and replaces the fields it owns: `LocalSocksProxyPort` (the chosen port),
`DisableLocalHTTPProxy: true`, `DataRootDirectory`, `EgressRegion` (from the profile; empty means
any), `EnableUpgradeDownload: false`; it removes `LocalHttpProxyPort`, `ListenInterface`,
`UpstreamProxyURL`, unix-socket and notice-file settings. When the JSON has no
`DNSResolverAlternateServers` it adds `1.1.1.1, 8.8.8.8, 9.9.9.9`: without the Android Java library
Psiphon has no system DNS list, and Go's own resolver on Android without cgo asks 127.0.0.1:53.

**Readiness.** The SOCKS port (SOCKS4a/5, CONNECT only, no UDP) opens as soon as the program starts,
before any tunnel exists (`ListeningSocksProxyPort` notice); CONNECT requests fail until a `Tunnels`
notice with count >= 1. Checked by running ConsoleClient locally with placeholder settings: the
port answered within a second and replied "general failure" to CONNECT with no tunnel. So the app's
port check passes early and traffic stays blocked, not leaked, until Psiphon has a server. If the
port is taken, ConsoleClient exits instead of choosing another one. The data directory must exist
before start (the app creates it). SIGTERM stops it cleanly.

Psiphon's SOCKS server treats a SOCKS login as transport arguments rather than checking it, so the
app sends none (`socksAuth = false`).

**Profile.** `PsiphonSidecar.profile(region)` makes a `VlessProfile` with
`protocolType = MIXED` and `extraSettings = {"engine":"psiphon","region":"<CC>"}`.

## DNS tunnel (dnstt)

dnstt-client carries one local TCP port to a dnstt-server inside DNS queries sent through a public
resolver (DoH, DoT or plain UDP DNS), encrypted with Noise to the server's public key. The server
forwards the stream to whatever its operator runs; for this app that must be a SOCKS5 server
without a login. The app starts
`libdnstt.so (-doh URL | -dot IP:PORT | -udp IP:PORT) -pubkey <hex> [-utls SPEC] <domain> 127.0.0.1:<port>`.

The client listens before the Noise handshake, so the port check passes on start; it exits if the
session fails. The resolver must be reachable without a DNS lookup: a DoH URL with an IP host
(well-known DoH names are replaced via `DnsResolvers`), or an IP for DoT/UDP. Host names are refused.

Link format (this app's own):
`dnstt://<64-hex server key>@<tunnel domain>?doh=https://1.1.1.1/dns-query#Name`
(`dot=9.9.9.9[:853]` or `udp=8.8.8.8[:53]` instead of `doh`; optional `utls=`). With no resolver
the link uses `https://1.1.1.1/dns-query`. `DnsttSidecar.parse`, `toLink` and `profile` build
profiles with `extraSettings = {"engine":"dnstt",...}`.

## Pinned versions and hashes

Both are built by `scripts/engines/psiphon.sh` and `scripts/engines/dnstt.sh`
(`ABI=arm64-v8a|armeabi-v7a|x86_64 OUT=<dir>`), with `CGO_ENABLED=0 GOOS=android`,
`-trimpath -ldflags "-s -w"` and Go toolchain go1.26.8. Source comes from `go mod download`,
which checks it against the Go checksum database, and the scripts also compare the hash below.

| Module | Version | Commit | Module hash (h1, sum.golang.org) | go.mod hash |
|---|---|---|---|---|
| `github.com/Psiphon-Labs/psiphon-tunnel-core` | `v0.0.14-beta-ios.0.20260928190446-4d6eb0e8a7e9` | `4d6eb0e8a7e91d3f9a615f70ef65668b8b3595ec` | `h1:Ay/hVsv+zLoml03OvdAuzQXQ64t4N9DW0Sc8XG28BNE=` | `h1:KS+6ifNiSmFA5ghD7i4XrrCZCWm27ZCuKrzPpbDpGjw=` |
| `www.bamsoftware.com/git/dnstt.git` | `v1.20260501.0` | `0c5c52a57d899c05428c116898941761a2ed83c2` | `h1:8i4tQWwBCrhsn6+c8fpjSIyaa+u0MOvdOa1FIxNOz6M=` | `h1:J4kVFxhn2bZqSqfE9l7keNTtsc+dRR6+uNH4kPu5VIs=` |

Dependencies are pinned by each module's own `go.sum` and checked the same way.

Build notes for Psiphon:
- Its `go.mod` replaces `github.com/pion/dtls/v2` with `./replace/dtls`, a nested module the
  published module zip leaves out. It is only used with the `PSIPHON_ENABLE_REFRACTION_NETWORKING`
  build tag, which this build does not set; the script drops the replace (and `go version -m`
  shows `pion/dtls/v2` is not linked into the program).
- `github.com/wlynxg/anet` (Android network interfaces for the WebRTC code) uses `//go:linkname`
  into `net`, which Go 1.23+ only links with `-ldflags=-checklinkname=0`; the script passes it.
- No build tags are set, so the default bolt data store is used and refraction networking
  (Conjure) is not included.

## Licenses

- **psiphon-tunnel-core**: GNU GPL version 3 (`LICENSE` in the module, verified; source files say
  "version 3 ... or (at your option) any later version"). The program also contains Go modules under
  their own permissive licenses (listed in its `go.sum`).
- **dnstt**: `COPYING` in the module is the CC0 1.0 Universal public-domain dedication (verified).

The app talks to both programs only over a local socket, so they stay separate works from the app.

## Source for the Psiphon program (GPL-3.0 offer)

Every APK that contains `libpsiphon.so` must make its complete corresponding source available.
It is exactly:

1. The module above at the pinned version, downloadable from the Go module proxy:
   `https://proxy.golang.org/github.com/!psiphon-!labs/psiphon-tunnel-core/@v/v0.0.14-beta-ios.0.20260928190446-4d6eb0e8a7e9.zip`
   (or `git clone https://github.com/Psiphon-Labs/psiphon-tunnel-core` and check out commit
   `4d6eb0e8a7e91d3f9a615f70ef65668b8b3595ec`).
2. Its dependencies, fetched by `go mod download` in that source (versions and hashes in its `go.sum`).
3. The build script `scripts/engines/psiphon.sh` in this repository, which says how the program was built.

Releases that ship the program should attach (or link next to the APK) an archive made with
`go mod vendor` in that source plus the build script, and keep it available for at least three
years, so the source does not depend on third-party hosting.
