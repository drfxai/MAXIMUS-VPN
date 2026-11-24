# Shir o Khorshid (شیر و خورشید)

A built-in method of **Connect without a config**, next to Psiphon. It runs the tunnel core of
[Shir o Khorshid](https://github.com/shirokhorshid/shirokhorshid-android), the community Psiphon fork for Iran, as a
separate program (`libshirokhorshid.so`), the same way the Psiphon engine runs.

## What the fork adds over upstream Psiphon

The core is [shirokhorshid/psiphon-tunnel-core](https://github.com/shirokhorshid/psiphon-tunnel-core) branch
`shirokhorshid`, pinned to `520f37c980df6909830ad541b42c305e648ed60d`. It merges upstream Psiphon tunnel-core
`4d6eb0e8` (the commit `scripts/engines/psiphon.sh` builds) and adds:

- **CDN fronting**: `FRONTED-MEEK-CDN-OSSH`, `FRONTED-MEEK-CDN-HTTP-OSSH` and `FRONTED-MEEK-CDN-QUIC-OSSH` dial
  fronted meek to CDN edge addresses instead of the usual front. Edges come from `FrontedMeekCDNScanSpec`
  (IPv4 or CIDR) and the fork's built-in corpus (`FrontedMeekCDNScanUseBuiltInSpec`: Akamai, Fastly, Bunny,
  Cloudflare, CloudFront and others). The core scans them, remembers working edges per network and logs
  `cdn fronting scan found (ip: …)`.
- **Conduit filtering**: `InproxyRejectProxyCIDRs` (and `InproxyRejectProxyCountryCodes` with a MaxMind database)
  reject a matched in-proxy (Conduit) volunteer by address.
- Aggressive establishment, local proxy authentication and in-proxy dial diagnostics (not used by MAXIMUS).

## Settings (Connect without a config → Shir o Khorshid settings)

| Setting | Values | Core config |
|---|---|---|
| Protocol | Auto (default), Conduit, Direct | Auto: no limit. Conduit: `LimitTunnelProtocols` = `INPROXY-WEBRTC-*`. Direct: direct protocols plus the CDN ones when CDN fronting is on, `DisableTactics` |
| Exit country | empty = Auto (Psiphon picks the best server anywhere), or two letters | `EgressRegion` |
| CDN fronting | on (default) / off; ignored in Conduit mode | `FrontedMeekCDNScanUseBuiltInSpec` |
| CDN edge IPs | optional IPv4 addresses or ranges | `FrontedMeekCDNScanSpec.IPCandidates`, after which the edges the scanner found on this network before are added |
| Skip Conduit volunteers in Iran | on (default) | `InproxyRejectProxyCIDRs` from the bundled `geoip-lite.dat` `IR` list |

Network settings (propagation channel, sponsor, server list URLs) are the same ones the Psiphon engine uses: this
build's Psiphon settings, an imported Psiphon.config, or the bundled public profile.

## Smart Connect

Shir o Khorshid is in the recovery pool (`RecoveryEngineCatalog`, family `PSIPHON`). It follows direct Psiphon, and
goes before it on a network where direct Psiphon failed to start recently (`PsiphonChainMemory`, or a recent
Psiphon failure in the engine ledger).

In an automatic connect (Smart Connect, auto failover or the power button's fallback; never a pinned, exit-locked or
forced one), a direct Psiphon that fails to start, or whose tunnel stops carrying traffic, hands over to Shir o
Khorshid with the user's saved choices, and a Shir o Khorshid started that way that also fails to start hands over to
Psiphon over Lantern (`PsiphonFallbackLadder`). Where Psiphon over Lantern is already the path verified on the network,
Shir o Khorshid is skipped and the chain goes next. A Shir o Khorshid the user picked never hands over.

## Diagnostics

The fork prints Psiphon's notices (its `ConsoleClient` and `notice.go` match upstream 4d6eb0e8, including the
readiness notice `Tunnels {"count":1}` that the start waits for). They are read like Psiphon's (`SidecarProcess.psiphonFamily`):
a start that does not come up reports `psiphon.notices` (the last 20 notice types with whitelisted, address-free
fields), `psiphon.dials` / `psiphon.verdict` (protocols dialed, servers connected) and the region count, and its reason
says how far it got ("Shir o Khorshid startup timed out after 120s: server dialing did not establish a tunnel …").
Build 174 had only "readiness marker was not observed within 120s" for two failures.

## Not included

- The fork's private Conduit compartment ID, its build-time server entries and its MaxMind database are secrets of
  its own CI. MAXIMUS does not bundle Psiphon server lists; the program downloads the remote lists at runtime.
- No icons or branding.

## Source and license

GPL-3.0. Built by `scripts/engines/shirokhorshid.sh` (git fetch of the pinned commit, checked, built from `vendor/`);
the corresponding source with the MAXIMUS ConsoleClient change is a release asset, `Shirokhorshid-source.tar.gz`.
`.github/workflows/shirokhorshid-engine.yml` builds it for all three ABIs and runs a host build with every mode's config.
