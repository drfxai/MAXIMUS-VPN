# V1.0.4 build 44 integration

This update builds on main's DNS/security and private-Psiphon changes; it does not
replace the existing TUN, Smart Manager or Adaptive Transport architecture.

## Cores and connection security

- libXray v26.10.31 remains hash-pinned. sing-box 1.14.4 is a separate official,
  per-ABI hash-verified executable, with source and license references in notices.
- Normalized VLESS, VMess, Trojan and Shadowsocks can select sing-box per profile,
  in Settings, or as a measured recovery alternative. Untranslatable TLS pins,
  masks, ECH and protocol extras keep their original engine; no security fields
  are silently dropped.
- `cipherSuites`, `finalmask` and the selected core survive share-link import and
  export. Security/DPI variants no longer collapse during deduplication.
- Public Psiphon validates both literal and base64 TransferURL destinations at
  launch. Private bootstrap remains separate. WireGuard and AmneziaWG default to
  MTU 1280 when unspecified.
- Settings provides an explicit local-proxy action using the selected compatible
  server. This mode creates no VPN interface and provides no device-wide Kill
  Switch. Its user-facing SOCKS5 entrance binds only loopback, requires fresh
  per-session credentials and forwards names without local resolution. A stopped
  engine closes the entrance. Native engines with unauthenticated internal SOCKS
  listeners retain that upstream limitation; the entrance does not add native
  authentication support to Psiphon or Tor.

## Smart Manager

- Existing listener-aware SOAR/Cotten preflight, DNS delegation, DNSTT forwarding,
  reviewed sequential batch installation and private Psiphon install are reused.
- Mutating tool scripts share a kernel flock across SSH sessions and a bounded
  remote result journal containing only RUNNING/COMPLETED/FAILED and exit codes.
  Installer rollback traps execute in a child shell; they cannot erase the outer
  outcome guard. Read-only log inspection does not acquire the mutation lock.
- Explicit DNS-tunnel removal can purge keys and configuration after ownership
  and stopped-service checks. The confirmation names irreversible client impact;
  the low-level default still retains keys. System DNS and unrelated rules stay.
- Subscription core/country presets are encrypted, applied to existing nodes and
  refreshed/offline lists, and do not overwrite explicit per-node core choices,
  custom TLS or routing. Country exclusion affects automatic selection only.

## Adaptive Transport

- sing-box variants compete only after actual engine/traffic tests; a running
  process or open local port is never recorded as verified egress.
- Physical-network callbacks exclude VPN interfaces, debounce callback bursts,
  invalidate old evidence and rebuild after network/address changes. The current
  capture interface remains in place during loss and reconnect.
- Matrix Transport H2 is treated as TCP/TLS; H3/WireGuard carriers as UDP.
- Existing bidirectional transfer evidence, DNS/MTU intelligence, IPv4/IPv6
  Cloudflare measurement and multi-hop recovery remain in use.

## Validation boundary

The Android unit/lint workflow and signed release gates are authoritative for
build success. Native loopback tests use app-generated JSON with the official
sing-box executable: authenticated SOCKS, four protocols, 64 KiB upload/download,
wrong-password rejection and TLS hostname/trust rejection. Physical carrier and
phone-to-user-VPS tests are separate and cannot be inferred from CI. No live VPS
or Cloudflare zone is mutated by a build or release.

The build does not claim that every upstream AmneziaWG 3.1 extension or arbitrary
WireGuard split-routing configuration is implemented: unsupported options must
remain unavailable rather than weaken the default full-capture protection.
