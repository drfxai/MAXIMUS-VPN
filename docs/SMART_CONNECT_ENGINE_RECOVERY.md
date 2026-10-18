# Smart Connect engine recovery

## Source review

Reference supplied for this work: UAC-SNI-Spoofer-Android archive at
`b784ebd149336f4463906509738d354dd9850e23` (2.0.8 / 451).
It demonstrates separate Xray, Tor WebTunnel and WARP/Psiphon paths. This implementation
reuses MAXIMUS's existing runtimes; no UAC source, binaries, public bridge catalog,
account material or application identifiers are copied.

## Implemented

- A recovery catalog for Smart Connect, using the LAB experiment machinery: saved sidecar profiles,
  including user-imported WebTunnel and obfs4 bridges, plus available MASQUE, Psiphon,
  Snowflake and Psiphon-over-MASQUE defaults.
- Stable default IDs; path-based deduplication includes engine settings. One private
  bridge no longer suppresses a distinct Snowflake fallback. Saved bridge URLs and
  Psiphon regions are retained.
- Smart Connect uses LAB real-request testing before choosing a recovery engine, in
  DAILY and GOD MODE. Unmeasured MASQUE/Psiphon choices can no longer be overwritten
  by the later Tor branch. Existing Xray server races, ECH and safe variants remain.
- Psiphon over WARP MASQUE (HTTP/3) using the bundled usque carrier. MASQUE starts
  first; Psiphon dials through its authenticated loopback SOCKS port. Psiphon remains
  the final exit seen by destinations. Both programs stop if a chain hop fails.
- The chain is available in the existing LAB chain picker and the automatic pool.
  Missing Psiphon settings or executables make it unavailable, never a tested failure.
- MASQUE and this chain have separate UDP/QUIC families. A measured QUIC block skips
  these HTTP/3 paths without skipping WebTunnel. Ordinary WireGuard is a separate path.
- A selected-host DNS failure can continue into recovery when exit switching is
  allowed. Trade mode and exact/manual connections retain their selected exit.
- Cancellation interrupts isolated engine waits and executes their cleanup paths.
- APK validation requires the usque executable alongside the other engine programs.

## Prerequisites and limits

Import private Tor bridge lines through the existing universal importer. WebTunnel
is already built into the pinned lyrebird executable; it requires a reachable bridge.
Psiphon still requires this application's own network settings via PSIPHON_CONFIG.
The implementation does not borrow another application's settings.

Aether's MASQUE-over-HTTP/2 runtime is not bundled: its unpinned TLS path disables
server authentication. MAXIMUS retains usque's existing server authentication and
its HTTP/3 implementation. No HTTP/2 capability is claimed by this change.

An isolated SOCKS request verifies the engine and chain, not another app's complete
TUN path. The running VPN is checked again after startup, with background upload and
download checks. Device tests remain necessary on ARM64/ARM32, with Wi-Fi/mobile
handover, blocked UDP and cancellation during startup. No field result is claimed.

Public version and signing configuration remain unchanged. This change does not
publish an APK or replace release assets.
