# Smart Connect engine recovery

## Source and implementation

Reference supplied: UAC-SNI-Spoofer-Android archive at
`b784ebd149336f4463906509738d354dd9850e23` (2.0.8 / 451).
MAXIMUS reuses its Xray, Psiphon, Tor/lyrebird and usque runtimes. A separate
Aether-derived executable, Matrix Transport, is built from this immutable source
with the local TLS policy patch. No third-party application identity, account
settings, public bridge catalog or prebuilt binary is copied into MAXIMUS.

## Path mapping

| Path | Outer transport | Requirements |
| --- | --- | --- |
| Saved Xray configs and ECH variants | Existing config transport | User's working server/config |
| Psiphon direct | TCP-only automatic recipe; explicit saved configs retain their settings | Valid application network settings |
| Tor WebTunnel / obfs4 / meek | TCP/HTTPS bridge | Imported private bridge lines |
| Tor Snowflake | Its own WebRTC transport, not advertised as TCP-only | Bundled lyrebird and reachable broker/volunteer |
| WARP direct | WireGuard UDP | Registered device; protected API connection |
| WARP MASQUE (usque) | HTTP/3/QUIC | Bundled executable and authenticated registration |
| Matrix Transport | Pinned MASQUE HTTP/2/TCP or HTTP/3/QUIC; WireGuard UDP; WARP-in-WARP | Built native runtime and authenticated registration |
| Layered recovery (UAC PoW pattern) | Psiphon through usque or Matrix HTTP/2 | Both runtimes and own Psiphon settings; final exit is Psiphon |

"Direct" means an engine connects without an intermediate carrier; it never means
silently bypassing VPN protection. WireGuard and Snowflake are not labelled as TCP.
No proof-of-work mining is added. HTTP/2 comes from an authenticated carrier, not
from relabelling WireGuard. Imported endpoint credentials, TLS identity and ports
are preserved. Trade and exact/manual sessions do not switch exits automatically.

## Smart Connect

- A bounded recovery catalog includes available engines and distinct saved bridges.
  Stable IDs and path-based deduplication preserve separate carrier/bridge settings.
- Engines are tested serially through the LAB, with real requests and rechecks. No
  untested engine selection can be overwritten by a later Tor branch.
- WARP recovery is available in DAILY and survival modes. Sidecar availability is
  initialized at application startup, including before the first VPN service run.
- Missing executables/settings are unavailable, not tested failures. UDP/QUIC
  restrictions gate the corresponding families, retaining HTTP/2 and WebTunnel.
- Stop interrupts isolated startup waits. Engine processes, registration children,
  generated credentials and LAB work folders are cleaned up on failure/cancellation.

## Clean IP scanning

TCP timing is only a shortlist. Smart Connect probes several edges using the actual
config, then rechecks passing paths before selecting one. Scanner sockets bypass
the temporary traffic-blocking interface explicitly. TLS probes validate both CA
trust and hostname. REALITY endpoints are not rewritten to Cloudflare addresses.
The chosen copy preserves protocol, port, SNI, Host, path and credentials.

## Configuration and security

The LAB chain page accepts authorized Psiphon network settings JSON, validates the
required fields and HTTPS list addresses, and stores it using Android Keystore-backed
encryption. Settings take effect on the next connection. Release-provided settings
remain a fallback. Imported WebTunnel lines use the existing universal importer.

Matrix Transport requires the source's SPKI allowlist at every MASQUE handshake;
missing/malformed/mismatched pins fail closed. Unauthenticated account fronting is
disabled; account requests use the normal authenticated HTTPS client. Upstream
logos/branding are not used. The complete locked Rust source/dependencies, upstream
license/trademark notices, patch and revision are included in the corresponding
source archive beside releases containing the runtime.

## Validation limits

A local SOCKS request proves an engine/chain carries traffic; another app's complete
TUN path still needs device testing. The running VPN is checked again after startup
and background upload/download tests run on the same route. Native builds, Android
TLS-policy execution, JVM tests, emulator launch checks and carrier field tests are
reported separately. No device or field result is implied by source availability.

Public version and signing identity are unchanged. This branch does not publish
an APK or replace release assets without a separate release action.
